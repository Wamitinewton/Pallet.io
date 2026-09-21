package io.pallet.apigateway.routing;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ProxyClientConfiguration {

    // Apache HttpClient's default strategy re-sends any method once on a 503 or 429, bypassing the
    // route's retry policy, which only ever retries GET.
    @Bean
    ClientHttpRequestFactoryBuilder<?> proxyClientHttpRequestFactoryBuilder() {
        return ClientHttpRequestFactoryBuilder.httpComponents()
                .withHttpClientCustomizer(HttpClientBuilder::disableAutomaticRetries);
    }
}
