package io.pallet.gitintegration.repolink.dto;

import java.util.UUID;

/** {@code eventId} is the {@code GitPushReceived} that asks for the build. */
public record ManualBuildResultDto(UUID eventId, String commitSha, String branch) {}
