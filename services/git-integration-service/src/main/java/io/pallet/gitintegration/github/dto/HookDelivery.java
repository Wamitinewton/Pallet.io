package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * One attempt in {@code GET /app/hook/deliveries}. A redelivery is a new entry with its own {@code id} and the original
 * {@code guid}; {@code status} is {@code OK} only when the receiver answered 2xx.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HookDelivery(
        long id,
        String guid,
        @JsonProperty("delivered_at") Instant deliveredAt,
        boolean redelivery,
        String status,
        @JsonProperty("status_code") int statusCode,
        String event,
        String action,
        @JsonProperty("installation_id") Long installationId) {

    public static final String STATUS_OK = "OK";

    public boolean succeeded() {
        return STATUS_OK.equals(status);
    }
}
