package io.pallet.gitintegration;

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
import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.config.CrossTenant;
import io.pallet.gitintegration.repolink.TenantRuleFixtureRepository;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@UnitTest
class ArchitectureTest {

    private static final String ROOT = "io.pallet.gitintegration";

    /**
     * Tenant tables and the packages whose repositories map them. {@code installation} holds GitHub-keyed tables too,
     * so there only the {@code InstallationLink} repository is tenant-scoped.
     */
    private static final Set<String> TENANT_PACKAGES = Set.of("repolink", "push", "checks", "projection", "retention");

    private static final String TENANT_INSTALLATION_REPOSITORY = "InstallationLinkRepository";

    private static final Set<String> JWT_CLAIM_READERS = Set.of(
            "getClaim",
            "getClaims",
            "getClaimAsMap",
            "getClaimAsString",
            "getClaimAsStringList",
            "getClaimAsInstant",
            "getClaimAsBoolean",
            "getClaimAsURL",
            "hasClaim",
            "getTokenAttributes");

    /** Suffix of the type holding a GitHub user token (checkpoint 08). */
    private static final String USER_TOKEN_SUFFIX = "UserToken";

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
                .hasMessageNotContaining("findByRepoId");
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

    @Test
    void nothingPublishesToKafkaOrDedupesOutsideTheOutboxAndInbox() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("io.pallet.common.messaging.PlatformEventPublisher")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("io.pallet.common.messaging.EventIdempotencyGuard")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void onlyTheGitHubClientMakesOutboundHttpCalls() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..github..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.client.RestClient")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.reactive.function.client.WebClient")
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("java.net.http.HttpClient")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void nothingReadsAnOrgOrARoleOffTheToken() {
        noClasses()
                .should()
                .callMethodWhere(readsARawJwtClaim())
                .orShould()
                .dependOnClassesThat()
                .haveFullyQualifiedName("io.pallet.common.security.OrgContext")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void onlyTheSessionAndGitHubPackagesHoldAGitHubUserToken() {
        noClasses()
                .that()
                .resideOutsideOfPackages("..session..", "..github..")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith(USER_TOKEN_SUFFIX)
                .allowEmptyShould(true)
                .check(classes);
    }

    private static DescribedPredicate<JavaClass> tenantRepositories() {
        return DescribedPredicate.describe(
                "are repositories of a tenant table",
                javaClass -> javaClass.getSimpleName().endsWith("Repository")
                        && (Arrays.stream(javaClass.getPackageName().split("\\."))
                                        .anyMatch(TENANT_PACKAGES::contains)
                                || javaClass.getSimpleName().equals(TENANT_INSTALLATION_REPOSITORY)));
    }

    private static DescribedPredicate<JavaMethodCall> readsARawJwtClaim() {
        return DescribedPredicate.describe(
                "reads a claim other than sub off a Jwt",
                call -> (call.getTargetOwner().isAssignableTo(Jwt.class)
                                || call.getTargetOwner().isAssignableTo(JwtAuthenticationToken.class))
                        && JWT_CLAIM_READERS.contains(call.getName()));
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
}
