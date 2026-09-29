package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One page of {@code GET /user/installations}: the installations both the app and the user can reach. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserInstallations(@JsonProperty("total_count") int totalCount, List<Installation> installations) {}
