package io.pallet.notification.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.notification.config.NotificationServiceProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrgRateLimiterTest {

    private static OrgRateLimiter rateLimiterWithPermitsPerPeriod(int permitsPerPeriod) {
        NotificationServiceProperties properties = new NotificationServiceProperties(
                new NotificationServiceProperties.Email("no-reply@pallet.local"),
                new NotificationServiceProperties.RateLimit(
                        permitsPerPeriod, Duration.ofMinutes(1), Duration.ofSeconds(30), 500));
        return new OrgRateLimiter(properties);
    }

    @Test
    void theNPlusOnethCallForTheSameOrgIsRejectedImmediately() {
        OrgRateLimiter rateLimiter = rateLimiterWithPermitsPerPeriod(3);

        assertThat(rateLimiter.tryAcquire("org-1")).isTrue();
        assertThat(rateLimiter.tryAcquire("org-1")).isTrue();
        assertThat(rateLimiter.tryAcquire("org-1")).isTrue();

        Instant before = Instant.now();
        boolean fourthAcquire = rateLimiter.tryAcquire("org-1");
        Duration elapsed = Duration.between(before, Instant.now());

        assertThat(fourthAcquire).isFalse();
        assertThat(elapsed).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void oneOrgExhaustingItsBudgetDoesNotAffectAnotherOrg() {
        OrgRateLimiter rateLimiter = rateLimiterWithPermitsPerPeriod(1);

        assertThat(rateLimiter.tryAcquire("org-1")).isTrue();
        assertThat(rateLimiter.tryAcquire("org-1")).isFalse();

        assertThat(rateLimiter.tryAcquire("org-2")).isTrue();
    }
}
