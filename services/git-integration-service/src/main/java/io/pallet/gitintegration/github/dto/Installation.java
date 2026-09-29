package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/** {@code GET /app/installations/{id}}, trimmed to the fields the service reads. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Installation(
        long id,
        Account account,
        @JsonProperty("repository_selection") String repositorySelection,
        Map<String, String> permissions,
        @JsonProperty("suspended_at") Instant suspendedAt) {

    public Installation {
        permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(long id, String login, String type) {}

    public boolean suspended() {
        return suspendedAt != null;
    }

    public boolean allRepositories() {
        return "all".equals(repositorySelection);
    }
}
