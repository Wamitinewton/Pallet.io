package io.pallet.orgteam.security;

import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@TestConfiguration(proxyBeanMethods = false)
public class SignedTokenTestConfiguration {

    @Bean
    JwtDecoder jwtDecoder(List<OAuth2TokenValidator<Jwt>> validators) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey(OrgTeamTestTokens.publicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new DelegatingOAuth2TokenValidator<>(validators)));
        return decoder;
    }
}
