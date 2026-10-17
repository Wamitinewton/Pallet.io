package io.pallet.buildqueue.security;

import io.pallet.common.api.ApiPathProperties;
import io.pallet.common.security.PublicApiPaths;
import io.pallet.common.security.RedisRevokedSessionRegistry;
import io.pallet.common.security.RevokedSessionRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Adds this service's API docs to {@code platform-common-security}'s stateless, CSRF-free resource-server chain. Every
 * other path but actuator health needs a bearer token.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {

    static final String NAMESPACE = "/build-queue";
    static final String API_DOCS_PATHS = NAMESPACE + "/v3/api-docs/**";

    /** Replaces {@code platform-common-openapi}'s exact-path bean so the docs' sub-resources are public too. */
    @Bean
    PublicApiPaths openApiPublicApiPaths(ApiPathProperties apiPath) {
        return new PublicApiPaths(HttpMethod.GET, apiPath.prefix() + API_DOCS_PATHS);
    }

    /** An in-memory registry would make a revocation visible on one instance only (ADR-0015). */
    @Bean
    SmartInitializingSingleton sharedSessionRevocation(RevokedSessionRegistry registry) {
        return () -> {
            if (!(registry instanceof RedisRevokedSessionRegistry)) {
                throw new IllegalStateException("session revocation must use the shared Redis registry, found "
                        + registry.getClass().getName());
            }
        };
    }
}
