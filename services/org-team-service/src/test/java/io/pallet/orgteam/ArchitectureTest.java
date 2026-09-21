package io.pallet.orgteam;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.orgteam.config.CrossTenant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@UnitTest
class ArchitectureTest {

    private static final String[] TENANT_PACKAGES = {"..org..", "..member..", "..invite..", "..team..", "..app.."};
    private static final String[] JWT_CLAIM_READERS = {
        "getClaim", "getClaimAsMap", "getClaims", "getClaimAsStringList", "getClaimAsString", "getClaimAsInstant"
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.pallet.orgteam");
    }

    @Test
    void tenantRepositoryMethodsTakeAnOrgIdOrDeclareThemselvesCrossTenant() {
        methods()
                .that()
                .areDeclaredInClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .and()
                .areDeclaredInClassesThat()
                .areInterfaces()
                .and()
                .areDeclaredInClassesThat()
                .resideInAnyPackage(TENANT_PACKAGES)
                .should(takeOrgIdOrBeCrossTenant())
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void onlyTheOutboxTouchesTheKafkaPublishingPath() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..outbox..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("io.pallet.common.messaging.PlatformEventPublisher")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void theRedisIdempotencyGuardIsNeverUsed() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("io.pallet.common.messaging.EventIdempotencyGuard")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void onlyTheAccessResolverReadsJwtClaimsByHand() {
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName("io.pallet.orgteam.security.AccessResolver")
                .should()
                .callMethodWhere(readsRawJwtClaim())
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void onlyTheSweepsCallCrossTenantRepositoryMethods() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..retention..")
                .and()
                .doNotHaveFullyQualifiedName("io.pallet.orgteam.invite.InviteExpirySweep")
                .should()
                .callMethodWhere(targetsCrossTenantMethod())
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void onlyObservabilityAndTheOutboxTouchTheMeterRegistry() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..observability..")
                .and()
                .resideOutsideOfPackage("..outbox..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("io.micrometer.core.instrument.MeterRegistry")
                .allowEmptyShould(true)
                .check(classes);
    }

    private static DescribedPredicate<JavaMethodCall> targetsCrossTenantMethod() {
        return DescribedPredicate.describe(
                "targets a @CrossTenant repository method",
                call -> call.getTarget().resolveMember().stream()
                        .anyMatch(method -> method.isAnnotatedWith(CrossTenant.class)));
    }

    private static DescribedPredicate<JavaMethodCall> readsRawJwtClaim() {
        return DescribedPredicate.describe(
                "reads a raw claim off a Jwt",
                call -> call.getTargetOwner().isEquivalentTo(Jwt.class)
                        && Arrays.asList(JWT_CLAIM_READERS).contains(call.getName()));
    }

    private static ArchCondition<JavaMethod> takeOrgIdOrBeCrossTenant() {
        return new ArchCondition<>("declare an orgId parameter or be annotated @CrossTenant") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean crossTenant = method.isAnnotatedWith(CrossTenant.class);
                boolean hasOrgId = Arrays.stream(method.reflect().getParameters())
                        .anyMatch(parameter -> parameter.getName().equals("orgId"));
                if (!crossTenant && !hasOrgId) {
                    events.add(SimpleConditionEvent.violated(
                            method, method.getFullName() + " has no orgId parameter and is not @CrossTenant"));
                }
            }
        };
    }
}
