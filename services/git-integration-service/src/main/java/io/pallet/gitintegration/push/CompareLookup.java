package io.pallet.gitintegration.push;

import io.pallet.gitintegration.delivery.NeedsGitHub.Lookup;
import io.pallet.gitintegration.scm.ScmProvider;
import io.pallet.gitintegration.scm.ScmProvider.CompareStatus;

/**
 * How a push's {@code after} relates to an app's accepted {@code head}. When GitHub no longer has one of the two, the
 * pushed commit decides: gone means the push replays history GitHub has discarded ({@code BEHIND}); still there means
 * the head was rewritten away ({@code DIVERGED}).
 */
public record CompareLookup(long installationId, long repoId, String head, String after)
        implements Lookup<CompareStatus> {

    @Override
    public CompareStatus perform(ScmProvider scm) {
        return scm.compare(installationId, repoId, head, after)
                .orElseGet(() -> scm.commitExists(installationId, repoId, after)
                        ? CompareStatus.DIVERGED
                        : CompareStatus.BEHIND);
    }
}
