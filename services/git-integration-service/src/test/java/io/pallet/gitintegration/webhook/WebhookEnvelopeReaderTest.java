package io.pallet.gitintegration.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.support.WebhookFixtures;
import io.pallet.gitintegration.webhook.WebhookEnvelopeReader.WebhookEnvelope;
import io.pallet.gitintegration.webhook.WebhookExceptions.MalformedWebhookPayloadException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@UnitTest
class WebhookEnvelopeReaderTest {

    private static final long MAX_DOCUMENT = 4L * 1024 * 1024;

    private final WebhookEnvelopeReader reader = new WebhookEnvelopeReader(MAX_DOCUMENT);
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void readsTheActionAndTheInstallationId() {
        WebhookEnvelope envelope = reader.read(WebhookFixtures.load("installation-created.json"));

        assertThat(envelope.action()).isEqualTo("created");
        assertThat(envelope.installationId()).isEqualTo(41000001L);
        assertThat(envelope.sanitized()).isFalse();
    }

    @Test
    void aPushHasNoActionAndTheCopyIsTheSameDocument() {
        byte[] body = WebhookFixtures.load("push-main.json");

        WebhookEnvelope envelope = reader.read(body);

        assertThat(envelope.action()).isNull();
        assertThat(envelope.installationId()).isEqualTo(41000001L);
        assertThat(mapper.readTree(envelope.payload())).isEqualTo(mapper.readTree(body));
    }

    @Test
    void aDeliveryWithoutAnInstallationHasNoInstallationId() {
        assertThat(reader.read(WebhookFixtures.load("github-app-authorization-revoked.json"))
                        .installationId())
                .isNull();
    }

    @Test
    void onlyTopLevelFieldsAreRead() {
        WebhookEnvelope envelope =
                read("{\"pull\":{\"action\":\"nested\",\"installation\":{\"id\":7}},\"installation\":{\"id\":9}}");

        assertThat(envelope.action()).isNull();
        assertThat(envelope.installationId()).isEqualTo(9L);
    }

    @Test
    void sixtyFourLevelsOfNestingAreAccepted() {
        assertThat(read(nested(WebhookEnvelopeReader.MAX_NESTING_DEPTH)).payload())
                .isNotEmpty();
    }

    @Test
    void aDocumentNestedSixtyFiveLevelsIsRejected() {
        assertRejected(nested(WebhookEnvelopeReader.MAX_NESTING_DEPTH + 1));
    }

    @Test
    void aStringOverTheLimitIsRejectedWhereverItIs() {
        String tooLong = "a".repeat(WebhookEnvelopeReader.MAX_STRING_LENGTH + 1);

        assertRejected("{\"head_commit\":{\"message\":\"" + tooLong + "\"}}");
    }

    @Test
    void aDocumentOverTheLengthLimitIsRejected() {
        WebhookEnvelopeReader small = new WebhookEnvelopeReader(64);

        assertThatThrownBy(() -> small.read(("{\"zen\":\"" + "z".repeat(100) + "\"}").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(MalformedWebhookPayloadException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"action\":\"created\"} {}", "{\"action\":\"created\"}x", "{}]"})
    void trailingContentIsRejected(String json) {
        assertRejected(json);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"installation\":{\"id\":-5}}",
                "{\"installation\":{\"id\":0}}",
                "{\"installation\":{\"id\":\"41000001\"}}",
                "{\"installation\":{\"id\":1.5}}",
                "{\"installation\":{\"id\":true}}",
                "{\"installation\":{\"id\":{\"value\":1}}}",
                "{\"installation\":{\"id\":99999999999999999999999}}"
            })
    void anInstallationIdThatIsNotAPositiveLongIsRejected(String json) {
        assertRejected(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"action\":5}", "{\"action\":[\"created\"]}", "{\"action\":{\"name\":\"created\"}}"})
    void anActionThatIsNotAStringIsRejected(String json) {
        assertRejected(json);
    }

    @Test
    void anActionOverSixtyFourCharactersIsRejected() {
        assertRejected("{\"action\":\"" + "a".repeat(WebhookEnvelopeReader.MAX_ACTION_LENGTH + 1) + "\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "[]", "\"push\"", "42", "null", "{\"action\":", "{\"action\" \"x\"}", "{'a':1}"})
    void aBodyThatIsNotOneJsonObjectIsRejected(String json) {
        assertRejected(json);
    }

    @Test
    void aNulEscapeInAValueOrANameIsReplacedAndReported() {
        WebhookEnvelope envelope = read("{\"head_commit\":{\"message\":\"fix\\u0000bug\"},\"k\\u0000\":1}");

        assertThat(envelope.sanitized()).isTrue();
        assertThat(envelope.payload()).doesNotContain("\\u0000");
        JsonNode stored = mapper.readTree(envelope.payload());
        assertThat(stored.at("/head_commit/message").asString()).isEqualTo("fix�bug");
        assertThat(stored.has("k�")).isTrue();
    }

    @Test
    void numbersAndUnicodeSurviveTheCopyExactly() {
        WebhookEnvelope envelope =
                read("{\"size\":12345678901234567890,\"ratio\":0.1000000000000000055511," + "\"message\":\"café 🚀\"}");

        assertThat(envelope.payload()).contains("12345678901234567890", "0.1000000000000000055511");
        assertThat(mapper.readTree(envelope.payload()).get("message").asString())
                .isEqualTo("café 🚀");
    }

    private WebhookEnvelope read(String json) {
        return reader.read(json.getBytes(StandardCharsets.UTF_8));
    }

    private void assertRejected(String json) {
        assertThatThrownBy(() -> read(json)).isInstanceOf(MalformedWebhookPayloadException.class);
    }

    private static String nested(int depth) {
        return "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1);
    }
}
