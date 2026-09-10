package io.pallet.notification.template;

import io.pallet.notification.domain.Channel;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Loads every {@code templates/notification/*.yml} + paired {@code .html} classpath resource at
 * construction time, so a missing template, a duplicate {@code notificationType}, or an unpaired
 * {@code .html} file fails service startup instead of surfacing on the first matching event.
 */
public final class NotificationTemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(NotificationTemplateRegistry.class);

    static final String DEFAULT_LOCATION_PATTERN = "classpath*:templates/notification/*.yml";
    private static final String TEMPLATE_NAME_PREFIX = "notification/";

    private final Map<String, NotificationTemplate> templatesByType;

    NotificationTemplateRegistry(ResourcePatternResolver resourcePatternResolver, String locationPattern) {
        this.templatesByType = loadTemplates(resourcePatternResolver, locationPattern);
        log.info("Loaded {} notification templates", templatesByType.size());
    }

    public NotificationTemplate resolve(String notificationType) {
        NotificationTemplate template = templatesByType.get(notificationType);
        if (template == null) {
            throw new UnknownNotificationTypeException(notificationType);
        }
        return template;
    }

    private static Map<String, NotificationTemplate> loadTemplates(
            ResourcePatternResolver resourcePatternResolver, String locationPattern) {
        Resource[] resources;
        try {
            resources = resourcePatternResolver.getResources(locationPattern);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan notification templates at " + locationPattern, e);
        }

        Map<String, NotificationTemplate> templates = new HashMap<>();
        for (Resource resource : resources) {
            NotificationTemplate template = loadTemplate(resource);
            NotificationTemplate duplicate = templates.putIfAbsent(template.notificationType(), template);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate notification template for type '%s': %s"
                        .formatted(template.notificationType(), resource.getFilename()));
            }
        }
        return Map.copyOf(templates);
    }

    private static NotificationTemplate loadTemplate(Resource resource) {
        Map<String, Object> metadata = parseMetadata(resource);

        String notificationType = requireString(metadata, "notificationType", resource);
        String subjectTemplate = requireString(metadata, "subjectTemplate", resource);
        Set<Channel> defaultChannels = parseChannels(metadata, notificationType, resource);

        String filename = resource.getFilename();
        if (filename == null) {
            throw new IllegalStateException("Notification template resource has no filename: " + resource);
        }
        String baseName = stripYamlExtension(filename);
        Resource pairedHtml;
        try {
            pairedHtml = resource.createRelative(baseName + ".html");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve body template next to " + resource.getFilename(), e);
        }
        if (!pairedHtml.exists()) {
            throw new MissingBodyTemplateException(notificationType, pairedHtml.getDescription());
        }

        String bodyTemplate = TEMPLATE_NAME_PREFIX + baseName;
        return new NotificationTemplate(notificationType, defaultChannels, subjectTemplate, bodyTemplate);
    }

    private static Map<String, Object> parseMetadata(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new IllegalStateException(
                        "Notification template metadata is not a YAML mapping: " + resource.getFilename());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) map;
            return metadata;
        } catch (IOException | YAMLException e) {
            throw new IllegalStateException(
                    "Failed to parse notification template metadata: " + resource.getFilename(), e);
        }
    }

    private static String requireString(Map<String, Object> metadata, String key, Resource resource) {
        Object value = metadata.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalStateException("Notification template metadata missing required field '%s': %s"
                    .formatted(key, resource.getFilename()));
        }
        return string;
    }

    private static Set<Channel> parseChannels(
            Map<String, Object> metadata, String notificationType, Resource resource) {
        Object value = metadata.get("defaultChannels");
        if (!(value instanceof List<?> rawChannels) || rawChannels.isEmpty()) {
            throw new IllegalStateException("Notification template for type '%s' declares no defaultChannels: %s"
                    .formatted(notificationType, resource.getFilename()));
        }
        Set<Channel> channels = new HashSet<>();
        for (Object rawChannel : rawChannels) {
            channels.add(Channel.valueOf(String.valueOf(rawChannel)));
        }
        return channels;
    }

    private static String stripYamlExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex < 0 ? filename : filename.substring(0, dotIndex);
    }
}
