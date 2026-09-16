package io.pallet.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.api.PalletApiAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        classes = PalletResourceServerAutoConfigurationIntegrationTest.TestApp.class,
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.invalid/jwks")
@AutoConfigureMockMvc
class PalletResourceServerAutoConfigurationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void aContributedPublicApiPathBypassesAuthenticationOnTheDefaultChain() throws Exception {
        mvc.perform(get("/open")).andExpect(status().isOk());
    }

    @Test
    void everyOtherRouteStaysBehindAValidToken() throws Exception {
        mvc.perform(get("/secured")).andExpect(status().isUnauthorized());
    }

    @Test
    void aMissingTokenRendersAnErrorResponseBodyInsteadOfAnEmptyOne() throws Exception {
        mvc.perform(get("/secured"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.statusCode").value(401))
                .andExpect(jsonPath("$.path").value("/secured"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = PalletApiAutoConfiguration.class)
    static class TestApp {

        @RestController
        static class TestController {

            @GetMapping("/open")
            String open() {
                return "open";
            }

            @GetMapping("/secured")
            String secured() {
                return "secured";
            }
        }

        @Bean
        PublicApiPaths openPublicApiPaths() {
            return new PublicApiPaths(HttpMethod.GET, "/open");
        }
    }
}
