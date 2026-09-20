package io.pallet.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The OpenAPI spec must be reachable with no token: {@code api-gateway}'s own {@code public-paths}
 * allowlist only controls the edge layer (ADR-0012 decision 2) - this service's own chain has to
 * permit it independently, or a request forwarded through the gateway still 401s here.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(KeycloakTestContainerConfiguration.class)
class SecurityConfigurationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    /**
     * Every account-bootstrap and recovery entry point has no token to present. An empty JSON body
     * is enough: the request must get past the security chain and be rejected (or served) by the
     * controller, never turned away with a 401.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/identity/signup",
                "/api/v1/identity/invites/some-token/accept",
                "/api/v1/identity/auth/login",
                "/api/v1/identity/auth/refresh",
                "/api/v1/identity/auth/email/verify",
                "/api/v1/identity/auth/email/resend-verification",
                "/api/v1/identity/auth/password/forgot",
                "/api/v1/identity/auth/password/reset"
            })
    void publicPostEndpointsAreNotBlockedByTheSecurityChain(String path) throws Exception {
        int status = mvc.perform(
                        post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isNotEqualTo(401).isNotEqualTo(403);
    }

    @Test
    void slugAvailabilityIsPublic() throws Exception {
        mvc.perform(get("/api/v1/identity/signup/slugs/some-slug/availability")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/identity/auth/logout", "/api/v1/identity/users/me/password"})
    void nonPublicPostEndpointsStillRequireAToken(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/identity/users/me", "/api/v1/identity/users/me/sessions"})
    void nonPublicGetEndpointsStillRequireAToken(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void apiDocsIsReachableWithNoAuthenticationTokenAtItsSingleNonDoublePrefixedPath() throws Exception {
        mvc.perform(get("/api/v1/identity/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/identity/signup']").exists());
    }
}
