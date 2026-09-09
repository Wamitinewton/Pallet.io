package io.pallet.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.pallet.notification.domain.Channel;
import io.pallet.notification.template.TemplateRenderer.RenderedNotification;
import org.junit.jupiter.api.Test;

class InAppChannelIntegrationTest {

    private final InAppChannel channel = new InAppChannel();

    @Test
    void typeIsInApp() {
        assertThat(channel.type()).isEqualTo(Channel.IN_APP);
    }

    @Test
    void deliverNeverThrowsForAnyInput() {
        RenderedNotification notification = new RenderedNotification("Title", "<p>Body</p>");

        assertThatCode(() -> channel.deliver(notification, "user-1")).doesNotThrowAnyException();
        assertThatCode(() -> channel.deliver(null, null)).doesNotThrowAnyException();
    }
}
