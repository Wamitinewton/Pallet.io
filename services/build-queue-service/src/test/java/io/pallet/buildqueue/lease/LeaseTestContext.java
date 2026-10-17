package io.pallet.buildqueue.lease;

import io.pallet.buildqueue.support.StubBuilder;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * One cached context for every lease test, with deadlines short enough to pass inside a test. The queue age is longer
 * than the time a build waits behind one that runs out its attempts or its lease, so only the queue age test reaches
 * it. The run lease is set in application-test.yml instead, since whichever context claims a {@code build.started}
 * computes it.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@IntegrationTest
@Import({RedisTestContainerConfiguration.class, StubBuilder.class})
@TestPropertySource(
        properties = {
            "pallet.build-queue.dispatch.start-timeout=PT2S",
            "pallet.build-queue.dispatch.poll-interval=PT0.2S",
            "pallet.build-queue.lease.sweep-interval=PT0.5S",
            "pallet.build-queue.lease.reachability-timeout=PT1S",
            "pallet.build-queue.queue.max-age=PT12S"
        })
@interface LeaseTestContext {}
