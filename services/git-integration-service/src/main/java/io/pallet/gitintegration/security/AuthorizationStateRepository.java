package io.pallet.gitintegration.security;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The single-use half of an authorization state: a nonce row whose deadline is the database's clock. */
@Repository
public class AuthorizationStateRepository {

    private final JdbcClient jdbc;

    AuthorizationStateRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** @return the stored {@code expires_at} */
    Instant insert(UUID nonce, AuthorizationPurpose purpose, String userId, String orgId, Duration ttl) {
        return jdbc.sql("""
                        INSERT INTO git_integration.authorization_states (nonce, purpose, user_id, org_id, expires_at)
                        VALUES (:nonce, :purpose, :userId, :orgId, now() + :ttlMillis * interval '1 millisecond')
                        RETURNING expires_at
                        """)
                .param("nonce", nonce)
                .param("purpose", purpose.name())
                .param("userId", userId)
                .param("orgId", orgId)
                .param("ttlMillis", ttl.toMillis())
                .query(Timestamp.class)
                .single()
                .toInstant();
    }

    /** @return whether this call consumed the state; false if it was used, expired, or never matched */
    boolean consume(UUID nonce, AuthorizationPurpose purpose, String userId, String orgId) {
        return jdbc.sql("""
                        UPDATE git_integration.authorization_states SET consumed_at = now()
                         WHERE nonce = :nonce AND consumed_at IS NULL AND expires_at > now()
                           AND purpose = :purpose AND user_id = :userId
                           AND org_id IS NOT DISTINCT FROM CAST(:orgId AS VARCHAR)
                        """)
                        .param("nonce", nonce)
                        .param("purpose", purpose.name())
                        .param("userId", userId)
                        .param("orgId", orgId)
                        .update()
                == 1;
    }
}
