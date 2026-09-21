package io.pallet.orgteam.outbox.chaos;

import java.util.concurrent.atomic.AtomicBoolean;

final class RevertibleFault implements Fault {

    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Runnable revert;

    RevertibleFault(Runnable revert) {
        this.revert = revert;
    }

    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            revert.run();
        }
    }
}
