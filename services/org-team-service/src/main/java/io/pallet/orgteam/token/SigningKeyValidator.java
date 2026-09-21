package io.pallet.orgteam.token;

import io.pallet.orgteam.config.InviteSigningProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public class SigningKeyValidator implements InitializingBean {

    static final int MIN_KEY_BYTES = 32;

    private final InviteSigningProperties properties;

    SigningKeyValidator(InviteSigningProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        String key = properties.signingKey();
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "pallet.invites.signing-key must be set and at least " + MIN_KEY_BYTES + " bytes long");
        }
    }
}
