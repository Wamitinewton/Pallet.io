package io.pallet.gitintegration.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.checks.DesiredCheck.Conclusion;
import io.pallet.gitintegration.checks.DesiredCheck.Phase;
import io.pallet.gitintegration.checks.DesiredStateWriter.Result;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.repolink.RepoLink;
import io.pallet.gitintegration.repolink.RepoLinkRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@UnitTest
class DesiredStateWriterTest {

    private static final String ORG = "org-1";
    private static final UUID APP = UUID.fromString("5b1f6f0e-9d0c-4c57-9a55-0d2a1f7a9c11");
    private static final String SHA = "a".repeat(40);

    private final CheckRunRepository checkRuns = mock(CheckRunRepository.class);
    private final RepoLinkRepository links = mock(RepoLinkRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DesiredStateWriter writer = writer(false);
    private final DesiredStateWriter awaitingDeploy = writer(true);

    @Test
    void aLaterStateAlwaysWins() {
        DesiredCheck started = writer.buildStarted();
        DesiredCheck succeeded = writer.buildSucceeded();

        assertThat(DesiredStateWriter.merge(started, succeeded)).contains(succeeded);
        assertThat(DesiredStateWriter.merge(queued(), started)).contains(started);
    }

    @Test
    void aStartedAfterItsBuildFinishedCannotReopenTheCheck() {
        assertThat(DesiredStateWriter.merge(writer.buildSucceeded(), writer.buildStarted()))
                .isEmpty();
        assertThat(DesiredStateWriter.merge(writer.buildFailed("boom"), writer.buildStarted()))
                .isEmpty();
        assertThat(DesiredStateWriter.merge(awaitingDeploy.buildSucceeded(), awaitingDeploy.buildStarted()))
                .as("same state, earlier phase")
                .isEmpty();
    }

    @Test
    void aBuildResultCannotOverwriteAnotherBuildResult() {
        assertThat(DesiredStateWriter.merge(writer.buildFailed("boom"), writer.buildSucceeded()))
                .isEmpty();
        assertThat(DesiredStateWriter.merge(writer.buildSucceeded(), writer.buildFailed("boom")))
                .isEmpty();
    }

    @Test
    void aDeployFailureChangesACompletedBuildSuccessToFailure() {
        DesiredCheck failed = writer.deployStateChanged("FAILED", null);

        Optional<DesiredCheck> merged = DesiredStateWriter.merge(writer.buildSucceeded(), failed);

        assertThat(merged).contains(failed);
        assertThat(failed.state()).isEqualTo(CheckState.COMPLETED);
        assertThat(failed.conclusion()).isEqualTo(Conclusion.FAILURE);
        assertThat(DesiredStateWriter.merge(writer.buildSucceeded(), writer.deployStateChanged("ROLLED_BACK", null)))
                .get()
                .extracting(DesiredCheck::conclusion)
                .isEqualTo(Conclusion.FAILURE);
    }

    @Test
    void aDeployInProgressCannotReopenACompletedCheck() {
        assertThat(DesiredStateWriter.merge(writer.buildSucceeded(), writer.deployStateChanged("ROUTING", null)))
                .isEmpty();
        assertThat(DesiredStateWriter.merge(
                        writer.deployStateChanged("LIVE", "https://web.acme.app"),
                        writer.deployStateChanged("ROUTING", null)))
                .isEmpty();
    }

    @Test
    void deployUpdatesReplaceEachOther() {
        DesiredCheck routing = awaitingDeploy.deployStateChanged("ROUTING", null);
        DesiredCheck healthChecking = awaitingDeploy.deployStateChanged("HEALTH_CHECKING", null);
        DesiredCheck live = awaitingDeploy.deployStateChanged("LIVE", "https://web.acme.app");

        assertThat(DesiredStateWriter.merge(awaitingDeploy.buildSucceeded(), routing))
                .contains(routing);
        assertThat(DesiredStateWriter.merge(routing, healthChecking)).contains(healthChecking);
        assertThat(DesiredStateWriter.merge(healthChecking, live)).contains(live);
        assertThat(DesiredStateWriter.merge(live, awaitingDeploy.deployStateChanged("FAILED", null)))
                .isPresent();
        assertThat(DesiredStateWriter.merge(live, live)).isEmpty();
    }

    @Test
    void withoutAwaitDeployASuccessfulBuildCompletesTheCheck() {
        DesiredCheck succeeded = writer.buildSucceeded();

        assertThat(succeeded.state()).isEqualTo(CheckState.COMPLETED);
        assertThat(succeeded.conclusion()).isEqualTo(Conclusion.SUCCESS);
        assertThat(succeeded.phase()).isEqualTo(Phase.BUILD_FINISHED);
    }

    @Test
    void withAwaitDeployASuccessfulBuildWaitsForTheDeploy() {
        DesiredCheck succeeded = awaitingDeploy.buildSucceeded();

        assertThat(succeeded.state()).isEqualTo(CheckState.IN_PROGRESS);
        assertThat(succeeded.conclusion()).isNull();
        assertThat(succeeded.summary()).isEqualTo("Built, deploying");
        assertThat(DesiredStateWriter.merge(awaitingDeploy.buildStarted(), succeeded))
                .contains(succeeded);
    }

    @Test
    void theLiveStateLinksTheLiveUrlAndOthersKeepTheCheckRunning() {
        assertThat(writer.deployStateChanged("LIVE", "https://web.acme.app"))
                .returns(CheckState.COMPLETED, DesiredCheck::state)
                .returns(Conclusion.SUCCESS, DesiredCheck::conclusion)
                .returns("https://web.acme.app", DesiredCheck::detailsUrl);
        assertThat(writer.deployStateChanged("SOMETHING_NEW", null))
                .returns(CheckState.IN_PROGRESS, DesiredCheck::state)
                .returns("Deploying: something new", DesiredCheck::summary);
    }

    @Test
    void onlyAnAbsoluteHttpUrlBecomesTheDetailsLink() {
        assertThat(DesiredStateWriter.detailsUrl("https://web.acme.app/")).isEqualTo("https://web.acme.app/");
        assertThat(DesiredStateWriter.detailsUrl("javascript:alert(1)")).isNull();
        assertThat(DesiredStateWriter.detailsUrl("https://user:pw@web.acme.app"))
                .isNull();
        assertThat(DesiredStateWriter.detailsUrl("/relative")).isNull();
        assertThat(DesiredStateWriter.detailsUrl("https://" + "a".repeat(600) + ".app"))
                .isNull();
        assertThat(DesiredStateWriter.detailsUrl(null)).isNull();
    }

    @Test
    void aFailureReasonIsOneTruncatedLineOfEscapedText() {
        DesiredCheck failed = writer.buildFailed("![x](https://evil.test/p.png)\n<b>line two</b>");

        assertThat(failed.summary())
                .isEqualTo("Build failed: \\!\\[x\\]\\(https://evil\\.test/p\\.png\\) \\<b\\>line two\\</b\\>")
                .doesNotContain("\n");
        String longReason = DesiredStateWriter.plainText("x".repeat(2000), DesiredStateWriter.MAX_REASON_LENGTH);
        assertThat(longReason).hasSize(DesiredStateWriter.MAX_REASON_LENGTH).endsWith("…");
        assertThat(writer.buildFailed(null).summary()).isEqualTo("Build failed");
    }

    @Test
    void anAppWithoutAnActiveLinkIsDroppedAndCounted() {
        when(links.existsByOrgIdAndAppIdAndStatus(ORG, APP, RepoLink.Status.ACTIVE))
                .thenReturn(false);

        assertThat(writer.apply(ORG, APP, SHA, writer.buildStarted())).isEqualTo(Result.DROPPED);

        verify(checkRuns, never()).insertIfAbsent(anyString(), any(), anyString(), any());
        assertThat(meters.counter(CheckRunMetrics.DROPPED, "reason", "unlinked").count())
                .isEqualTo(1);
    }

    @Test
    void theFirstDesireCreatesTheRowAndALaterOneMovesItForward() {
        when(links.existsByOrgIdAndAppIdAndStatus(ORG, APP, RepoLink.Status.ACTIVE))
                .thenReturn(true);
        when(checkRuns.insertIfAbsent(ORG, APP, SHA, writer.buildStarted())).thenReturn(true);

        assertThat(writer.apply(ORG, APP, SHA, writer.buildStarted())).isEqualTo(Result.APPLIED);

        when(checkRuns.insertIfAbsent(ORG, APP, SHA, writer.buildSucceeded())).thenReturn(false);
        when(checkRuns.lock(ORG, APP, SHA)).thenReturn(Optional.of(row(writer.buildStarted())));

        assertThat(writer.apply(ORG, APP, SHA, writer.buildSucceeded())).isEqualTo(Result.APPLIED);
        verify(checkRuns).updateDesired(ORG, APP, SHA, writer.buildSucceeded());
    }

    @Test
    void aDesireThatWouldMoveTheCheckBackIsNotWritten() {
        when(links.existsByOrgIdAndAppIdAndStatus(ORG, APP, RepoLink.Status.ACTIVE))
                .thenReturn(true);
        when(checkRuns.insertIfAbsent(ORG, APP, SHA, writer.buildStarted())).thenReturn(false);
        when(checkRuns.lock(ORG, APP, SHA)).thenReturn(Optional.of(row(writer.buildSucceeded())));

        assertThat(writer.apply(ORG, APP, SHA, writer.buildStarted())).isEqualTo(Result.UNCHANGED);

        verify(checkRuns, never()).updateDesired(anyString(), any(), anyString(), any());
    }

    private DesiredStateWriter writer(boolean awaitDeploy) {
        GitIntegrationProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                        "pallet.git.github.app-id", "1",
                        "pallet.git.github.client-id", "client",
                        "pallet.git.github.app-slug", "pallet",
                        "pallet.git.checks.await-deploy", String.valueOf(awaitDeploy))))
                .bind("pallet.git", Bindable.of(GitIntegrationProperties.class))
                .get();
        return new DesiredStateWriter(checkRuns, links, new CheckRunMetrics(meters), properties);
    }

    private static DesiredCheck queued() {
        return new DesiredCheck(CheckState.QUEUED, null, null, "Queued", Phase.BUILD_STARTED);
    }

    private static CheckRun row(DesiredCheck desired) {
        return new CheckRun(APP, SHA, ORG, null, desired, 1, null, null, 0);
    }
}
