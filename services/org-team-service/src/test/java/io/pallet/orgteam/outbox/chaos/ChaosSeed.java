package io.pallet.orgteam.outbox.chaos;

/** Seeds every randomized chaos decision, and prints the seed so a failing run can be replayed exactly. */
final class ChaosSeed {

    static final String PROPERTY = "pallet.chaos.seed";

    private ChaosSeed() {}

    static long next(String testName) {
        long seed = Long.getLong(PROPERTY, System.nanoTime());
        System.out.printf("[chaos] %s seed=%d (replay with -D%s=%d)%n", testName, seed, PROPERTY, seed);
        return seed;
    }
}
