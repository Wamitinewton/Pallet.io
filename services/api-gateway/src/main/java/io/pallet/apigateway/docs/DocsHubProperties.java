package io.pallet.apigateway.docs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pallet.docs")
public record DocsHubProperties(
        @DefaultValue("Pallet API Docs") String title) {}
