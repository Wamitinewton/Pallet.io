package io.pallet.gitintegration.github;

import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.github.GitHubExceptions.InstallationTokenRejectedException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Installation tokens per {@code (installationId, TokenScope)}, in memory only, refreshed {@code tokens.refresh-skew}
 * before expiry. Concurrent callers for one key share a single mint, and the least recently used key is dropped
 * past {@code tokens.max-cached}.
 */
public class InstallationTokenCache {

    @FunctionalInterface
    interface Minter {

        InstallationToken mint(long installationId, TokenScope scope);
    }

    private record Key(long installationId, TokenScope scope) {}

    private final Clock clock;
    private final Duration refreshSkew;
    private final ReentrantLock lock = new ReentrantLock();
    private final LeastRecentlyUsed entries;

    public InstallationTokenCache(GitIntegrationProperties properties, Clock clock) {
        this.clock = clock;
        this.refreshSkew = properties.tokens().refreshSkew();
        this.entries = new LeastRecentlyUsed(properties.tokens().maxCached());
    }

    /** Runs {@code call} with a token; if GitHub rejects it, drops that token and retries once with a fresh one. */
    <T> T withToken(long installationId, TokenScope scope, Minter minter, Function<InstallationToken, T> call) {
        InstallationToken token = token(installationId, scope, minter);
        try {
            return call.apply(token);
        } catch (InstallationTokenRejectedException rejected) {
            invalidate(installationId, scope, token);
        }
        InstallationToken fresh = token(installationId, scope, minter);
        try {
            return call.apply(fresh);
        } catch (InstallationTokenRejectedException rejectedAgain) {
            invalidate(installationId, scope, fresh);
            throw rejectedAgain;
        }
    }

    InstallationToken token(long installationId, TokenScope scope, Minter minter) {
        Key key = new Key(installationId, scope);
        CompletableFuture<InstallationToken> pending;
        CompletableFuture<InstallationToken> claimed = null;
        lock.lock();
        try {
            pending = entries.get(key);
            if (pending == null || isStale(pending)) {
                claimed = new CompletableFuture<>();
                entries.put(key, claimed);
                pending = claimed;
            }
        } finally {
            lock.unlock();
        }
        if (claimed != null) {
            return mint(key, claimed, minter);
        }
        try {
            return pending.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
    }

    /** Drops every cached scope for the installation, e.g. once it is uninstalled. */
    public void evict(long installationId) {
        lock.lock();
        try {
            entries.keySet().removeIf(key -> key.installationId() == installationId);
        } finally {
            lock.unlock();
        }
    }

    /** Drops the key only while it still holds {@code rejected}, so a token another caller just re-minted survives. */
    void invalidate(long installationId, TokenScope scope, InstallationToken rejected) {
        Key key = new Key(installationId, scope);
        lock.lock();
        try {
            CompletableFuture<InstallationToken> pending = entries.get(key);
            if (pending != null && pending.isDone() && rejected.equals(pending.getNow(null))) {
                entries.remove(key);
            }
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    private InstallationToken mint(Key key, CompletableFuture<InstallationToken> claimed, Minter minter) {
        try {
            InstallationToken token = minter.mint(key.installationId(), key.scope());
            claimed.complete(token);
            return token;
        } catch (RuntimeException | Error failure) {
            lock.lock();
            try {
                entries.remove(key, claimed);
            } finally {
                lock.unlock();
            }
            claimed.completeExceptionally(failure);
            throw failure;
        }
    }

    private boolean isStale(CompletableFuture<InstallationToken> pending) {
        if (!pending.isDone()) {
            return false;
        }
        if (pending.isCompletedExceptionally()) {
            return true;
        }
        InstallationToken token = pending.getNow(null);
        return !clock.instant().isBefore(token.expiresAt().minus(refreshSkew));
    }

    private static final class LeastRecentlyUsed extends LinkedHashMap<Key, CompletableFuture<InstallationToken>> {

        private final int maxEntries;

        LeastRecentlyUsed(int maxEntries) {
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, CompletableFuture<InstallationToken>> eldest) {
            return this.size() > maxEntries;
        }
    }
}
