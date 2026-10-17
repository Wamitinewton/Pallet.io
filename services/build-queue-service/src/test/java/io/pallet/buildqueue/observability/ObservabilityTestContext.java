package io.pallet.buildqueue.observability;

import io.pallet.buildqueue.support.StubBuilder;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** One cached context for the observability tests: the stub builder, MockMvc for the scrape, and captured spans. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, StubBuilder.class, CapturedSpans.class})
@interface ObservabilityTestContext {}
