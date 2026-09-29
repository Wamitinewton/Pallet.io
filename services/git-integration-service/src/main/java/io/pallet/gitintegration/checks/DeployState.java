package io.pallet.gitintegration.checks;

import java.util.Arrays;
import java.util.Optional;

/**
 * The deployment states {@code deploy-orchestrator-service} announces on {@code deploy.state.changed} (PROJECT.md), and
 * the only place that decides which of them end a check run. A state not listed here keeps the check in progress.
 */
enum DeployState {
    QUEUED,
    BUILDING,
    PUSHING,
    PROVISIONING,
    ROUTING,
    HEALTH_CHECKING,
    LIVE,
    FAILED,
    ROLLED_BACK;

    boolean isLive() {
        return this == LIVE;
    }

    boolean isFailed() {
        return this == FAILED || this == ROLLED_BACK;
    }

    static Optional<DeployState> fromWireName(String name) {
        return Arrays.stream(values())
                .filter(state -> state.name().equals(name))
                .findFirst();
    }
}
