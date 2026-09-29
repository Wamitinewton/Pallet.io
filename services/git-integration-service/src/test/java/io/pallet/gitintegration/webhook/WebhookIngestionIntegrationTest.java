package io.pallet.gitintegration.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.api.ApiResponse;
import io.pallet.common.error.ErrorResponse;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.assertions.ErrorResponseAssert;
import io.pallet.common.test.assertions.PalletAssertions;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryStore;
import io.pallet.gitintegration.delivery.SubscribedEvents;
import io.pallet.gitintegration.support.ProbeController;
import io.pallet.gitintegration.support.TestSecrets;
import io.pallet.gitintegration.support.Tokens;
import io.pallet.gitintegration.support.WebhookFixtures;
import io.pallet.gitintegration.support.WebhookFixtures.Delivery;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, Tokens.LocalDecoder.class, ProbeController.class})
class WebhookIngestionIntegrationTest {

    private static final int MAX_BODY = 1024 * 1024;
    private static final String TRACEPARENT = "00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JsonMapper jsonMapper;

    @LocalServerPort
    private int port;

    private final List<String> deliveryIds = new ArrayList<>();

    @AfterEach
    void removeDeliveries() {
        deliveryIds.forEach(
                id -> jdbc.update("delete from git_integration.webhook_deliveries where delivery_id = ?::uuid", id));
    }

    @Test
    void aSignedPushIsStoredOnceAndAcknowledgedWith202ThenARedeliveryWith200() throws Exception {
        Delivery push = track(WebhookFixtures.delivery("push-main.json"));
        double storedBefore = received("push", WebhookMetrics.STORED);
        double duplicateBefore = received("push", WebhookMetrics.DUPLICATE);

        MockHttpServletResponse first = push.post(mvc);

        assertThat(first.getStatus()).isEqualTo(202);
        assertThat(message(first)).isEqualTo("Delivery accepted.");
        Map<String, Object> row = row(push.deliveryId());
        assertThat(row)
                .containsEntry("event", "push")
                .containsEntry("status", "RECEIVED")
                .containsEntry("installation_id", 41000001L)
                .containsEntry("attempts", 0);
        assertThat(row.get("action")).isNull();
        assertThat(row.get("outcome_reason")).isNull();
        assertThat((String) row.get("traceparent")).matches(TRACEPARENT);
        assertThat(jsonMapper.readTree((String) row.get("payload")))
                .isEqualTo(jsonMapper.readTree(WebhookFixtures.load("push-main.json")));

        MockHttpServletResponse again = push.post(mvc);

        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(message(again)).isEqualTo("Delivery already received.");
        assertThat(count(push.deliveryId())).isOne();
        assertThat(received("push", WebhookMetrics.STORED)).isEqualTo(storedBefore + 1);
        assertThat(received("push", WebhookMetrics.DUPLICATE)).isEqualTo(duplicateBefore + 1);
    }

    @Test
    void theActionIsStoredForAnEventThatHasOne() {
        Delivery created = track(WebhookFixtures.delivery("installation-created.json"));

        assertThat(created.post(mvc).getStatus()).isEqualTo(202);

        assertThat(row(created.deliveryId()))
                .containsEntry("event", "installation")
                .containsEntry("action", "created")
                .containsEntry("installation_id", 41000001L);
    }

    @Test
    void everyRecordedFixtureIsAccepted() {
        for (String fixture : List.of(
                "push-forced.json",
                "push-tag.json",
                "push-branch-deleted.json",
                "push-truncated-commits.json",
                "installation-deleted.json",
                "installation-suspend.json",
                "installation-unsuspend.json",
                "installation-repositories-added.json",
                "installation-repositories-removed.json",
                "repository-renamed.json",
                "repository-transferred.json",
                "repository-deleted.json",
                "repository-archived.json",
                "github-app-authorization-revoked.json")) {
            Delivery delivery = track(WebhookFixtures.delivery(fixture));

            assertThat(delivery.post(mvc).getStatus()).as(fixture).isEqualTo(202);
            assertThat(row(delivery.deliveryId()))
                    .as(fixture)
                    .containsEntry("status", "RECEIVED")
                    .containsEntry("event", WebhookFixtures.eventOf(fixture));
        }
    }

    @Test
    void aPingIsAnsweredWithPongAndNotStored() throws Exception {
        Delivery ping = track(WebhookFixtures.delivery("ping.json"));

        MockHttpServletResponse response = ping.post(mvc);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(message(response)).isEqualTo("pong");
        assertThat(count(ping.deliveryId())).isZero();
    }

