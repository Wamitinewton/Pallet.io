package io.pallet.notification.channel;

import io.pallet.common.error.ExternalServiceException;
import io.pallet.common.observability.Monitored;
import io.pallet.common.resilience.ExternalCall;
import io.pallet.notification.config.NotificationServiceProperties;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.template.TemplateRenderer.RenderedNotification;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
class EmailChannel implements NotificationChannel {

    private static final String EMAIL_SEND_POLICY = "email-send";

    private final JavaMailSender mailSender;
    private final ExternalCall externalCall;
    private final NotificationServiceProperties.Email emailProperties;

    EmailChannel(JavaMailSender mailSender, ExternalCall externalCall, NotificationServiceProperties properties) {
        this.mailSender = mailSender;
        this.externalCall = externalCall;
        this.emailProperties = properties.email();
    }

    @Override
    public Channel type() {
        return Channel.EMAIL;
    }

    @Override
    @Monitored
    public void deliver(RenderedNotification notification, String recipient) throws ChannelDeliveryException {
        try {
            externalCall.call(EMAIL_SEND_POLICY, () -> send(notification, recipient));
        } catch (ExternalServiceException e) {
            throw new ChannelDeliveryException("Email send failed to " + recipient, e);
        }
    }

    private Void send(RenderedNotification notification, String recipient) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message);
            helper.setFrom(emailProperties.from());
            helper.setTo(recipient);
            helper.setSubject(notification.title());
            helper.setText(notification.body(), true);
            mailSender.send(message);
            return null;
        } catch (MessagingException e) {
            throw new MailParseException(e);
        }
    }
}
