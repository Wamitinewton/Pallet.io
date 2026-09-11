package io.pallet.common.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The path prefix every {@code @RestController} endpoint is served under, shared by every
 * service through {@code config-repo}. Bumping the API version is changing {@code prefix} in one
 * place, not editing a {@code @RequestMapping} in each service.
 */
@ConfigurationProperties("pallet.api")
public record ApiPathProperties(@DefaultValue("/api/v1") String prefix) {}
