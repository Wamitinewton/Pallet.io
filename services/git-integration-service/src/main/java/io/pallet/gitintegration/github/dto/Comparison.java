package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code GET /repositories/{id}/compare/{base}...{head}}, trimmed to how {@code head} relates to {@code base}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Comparison(String status) {}
