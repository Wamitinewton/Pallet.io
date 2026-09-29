package io.pallet.gitintegration.session.dto;

import java.time.Instant;

public record AuthorizationStartResponse(String authorizeUrl, Instant expiresAt) {}
