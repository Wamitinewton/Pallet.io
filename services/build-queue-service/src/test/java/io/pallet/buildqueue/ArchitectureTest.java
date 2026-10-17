package io.pallet.buildqueue;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.pallet.buildqueue.build.TenantRuleFixtureRepository;
import io.pallet.buildqueue.config.CrossTenant;
import io.pallet.common.test.annotations.UnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@UnitTest
class ArchitectureTest {

    private static final String ROOT = "io.pallet.buildqueue";

    private static final String CLOCK_CONFIGURATION = ROOT + ".config.ClockConfiguration";

    private static final Set<Class<?>> WALL_CLOCK_OWNERS =
            Set.of(Instant.class, LocalDateTime.class, OffsetDateTime.class, ZonedDateTime.class);

    private static final Set<String> SYSTEM_CLOCK_FACTORIES = Set.of("systemUTC", "systemDefaultZone");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void tenantRepositoryMethodsTakeAnOrgIdOrDeclareWhyTheySpanOrgs() {
        tenantRule().check(classes);
    }

    @Test
    void theTenantRuleFailsARepositoryMethodWithoutAnOrgId() {
        JavaClasses fixture = new ClassFileImporter().importClasses(TenantRuleFixtureRepository.class);

        assertThatThrownBy(() -> tenantRule().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("findByAppId")
                .hasMessageContaining("findAllBlank")
                .hasMessageNotContaining("findByOrgIdAndAppId")
                .hasMessageNotContaining("findDispatchCandidates");
    }

    @Test
    void onlyTheOutboxReachesKafka() {
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".outbox..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("io.pallet.common.messaging.PlatformEventPublisher")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void onlyTheClockConfigurationReadsTheWallClock() {
        clockRule().check(classes);
    }

    @Test
    void theClockRuleFailsAClassThatReadsTheWallClock() {
        JavaClasses fixture = new ClassFileImporter().importClasses(WallClockFixture.class);

        assertThatThrownBy(() -> clockRule().check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Instant.now()")
                .hasMessageContaining("LocalDateTime.now()")
                .hasMessageContaining("Clock.systemUTC()")
                .hasMessageNotContaining("Instant.now(java.time.Clock)");
    }

    private static ArchRule clockRule() {
        return noClasses()
                .that()
                .doNotHaveFullyQualifiedName(CLOCK_CONFIGURATION)
                .should()
                .callMethodWhere(readsTheWallClock())
                .allowEmptyShould(true);
    }

    private static ArchRule tenantRule() {
        return methods()
                .that()
                .areDeclaredInClassesThat(tenantRepositories())
                .and()
                .areNotPrivate()
                .and()
                .doNotHaveModifier(JavaModifier.SYNTHETIC)
                .and()
                .doNotHaveModifier(JavaModifier.BRIDGE)
                .should(takeOrgIdOrBeCrossTenant())
                .allowEmptyShould(true);
    }

    /** Every repository in this service maps {@code builds}, {@code apps}, {@code orgs} or {@code org_memberships}. */
    private static DescribedPredicate<JavaClass> tenantRepositories() {
        return DescribedPredicate.describe(
                "are repositories",
                javaClass -> javaClass.getSimpleName().endsWith("Repository")
                        || javaClass.isAnnotatedWith(org.springframework.stereotype.Repository.class)
                        || javaClass.isAssignableTo(org.springframework.data.repository.Repository.class));
    }

    private static DescribedPredicate<JavaMethodCall> readsTheWallClock() {
        return DescribedPredicate.describe(
                "reads the system clock instead of the injected Clock",
                call -> (call.getName().equals("now")
                                && call.getTarget().getRawParameterTypes().isEmpty()
                                && WALL_CLOCK_OWNERS.stream().anyMatch(call.getTargetOwner()::isEquivalentTo))
                        || (call.getTargetOwner().isEquivalentTo(Clock.class)
                                && SYSTEM_CLOCK_FACTORIES.contains(call.getName())));
    }

    private static ArchCondition<JavaMethod> takeOrgIdOrBeCrossTenant() {
        return new ArchCondition<>("declare an orgId parameter or be @CrossTenant with a reason") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean crossTenant = method.isAnnotatedWith(CrossTenant.class)
                        && !method.getAnnotationOfType(CrossTenant.class)
                                .value()
                                .isBlank();
                boolean scoped = Arrays.stream(method.reflect().getParameters())
                        .anyMatch(parameter -> parameter.getName().equals("orgId"));
                if (!crossTenant && !scoped) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            method.getFullName() + " has no orgId parameter and is not @CrossTenant with a reason"));
                }
            }
        };
    }

    static final class WallClockFixture {

        Instant instant() {
            return Instant.now();
        }

        LocalDateTime localDateTime() {
            return LocalDateTime.now();
        }

        Clock systemClock() {
            return Clock.systemUTC();
        }

        Instant fromInjectedClock(Clock clock) {
            return Instant.now(clock);
        }
    }
}
