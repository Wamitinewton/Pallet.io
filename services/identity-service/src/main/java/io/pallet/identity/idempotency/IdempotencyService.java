package io.pallet.identity.idempotency;

import io.pallet.common.error.IdempotencyKeyReuseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes a mutating POST safe to retry: a fresh key runs {@code operation} and caches its result; a
 * replayed key with an identical request body returns the cached response without running
 * {@code operation} again; a replayed key with a different body is a caller bug, surfaced as
 * {@link IdempotencyKeyReuseException}.
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;
    private final JsonMapper jsonMapper;

    public IdempotencyService(IdempotencyKeyRepository repository, ObjectProvider<JsonMapper> jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper.getIfAvailable(() -> JsonMapper.builder().build());
    }

    public <T> T execute(String idempotencyKey, Object requestBody, Supplier<T> operation, Class<T> responseType) {
        String requestHash = hash(requestBody);

        return repository
                .findById(idempotencyKey)
                .map(existing -> replay(existing, requestHash, responseType))
                .orElseGet(() -> executeAndCache(idempotencyKey, requestHash, operation));
    }

    private <T> T replay(IdempotencyKeyRecord existing, String requestHash, Class<T> responseType) {
        if (!MessageDigest.isEqual(
                existing.getRequestHash().getBytes(StandardCharsets.UTF_8),
                requestHash.getBytes(StandardCharsets.UTF_8))) {
            throw new IdempotencyKeyReuseException(
                    "Idempotency-Key '" + existing.getIdempotencyKey() + "' was reused with a different request body.");
        }
        return jsonMapper.readValue(existing.getResponseBody(), responseType);
    }

    private <T> T executeAndCache(String idempotencyKey, String requestHash, Supplier<T> operation) {
        T result = operation.get();
        String responseBody = jsonMapper.writeValueAsString(result);
        repository.save(
                new IdempotencyKeyRecord(idempotencyKey, requestHash, HttpStatus.CREATED.value(), responseBody));
        return result;
    }

    private String hash(Object requestBody) {
        byte[] canonicalJson = jsonMapper.writeValueAsBytes(requestBody);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
