package io.pallet.gitintegration.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.delivery.payload.AppAuthorizationPayload;
import io.pallet.gitintegration.delivery.payload.DeliveryPayload;
import io.pallet.gitintegration.delivery.payload.InstallationPayload;
import io.pallet.gitintegration.delivery.payload.InstallationRepositoriesPayload;
import io.pallet.gitintegration.delivery.payload.PushPayload;
import io.pallet.gitintegration.delivery.payload.PushPayload.HeadCommit;
import io.pallet.gitintegration.delivery.payload.RepositoryPayload;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class PayloadParserTest {

    private static final String SECRET_MARKER = "Zq9marker";

    private final PayloadParser parser = new PayloadParser(8L * 1024 * 1024, PayloadFixtures.SKIP_MARKERS);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "push-main.json",
                "push-forced.json",
                "push-tag.json",
                "push-branch-deleted.json",
                "push-truncated-commits.json",
                "installation-created.json",
                "installation-deleted.json",
                "installation-suspend.json",
                "installation-unsuspend.json",
                "installation-repositories-added.json",
                "installation-repositories-removed.json",
                "repository-renamed.json",
                "repository-transferred.json",
                "repository-deleted.json",
                "repository-archived.json",
                "github-app-authorization-revoked.json"
            })
    void everyRecordedFixtureParsesAndCarriesNoEmail(String fixture) {
        DeliveryPayload payload = parse(fixture);

        assertThat(payload).isNotNull();
        assertThat(payload.toString()).doesNotContain("@").doesNotContain("noreply");
    }

    @Test
    void aPushKeepsWhatPushProcessingNeeds() {
        PushPayload push = (PushPayload) parse("push-main.json");

        assertThat(push.ref()).isEqualTo("refs/heads/main");
        assertThat(push.branch()).contains("main");
        assertThat(push.before()).isEqualTo("1f9a6c0e3b0d2a4c8e7f6a5b4c3d2e1f0a9b8c7d");
        assertThat(push.after()).isEqualTo("8d3e5b7a9c1f2e4d6b8a0c2e4f6a8b0d2c4e6f8a");
        assertThat(push.created()).isFalse();
        assertThat(push.deleted()).isFalse();
        assertThat(push.forced()).isFalse();
        assertThat(push.installationId()).isEqualTo(41000001L);
        assertThat(push.repositoryId()).isEqualTo(700000001L);
        assertThat(push.repositoryFullName()).isEqualTo("pallet-fixtures/hello-web");
        assertThat(push.senderId()).isEqualTo(9100001L);
        assertThat(push.headCommit())
                .contains(new HeadCommit(
                        "8d3e5b7a9c1f2e4d6b8a0c2e4f6a8b0d2c4e6f8a", "Tune startup probe", "fixture-dev", false));
        assertThat(push.commits()).hasSize(2);
        assertThat(push.commits().getFirst().modified()).containsExactly("src/main/App.java");
        assertThat(push.commits().getFirst().added()).isEmpty();
    }

    @Test
    void aTagAndADeletedBranchParse() {
        PushPayload tag = (PushPayload) parse("push-tag.json");
        PushPayload deleted = (PushPayload) parse("push-branch-deleted.json");

        assertThat(tag.isBranch()).isFalse();
        assertThat(tag.branch()).isEmpty();
        assertThat(tag.before()).isEqualTo(PushPayload.NULL_SHA);
        assertThat(tag.headCommit()).isEmpty();
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.after()).isEqualTo(PushPayload.NULL_SHA);
        assertThat(deleted.branch()).contains("feature/old-banner");
    }

    @Test
    void theAuthorFallsBackToTheNameAndTheEmailIsNeverRead() {
        PushPayload push = (PushPayload) parse(
                "push-main.json",
                Map.of(
                        "/head_commit/author",
                        Map.of("name", "Fixture Dev", "email", "someone-" + SECRET_MARKER + "@example.com")));

        assertThat(push.headCommit().orElseThrow().author()).isEqualTo("Fixture Dev");
        assertThat(push.toString()).doesNotContain(SECRET_MARKER);
    }

    @Test
    void aHeadCommitWithoutAnAuthorHasNone() {
        PushPayload push = (PushPayload) parse("push-main.json", Map.of("/head_commit/author", Map.of()));

        assertThat(push.headCommit().orElseThrow().author()).isNull();
    }

    @Test
    void aTenKilobyteMessageIsCutToItsFirstLineAndAtMost256Characters() {
        String longFirstLine = "x".repeat(10 * 1024);
        PushPayload push = (PushPayload)
                parse("push-main.json", Map.of("/head_commit/message", longFirstLine + "\n\nbody " + SECRET_MARKER));

        String message = push.headCommit().orElseThrow().message();
        assertThat(message).hasSize(PayloadParser.MAX_MESSAGE_LENGTH).isEqualTo("x".repeat(256));
        assertThat(push.toString()).doesNotContain(SECRET_MARKER);
    }

    @Test
    void onlyTheFirstLineOfAMessageIsKept() {
        PushPayload push = (PushPayload)
                parse("push-main.json", Map.of("/head_commit/message", "Fix probe\r\nlonger explanation"));

        assertThat(push.headCommit().orElseThrow().message()).isEqualTo("Fix probe");
    }

    @Test
    void aSkipMarkerIsFoundPastTheFirstLineAndPastTheTruncationLimit() {
        String longFirstLine = "x".repeat(10 * 1024);

        assertThat(skipMarked("Fix probe [skip ci]")).isTrue();
        assertThat(skipMarked("Fix probe\n\nDocs only [pallet skip]")).isTrue();
        assertThat(skipMarked(longFirstLine + " [ci skip]")).isTrue();
        assertThat(skipMarked("Fix probe [skip-ci] [SKIP CI]")).isFalse();
    }

    @Test
    void aPushWithoutAHeadCommitIsNotSkipMarked() {
        assertThat(((PushPayload) parse("push-tag.json")).skipMarked()).isFalse();
    }

    @Test
    void truncationNeverSplitsASurrogatePair() {
        String message = "a".repeat(255) + "😀tail";

        assertThat(PayloadParser.truncate(message, 256)).isEqualTo("a".repeat(255));
    }

    @Test
    void anUnknownFieldIsIgnored() {
        assertThat(parse("push-main.json", Map.of("/something_new", Map.of("nested", List.of(1, 2)))))
                .isInstanceOf(PushPayload.class);
    }

    @Test
    void aMissingRepositoryIdNamesTheField() {
        assertMalformed("push-main.json", "/repository/id", null, "repository.id: is required");
    }

    @Test
    void idsMustBePositiveIntegers() {
        assertMalformed("push-main.json", "/repository/id", 0, "repository.id: must be a positive integer");
        assertMalformed("push-main.json", "/repository/id", -4, "repository.id: must be a positive integer");
        assertMalformed("push-main.json", "/installation/id", 1.5, "installation.id: must be a positive integer");
        assertMalformed("push-main.json", "/sender/id", SECRET_MARKER, "sender.id: must be a positive integer");
        assertMalformed(
                "push-main.json",
                "/repository/id",
                new BigInteger("99999999999999999999"),
                "repository.id: must be a positive integer");
    }

    @Test
    void shasMustBeFortyLowercaseHexCharacters() {
        assertMalformed(
                "push-main.json",
                "/after",
                "8D3E5B7A9C1F2E4D6B8A0C2E4F6A8B0D2C4E6F8A",
                "after: must be 40 lowercase hex characters");
        assertMalformed("push-main.json", "/before", "1f9a6c0e", "before: must be 40 lowercase hex characters");
        assertMalformed(
                "push-main.json",
                "/head_commit/id",
                "zz9a6c0e3b0d2a4c8e7f6a5b4c3d2e1f0a9b8c7d",
                "head_commit.id: must be 40 lowercase hex characters");
    }

    @Test
    void afterMayBeAllZerosOnlyForADeletedRef() {
        assertMalformed(
                "push-main.json",
                "/after",
                PushPayload.NULL_SHA,
                "after: must not be all zeros unless the ref was deleted");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "refs/heads/",
                "refs/heads/-leading-dash",
                "refs/heads/a..b",
                "refs/heads/has space",
                "refs/heads/tab\there",
                "refs/heads/a~1",
                "refs/heads/a^b",
                "refs/heads/a:b",
                "refs/heads/a?b",
                "refs/heads/a*b",
                "refs/heads/a[b",
                "refs/heads/a\\b",
                "refs/heads/trailing/",
                "refs/heads/a//b",
                "refs/heads/ends.",
                "refs/heads/feature.lock",
                "refs/heads/feature.lock/child",
                "refs/heads/.hidden",
                "refs/heads/dir/.hidden",
                "refs/heads/at@{brace",
                "refs/heads/@",
                "refs/heads/del\u007f",
                "refs/pull/1/head",
                "main"
            })
    void aRefBreakingGitsRulesIsMalformed(String ref) {
        assertThatThrownBy(() -> parse("push-main.json", Map.of("/ref", ref)))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessageStartingWith("ref: ");
    }

    @Test
    void aRefNameIsAtMost255Bytes() {
        String name = "é".repeat(128);

        assertThatThrownBy(() -> parse("push-main.json", Map.of("/ref", "refs/heads/" + name)))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessage("ref: must be at most 255 bytes");
        assertThat(name.getBytes(StandardCharsets.UTF_8)).hasSize(256);
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "feature/login-page", "release-2026.09", "user/ünïcode", "a/b/c", "v1.4.0"})
    void aValidRefParses(String name) {
        PushPayload push = (PushPayload) parse("push-main.json", Map.of("/ref", "refs/heads/" + name));

        assertThat(push.branch()).contains(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-slash", "a/b/c", "owner/..", "owner/.", "has space/repo", "owner/", "/name"})
    void aFullNameOutsideGitHubsCharacterSetIsMalformed(String fullName) {
        assertThatThrownBy(() -> parse("push-main.json", Map.of("/repository/full_name", fullName)))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessageStartingWith("repository.full_name: ");
    }

    @Test
    void aFullNameLongerThanGitHubAllowsIsMalformed() {
        assertMalformed(
                "push-main.json",
                "/repository/full_name",
                "a".repeat(40) + "/repo",
                "repository.full_name: must be owner/name in GitHub's character set");
    }

    @Test
    void commitPathsAreRequiredAndLengthLimited() {
        assertMalformed("push-main.json", "/commits/0/added", null, "commits[0].added: is required");
        assertMalformed(
                "push-main.json",
                "/commits/1/modified",
                List.of("ok.txt", "p".repeat(PayloadParser.MAX_PATH_LENGTH + 1)),
                "commits[1].modified[1]: must be 1 to 4096 characters");
        assertMalformed("push-main.json", "/commits/0/removed", List.of(7), "commits[0].removed[0]: must be a string");
    }

    @Test
    void theForcedAndDeletedFlagsAreRequiredBooleans() {
        assertMalformed("push-main.json", "/forced", null, "forced: is required");
        assertMalformed("push-main.json", "/deleted", "false", "deleted: must be a boolean");
    }

    @Test
    void anInstallationKeepsItsAccountSelectionAndPermissions() {
        InstallationPayload created = (InstallationPayload) parse("installation-created.json");
        InstallationPayload suspended = (InstallationPayload) parse("installation-suspend.json");

        assertThat(created.action()).isEqualTo("created");
        assertThat(created.installation().id()).isEqualTo(41000001L);
        assertThat(created.installation().account().login()).isEqualTo("pallet-fixtures");
        assertThat(created.installation().account().type()).isEqualTo("Organization");
        assertThat(created.installation().repositorySelection()).isEqualTo("selected");
        assertThat(created.installation().permissions())
                .containsEntry("checks", "write")
                .containsEntry("contents", "read");
        assertThat(created.installation().suspended()).isFalse();
        assertThat(created.repositories()).extracting("id").containsExactly(700000001L, 700000002L);
        assertThat(suspended.installation().suspended()).isTrue();
    }

    @Test
    void anInstallationAccountMustBeAUserOrAnOrganization() {
        assertMalformed(
                "installation-created.json",
                "/installation/account/type",
                "Enterprise",
                "installation.account.type: must be User or Organization");
        assertMalformed(
                "installation-created.json",
                "/installation/repository_selection",
                "some",
                "installation.repository_selection: must be all or selected");
        assertMalformed(
                "installation-created.json",
                "/installation/permissions",
                Map.of("checks", "WRITE; drop"),
                "installation.permissions.checks: must be lowercase letters and underscores");
    }

    @Test
    void installationRepositoriesListBothSides() {
        InstallationRepositoriesPayload added =
                (InstallationRepositoriesPayload) parse("installation-repositories-added.json");

        assertThat(added.action()).isEqualTo("added");
        assertThat(added.added()).singleElement().satisfies(repository -> {
            assertThat(repository.id()).isEqualTo(700000002L);
            assertThat(repository.fullName()).isEqualTo("pallet-fixtures/worker");
            assertThat(repository.isPrivate()).isTrue();
        });
        assertThat(added.removed()).isEmpty();
    }

    @Test
    void aRepositoryEventKeepsItsDefaultBranchAndArchivedFlag() {
        RepositoryPayload archived = (RepositoryPayload) parse("repository-archived.json");

        assertThat(archived.action()).isEqualTo("archived");
        assertThat(archived.repository().defaultBranch()).isEqualTo("main");
        assertThat(archived.repository().archived()).isTrue();
        assertThat(archived.installationId()).isEqualTo(41000001L);
        assertMalformed(
                "repository-renamed.json",
                "/repository/default_branch",
                "bad..branch",
                "repository.default_branch: must not contain '..'");
    }

    @Test
    void anAuthorizationRevocationNamesItsSender() {
        AppAuthorizationPayload revoked = (AppAuthorizationPayload) parse("github-app-authorization-revoked.json");

        assertThat(revoked.action()).isEqualTo("revoked");
        assertThat(revoked.senderId()).isEqualTo(9100001L);
    }

    @Test
    void anActionIsRequiredWhereTheEventHasOne() {
        assertMalformed("repository-deleted.json", "/action", null, "action: is required");
        assertMalformed(
                "installation-deleted.json", "/action", "Deleted", "action: must be lowercase letters and underscores");
    }

    @Test
    void anAbsentOrUnparseablePayloadIsMalformed() {
        assertThatThrownBy(() -> parser.parse("push", null)).hasMessage("payload: must be present");
        assertThatThrownBy(() -> parser.parse("push", "{\"ref\":"))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessage("payload: must be one JSON document within the read limits");
        assertThatThrownBy(() -> parser.parse("push", "[1,2]")).hasMessage("payload: must be a JSON object");
    }

    @Test
    void aDocumentPastTheReadLimitIsMalformed() {
        PayloadParser small = new PayloadParser(64, PayloadFixtures.SKIP_MARKERS);

        assertThatThrownBy(() ->
                        small.parse("push", new String(WebhookFixtures.load("push-main.json"), StandardCharsets.UTF_8)))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessage("payload: must be one JSON document within the read limits");
    }

    private boolean skipMarked(String message) {
        return ((PushPayload) parse("push-main.json", Map.of("/head_commit/message", message))).skipMarked();
    }

    private DeliveryPayload parse(String fixture) {
        return parser.parse(
                WebhookFixtures.eventOf(fixture), new String(WebhookFixtures.load(fixture), StandardCharsets.UTF_8));
    }

    private DeliveryPayload parse(String fixture, Map<String, Object> overrides) {
        WebhookFixtures.Delivery delivery = WebhookFixtures.delivery(fixture);
        overrides.forEach(delivery::with);
        return parser.parse(WebhookFixtures.eventOf(fixture), new String(delivery.body(), StandardCharsets.UTF_8));
    }

    private void assertMalformed(String fixture, String pointer, Object value, String message) {
        WebhookFixtures.Delivery delivery = WebhookFixtures.delivery(fixture).with(pointer, value);
        String payload = new String(delivery.body(), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parse(WebhookFixtures.eventOf(fixture), payload))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessage(message)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET_MARKER));
    }
}
