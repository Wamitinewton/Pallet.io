package io.pallet.orgteam.observability;

import io.pallet.common.api.ApiPathProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.observation.ServerRequestObservationConvention;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ObservabilityConfiguration {

    private static final int ACCESS_LOG_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    FilterRegistrationBean<RedactingAccessLog> redactingAccessLog(ApiPathProperties apiPath) {
        FilterRegistrationBean<RedactingAccessLog> registration =
                new FilterRegistrationBean<>(new RedactingAccessLog(apiPath.prefix() + "/org-team/invites/"));
        registration.setOrder(ACCESS_LOG_ORDER);
        return registration;
    }

    @Bean
    HandlerExceptionResolver redactingExceptionResolver() {
        return new RedactingExceptionResolver();
    }

    @Bean
    ServerRequestObservationConvention redactingServerRequestObservationConvention(ObservationProperties properties) {
        return new RedactingServerRequestObservationConvention(
                properties.getHttp().getServer().getRequests().getName());
    }
}
