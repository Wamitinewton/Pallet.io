package io.pallet.gitintegration.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Recorded GitHub payloads from {@code src/test/resources/github/webhooks/}, signed with the test webhook secret the
 * way GitHub signs them.
 *
 * <pre>
 * WebhookFixtures.post(mvc, "push-main.json", Map.of("/after", sha));
 * WebhookFixtures.delivery("push-main.json").deliveryId(id).secret(TestSecrets.PREVIOUS_WEBHOOK_SECRET).post(mvc);
 * </pre>
 */
public final class WebhookFixtures {

    public static final String PATH = "/api/v1/git-integration/webhooks/github";

    private static final String FIXTURES = "/github/webhooks/";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private WebhookFixtures() {}

    public static Delivery delivery(String fixture) {
        return new Delivery(fixture);
    }

    public static MockHttpServletResponse post(MockMvc mvc, String fixture, Map<String, Object> overrides) {
        Delivery delivery = delivery(fixture);
        overrides.forEach(delivery::with);
        return delivery.post(mvc);
    }

    public static byte[] load(String fixture) {
        try (InputStream in = WebhookFixtures.class.getResourceAsStream(FIXTURES + fixture)) {
            if (in == null) {
                throw new IllegalArgumentException("No webhook fixture " + fixture);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The {@code X-Hub-Signature-256} value GitHub would send for {@code body}. */
    public static String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code push-main.json} is {@code push}; the same mapping {@code scripts/send-webhook.sh} uses. */
    public static String eventOf(String fixture) {
        String base = fixture.replaceFirst("\\.json$", "");
        if (base.startsWith("installation-repositories-")) {
            return "installation_repositories";
        }
        if (base.startsWith("github-app-authorization-")) {
            return "github_app_authorization";
        }
        int dash = base.indexOf('-');
        return dash < 0 ? base : base.substring(0, dash);
    }

    public static final class Delivery {

        private final String fixture;
        private final Map<String, Object> overrides = new LinkedHashMap<>();
        private String event;
        private String deliveryId = UUID.randomUUID().toString();
        private String secret = TestSecrets.WEBHOOK_SECRET;
        private String contentType = MediaType.APPLICATION_JSON_VALUE;
        private byte[] body;

        private Delivery(String fixture) {
            this.fixture = fixture;
            this.event = eventOf(fixture);
        }

        /** Replaces the value at a JSON pointer, e.g. {@code /installation/id}. */
        public Delivery with(String pointer, Object value) {
            overrides.put(pointer, value);
            return this;
        }

        public Delivery event(String event) {
            this.event = event;
            return this;
        }

        public Delivery deliveryId(Object deliveryId) {
            this.deliveryId = String.valueOf(deliveryId);
            return this;
        }

        public Delivery secret(String secret) {
            this.secret = secret;
            return this;
        }

        public Delivery contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        /** Sends these bytes instead of the fixture, still signed. */
        public Delivery rawBody(byte[] body) {
            this.body = body.clone();
            return this;
        }

        public String deliveryId() {
            return deliveryId;
        }

        public byte[] body() {
            if (body != null) {
                return body.clone();
            }
            byte[] recorded = load(fixture);
            return overrides.isEmpty() ? recorded : patch(recorded);
        }

        public String signature() {
            return sign(body(), secret);
        }

        public MockHttpServletRequestBuilder request() {
            byte[] bytes = body();
            MockHttpServletRequestBuilder request =
                    MockMvcRequestBuilders.post(PATH).content(bytes);
            if (contentType != null) {
                request.contentType(contentType);
            }
            if (event != null) {
                request.header("X-GitHub-Event", event);
            }
            if (deliveryId != null) {
                request.header("X-GitHub-Delivery", deliveryId);
            }
            if (secret != null) {
                request.header("X-Hub-Signature-256", sign(bytes, secret));
            }
            return request;
        }

        public MockHttpServletResponse post(MockMvc mvc) {
            try {
                return mvc.perform(request()).andReturn().getResponse();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private byte[] patch(byte[] recorded) {
            JsonNode root = MAPPER.readTree(recorded);
            overrides.forEach((pointer, value) -> {
                JsonPointer path = JsonPointer.compile(pointer);
                JsonNode parent = root.at(path.head());
                JsonNode replacement = MAPPER.valueToTree(value);
                if (parent instanceof ObjectNode object) {
                    object.set(path.last().getMatchingProperty(), replacement);
                } else if (parent instanceof ArrayNode array) {
                    array.set(path.last().getMatchingIndex(), replacement);
                } else {
                    throw new IllegalArgumentException("No object or array at " + path.head() + " in " + fixture);
                }
            });
            return MAPPER.writeValueAsBytes(root);
        }
    }
}
