package io.pallet.notification.template;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateProcessingException;

@Component
class ThymeleafTemplateRenderer implements TemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(ThymeleafTemplateRenderer.class);
    private static final Pattern SUBJECT_VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    private final TemplateEngine templateEngine;

    ThymeleafTemplateRenderer(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    @Override
    public RenderedNotification render(NotificationTemplate template, Map<String, Object> variables) {
        Map<String, Object> safeVariables = variables == null ? Map.of() : variables;
        String title = substituteSubject(template.notificationType(), template.subjectTemplate(), safeVariables);
        String body = renderBody(template, safeVariables);
        return new RenderedNotification(title, body);
    }

    private String substituteSubject(String notificationType, String subjectTemplate, Map<String, Object> variables) {
        Matcher matcher = SUBJECT_VARIABLE_PATTERN.matcher(subjectTemplate);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!variables.containsKey(key)) {
                log.warn(
                        "Notification type '{}' subject template references undeclared variable '{}'",
                        notificationType,
                        key);
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            Object value = variables.get(key);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : value.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String renderBody(NotificationTemplate template, Map<String, Object> variables) {
        Context context = new Context(Locale.ROOT, variables);
        try {
            return templateEngine.process(template.bodyTemplate(), context);
        } catch (TemplateProcessingException e) {
            log.warn(
                    "Notification type '{}' body template referenced an undeclared variable",
                    template.notificationType(),
                    e);
            return "";
        }
    }
}
