package io.pallet.gitintegration.push;

import static io.pallet.gitintegration.push.PushPayloads.INSTALLATION_ID;
import static io.pallet.gitintegration.push.PushPayloads.REPO_ID;
import static io.pallet.gitintegration.push.PushPayloads.push;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.delivery.PayloadFixtures;
import io.pallet.gitintegration.delivery.payload.PushPayload;
import io.pallet.gitintegration.installation.Installation;
import io.pallet.gitintegration.installation.InstallationRepository;
import io.pallet.gitintegration.repolink.RepoLink;
import io.pallet.gitintegration.repolink.RepoLinkRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;

@UnitTest
class SkipRulesTest {

    @Mock
    private InstallationRepository installations;

    @Mock
    private RepoLinkRepository links;

    private final RepoLink link = mock(RepoLink.class);
    private SkipRules rules;

    @BeforeEach
    void setUp() {
        rules = new SkipRules(installations, links);
        Optional<Installation> active = installation(Installation.Status.ACTIVE);
        lenient().when(installations.findById(INSTALLATION_ID)).thenReturn(active);
        lenient().when(links.findPushTargets(INSTALLATION_ID, REPO_ID, "main")).thenReturn(List.of(link));
    }

    @Test
    void aPushToALinkedBranchBuildsItsLinks() {
        assertThat(rules.apply(push().build())).isEqualTo(new SkipRules.Build("main", List.of(link)));
    }

    @Test
    void aTagIsNotABranch() {
        assertThat(rules.apply(push().ref("refs/tags/v1.0.0").build()))
                .isEqualTo(new SkipRules.Skip(SkipRules.NOT_A_BRANCH));
        verify(installations, never()).findById(anyLong());
    }

    @Test
    void aDeletedBranchBuildsNothing() {
        assertThat(rules.apply(push().deleted().build())).isEqualTo(new SkipRules.Skip(SkipRules.BRANCH_DELETED));
        verify(installations, never()).findById(anyLong());
    }

    @Test
    void aDeletedTagIsNotABranchFirst() {
        assertThat(rules.apply(push().ref("refs/tags/v1.0.0").deleted().build()))
                .isEqualTo(new SkipRules.Skip(SkipRules.NOT_A_BRANCH));
    }

    @ParameterizedTest
    @EnumSource(
            value = Installation.Status.class,
            names = {"SUSPENDED", "DELETED"})
    void anInactiveInstallationBuildsNothing(Installation.Status status) {
        Optional<Installation> inactive = installation(status);
        when(installations.findById(INSTALLATION_ID)).thenReturn(inactive);

        assertThat(rules.apply(push().build())).isEqualTo(new SkipRules.Skip(SkipRules.INSTALLATION_INACTIVE));
        verify(links, never()).findPushTargets(anyLong(), anyLong(), anyString());
    }

    @Test
    void anUnknownInstallationBuildsNothing() {
        when(installations.findById(INSTALLATION_ID)).thenReturn(Optional.empty());

        assertThat(rules.apply(push().build())).isEqualTo(new SkipRules.Skip(SkipRules.INSTALLATION_INACTIVE));
    }

    @Test
    void aBranchNoAppAutoDeploysFromBuildsNothing() {
        when(links.findPushTargets(INSTALLATION_ID, REPO_ID, "main")).thenReturn(List.of());

        assertThat(rules.apply(push().build())).isEqualTo(new SkipRules.Skip(SkipRules.NO_LINKED_APP));
    }

    @Test
    void anInactiveInstallationWinsOverASkipMarker() {
        Optional<Installation> suspended = installation(Installation.Status.SUSPENDED);
        when(installations.findById(INSTALLATION_ID)).thenReturn(suspended);

        assertThat(rules.apply(push().skipMarked().build()))
                .isEqualTo(new SkipRules.Skip(SkipRules.INSTALLATION_INACTIVE));
    }

    @Test
    void noLinkedAppWinsOverASkipMarker() {
        when(links.findPushTargets(INSTALLATION_ID, REPO_ID, "main")).thenReturn(List.of());

        assertThat(rules.apply(push().skipMarked().build())).isEqualTo(new SkipRules.Skip(SkipRules.NO_LINKED_APP));
    }

    @Test
    void aSkipMarkerStopsALinkedPush() {
        assertThat(rules.apply(push().skipMarked().build())).isEqualTo(new SkipRules.Skip(SkipRules.SKIP_MARKER));
    }

    @Test
    void aMarkerAnywhereInAMultiLineMessageSkips() {
        PushPayload marked = parse("Tune startup probe\n\nOnly docs changed.\n[pallet skip]\n");
        PushPayload unmarked = parse("Tune startup probe\n\nMentions skip ci without brackets\n");

        assertThat(rules.apply(marked)).isEqualTo(new SkipRules.Skip(SkipRules.SKIP_MARKER));
        assertThat(rules.apply(unmarked)).isInstanceOf(SkipRules.Build.class);
    }

    private static PushPayload parse(String message) {
        return PayloadFixtures.push(
                "push-main.json",
                Map.of(
                        "/head_commit/message", message,
                        "/installation/id", INSTALLATION_ID,
                        "/repository/id", REPO_ID));
    }

    private static Optional<Installation> installation(Installation.Status status) {
        Installation installation = mock(Installation.class);
        lenient().when(installation.status()).thenReturn(status);
        return Optional.of(installation);
    }
}
