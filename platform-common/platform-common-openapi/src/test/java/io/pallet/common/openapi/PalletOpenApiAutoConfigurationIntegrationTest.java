package io.pallet.common.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.api.PalletApiAutoConfiguration;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PalletApiAutoConfiguration} is excluded so the fixture's own routes and springdoc's
 * {@code @RestController} endpoint both stay reachable at their literal, unprefixed paths — this
 * test isolates the OpenAPI module's behaviour, not the API path prefix.
 */
@SpringBootTest(
        classes = PalletOpenApiAutoConfigurationIntegrationTest.TestApp.class,
        properties = "spring.application.name=fixture-service")
@AutoConfigureMockMvc
class PalletOpenApiAutoConfigurationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void apiDocsExposesTheBearerSchemeTheErrorSchemaAndStandardResponses() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                        .value("bearer"))
                .andExpect(jsonPath("$.components.schemas.ErrorResponse").exists())
                .andExpect(jsonPath("$.paths['/secured'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/secured'].get.responses['500']").exists())
                .andExpect(jsonPath("$.security[0].bearerAuth").exists());
    }

    @Test
    void anExplicitSecurityRequirementsAnnotationOptsAnEndpointOutOfTheBearerRequirement() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/public'].get.security").isEmpty());
    }

    @Test
    void aServiceSuppliedOpenApiBeanReplacesTheDefaultInfoEntirelyInsteadOfMerging() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PalletOpenApiAutoConfiguration.class))
                .withUserConfiguration(CustomOpenApiConfiguration.class)
                .withPropertyValues("spring.application.name=fixture-service")
                .run(context -> {
                    OpenAPI openApi = context.getBean(OpenAPI.class);
                    assertThat(openApi.getInfo().getTitle()).isEqualTo("Custom Service");
                    assertThat(openApi.getComponents()).isNull();
                });
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = PalletApiAutoConfiguration.class)
    static class TestApp {

        @RestController
        static class TestController {

            @GetMapping("/secured")
            String secured() {
                return "secured";
            }

            @SecurityRequirements
            @GetMapping("/public")
            String publicEndpoint() {
                return "public";
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomOpenApiConfiguration {

        @Bean
        OpenAPI customOpenApi() {
            return new OpenAPI().info(new Info().title("Custom Service"));
        }
    }
}
