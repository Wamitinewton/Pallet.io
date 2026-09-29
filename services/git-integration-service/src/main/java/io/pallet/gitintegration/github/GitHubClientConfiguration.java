package io.pallet.gitintegration.github;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.common.resilience.ResilienceRegistries;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.PrivateKey;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class GitHubClientConfiguration {

    @Bean
    AppJwtSigner appJwtSigner(PrivateKey githubAppPrivateKey, GitIntegrationProperties properties, Clock clock) {
        return new AppJwtSigner(githubAppPrivateKey, properties.github().clientId(), clock);
    }

    @Bean
    InstallationTokenCache installationTokenCache(GitIntegrationProperties properties, Clock clock) {
        return new InstallationTokenCache(properties, clock);
    }

    @Bean
    GitHubRateLimitGuard gitHubRateLimitGuard(GitIntegrationProperties properties, Clock clock, MeterRegistry meters) {
        return new GitHubRateLimitGuard(properties, clock, meters);
    }

    @Bean
    GitHubHttp gitHubHttp(
            GitIntegrationProperties properties,
            ExternalCall externalCall,
            ResilienceRegistries resilience,
            GitHubRateLimitGuard rateLimits,
            JsonMapper jsonMapper,
            MeterRegistry meters,
            Clock clock,
            ObjectProvider<ObservationRegistry> observations) {
        for (String policy : List.of(GitHubCredential.API_POLICY, GitHubCredential.USER_POLICY)) {
            resilience.circuitBreaker(policy);
        }
        GitIntegrationProperties.GitHub github = properties.github();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(github.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(github.readTimeout());
        ObservationRegistry observationRegistry = observations.getIfAvailable(() -> ObservationRegistry.NOOP);
        return new GitHubHttp(
                httpClient,
                restClient(github.apiBaseUrl(), requestFactory, observationRegistry),
                restClient(github.webBaseUrl(), requestFactory, observationRegistry),
                externalCall,
                new GitHubResponseClassifier(clock),
                rateLimits,
                jsonMapper,
                meters);
    }

    @Bean
    GitHubClient gitHubClient(
            GitHubHttp http,
            AppJwtSigner appJwtSigner,
            InstallationTokenCache tokens,
            GitIntegrationProperties properties,
            Clock clock) {
        GitIntegrationProperties.GitHub github = properties.github();
        return new GitHubClient(http, appJwtSigner, tokens, github.clientId(), github.clientSecret(), clock);
    }

    private static RestClient restClient(
            URI baseUrl, JdkClientHttpRequestFactory requestFactory, ObservationRegistry observationRegistry) {
        return RestClient.builder()
                .baseUrl(baseUrl.toString())
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
    }
}
