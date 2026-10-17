package io.pallet.buildqueue.api;

import io.pallet.buildqueue.api.dto.WaitingOn;
import java.util.Set;

/** Explains a waiting build with the first of the dispatcher's eligibility rules that holds it. */
final class WaitingOnResolver {

    /**
     * What the dispatcher would see for one org, read once per request. {@code platformInFlight} is the one
     * cross-tenant number here and only ever becomes a reason.
     */
    record Snapshot(
            boolean paused,
            Set<String> appsInFlight,
            int orgInFlight,
            int orgMaxConcurrent,
            long platformInFlight,
            int globalConcurrency) {

        Snapshot {
            appsInFlight = Set.copyOf(appsInFlight);
        }
    }

    private WaitingOnResolver() {}

    static WaitingOn resolve(String appId, Snapshot snapshot) {
        if (snapshot.paused()) {
            return WaitingOn.DISPATCH_PAUSED;
        }
        if (snapshot.appsInFlight().contains(appId)) {
            return WaitingOn.APP_BUSY;
        }
        if (snapshot.orgInFlight() >= snapshot.orgMaxConcurrent()) {
            return WaitingOn.ORG_CONCURRENCY;
        }
        if (snapshot.platformInFlight() >= snapshot.globalConcurrency()) {
            return WaitingOn.PLATFORM_CAPACITY;
        }
        return WaitingOn.NEXT_ROUND;
    }
}