    @Test
    void anUnsubscribedEventIsStoredAsIgnored() {
        Delivery issues =
                track(WebhookFixtures.delivery("repository-renamed.json").event("issues"));
        double ignoredBefore = received(SubscribedEvents.OTHER, WebhookMetrics.IGNORED);

        assertThat(issues.post(mvc).getStatus()).isEqualTo(202);

        assertThat(row(issues.deliveryId()))
                .containsEntry("event", "issues")
                .containsEntry("status", "IGNORED")
                .containsEntry("outcome_reason", DeliveryStore.UNSUBSCRIBED_EVENT);
        assertThat(received(SubscribedEvents.OTHER, WebhookMetrics.IGNORED)).isEqualTo(ignoredBefore + 1);
    }

    @Test
    void aBadSignatureIs401WithAGenericBodyAndStoresNothing() throws Exception {
        Delivery forged = track(WebhookFixtures.delivery("push-main.json").secret(TestSecrets.hex(32)));
        double failuresBefore = counter(WebhookMetrics.SIGNATURE_FAILURES);
        double rejectedBefore = received("push", WebhookMetrics.REJECTED);

        MockHttpServletResponse response = forged.post(mvc);

        assertError(response, 401)
                .hasErrorCode("WEBHOOK_SIGNATURE_INVALID")
                .hasMessage("The webhook signature is invalid.");
        assertThat(response.getContentAsString()).doesNotContainIgnoringCase("secret");
        assertThat(count(forged.deliveryId())).isZero();
        assertThat(counter(WebhookMetrics.SIGNATURE_FAILURES)).isEqualTo(failuresBefore + 1);
        assertThat(received("push", WebhookMetrics.REJECTED)).isEqualTo(rejectedBefore + 1);
    }

    @Test
    void aMissingSignatureIs401() throws Exception {
        Delivery unsigned = track(WebhookFixtures.delivery("push-main.json").secret(null));

        assertError(unsigned.post(mvc), 401).hasErrorCode("WEBHOOK_SIGNATURE_INVALID");
        assertThat(count(unsigned.deliveryId())).isZero();
    }

    @Test
    void aDeliverySignedWithThePreviousSecretIsAcceptedAndCountedAsPrevious() {
        Delivery rotated =
                track(WebhookFixtures.delivery("push-main.json").secret(TestSecrets.PREVIOUS_WEBHOOK_SECRET));
        double previousBefore = signatureVerified(WebhookSignatureVerifier.PREVIOUS);

        assertThat(rotated.post(mvc).getStatus()).isEqualTo(202);

        assertThat(count(rotated.deliveryId())).isOne();
        assertThat(signatureVerified(WebhookSignatureVerifier.PREVIOUS)).isEqualTo(previousBefore + 1);
    }

    @Test
    void aContentLengthAboveTheCapIs413WithoutTheBodyBeingRead() throws Exception {
        String head = "POST " + WebhookFixtures.PATH + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + (MAX_BODY + 1) + "\r\n"
                + "\r\n";

        assertThat(rawStatus(head, new byte[0], false)).isEqualTo(413);
    }

    @Test
    void aChunkedBodyAboveTheCapIs413() throws Exception {
        String head = "POST " + WebhookFixtures.PATH + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n";

        assertThat(rawStatus(head, new byte[MAX_BODY + 1], true)).isEqualTo(413);
    }

    @Test
    void aBodyExactlyAtTheCapIsNotRejectedForItsSize() throws Exception {
        byte[] body = new byte[MAX_BODY];
        Arrays.fill(body, (byte) ' ');
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(json, 0, body, 0, json.length);
        Delivery atCap = track(WebhookFixtures.delivery("push-main.json").rawBody(body));

        assertThat(atCap.post(mvc).getStatus()).isEqualTo(202);
    }

    @Test
    void aSignedDeliveryWithANonUuidDeliveryIdIs400() throws Exception {
        MockHttpServletResponse response = WebhookFixtures.delivery("push-main.json")
                .deliveryId("72d3162e")
                .post(mvc);

        assertError(response, 400).hasErrorCode("INVALID_WEBHOOK_HEADERS");
    }

    @Test
    void aSignedDeliveryWithAnInvalidEventNameIs400() throws Exception {
        Delivery delivery = track(WebhookFixtures.delivery("push-main.json").event("Push-Event"));

        assertError(delivery.post(mvc), 400).hasErrorCode("INVALID_WEBHOOK_HEADERS");
        assertThat(count(delivery.deliveryId())).isZero();
    }

    @Test
    void aSignedPlainTextDeliveryIs415() throws Exception {
        Delivery delivery = track(WebhookFixtures.delivery("push-main.json").contentType(MediaType.TEXT_PLAIN_VALUE));

        assertError(delivery.post(mvc), 415).hasErrorCode("UNSUPPORTED_MEDIA_TYPE");
        assertThat(count(delivery.deliveryId())).isZero();
    }

