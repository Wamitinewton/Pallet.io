package io.pallet.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pallet.notification")
public record NotificationServiceProperties(@DefaultValue Email email) {

    public record Email(String from) {}
}
