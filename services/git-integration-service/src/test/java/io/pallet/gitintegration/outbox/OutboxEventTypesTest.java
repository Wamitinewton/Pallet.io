package io.pallet.gitintegration.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.outbox.OutboxEventTypes;
import io.pallet.common.outbox.UnknownEventTypeException;
import io.pallet.common.test.annotations.UnitTest;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

@UnitTest
class OutboxEventTypesTest {

    private static final Set<Class<? extends PlatformEvent>> PUBLISHED =
            Set.of(GitPushReceived.class, NotificationRequested.class, AuditEventRecorded.class);

    private final OutboxEventTypes eventTypes = new OutboxEventTypesConfiguration().outboxEventTypes();

    @Test
    void exactlyTheThreePublishedTypesAreRegisteredUnderTheirTopics() throws Exception {
        assertThat(eventTypes.publishedTypes()).isEqualTo(PUBLISHED);
        for (Class<? extends PlatformEvent> eventClass : PUBLISHED) {
            String type = (String) eventClass.getField("TYPE").get(null);
            assertThat(eventTypes.classFor(type)).isEqualTo(eventClass);
        }
    }

    @Test
    void aConsumedTypeCannotBeAppended() {
        assertThatThrownBy(() -> eventTypes.classFor(OrgDeleted.TYPE)).isInstanceOf(UnknownEventTypeException.class);
    }

    @Test
    void everyEventTheServiceConstructsIsRegistered() {
        JavaClasses main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.pallet.gitintegration");

        assertThat(constructedEvents(main))
                .as("an event built in main code must be declared in OutboxEventTypesConfiguration")
                .isSubsetOf(eventTypes.publishedTypes());
    }

    @Test
    void theScanSeesConstructorsAndStaticFactories() {
        JavaClasses fixture = new ClassFileImporter().importClasses(UnregisteredEventFixture.class);

        assertThat(constructedEvents(fixture)).containsExactlyInAnyOrder(OrgDeleted.class, AppDeleted.class);
    }

    /** Event classes whose constructor, or a static factory returning the class, is called. */
    private static Set<Class<?>> constructedEvents(JavaClasses classes) {
        Set<Class<?>> constructed = new HashSet<>();
        for (JavaClass javaClass : classes) {
            Stream<JavaClass> constructors =
                    javaClass.getConstructorCallsFromSelf().stream().map(JavaConstructorCall::getTargetOwner);
            Stream<JavaClass> factories = javaClass.getMethodCallsFromSelf().stream()
                    .filter(call -> call.getTarget().getRawReturnType().equals(call.getTargetOwner()))
                    .map(JavaMethodCall::getTargetOwner);
            Stream.concat(constructors, factories)
                    .map(JavaClass::reflect)
                    .filter(PlatformEvent.class::isAssignableFrom)
                    .forEach(constructed::add);
        }
        return constructed;
    }
}
