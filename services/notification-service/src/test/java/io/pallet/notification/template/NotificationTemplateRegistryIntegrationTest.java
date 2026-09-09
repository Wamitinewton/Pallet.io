package io.pallet.notification.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.notification.domain.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

class NotificationTemplateRegistryIntegrationTest {

    private static final ResourcePatternResolver RESOLVER = new PathMatchingResourcePatternResolver();

    @Test
    void resolveReturnsTheWelcomeTemplateLoadedFromTheRealClasspath() {
        NotificationTemplateRegistry registry =
                new NotificationTemplateRegistry(RESOLVER, NotificationTemplateRegistry.DEFAULT_LOCATION_PATTERN);

        NotificationTemplate template = registry.resolve("WELCOME");

        assertThat(template.notificationType()).isEqualTo("WELCOME");
        assertThat(template.defaultChannels()).containsExactlyInAnyOrder(Channel.EMAIL, Channel.IN_APP);
        assertThat(template.subjectTemplate()).isEqualTo("Welcome to Pallet, {{name}}");
        assertThat(template.bodyTemplate()).isEqualTo("notification/welcome");
    }

    @Test
    void resolveThrowsForAnUnknownNotificationType() {
        NotificationTemplateRegistry registry =
                new NotificationTemplateRegistry(RESOLVER, NotificationTemplateRegistry.DEFAULT_LOCATION_PATTERN);

        assertThatThrownBy(() -> registry.resolve("DOES_NOT_EXIST"))
                .isInstanceOf(UnknownNotificationTypeException.class);
    }

    @Test
    void constructionFailsWhenTwoTemplateFilesDeclareTheSameNotificationType() {
        assertThatThrownBy(() -> new NotificationTemplateRegistry(
                        RESOLVER, "classpath:fixtures/notification-template/duplicate-type/*.yml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DUPLICATE");
    }

    @Test
    void constructionFailsWithMissingBodyTemplateExceptionWhenTheHtmlFileIsAbsent() {
        assertThatThrownBy(() -> new NotificationTemplateRegistry(
                        RESOLVER, "classpath:fixtures/notification-template/missing-html/*.yml"))
                .isInstanceOf(MissingBodyTemplateException.class)
                .hasMessageContaining("ORPHAN");
    }
}
