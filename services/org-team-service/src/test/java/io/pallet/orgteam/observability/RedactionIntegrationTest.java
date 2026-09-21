package io.pallet.orgteam.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.observability.CapturedSpansConfiguration.CapturedSpans;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class, CapturedSpansConfiguration.class})
class RedactionIntegrationTest extends ObservabilityIntegrationSupport {

    private static final String PREVIEW = API + "/invites/";
    private static final String TEMPLATE = PREVIEW + "{token}";

    @Autowired
    private CapturedSpans spans;

    @Autowired
    private MeterRegistry registry;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger root;

    @BeforeEach
    void captureLogs() {
        spans.clear();
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(logs);
        logs.stop();
    }

    private String issueToken() throws Exception {
        TestOrg org = newTeamOrg();
        perform(
                        post(API + "/orgs/{orgId}/invites", org.orgId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"jane@example.com\",\"role\":\"DEVELOPER\"}"),
                        org.orgId(),
                        org.owner())
                .andExpect(status().isCreated());
        String acceptUrl = jdbc.queryForObject(
                "SELECT payload->'variables'->>'acceptUrl' FROM org_team.outbox_events "
                        + "WHERE org_id = ? AND sensitive",
                String.class,
                org.orgId());
        return acceptUrl.substring(acceptUrl.indexOf("/invites/") + "/invites/".length());
    }

    private List<String> everythingLogged() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : logs.list) {
            lines.add(event.getFormattedMessage());
            IThrowableProxy throwable = event.getThrowableProxy();
            if (throwable != null) {
                lines.add(ThrowableProxyUtil.asString(throwable));
            }
        }
        return lines;
    }

    private List<String> everySpanAttribute() {
        List<String> values = new ArrayList<>();
        for (SpanData span : spans.all()) {
            values.add(span.getName());
            span.getAttributes().forEach((key, value) -> values.add(key.getKey() + "=" + value));
        }
        return values;
    }

    private List<String> everyMeterTag() {
        List<String> values = new ArrayList<>();
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> values.add(tag.getValue())));
        return values;
    }

    private void assertNoTokenAnywhere(String token) {
        assertThat(everythingLogged()).noneMatch(line -> line.contains(token));
        assertThat(everySpanAttribute()).noneMatch(value -> value.contains(token));
        assertThat(everyMeterTag()).noneMatch(value -> value.contains(token));
    }

    @Test
    void aSuccessfulPreviewLeavesNoTokenInLogsSpansOrMetricsButRecordsTheRouteTemplate() throws Exception {
        String token = issueToken();
        spans.clear();
        logs.list.clear();

        mvc.perform(get(PREVIEW + "{token}", token)).andExpect(status().isOk());

        assertNoTokenAnywhere(token);
        assertThat(everySpanAttribute()).anyMatch(value -> value.endsWith(TEMPLATE));
        assertThat(everythingLogged()).anyMatch(line -> line.contains("GET " + TEMPLATE + " 200"));
    }

    @Test
    void aFailedPreviewEchoesTheTemplateInItsBodyAndLeaksNothingElsewhere() throws Exception {
        String token = issueToken();
        String tampered = token.substring(0, token.length() - 3) + "AAA";
        spans.clear();
        logs.list.clear();

        String body = mvc.perform(get(PREVIEW + "{token}", tampered))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(tampered).doesNotContain(token).contains(TEMPLATE);
        assertNoTokenAnywhere(tampered);
        assertNoTokenAnywhere(token);
    }

    @Test
    void anUnroutedPathUnderThePreviewPrefixIsRedactedToo() throws Exception {
        String token = issueToken();
        spans.clear();
        logs.list.clear();

        String notFound = mvc.perform(get(PREVIEW + "{token}/extra", token))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String wrongMethod = mvc.perform(post(PREVIEW + "{token}", token))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(notFound).doesNotContain(token);
        assertThat(wrongMethod).doesNotContain(token);
        assertNoTokenAnywhere(token);
    }

    @Test
    void theRealTokenStillRoutesToThePreviewBecauseRedactionStartsAfterRouting() throws Exception {
        String token = issueToken();

        mvc.perform(get(PREVIEW + "{token}", token)).andExpect(status().isOk());
    }

    @Test
    void aGarbageBearerCannotTurnThePublicPreviewIntoAnEchoing401() throws Exception {
        String token = issueToken();
        logs.list.clear();

        String body = mvc.perform(get(PREVIEW + "{token}", token).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(token);
        assertNoTokenAnywhere(token);
    }
}
