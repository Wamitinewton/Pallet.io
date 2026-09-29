package io.pallet.gitintegration.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.error.ExternalServiceException;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.github.GitHubUserToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Encrypted GitHub user sessions in Redis: {@code git:user-session:{sub}} holds the session, and
 * {@code git:user-session-by-github:{githubUserId}} is the set of subjects whose session was issued to that GitHub
 * user, so a revocation webhook reaches every Pallet account signed in with it. Index members are checked against the
 * session before anything is deleted, so a stale member never ends someone else's session.
 */
@Component
public class GitHubUserSessionStore {

    public static final String UNDECRYPTABLE = "git.sessions.undecryptable";

    static final String SESSION_KEY_PREFIX = "git:user-session:";
    static final String INDEX_KEY_PREFIX = "git:user-session-by-github:";

    record StoredSession(
            @JsonProperty("t") String token,
            @JsonProperty("u") long githubUserId,
            @JsonProperty("l") String githubLogin,
            @JsonProperty("e") long expiresAtEpochMilli) {

        @Override
        public @NonNull String toString() {
            return "StoredSession[token=<redacted>, githubUserId=" + githubUserId + "]";
        }
    }

    private final StringRedisTemplate redis;
    private final SessionCipher cipher;
    private final JsonMapper json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Duration maxTtl;

    GitHubUserSessionStore(
            StringRedisTemplate redis,
            SessionCipher cipher,
            JsonMapper json,
            Clock clock,
            MeterRegistry meters,
            GitIntegrationProperties properties) {
        this.redis = redis;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
        this.meters = meters;
        this.maxTtl = properties.userSession().maxTtl();
    }

    /** Stores the session for the shorter of {@code max-ttl} and the time left until it expires. */
    public void save(String sub, GitHubUserSession session) {
        Duration left = Duration.between(clock.instant(), session.expiresAt());
        Duration ttl = left.compareTo(maxTtl) < 0 ? left : maxTtl;
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("The session has already expired");
        }
        String key = sessionKey(sub);
        byte[] plaintext = json.writeValueAsBytes(new StoredSession(
                session.token().value(),
                session.githubUserId(),
                session.githubLogin(),
                session.expiresAt().toEpochMilli()));
        String value = cipher.encrypt(plaintext, key);
        String index = indexKey(session.githubUserId());
        transaction(ops -> {
            ops.opsForValue().set(key, value, ttl);
            ops.opsForSet().add(index, sub);
            ops.expire(index, maxTtl);
        });
    }

    public Optional<GitHubUserSession> find(String sub) {
        return read(sub)
                .map(stored -> new GitHubUserSession(
                        new GitHubUserToken(stored.token()),
                        stored.githubUserId(),
                        stored.githubLogin(),
                        Instant.ofEpochMilli(stored.expiresAtEpochMilli())));
    }

    public void delete(String sub) {
        Optional<StoredSession> stored = read(sub);
        transaction(ops -> {
            ops.delete(sessionKey(sub));
            stored.ifPresent(session -> ops.opsForSet().remove(indexKey(session.githubUserId()), sub));
        });
    }

    /** @return the subjects whose live session was issued to {@code githubUserId} */
    public List<String> findSubjectsByGithubUser(long githubUserId) {
        Set<String> members = redis(() -> redis.opsForSet().members(indexKey(githubUserId)));
        List<String> subjects = new ArrayList<>();
        if (members == null) {
            return subjects;
        }
        for (String sub : members.stream().sorted().toList()) {
            read(sub).filter(session -> session.githubUserId() == githubUserId).ifPresent(session -> subjects.add(sub));
        }
        return subjects;
    }

    /** Ends every session issued to {@code githubUserId}, and drops its index. */
    public void deleteByGithubUser(long githubUserId) {
        List<String> subjects = findSubjectsByGithubUser(githubUserId);
        transaction(ops -> {
            subjects.forEach(sub -> ops.delete(sessionKey(sub)));
            ops.delete(indexKey(githubUserId));
        });
    }

    private Optional<StoredSession> read(String sub) {
        String key = sessionKey(sub);
        String value = redis(() -> redis.opsForValue().get(key));
        if (value == null) {
            return Optional.empty();
        }
        Optional<StoredSession> stored = cipher.decrypt(value, key).flatMap(this::parse);
        if (stored.isEmpty()) {
            meters.counter(UNDECRYPTABLE).increment();
            return Optional.empty();
        }
        return stored.filter(session -> session.expiresAtEpochMilli() > clock.millis());
    }

    private Optional<StoredSession> parse(byte[] plaintext) {
        try {
            StoredSession stored = json.readValue(plaintext, StoredSession.class);
            return stored.token() == null || stored.githubLogin() == null ? Optional.empty() : Optional.of(stored);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void transaction(TransactionBody body) {
        redis(() -> redis.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> List<Object> execute(@NonNull RedisOperations<K, V> operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                ops.multi();
                body.apply(ops);
                return ops.exec();
            }
        }));
    }

    /**
     * The Redis failure is described, not chained: exception handlers match on causes too, and a chained
     * {@code QueryTimeoutException} would be answered as the database being down.
     */
    private static <T> T redis(Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            throw new ExternalServiceException(
                    "GitHub sessions are temporarily unavailable. Try again shortly.",
                    "Redis unavailable for GitHub user sessions: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    null);
        }
    }

    static String sessionKey(String sub) {
        return SESSION_KEY_PREFIX + sub;
    }

    static String indexKey(long githubUserId) {
        return INDEX_KEY_PREFIX + githubUserId;
    }

    @FunctionalInterface
    private interface TransactionBody {

        void apply(RedisOperations<String, String> ops);
    }
}
