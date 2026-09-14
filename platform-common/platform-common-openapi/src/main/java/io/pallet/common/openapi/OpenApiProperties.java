package io.pallet.common.openapi;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Optional overrides for the default {@code Info} block. A blank {@code title} falls back to a
 * title-cased {@code spring.application.name}; a blank {@code description} falls back to a
 * platform-wide sentence describing the {@code ApiResponse}/{@code PageResponse}/
 * {@code ErrorResponse} envelope.
 */
@ConfigurationProperties("pallet.openapi")
public record OpenApiProperties(
        @DefaultValue("") String title, @DefaultValue("") String description) {}
