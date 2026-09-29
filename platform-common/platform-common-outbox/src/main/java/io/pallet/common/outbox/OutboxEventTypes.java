package io.pallet.common.outbox;

import io.pallet.common.events.PlatformEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The event types a service publishes through its outbox, resolved from each record's {@code TYPE} constant. */
public final class OutboxEventTypes {

    private final Map<String, Class<? extends PlatformEvent>> types;
    private final Set<Class<? extends PlatformEvent>> publishedTypes;

    private OutboxEventTypes(Map<String, Class<? extends PlatformEvent>> types) {
        this.types = Map.copyOf(types);
        this.publishedTypes = Set.copyOf(types.values());
    }

    /**
     * @throws IllegalArgumentException if a class lacks a {@code public static final String TYPE}, or two classes
     *     declare the same type
     */
    @SafeVarargs
    public static OutboxEventTypes of(Class<? extends PlatformEvent>... eventClasses) {
        Map<String, Class<? extends PlatformEvent>> types = new HashMap<>();
        for (Class<? extends PlatformEvent> eventClass : List.of(eventClasses)) {
            String type = typeOf(eventClass);
            Class<? extends PlatformEvent> previous = types.putIfAbsent(type, eventClass);
            if (previous != null) {
                throw new IllegalArgumentException("Event type " + type + " is declared by both " + previous.getName()
                        + " and " + eventClass.getName());
            }
        }
        return new OutboxEventTypes(types);
    }

    public static OutboxEventTypes none() {
        return new OutboxEventTypes(Map.of());
    }

    public Class<? extends PlatformEvent> classFor(String eventType) {
        Class<? extends PlatformEvent> eventClass = types.get(eventType);
        if (eventClass == null) {
            throw new UnknownEventTypeException(eventType);
        }
        return eventClass;
    }

    public Set<Class<? extends PlatformEvent>> publishedTypes() {
        return publishedTypes;
    }

    Set<String> typeNames() {
        return types.keySet();
    }

    private static String typeOf(Class<? extends PlatformEvent> eventClass) {
        try {
            Field field = eventClass.getField("TYPE");
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers) || field.getType() != String.class) {
                throw new IllegalArgumentException(eventClass.getName() + ".TYPE must be a static final String");
            }
            String type = (String) field.get(null);
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException(eventClass.getName() + ".TYPE must not be blank");
            }
            return type;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalArgumentException(eventClass.getName() + " declares no public TYPE constant", e);
        }
    }
}
