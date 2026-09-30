package io.pallet.gitintegration.webhook;

import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.github.GitHubClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@link GitHubIpAllowlist} current from {@code GET /meta}: every {@code refresh-interval} once loaded, every
 * {@code retry-interval} until then. A failed read keeps the ranges already held.
 */
@Component
@ConditionalOnProperty(prefix = "pallet.git.webhook.github-ip-allowlist", name = "enabled", havingValue = "true")
class GitHubMetaRefresher {

    private static final Logger log = LoggerFactory.getLogger(GitHubMetaRefresher.class);

    private final GitHubClient github;
    private final GitHubIpAllowlist allowlist;
    private final Clock clock;
    private final Duration refreshInterval;

    GitHubMetaRefresher(
            GitHubClient github, GitHubIpAllowlist allowlist, Clock clock, GitIntegrationProperties properties) {
        this.github = github;
        this.allowlist = allowlist;
        this.clock = clock;
        this.refreshInterval = properties.webhook().githubIpAllowlist().refreshInterval();
    }

    @Scheduled(fixedDelayString = "${pallet.git.webhook.github-ip-allowlist.retry-interval:PT5M}")
    void refreshWhenDue() {
        Optional<Instant> loadedAt = allowlist.loadedAt();
        if (loadedAt.isPresent() && clock.instant().isBefore(loadedAt.get().plus(refreshInterval))) {
            return;
        }
        refresh();
    }

    /** @return whether the ranges were replaced */
    boolean refresh() {
        try {
            allowlist.replace(github.hookRanges(), clock.instant());
            return true;
        } catch (RuntimeException e) {
            log.warn(
                    "GitHub hook ranges not refreshed, {}: {}",
                    allowlist.loadedAt().isPresent() ? "keeping the previous ranges" : "webhooks stay unchecked",
                    e.getClass().getSimpleName());
            return false;
        }
    }
}
