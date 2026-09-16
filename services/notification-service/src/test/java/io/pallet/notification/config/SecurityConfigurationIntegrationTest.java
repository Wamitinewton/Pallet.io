package io.pallet.notification.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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

    @Test
    void apiDocsIsReachableWithNoAuthenticationTokenAtItsSingleNonDoublePrefixedPath() throws Exception {
        mvc.perform(get("/api/v1/notification/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/notification/notifications']")
                        .exists());
    }

    @Test
    void everyOtherEndpointStaysBehindAValidToken() throws Exception {
        mvc.perform(get("/api/v1/notification/notifications")).andExpect(status().isUnauthorized());
    }
}
