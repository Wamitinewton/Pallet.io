package io.pallet.gitintegration.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.github.GitHubExceptions.InstallationTokenRejectedException;
import io.pallet.gitintegration.github.InstallationTokenCache.Minter;
import io.pallet.gitintegration.github.TokenScope.Access;
import io.pallet.gitintegration.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

@UnitTest
class InstallationTokenCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final TokenScope CONTENTS_READ = TokenScope.repository(42, Map.of("contents", Access.READ));

    private final MutableClock clock = new MutableClock(NOW);
    private final CountingMinter minter = new CountingMinter();
    private final InstallationTokenCache cache =
            new InstallationTokenCache(properties(10), clock, new SimpleMeterRegistry());

    @Test
    void aTokenIsCachedPerScope() {
        InstallationToken whole = cache.token(1, TokenScope.INSTALLATION, minter);
        InstallationToken narrowed = cache.token(1, CONTENTS_READ, minter);

        assertThat(cache.token(1, TokenScope.INSTALLATION, minter)).isSameAs(whole);
        assertThat(cache.token(1, CONTENTS_READ, minter)).isSameAs(narrowed);
        assertThat(narrowed).isNotEqualTo(whole);
        assertThat(minter.mints.get()).isEqualTo(2);
    }

    @Test
    void anEqualScopeBuiltSeparatelyHitsTheSameEntry() {
        cache.token(1, TokenScope.repository(42, Map.of("contents", Access.READ)), minter);
        cache.token(1, TokenScope.repository(42, Map.of("contents", Access.READ)), minter);

        assertThat(minter.mints.get()).isOne();
    }

    @Test
    void aTokenIsRefreshedWithinTheSkewOfItsExpiry() {
        InstallationToken first = cache.token(1, TokenScope.INSTALLATION, minter);

        clock.advance(Duration.ofMinutes(54).plusSeconds(59));
        assertThat(cache.token(1, TokenScope.INSTALLATION, minter)).isSameAs(first);

        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.token(1, TokenScope.INSTALLATION, minter)).isNotEqualTo(first);
        assertThat(minter.mints.get()).isEqualTo(2);
    }

    @Test
    void twentyConcurrentCallersMintOnce() throws Exception {
        CountDownLatch arrived = new CountDownLatch(20);
        CountDownLatch release = new CountDownLatch(1);
        Minter slow = (installationId, scope) -> {
            await(release);
            return minter.mint(installationId, scope);
        };
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<InstallationToken>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> {
                    arrived.countDown();
                    return cache.token(1, TokenScope.INSTALLATION, slow);
                }));
            }
            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            InstallationToken first = results.getFirst().get(5, TimeUnit.SECONDS);
            for (Future<InstallationToken> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isSameAs(first);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(minter.mints.get()).isOne();
    }

    @Test
    void aFailedMintDoesNotPoisonTheKey() {
        Minter failing = (installationId, scope) -> {
            throw new IllegalStateException("GitHub is down");
        };

        assertThatThrownBy(() -> cache.token(1, TokenScope.INSTALLATION, failing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(cache.token(1, TokenScope.INSTALLATION, minter)).isNotNull();
        assertThat(minter.mints.get()).isOne();
    }

    @Test
    void evictDropsEveryScopeOfThatInstallationOnly() {
        cache.token(1, TokenScope.INSTALLATION, minter);
        cache.token(1, CONTENTS_READ, minter);
        cache.token(2, TokenScope.INSTALLATION, minter);

        cache.evict(1);

        assertThat(cache.size()).isOne();
        cache.token(1, TokenScope.INSTALLATION, minter);
        cache.token(1, CONTENTS_READ, minter);
        cache.token(2, TokenScope.INSTALLATION, minter);
        assertThat(minter.mints.get()).isEqualTo(5);
    }

    @Test
    void aRejectedTokenIsReplacedAndTheCallRetriedOnce() {
        InstallationToken stale = cache.token(1, TokenScope.INSTALLATION, minter);
        List<InstallationToken> used = new ArrayList<>();

        String result = cache.withToken(1, TokenScope.INSTALLATION, minter, token -> {
            used.add(token);
            if (token.equals(stale)) {
                throw new InstallationTokenRejectedException();
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(used).hasSize(2);
        assertThat(used.getLast()).isNotEqualTo(stale);
        assertThat(cache.token(1, TokenScope.INSTALLATION, minter)).isEqualTo(used.getLast());
    }

    @Test
    void aSecondRejectionPropagatesAndLeavesNothingCached() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> cache.withToken(1, TokenScope.INSTALLATION, minter, token -> {
                    calls.incrementAndGet();
                    throw new InstallationTokenRejectedException();
                }))
                .isInstanceOf(InstallationTokenRejectedException.class);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(cache.size()).isZero();
    }

    @Test
    void invalidatingAnOlderTokenKeepsTheNewerOne() {
        InstallationToken old = cache.token(1, TokenScope.INSTALLATION, minter);
        cache.evict(1);
        InstallationToken current = cache.token(1, TokenScope.INSTALLATION, minter);

        cache.invalidate(1, TokenScope.INSTALLATION, old);

        assertThat(cache.token(1, TokenScope.INSTALLATION, minter)).isSameAs(current);
    }

    @Test
    void theLeastRecentlyUsedKeyIsDroppedPastTheLimit() {
        InstallationTokenCache small = new InstallationTokenCache(properties(2), clock, new SimpleMeterRegistry());
        small.token(1, TokenScope.INSTALLATION, minter);
        small.token(2, TokenScope.INSTALLATION, minter);
        small.token(1, TokenScope.INSTALLATION, minter);

        small.token(3, TokenScope.INSTALLATION, minter);

        assertThat(small.size()).isEqualTo(2);
        small.token(1, TokenScope.INSTALLATION, minter);
        assertThat(minter.mints.get()).isEqualTo(3);
        small.token(2, TokenScope.INSTALLATION, minter);
        assertThat(minter.mints.get()).isEqualTo(4);
    }

    @Test
    void toStringIsRedacted() {
        InstallationToken token = cache.token(1, TokenScope.INSTALLATION, minter);

        assertThat(token.toString()).doesNotContain(token.value());
        assertThat(new GitHubCredential.Installation(1, token).toString()).doesNotContain(token.value());
    }

    private final class CountingMinter implements Minter {

        private final AtomicInteger mints = new AtomicInteger();

        @Override
        public InstallationToken mint(long installationId, TokenScope scope) {
            int n = mints.incrementAndGet();
            return new InstallationToken(
                    "ghs_" + installationId + "_" + n, clock.instant().plus(Duration.ofHours(1)));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static GitIntegrationProperties properties(int maxCached) {
        return new Binder(new MapConfigurationPropertySource(Map.of(
                        "pallet.git.github.app-id", "1",
                        "pallet.git.github.client-id", "client",
                        "pallet.git.github.app-slug", "pallet",
                        "pallet.git.tokens.max-cached", String.valueOf(maxCached))))
                .bind("pallet.git", Bindable.of(GitIntegrationProperties.class))
                .get();
    }
}
