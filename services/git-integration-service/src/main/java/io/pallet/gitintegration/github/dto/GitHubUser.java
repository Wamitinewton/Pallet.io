package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code GET /user}, trimmed to the id and login. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubUser(long id, String login) {}
