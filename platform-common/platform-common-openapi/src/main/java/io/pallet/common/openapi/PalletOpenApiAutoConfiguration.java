package io.pallet.common.openapi;

import io.pallet.common.error.ErrorResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@code bearerAuth} security scheme, a default {@link Info} block derived from
 * {@code spring.application.name}, and the {@link ErrorResponseOperationCustomizer}. Both beans
 * are {@link ConditionalOnMissingBean} so a service can supply its own {@link OpenAPI} bean
 * (a richer {@code Info}, a {@code Contact}, a {@code License}) and this default steps aside
 * entirely rather than merging with it.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(OpenAPI.class)
@EnableConfigurationProperties(OpenApiProperties.class)
public class PalletOpenApiAutoConfiguration {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";
    private static final String DEFAULT_DESCRIPTION = "Every endpoint returns an ApiResponse, or a "
            + "PageResponse nested inside one for a paginated result; failures are rendered as an "
            + "ErrorResponse by the platform's global exception handler.";

    @Bean
    @ConditionalOnMissingBean
    OpenAPI palletOpenApi(OpenApiProperties properties, @Value("${spring.application.name}") String appName) {
        String title = !properties.title().isBlank() ? properties.title() : humanize(appName);
        String description = !properties.description().isBlank() ? properties.description() : DEFAULT_DESCRIPTION;

        Components components = new Components()
                .addSecuritySchemes(
                        BEARER_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"));
        ModelConverters.getInstance()
                .resolveAsResolvedSchema(new AnnotatedType(ErrorResponse.class))
                .referencedSchemas
                .forEach(components::addSchemas);

        return new OpenAPI()
                .info(new Info().title(title).description(description))
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }

    @Bean
    @ConditionalOnMissingBean
    ErrorResponseOperationCustomizer errorResponseOperationCustomizer() {
        return new ErrorResponseOperationCustomizer();
    }

    private static String humanize(String appName) {
        StringBuilder title = new StringBuilder();
        for (String word : appName.split("[-_]")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!title.isEmpty()) {
                title.append(' ');
            }
            title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return title.toString();
    }
}
