package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** {@code GET /meta}, trimmed to the ranges GitHub delivers webhooks from. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Meta(List<String> hooks) {}
