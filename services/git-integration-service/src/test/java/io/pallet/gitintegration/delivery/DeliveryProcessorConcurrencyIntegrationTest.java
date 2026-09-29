package io.pallet.gitintegration.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.outbox.OutboxWriter;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryTestConfiguration.ScriptedHandler;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@IntegrationTest
@Import({RedisTestContainerConfiguration.class, DeliveryTestConfiguration.class})
class DeliveryProcessorConcurrencyIntegrationTest {

    private static final int DELIVERIES = 500;
    private static final int WORKERS = 3;
    private static final Duration DEADLINE = Duration.ofSeconds(90);
    private static final Duration IDLE = Duration.ofMillis(50);

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private ScriptedHandler handler;

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private JdbcTemplate jdbc;

    private DeliveryRows rows;

    @BeforeEach
    void setUp() {
        rows = new DeliveryRows(jdbc);
        handler.reset();
    }

    @AfterEach
    void tearDown() {
        rows.removeAll();
    }

    @Test
    void concurrentWorkersCommitEveryDeliveryExactlyOnceEvenWhenOneCrashesMidTransaction() throws Exception {
        List<UUID> ids = rows.insertMany("repository-archived.json", DELIVERIES);
        Set<UUID> ours = Set.copyOf(ids);
        Set<UUID> crashOnce = new HashSet<>(ids.subList(0, DELIVERIES / 10));
        Set<UUID> crashed = ConcurrentHashMap.newKeySet();
        Map<UUID, AtomicInteger> committed = new ConcurrentHashMap<>();
        handler.script(context -> {
            if (!ours.contains(context.deliveryId())) {
                return DeliveryOutcome.PROCESSED;
            }
            outbox.append(DeliveryProcessorIntegrationTest.pushEvent(context));
            if (crashOnce.contains(context.deliveryId()) && crashed.add(context.deliveryId())) {
                throw new IllegalStateException("worker crashed after appending");
            }
            committed
                    .computeIfAbsent(context.deliveryId(), id -> new AtomicInteger())
                    .incrementAndGet();
            return DeliveryOutcome.PROCESSED;
        });

        runWorkersUntil(() -> pending(ids) == 0);

        assertThat(pending(ids)).isZero();
        assertThat(committed).hasSize(DELIVERIES);
        assertThat(committed.values())
                .allSatisfy(count -> assertThat(count.get()).isOne());
        assertThat(crashed).isEqualTo(crashOnce);
        assertThat(ids).allSatisfy(id -> {
            assertThat(rows.status(id)).isEqualTo("PROCESSED");
            assertThat(handler.calls(id)).isEqualTo(crashOnce.contains(id) ? 2 : 1);
        });
        // The crashed round's attempt is recorded unless another worker had already re-claimed the delivery; either way
        // it is never counted twice.
        assertThat(crashOnce).allSatisfy(id -> assertThat(rows.attempts(id)).isBetween(0, 1));
        assertThat(outboxRows(ids)).isEqualTo(DELIVERIES);
    }

    private void runWorkersUntil(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            List<Future<?>> workers = IntStream.range(0, WORKERS)
                    .<Future<?>>mapToObj(i -> pool.submit(() -> {
                        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
                            if (processor.processBatch() == 0) {
                                sleep(IDLE);
                            }
                        }
                    }))
                    .toList();
            for (Future<?> worker : workers) {
                worker.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int pending(List<UUID> ids) {
        return new NamedParameterJdbcTemplate(jdbc)
                .queryForObject(
                        "SELECT count(*) FROM git_integration.webhook_deliveries"
                                + " WHERE delivery_id IN (:ids) AND status <> 'PROCESSED'",
                        Map.of("ids", ids),
                        Integer.class);
    }

    private int outboxRows(List<UUID> ids) {
        return new NamedParameterJdbcTemplate(jdbc)
                .queryForObject(
                        "SELECT count(*) FROM git_integration.outbox_events WHERE event_id IN (:ids)",
                        Map.of(
                                "ids",
                                ids.stream()
                                        .map(DeliveryProcessorIntegrationTest::eventId)
                                        .toList()),
                        Integer.class);
    }
}
