package io.pallet.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ApiPathPropertiesTest {

    @Test
    void defaultPrefixIsApiV1() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnableApiPathProperties.class)
                .run(context -> assertThat(
                                context.getBean(ApiPathProperties.class).prefix())
                        .isEqualTo("/api/v1"));
    }

    @Test
    void prefixBindsFromConfiguredProperty() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnableApiPathProperties.class)
                .withPropertyValues("pallet.api.prefix=/api/v2")
                .run(context -> assertThat(
                                context.getBean(ApiPathProperties.class).prefix())
                        .isEqualTo("/api/v2"));
    }

    @EnableConfigurationProperties(ApiPathProperties.class)
    private static final class EnableApiPathProperties {}
}
