package io.pallet.gitintegration.security;

import io.pallet.common.api.ApiPathProperties;
import io.pallet.common.security.PublicApiPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Adds this service's public paths to {@code platform-common-security}'s stateless, CSRF-free resource-server chain.
 * The webhook is authenticated by its HMAC signature, not a bearer token.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {

    static final String NAMESPACE = "/git-integration";
    static final String WEBHOOK_PATH = NAMESPACE + "/webhooks/github";
    static final String API_DOCS_PATHS = NAMESPACE + "/v3/api-docs/**";

    @Bean
    PublicApiPaths githubWebhookPublicPath(ApiPathProperties apiPath) {
        return new PublicApiPaths(HttpMethod.POST, apiPath.prefix() + WEBHOOK_PATH);
    }

    @Bean
    PublicApiPaths apiDocsPublicPaths(ApiPathProperties apiPath) {
        return new PublicApiPaths(HttpMethod.GET, apiPath.prefix() + API_DOCS_PATHS);
    }
}
