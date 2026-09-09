package io.pallet.notification.audience;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AudienceResolutionConfiguration {

    private static final String LOCAL_PROJECTION_PROPERTY = "pallet.notification.audience.local-projection";

    @Bean
    @ConditionalOnProperty(name = LOCAL_PROJECTION_PROPERTY, matchIfMissing = true)
    AudienceResolver localProjectionAudienceResolver(OrgMembershipRepository orgMembershipRepository) {
        return new LocalProjectionAudienceResolver(orgMembershipRepository);
    }

    @Bean
    @ConditionalOnProperty(name = LOCAL_PROJECTION_PROPERTY, havingValue = "false")
    AudienceResolver singleAudienceResolver() {
        return new SingleAudienceResolver();
    }
}
