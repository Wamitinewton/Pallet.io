package io.pallet.orgteam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pallet.invites")
public record InviteSigningProperties(String signingKey) {

    @Override
    public String toString() {
        return "InviteSigningProperties[signingKey=****]";
    }
}
