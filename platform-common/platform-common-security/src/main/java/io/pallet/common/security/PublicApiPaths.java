package io.pallet.common.security;

import org.springframework.http.HttpMethod;

public record PublicApiPaths(HttpMethod method, String... patterns) {}
