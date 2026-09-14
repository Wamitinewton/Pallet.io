package io.pallet.apigateway.config;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpMethod;

@ConfigurationProperties("pallet.gateway")
public record GatewayProperties(@DefaultValue Map<String, Route> routes) {

    public record Route(
            URI uri,
            String path,
            List<HttpMethod> allowedMethods,
            @DefaultValue List<String> publicPaths,
            String resiliencePolicy) {}

    public String[] allPublicPaths() {
        return routes.values().stream()
                .flatMap(route -> route.publicPaths().stream())
                .toArray(String[]::new);
    }
}
