package io.pallet.identity.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import io.pallet.common.test.json.JsonTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Deliberately does not import {@code KeycloakAdminTestConfiguration}: {@code application-test.yml}
 * points {@code pallet.identity.keycloak.server-url} at an unreachable port, so every token
 * endpoint call here is a genuine infra failure, which must surface as a {@code 502} through
 * {@code ExternalCall} - never as a {@code 401} bad-credentials outcome.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(KeycloakTestContainerConfiguration.class)
class AuthKeycloakOutageIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void loginWhileKeycloakIsUnreachableIsBadGatewayNotUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/identity/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new LoginRequest("someone@pallet-test.local", "whatever-pw"))))
                .andExpect(status().isBadGateway());
    }

    @Test
    void refreshWhileKeycloakIsUnreachableIsBadGatewayNotUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/identity/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonTestSupport.toJson(new RefreshRequest("some-refresh-token"))))
                .andExpect(status().isBadGateway());
    }
}
