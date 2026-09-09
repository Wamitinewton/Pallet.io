package io.pallet.notification.template;

import java.util.Map;

public interface TemplateRenderer {

    RenderedNotification render(NotificationTemplate template, Map<String, Object> variables);

    record RenderedNotification(String title, String body) {}
}
