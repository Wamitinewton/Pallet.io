package io.pallet.notification.audience;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.notification.domain.Audience;
import java.util.List;
import org.junit.jupiter.api.Test;

class SingleAudienceResolverTest {

    private final SingleAudienceResolver resolver = new SingleAudienceResolver();

    @Test
    void singleWithARecipientResolvesToOneRecipient() {
        List<Recipient> recipients = resolver.resolve("org-1", Audience.SINGLE, "user@example.com");

        assertThat(recipients).containsExactly(new Recipient("user@example.com", "user@example.com"));
    }

    @Test
    void singleWithNoRecipientResolvesToEmpty() {
        List<Recipient> recipients = resolver.resolve("org-1", Audience.SINGLE, null);

        assertThat(recipients).isEmpty();
    }

    @Test
    void orgAlwaysResolvesToEmptyRegardlessOfInput() {
        assertThat(resolver.resolve("org-1", Audience.ORG, null)).isEmpty();
        assertThat(resolver.resolve("org-1", Audience.ORG, "user@example.com")).isEmpty();
    }
}
