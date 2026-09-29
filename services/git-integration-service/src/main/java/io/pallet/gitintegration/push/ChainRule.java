package io.pallet.gitintegration.push;

import io.pallet.gitintegration.scm.ScmProvider.CompareStatus;
import java.util.Optional;

/**
 * Whether a push moves an app's branch head forward (ARCHITECTURE.md §The chain rule). Decided under the head's row
 * lock; only a push that is neither a fast-forward, a duplicate, nor a force push needs GitHub's compare answer.
 */
public final class ChainRule {

    private ChainRule() {}

    /**
     * @param before null when the caller doesn't know what {@code after} replaced, as for a reconciler pass; only the
     *     compare can then place {@code after}
     * @param compare how {@code after} relates to {@code head}, once GitHub has been asked
     */
    public static ChainDecision decide(
            Optional<String> head, String before, String after, boolean forced, Optional<CompareStatus> compare) {
        if (head.isEmpty()) {
            return ChainDecision.ACCEPT;
        }
        if (after.equals(head.get())) {
            return ChainDecision.DUPLICATE;
        }
        if (head.get().equals(before) || forced) {
            return ChainDecision.ACCEPT;
        }
        return compare.map(status -> switch (status) {
                    case AHEAD, DIVERGED -> ChainDecision.ACCEPT;
                    case IDENTICAL -> ChainDecision.DUPLICATE;
                    case BEHIND -> ChainDecision.STALE;
                })
                .orElse(ChainDecision.NEEDS_COMPARE);
    }
}
