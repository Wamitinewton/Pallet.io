package io.pallet.common.api;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Prefixes every {@code @RestController} endpoint with {@link ApiPathProperties#prefix()}
 * ({@code /api/v1} by default) so a version bump is a {@code config-repo} change, not a
 * {@code @RequestMapping} edit in each service. Actuator endpoints are registered through their
 * own handler mapping, not {@code @RestController}, so they're untouched by this and stay
 * reachable at their configured, unversioned path.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(WebMvcConfigurer.class)
@EnableConfigurationProperties(ApiPathProperties.class)
public class PalletApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "palletApiPathPrefixConfigurer")
    WebMvcConfigurer palletApiPathPrefixConfigurer(ApiPathProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void configurePathMatch(PathMatchConfigurer configurer) {
                configurer.addPathPrefix(properties.prefix(), HandlerTypePredicate.forAnnotation(RestController.class));
            }
        };
    }
}
