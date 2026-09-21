package io.pallet.orgteam.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.events.AppCreated;
import io.pallet.common.events.AppDeleted;
import io.pallet.common.events.AuditEventRecorded;
import io.pallet.common.events.DeployStateChanged;
import io.pallet.common.events.GitPushReceived;
import io.pallet.common.events.NotificationRequested;
import io.pallet.common.events.OrgDeleted;
import io.pallet.common.events.OrgInviteAccepted;
import io.pallet.common.events.OrgInviteRejected;
import io.pallet.common.events.OrgMemberAdded;
import io.pallet.common.events.OrgMemberRemoved;
import io.pallet.common.events.OrgMemberRoleChanged;
import io.pallet.common.events.OrgProvisioned;
import io.pallet.common.events.PlatformEvent;
import io.pallet.common.events.UserProfileUpdated;
import io.pallet.common.test.annotations.UnitTest;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

@UnitTest
class EventTypeRegistryTest {

    private static final Set<Class<? extends PlatformEvent>> PUBLISHED = Set.of(
            OrgMemberAdded.class,
            OrgMemberRemoved.class,
            OrgMemberRoleChanged.class,
            OrgDeleted.class,
            OrgInviteRejected.class,
            AppCreated.class,
            AppDeleted.class,
            NotificationRequested.class,
            AuditEventRecorded.class);

    private static final Set<Class<? extends PlatformEvent>> NOT_PUBLISHED_HERE = Set.of(
            OrgProvisioned.class,
            OrgInviteAccepted.class,
            UserProfileUpdated.class,
            DeployStateChanged.class,
            GitPushReceived.class);

    private final EventTypeRegistry registry = new EventTypeRegistry();

    @Test
    void everyPublishedTypeResolvesToItsClassByItsTopic() throws Exception {
        for (Class<? extends PlatformEvent> eventClass : PUBLISHED) {
            String type = (String) eventClass.getField("TYPE").get(null);
            assertThat(registry.classFor(type)).isEqualTo(eventClass);
        }
        assertThat(registry.publishedTypes()).isEqualTo(PUBLISHED);
    }

    @Test
    void anUnknownTypeIsRejected() {
        assertThatThrownBy(() -> registry.classFor("bogus.event"))
                .isInstanceOf(UnknownEventTypeException.class)
                .hasMessageContaining("bogus.event");
    }

    @Test
    void everyEventContractIsEitherRegisteredOrDeclaredNotPublishedHere() {
        Set<Class<?>> contracts = eventContracts();
        Set<Class<?>> classified = new HashSet<>(PUBLISHED);
        classified.addAll(NOT_PUBLISHED_HERE);

        assertThat(contracts)
                .as("a new event in platform-common-events must be registered or listed as not published here")
                .isEqualTo(classified);
    }

    private static Set<Class<?>> eventContracts() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(PlatformEvent.class));
        Set<Class<?>> contracts = new HashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("io.pallet.common.events")) {
            try {
                contracts.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return contracts;
    }
}
