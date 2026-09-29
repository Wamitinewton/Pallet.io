package io.pallet.gitintegration.delivery;

import io.pallet.gitintegration.scm.ScmProvider;
import java.util.List;

/**
 * Thrown by a handler that can't decide without asking GitHub. The processor rolls the delivery's transaction back,
 * performs every lookup with no transaction or row lock held, and runs the handler again with the answers in
 * {@link DeliveryContext#lookups()}.
 */
public final class NeedsGitHub extends RuntimeException {

    private final transient List<Lookup<?>> lookups;

    public NeedsGitHub(Lookup<?> lookup) {
        this(List.of(lookup));
    }

    public NeedsGitHub(List<? extends Lookup<?>> lookups) {
        super("The handler needs " + lookups.size() + " GitHub lookup(s)", null, false, false);
        if (lookups.isEmpty()) {
            throw new IllegalArgumentException("NeedsGitHub requires at least one lookup");
        }
        this.lookups = List.copyOf(lookups);
    }

    public List<Lookup<?>> lookups() {
        return lookups;
    }

    /**
     * One question for GitHub. Implementations are value objects (records): equal lookups share one answer, which is
     * how a handler finds its answer on the next round.
     */
    public interface Lookup<T> {

        /** @return the answer, never {@code null} */
        T perform(ScmProvider scm);
    }
}
