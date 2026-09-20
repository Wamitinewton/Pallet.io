package io.pallet.identity.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Goes over a real socket rather than MockMvc, since only Tomcat's own forwarded-header handling
 * turns {@code X-Forwarded-For} into the address {@link AuthRateLimitInterceptor} keys on. Behind
 * {@code api-gateway} every request's socket peer is the gateway, so without that handling every
 * caller would share one budget. Declares the same annotations as the other Keycloak-only test
 * classes so they all share one cached Spring context (and one Hikari pool) rather than each
 * holding its own against the shared Postgres.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(KeycloakTestContainerConfiguration.class)
class ForwardedClientAddressRateLimitIntegrationTest {

    private static final int PERMITS_PER_PERIOD = 20;

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void eachForwardedClientGetsItsOwnBudgetEvenThoughTheyShareOneSocketPeer() throws Exception {
        int lastStatusForFirstClient = 0;
        for (int i = 0; i <= PERMITS_PER_PERIOD; i++) {
            lastStatusForFirstClient = resendVerificationAs("198.51.100.1");
        }

        assertThat(lastStatusForFirstClient).isEqualTo(429);
        assertThat(resendVerificationAs("198.51.100.2")).isEqualTo(202);
    }

    private int resendVerificationAs(String forwardedClient) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/identity/auth/email/resend-verification"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedClient)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody@pallet-test.local\"}"))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }
}