    @Test
    void theSignatureIsCheckedBeforeTheContentTypeAndTheHeaders() throws Exception {
        MockHttpServletResponse response = WebhookFixtures.delivery("push-main.json")
                .contentType(MediaType.TEXT_PLAIN_VALUE)
                .deliveryId("not-a-uuid")
                .secret(TestSecrets.hex(32))
                .post(mvc);

        assertError(response, 401).hasErrorCode("WEBHOOK_SIGNATURE_INVALID");
    }

    @Test
    void aSignedBodyThatIsNotJsonIs400AndStoresNothing() throws Exception {
        Delivery delivery =
                track(WebhookFixtures.delivery("push-main.json").rawBody("{\"ref\":".getBytes(StandardCharsets.UTF_8)));

        assertError(delivery.post(mvc), 400).hasErrorCode("MALFORMED_WEBHOOK_PAYLOAD");
        assertThat(count(delivery.deliveryId())).isZero();
    }

    @Test
    void aNulEscapeInACommitMessageIsStoredAsTheReplacementCharacter() {
        Delivery delivery =
                track(WebhookFixtures.delivery("push-main.json").with("/head_commit/message", "fix\u0000bug"));
        double sanitizedBefore = counter(WebhookMetrics.PAYLOAD_SANITIZED);

        assertThat(delivery.post(mvc).getStatus()).isEqualTo(202);

        assertThat(jdbc.queryForObject(
                        "select payload #>> '{head_commit,message}' from git_integration.webhook_deliveries"
                                + " where delivery_id = ?::uuid",
                        String.class,
                        delivery.deliveryId()))
                .isEqualTo("fix�bug");
        assertThat(counter(WebhookMetrics.PAYLOAD_SANITIZED)).isEqualTo(sanitizedBefore + 1);
    }

    @Test
    void aBearerTokenForANonMemberChangesNothing() throws Exception {
        Delivery delivery = track(WebhookFixtures.delivery("push-main.json"));

        MockHttpServletResponse response = mvc.perform(delivery.request()
                        .header(
                                "Authorization",
                                "Bearer "
                                        + Tokens.forUser("not-a-member-" + UUID.randomUUID())
                                                .signed()))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(count(delivery.deliveryId())).isOne();
    }

    @Test
    void theHandlerAnswersTwoHundredSequentialPushesWithAP99Under200Ms() {
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Delivery delivery = track(WebhookFixtures.delivery("push-main.json"));
            long start = System.nanoTime();
            assertThat(delivery.post(mvc).getStatus()).isEqualTo(202);
            millis.add((System.nanoTime() - start) / 1_000_000);
        }
        Collections.sort(millis);

        assertThat(millis.get(197)).isLessThan(200L);
    }

    private Delivery track(Delivery delivery) {
        deliveryIds.add(delivery.deliveryId());
        return delivery;
    }

    private Map<String, Object> row(String deliveryId) {
        return jdbc.queryForMap(
                "select event, action, installation_id, payload::text as payload, status, outcome_reason, attempts,"
                        + " traceparent from git_integration.webhook_deliveries where delivery_id = ?::uuid",
                deliveryId);
    }

    private int count(String deliveryId) {
        return jdbc.queryForObject(
                "select count(*) from git_integration.webhook_deliveries where delivery_id = ?::uuid",
                Integer.class,
                deliveryId);
    }

    private String message(MockHttpServletResponse response) throws Exception {
        ApiResponse<Void> body =
                jsonMapper.readValue(response.getContentAsString(), new TypeReference<ApiResponse<Void>>() {});
        PalletAssertions.assertThat(body).isSuccess();
        return body.message();
    }

    private ErrorResponseAssert assertError(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        ErrorResponse body = jsonMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        return PalletAssertions.assertThat(body).isFailure().hasStatusCode(status);
    }

    private double received(String event, String result) {
        Counter counter = meters.find(WebhookMetrics.RECEIVED)
                .tag(WebhookMetrics.TAG_EVENT, event)
                .tag(WebhookMetrics.TAG_RESULT, result)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double signatureVerified(String secret) {
        Counter counter = meters.find(WebhookMetrics.SIGNATURE_VERIFIED)
                .tag(WebhookMetrics.TAG_SECRET, secret)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double counter(String name) {
        return meters.get(name).counter().count();
    }

    /** Sends a request over a plain socket, so a test controls {@code Content-Length} and chunking itself. */
    private int rawStatus(String head, byte[] body, boolean chunked) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            if (chunked) {
                int chunk = 64 * 1024;
                for (int offset = 0; offset < body.length; offset += chunk) {
                    int length = Math.min(chunk, body.length - offset);
                    out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body, offset, length);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            } else {
                out.write(body);
            }
            out.flush();
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = in.readLine();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
