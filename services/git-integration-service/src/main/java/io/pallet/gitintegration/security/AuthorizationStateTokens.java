package io.pallet.gitintegration.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.security.AccessExceptions.InvalidAuthorizationStateException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signed, single-use {@code state} for GitHub's install and authorize redirects: {@code base64url(payload) + "." +
 * base64url(HMAC-SHA256(payload))}, the payload a small JSON {@code {n, p, o, s, e}}. Not a JWT, so there is no
 * algorithm to negotiate. The payload checks reject forgeries without a write; the nonce row is what makes a state work
 * once.
 */
@Component
public class AuthorizationStateTokens {

    public static final String REJECTED = "git.authorization.state_rejected";

    static final int MAX_STATE_LENGTH = 512;

    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public record IssuedState(String state, Instant expiresAt) {

        @Override
        public String toString() {
            return "IssuedState[state=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }

    public record ConsumedState(AuthorizationPurpose purpose, String userId, String orgId) {}

    record StatePayload(
            @JsonProperty("n") UUID nonce,
            @JsonProperty("p") AuthorizationPurpose purpose,
            @JsonProperty("o") String orgId,
            @JsonProperty("s") String userId,
            @JsonProperty("e") long expiresAtEpochSecond) {}

    private final AuthorizationStateRepository states;
    private final SecretKeySpec key;
    private final Duration ttl;
    private final Clock clock;
    private final JsonMapper json;
    private final MeterRegistry meters;

    AuthorizationStateTokens(
            AuthorizationStateRepository states,
            GitIntegrationProperties properties,
            Clock clock,
            JsonMapper json,
            MeterRegistry meters) {
        this.states = states;
        this.key = new SecretKeySpec(
                Base64.getDecoder()
                        .decode(properties.authorization().stateSigningKey().strip()),
                HMAC);
        this.ttl = properties.authorization().stateTtl();
        this.clock = clock;
        this.json = json;
        this.meters = meters;
    }

    public IssuedState issue(AuthorizationPurpose purpose, String userId, String orgId) {
        Objects.requireNonNull(purpose, "purpose");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("A state is always bound to a user");
        }
        if (purpose.requiresOrg() == (orgId == null)) {
            throw new IllegalArgumentException("An org is bound to an INSTALL state and to nothing else");
        }
        UUID nonce = UUID.randomUUID();
        Instant expiresAt = states.insert(nonce, purpose, userId, orgId, ttl);
        byte[] payload =
                json.writeValueAsBytes(new StatePayload(nonce, purpose, orgId, userId, expiresAt.getEpochSecond()));
        return new IssuedState(
                ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload)), expiresAt);
    }

    /**
     * Checks the signature, expiry, purpose, user and org, then consumes the nonce.
     *
     * @throws InvalidAuthorizationStateException on the first check that fails, the same for every one
     */
    public ConsumedState consume(String state, AuthorizationPurpose expected, String userId, String orgId) {
        StatePayload payload = verified(state);
        if (clock.instant().getEpochSecond() >= payload.expiresAtEpochSecond()) {
            throw rejected("expired");
        }
        if (payload.purpose() != expected) {
            throw rejected("purpose");
        }
        if (userId == null || !userId.equals(payload.userId())) {
            throw rejected("user");
        }
        if (!Objects.equals(orgId, payload.orgId())) {
            throw rejected("org");
        }
        if (!states.consume(payload.nonce(), expected, userId, orgId)) {
            throw rejected("consumed");
        }
        return new ConsumedState(expected, userId, orgId);
    }

    private StatePayload verified(String state) {
        if (state == null || state.isEmpty() || state.length() > MAX_STATE_LENGTH) {
            throw rejected("malformed");
        }
        int dot = state.indexOf('.');
        if (dot <= 0 || dot != state.lastIndexOf('.')) {
            throw rejected("malformed");
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = DECODER.decode(state.substring(0, dot));
            signature = DECODER.decode(state.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw rejected("malformed");
        }
        if (!MessageDigest.isEqual(sign(payload), signature)) {
            throw rejected("signature");
        }
        StatePayload parsed;
        try {
            parsed = json.readValue(payload, StatePayload.class);
        } catch (RuntimeException e) {
            throw rejected("malformed");
        }
        if (parsed.nonce() == null || parsed.purpose() == null || parsed.userId() == null) {
            throw rejected("malformed");
        }
        return parsed;
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private InvalidAuthorizationStateException rejected(String reason) {
        meters.counter(REJECTED, "reason", reason).increment();
        return new InvalidAuthorizationStateException();
    }
}
