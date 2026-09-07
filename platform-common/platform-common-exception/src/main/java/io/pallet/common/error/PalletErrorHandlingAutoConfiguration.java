package io.pallet.common.error;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Activates the shared exception advice for any servlet web service on the classpath.
 * Every bean is {@link ConditionalOnMissingBean} on its type, so a service can supply
 * a bespoke advice and this configuration backs off.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RestControllerAdvice.class)
public class PalletErrorHandlingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler palletGlobalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
    SecurityExceptionHandler palletSecurityExceptionHandler() {
        return new SecurityExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.dao.DataAccessException")
    PersistenceExceptionHandler palletPersistenceExceptionHandler() {
        return new PersistenceExceptionHandler();
    }
}
