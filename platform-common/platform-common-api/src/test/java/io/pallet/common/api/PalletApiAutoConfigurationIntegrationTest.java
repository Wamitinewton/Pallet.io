package io.pallet.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = PalletApiAutoConfigurationIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoints.web.exposure.include=health")
class PalletApiAutoConfigurationIntegrationTest {

    @LocalServerPort
    private int port;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void restControllerEndpointIsServedUnderTheDefaultPrefix() {
        String body = client().get().uri("/api/v1/ping").retrieve().body(String.class);

        assertThat(body).isEqualTo("pong");
    }

    @Test
    void theUnprefixedPathIsNotReachable() {
        assertThatThrownBy(() -> client().get().uri("/ping").retrieve().body(String.class))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    @Test
    void actuatorStaysAtItsOwnUnversionedPath() {
        String body = client().get().uri("/actuator/health").retrieve().body(String.class);

        assertThat(body).contains("\"status\"");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @RestController
        static class PingController {

            @GetMapping("/ping")
            String ping() {
                return "pong";
            }
        }
    }
}
