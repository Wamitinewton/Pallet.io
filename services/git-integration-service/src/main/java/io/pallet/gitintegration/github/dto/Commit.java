package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code GET /repositories/{id}/commits/{sha}}, trimmed to the commit's own SHA. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Commit(String sha) {}
