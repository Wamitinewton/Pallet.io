package io.pallet.gitintegration.session.dto;

import java.time.Instant;

public record GitHubSessionResponse(String githubLogin, Instant expiresAt) {}
