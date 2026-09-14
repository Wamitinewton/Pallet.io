package io.pallet.apigateway.docs;

import io.pallet.apigateway.config.GatewayProperties;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders the platform-wide Scalar docs page at {@code /docs}, sourced from the same
 * {@link GatewayProperties} routing table that drives request routing — a service appears here the
 * moment it's reachable, never via a second registry.
 *
 * <p>{@code @Controller}, not {@code @RestController}: {@code PalletApiAutoConfiguration} prefixes
 * every {@code @RestController} endpoint with {@code pallet.api.prefix}, and {@code /docs} is
 * deliberately outside that versioned namespace, per ADR-0014.
 */
@Controller
class DocsHubController {

    private static final String SERVICE_SUFFIX = "-service";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GatewayProperties gatewayProperties;
    private final DocsHubProperties docsProperties;

    DocsHubController(GatewayProperties gatewayProperties, DocsHubProperties docsProperties) {
        this.gatewayProperties = gatewayProperties;
        this.docsProperties = docsProperties;
    }

    @GetMapping(value = "/docs", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> docsHub() {
        List<ScalarSource> sources = gatewayProperties.routes().entrySet().stream()
                .map(entry -> new ScalarSource(
                        entry.getValue().path().replace("/**", "/v3/api-docs"),
                        humanize(entry.getKey()),
                        entry.getKey()))
                .toList();
        return ResponseEntity.ok(renderScalarPage(docsProperties.title(), sources));
    }

    private static String renderScalarPage(String title, List<ScalarSource> sources) {
        String configuration = JSON.writeValueAsString(new ScalarConfiguration(sources, "purple", true));
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>%s</title>
                </head>
                <body>
                  <div id="api-reference"></div>
                  <script src="/docs/scalar.js"></script>
                  <script>
                    Scalar.createApiReference('#api-reference', %s);
                  </script>
                </body>
                </html>
                """.formatted(title, configuration);
    }

    static String humanize(String routeName) {
        String base = routeName.endsWith(SERVICE_SUFFIX)
                ? routeName.substring(0, routeName.length() - SERVICE_SUFFIX.length())
                : routeName;
        return base.isEmpty() ? base : Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    private record ScalarSource(String url, String title, String slug) {}

    private record ScalarConfiguration(List<ScalarSource> sources, String theme, boolean darkMode) {}
}
