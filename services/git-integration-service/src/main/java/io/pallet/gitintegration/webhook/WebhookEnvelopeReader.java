package io.pallet.gitintegration.webhook;

import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.delivery.WebhookJson;
import io.pallet.gitintegration.webhook.WebhookExceptions.MalformedWebhookPayloadException;
import java.io.StringWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code action} and {@code installation.id} from a verified delivery under explicit read limits and, in the same
 * pass, copies the document for storage with every {@code \u0000} replaced, since Postgres {@code jsonb} rejects it.
 */
@Component
public class WebhookEnvelopeReader {

    static final int MAX_NESTING_DEPTH = WebhookJson.MAX_NESTING_DEPTH;
    static final int MAX_STRING_LENGTH = WebhookJson.MAX_STRING_LENGTH;
    static final int MAX_ACTION_LENGTH = 64;

    private static final String ACTION = "action";
    private static final String INSTALLATION = "installation";
    private static final String ID = "id";
    private static final char NUL = '\u0000';
    private static final char REPLACEMENT = '�';

    private final JsonMapper mapper;

    @Autowired
    WebhookEnvelopeReader(GitIntegrationProperties properties) {
        this(properties.webhook().maxBody().toBytes());
    }

    WebhookEnvelopeReader(long maxDocumentLength) {
        this.mapper = WebhookJson.limitedMapper(maxDocumentLength);
    }

    /** @throws MalformedWebhookPayloadException if the body isn't one JSON object, breaks a limit, or has a bad field */
    public WebhookEnvelope read(byte[] body) {
        StringWriter copy = new StringWriter(body.length);
        Envelope envelope = new Envelope();
        try (JsonParser parser = mapper.createParser(body);
                JsonGenerator generator = mapper.createGenerator(copy)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new MalformedWebhookPayloadException("not a JSON object");
            }
            generator.writeStartObject();
            while (!parser.streamReadContext().inRoot()) {
                JsonToken token = parser.nextToken();
                if (token == null) {
                    throw new MalformedWebhookPayloadException("truncated document");
                }
                copy(token, parser, generator, envelope);
            }
            if (parser.nextToken() != null) {
                throw new MalformedWebhookPayloadException("content after the document");
            }
        } catch (StreamConstraintsException e) {
            throw new MalformedWebhookPayloadException("read limit exceeded");
        } catch (JacksonException e) {
            throw new MalformedWebhookPayloadException("not valid JSON");
        }
        return new WebhookEnvelope(envelope.action, envelope.installationId, copy.toString(), envelope.sanitized);
    }

    private static void copy(JsonToken token, JsonParser parser, JsonGenerator generator, Envelope envelope) {
        switch (token) {
            case PROPERTY_NAME -> generator.writeName(envelope.clean(parser.currentName()));
            case VALUE_STRING -> {
                envelope.accept(token, parser.streamReadContext(), parser);
                generator.writeString(envelope.clean(parser.getString()));
            }
            case START_OBJECT, START_ARRAY -> {
                envelope.accept(token, parser.streamReadContext().getParent(), parser);
                generator.copyCurrentEvent(parser);
            }
            case END_OBJECT, END_ARRAY -> generator.copyCurrentEvent(parser);
            default -> {
                envelope.accept(token, parser.streamReadContext(), parser);
                generator.copyCurrentEventExact(parser);
            }
        }
    }

    /** {@code payload} is the copy to store; {@code sanitized} says whether a {@code \u0000} was replaced in it. */
    public record WebhookEnvelope(String action, Long installationId, String payload, boolean sanitized) {}

    private static final class Envelope {

        private String action;
        private Long installationId;
        private boolean sanitized;

        /** {@code slot} is the context holding the value: the enclosing one for a value that opens a container. */
        void accept(JsonToken token, TokenStreamContext slot, JsonParser parser) {
            if (isTopLevel(slot, ACTION)) {
                action = switch (token) {
                    case VALUE_NULL -> null;
                    case VALUE_STRING -> requireActionLength(parser.getString());
                    default -> throw new MalformedWebhookPayloadException("action is not a string");
                };
            } else if (isInstallationId(slot)) {
                installationId = switch (token) {
                    case VALUE_NULL -> null;
                    case VALUE_NUMBER_INT -> requirePositiveLong(parser);
                    default -> throw new MalformedWebhookPayloadException("installation.id is not an integer");
                };
            }
        }

        String clean(String value) {
            if (value.indexOf(NUL) < 0) {
                return value;
            }
            sanitized = true;
            return value.replace(NUL, REPLACEMENT);
        }

        private static String requireActionLength(String value) {
            if (value.length() > MAX_ACTION_LENGTH) {
                throw new MalformedWebhookPayloadException("action is too long");
            }
            return value;
        }

        private static long requirePositiveLong(JsonParser parser) {
            JsonParser.NumberType type = parser.getNumberType();
            if (type != JsonParser.NumberType.INT && type != JsonParser.NumberType.LONG) {
                throw new MalformedWebhookPayloadException("installation.id is out of range");
            }
            long id = parser.getLongValue();
            if (id <= 0) {
                throw new MalformedWebhookPayloadException("installation.id is not positive");
            }
            return id;
        }

        private static boolean isTopLevel(TokenStreamContext slot, String name) {
            return slot != null && slot.inObject() && slot.getParent().inRoot() && name.equals(slot.currentName());
        }

        private static boolean isInstallationId(TokenStreamContext slot) {
            return slot != null
                    && slot.inObject()
                    && ID.equals(slot.currentName())
                    && isTopLevel(slot.getParent(), INSTALLATION);
        }
    }
}
