package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code GET /repositories/{id}/branches/{branch}}, trimmed to the head commit. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Branch(String name, Commit commit) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(String sha) {}
}
