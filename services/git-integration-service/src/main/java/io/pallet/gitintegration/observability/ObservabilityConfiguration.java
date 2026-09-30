package io.pallet.gitintegration.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

    private static final int ACCESS_LOG_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    SpanRedaction spanRedaction() {
        return new SpanRedaction();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<RedactingAccessLog> redactingAccessLog() {
        FilterRegistrationBean<RedactingAccessLog> registration =
                new FilterRegistrationBean<>(new RedactingAccessLog());
        registration.setOrder(ACCESS_LOG_ORDER);
        return registration;
    }
}
