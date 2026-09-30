package io.pallet.gitintegration.webhook;

import io.pallet.gitintegration.config.GitIntegrationProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * GitHub's webhook source ranges, a second layer behind the HMAC and never the authority. It fails open: until
 * {@code GET /meta} has loaded once, every address is allowed.
 */
@Component
public class GitHubIpAllowlist {

    public enum Decision {
        ALLOWED,
        REJECTED,
        UNCHECKED
    }

    private record Ranges(List<IpAddressMatcher> matchers, Instant loadedAt) {}

    private final boolean enabled;
    private final AtomicReference<Ranges> ranges = new AtomicReference<>();

    GitHubIpAllowlist(GitIntegrationProperties properties) {
        this.enabled = properties.webhook().githubIpAllowlist().enabled();
    }

    public boolean enabled() {
        return enabled;
    }

    /** @param address the client address as the gateway forwarded it */
    public Decision check(String address) {
        Ranges loaded = ranges.get();
        if (loaded == null) {
            return Decision.UNCHECKED;
        }
        if (address == null) {
            return Decision.REJECTED;
        }
        for (IpAddressMatcher matcher : loaded.matchers()) {
            try {
                if (matcher.matches(address)) {
                    return Decision.ALLOWED;
                }
            } catch (IllegalArgumentException notAnAddress) {
                return Decision.REJECTED;
            }
        }
        return Decision.REJECTED;
    }

    /** @throws IllegalArgumentException if any range is not a CIDR block, leaving the previous ranges in place */
    public void replace(List<String> cidrs, Instant loadedAt) {
        if (cidrs.isEmpty()) {
            throw new IllegalArgumentException("No hook ranges");
        }
        List<IpAddressMatcher> matchers =
                cidrs.stream().map(IpAddressMatcher::new).toList();
        ranges.set(new Ranges(matchers, loadedAt));
    }

    public Optional<Instant> loadedAt() {
        return Optional.ofNullable(ranges.get()).map(Ranges::loadedAt);
    }
}
