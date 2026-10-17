package io.pallet.buildqueue.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Counters and timers summed over every test context in this JVM. Every live context consumes every Kafka record and
 * the inbox lets one of them apply it, so a count may land in any context's registry.
 */
public final class Counters {

    private Counters() {}

    /**
     * The count of {@code name} with {@code tags} so far. A context may register its meters in more than one registry
     * (one per backend), so each backend's registries are summed on their own and the largest total is taken.
     */
    public static double count(String name, String... tags) {
        return leaves(Metrics.globalRegistry.getRegistries(), new HashSet<>()).stream()
                .collect(Collectors.groupingBy(
                        Object::getClass,
                        Collectors.summingDouble(registry -> registry.find(name).tags(tags).counters().stream()
                                .mapToDouble(Counter::count)
                                .sum())))
                .values()
                .stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0);
    }

    /** Recordings of the timer {@code name} with {@code tags} so far, summed as {@link #count} sums counters. */
    public static long timerCount(String name, String... tags) {
        return leaves(Metrics.globalRegistry.getRegistries(), new HashSet<>()).stream()
                .collect(Collectors.groupingBy(
                        Object::getClass,
                        Collectors.summingLong(registry -> registry.find(name).tags(tags).timers().stream()
                                .mapToLong(Timer::count)
                                .sum())))
                .values()
                .stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0);
    }

    /** A composite's meters live in its children, so only the children are counted. */
    private static Set<MeterRegistry> leaves(Collection<MeterRegistry> registries, Set<MeterRegistry> found) {
        for (MeterRegistry registry : registries) {
            if (registry instanceof CompositeMeterRegistry composite) {
                leaves(composite.getRegistries(), found);
            } else {
                found.add(registry);
            }
        }
        return found;
    }
}
