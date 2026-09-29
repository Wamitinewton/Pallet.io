package io.pallet.gitintegration.github;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.locks.ReentrantLock;

/** Signs the RS256 GitHub App JWT and reuses it until a minute before it expires. */
public class AppJwtSigner {

    static final Duration BACKDATE = Duration.ofSeconds(60);
    static final Duration LIFETIME = Duration.ofMinutes(9);
    static final Duration REUSE_MARGIN = Duration.ofMinutes(1);

    private final RSASSASigner signer;
    private final String clientId;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private AppJwt current;

    public AppJwtSigner(PrivateKey privateKey, String clientId, Clock clock) {
        this.signer = new RSASSASigner(privateKey);
        this.clientId = clientId;
        this.clock = clock;
    }

    AppJwt current() {
        lock.lock();
        try {
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            if (current == null || !now.isBefore(current.expiresAt().minus(REUSE_MARGIN))) {
                current = sign(now);
            }
            return current;
        } finally {
            lock.unlock();
        }
    }

    private AppJwt sign(Instant now) {
        Instant expiresAt = now.plus(LIFETIME);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(clientId)
                .issueTime(Date.from(now.minus(BACKDATE)))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the GitHub App JWT", e);
        }
        return new AppJwt(jwt.serialize(), expiresAt);
    }
}
