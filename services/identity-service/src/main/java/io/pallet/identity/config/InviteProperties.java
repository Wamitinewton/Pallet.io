package io.pallet.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pallet.invites")
public record InviteProperties(String signingKey) {}
