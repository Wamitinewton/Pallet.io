package io.pallet.orgteam.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.pallet.common.events.Topics;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.observability.CapturedSpansConfiguration.CapturedSpans;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

@IntegrationTest
@AutoConfigureMockMvc
@Import({
    RedisTestContainerConfiguration.class,
    SignedTokenTestConfiguration.class,
    CapturedSpansConfiguration.class,
    TracePropagationIntegrationTest.RoleChangeConsumer.class
})
@TestPropertySource(properties = "pallet.orgteam.outbox.enabled=true")
class TracePropagationIntegrationTest extends ObservabilityIntegrationSupport {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private CapturedSpans spans;

    @BeforeEach
    void clearSpans() {
        spans.clear();
    }

    @Test
    void aRoleChangeIsOneTraceFromTheRequestThroughTheRelayToTheConsumer() throws Exception {
        TestOrg org = newTeamOrg();
        String developer = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        perform(
                        patch(API + "/orgs/{orgId}/members/{userId}", org.orgId(), developer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"ADMIN\"}"),
                        org.orgId(),
                        org.owner())
                .andExpect(status().isOk());

        String traceId = requestSpan().getTraceId();
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(inTrace(traceId))
                        .extracting(SpanData::getKind)
                        .contains(SpanKind.SERVER, SpanKind.PRODUCER, SpanKind.CONSUMER));
        assertThat(inTrace(traceId)).extracting(SpanData::getName).contains("outbox relay");
    }

    private SpanData requestSpan() {
        return spans.all().stream()
                .filter(span -> span.getKind() == SpanKind.SERVER)
                .findFirst()
                .orElseThrow();
    }

    private List<SpanData> inTrace(String traceId) {
        return spans.all().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .toList();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RoleChangeConsumer {

        @Bean
        Listener roleChangeListener() {
            return new Listener();
        }

        static class Listener {

            @KafkaListener(
                    id = "trace-probe",
                    idIsGroup = false,
                    groupId = "trace-probe-${random.uuid}",
                    topics = Topics.ORG_MEMBER_ROLE_CHANGED)
            void onMessage(ConsumerRecord<String, JsonNode> record) {}
        }
    }
}
