package io.pallet.gitintegration.repolink.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** {@code eventId} is the {@code GitPushReceived} that asks for the build. */
public record ManualBuildResultDto(
        @Schema(description = "Names the build request; a retry with the same key returns the same id.")
        UUID eventId,

        @Schema(example = "0123456789abcdef0123456789abcdef01234567")
        String commitSha,

        @Schema(example = "main") String branch) {}
