package io.pallet.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.common.resilience.ExternalCallExecutor;
import io.pallet.common.resilience.ResilienceProperties;
import io.pallet.common.resilience.ResilienceRegistries;
import io.pallet.notification.config.NotificationServiceProperties;
import io.pallet.notification.template.TemplateRenderer.RenderedNotification;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@SpringBootTest(
        classes = EmailChannelIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "pallet.notification.email.from=no-reply@pallet.local",
            "pallet.resilience.defaults.retry.max-attempts=2",
            "pallet.resilience.defaults.retry.wait-duration=10ms",
            "pallet.resilience.defaults.retry.max-wait-duration=50ms",
            "pallet.resilience.defaults.time-limiter.timeout=1s",
            "pallet.resilience.defaults.circuit-breaker.minimum-number-of-calls=2",
            "pallet.resilience.defaults.circuit-breaker.sliding-window-size=2",
            "pallet.resilience.defaults.circuit-breaker.wait-duration-in-open-state=5s"
        })
class EmailChannelIntegrationTest {

    private static final String EMAIL_SEND_POLICY = "email-send";

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

    @Autowired
    private EmailChannel emailChannel;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreakerRegistry.circuitBreaker(EMAIL_SEND_POLICY).reset();
    }

    @Test
    void deliversAnHtmlEmailToTheRecipient() throws Exception {
        RenderedNotification notification = new RenderedNotification("Welcome to Pallet", "<p>Hello <b>Ada</b></p>");

        emailChannel.deliver(notification, "ada@example.com");

        assertThat(GREEN_MAIL.waitForIncomingEmail(1)).isTrue();
        MimeMessage message = GREEN_MAIL.getReceivedMessages()[0];
        assertThat(message.getSubject()).isEqualTo("Welcome to Pallet");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("ada@example.com");
        assertThat(message.getFrom()[0].toString()).isEqualTo("no-reply@pallet.local");
        assertThat(message.isMimeType("text/html")).isTrue();
        assertThat(GreenMailUtil.getBody(message)).contains("Hello <b>Ada</b>");
    }

    @Test
    void throwsAChannelDeliveryExceptionWhenTheSmtpServerIsUnreachable() {
        GREEN_MAIL.stop();
        RenderedNotification notification = new RenderedNotification("Welcome", "<p>Hello</p>");

        assertThatThrownBy(() -> emailChannel.deliver(notification, "ada@example.com"))
                .isInstanceOf(ChannelDeliveryException.class);
    }

    @Test
    void failsFastOnceTheCircuitBreakerHasTripped() {
        GREEN_MAIL.stop();
        RenderedNotification notification = new RenderedNotification("Welcome", "<p>Hello</p>");

        assertThatThrownBy(() -> emailChannel.deliver(notification, "ada@example.com"))
                .isInstanceOf(ChannelDeliveryException.class);
        assertThatThrownBy(() -> emailChannel.deliver(notification, "ada@example.com"))
                .isInstanceOf(ChannelDeliveryException.class);

        Instant start = Instant.now();
        assertThatThrownBy(() -> emailChannel.deliver(notification, "ada@example.com"))
                .isInstanceOf(ChannelDeliveryException.class);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(200));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({NotificationServiceProperties.class, ResilienceProperties.class})
    @Import(EmailChannel.class)
    static class TestConfig {

        @Bean
        JavaMailSender mailSender() {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost("localhost");
            sender.setPort(ServerSetupTest.SMTP.getPort());
            return sender;
        }

        @Bean
        ResilienceRegistries resilienceRegistries(ResilienceProperties properties) {
            return new ResilienceRegistries(properties);
        }

        @Bean(destroyMethod = "shutdown")
        ScheduledExecutorService resilienceScheduler() {
            return Executors.newScheduledThreadPool(2);
        }

        @Bean
        ExternalCall externalCall(ResilienceRegistries registries, ScheduledExecutorService resilienceScheduler) {
            return new ExternalCallExecutor(registries, resilienceScheduler);
        }

        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry(ResilienceRegistries registries) {
            return registries.circuitBreakerRegistry();
        }
    }
}
