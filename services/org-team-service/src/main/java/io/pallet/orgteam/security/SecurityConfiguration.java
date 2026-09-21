package io.pallet.orgteam.security;

import io.pallet.common.api.ApiPathProperties;
import io.pallet.common.security.PublicApiPaths;
import io.pallet.orgteam.config.OrgTeamProperties;
import io.pallet.orgteam.observability.OrgTeamMetrics;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {

    @Bean
    MembershipPolicy membershipPolicy() {
        return new MembershipPolicy();
    }

    @Bean
    PublicApiPaths invitePreviewPublicPath(ApiPathProperties apiPath) {
        return new PublicApiPaths(HttpMethod.GET, apiPath.prefix() + "/org-team/invites/*");
    }

    @Bean
    RecentAuthentication recentAuthentication(Clock clock, OrgTeamProperties properties, OrgTeamMetrics metrics) {
        return new RecentAuthentication(clock, properties.security().recentAuthWindow(), metrics);
    }
}
