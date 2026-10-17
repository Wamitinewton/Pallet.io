package io.pallet.buildqueue.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pallet.buildqueue.security.AccessExceptions.NotAMemberException;
import io.pallet.buildqueue.security.AccessExceptions.OrgNotFoundException;
import io.pallet.buildqueue.support.ReadModelFixtures;
import io.pallet.buildqueue.support.Tokens;
import io.pallet.common.error.AppException;
import io.pallet.common.test.annotations.RepositoryTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RepositoryTest
@Import({AccessResolver.class, AccessResolverIntegrationTest.Meters.class})
class AccessResolverIntegrationTest {

    @Autowired
    private AccessResolver resolver;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private ReadModelFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ReadModelFixtures(jdbc);
        newRequest();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void anActiveMemberResolvesToTheRoleOnTheirRow() {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "developer", "ACTIVE");
        authenticate(Tokens.forUser(userId));

        assertThat(resolver.resolve(orgId)).isEqualTo(new AccessContext(orgId, userId, Role.DEVELOPER));
    }

    @Test
    void theRowWinsOverAnyOrgOrRoleTheTokenClaims() {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "viewer", "ACTIVE");
        authenticate(Tokens.forUser(userId).claimingOrg("org-elsewhere").claimingRoles("owner"));

        assertThat(resolver.resolve(orgId).role()).isEqualTo(Role.VIEWER);
    }

    @Test
    void noMembershipRowIsOrgNotFound() {
        String orgId = fixtures.newOrg();
        fixtures.newMember(orgId, "owner", "ACTIVE");
        authenticate(Tokens.forUser("user-" + UUID.randomUUID()));

        assertDenied(orgId, OrgNotFoundException.class, AccessResolver.REASON_NO_MEMBERSHIP);
    }

    @Test
    void anOrgThisServiceHasNeverSeenIsOrgNotFound() {
        authenticate(Tokens.forUser("user-" + UUID.randomUUID()));

        assertDenied("org-" + UUID.randomUUID(), OrgNotFoundException.class, AccessResolver.REASON_NO_MEMBERSHIP);
    }

    @Test
    void aDeletedOrgIsOrgNotFoundEvenWithAnActiveRow() {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "owner", "ACTIVE");
        fixtures.deleteOrg(orgId);
        authenticate(Tokens.forUser(userId));

        assertDenied(orgId, OrgNotFoundException.class, AccessResolver.REASON_ORG_DELETED);
    }

    @Test
    void aDeletedOrgIsTaggedDeletedWhenTheCallerHasNoRow() {
        String orgId = fixtures.newOrg();
        fixtures.deleteOrg(orgId);
        authenticate(Tokens.forUser("user-" + UUID.randomUUID()));

        assertDenied(orgId, OrgNotFoundException.class, AccessResolver.REASON_ORG_DELETED);
    }

    @Test
    void aRemovedRowIsNotAMember() {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "admin", "REMOVED");
        authenticate(Tokens.forUser(userId));

        assertDenied(orgId, NotAMemberException.class, AccessResolver.REASON_NOT_A_MEMBER);
    }

    @Test
    void aTokenWithoutSubIsNotAMember() {
        String orgId = fixtures.newOrg();
        fixtures.newMember(orgId, "owner", "ACTIVE");
        authenticate(Tokens.forUser("ignored").withoutSubject());

        assertDenied(orgId, NotAMemberException.class, AccessResolver.REASON_NOT_A_MEMBER);
    }

    @Test
    void theTwoNotFoundCausesAreEqualExceptions() {
        String deletedOrg = fixtures.newOrg();
        String userId = fixtures.newMember(deletedOrg, "owner", "ACTIVE");
        fixtures.deleteOrg(deletedOrg);
        authenticate(Tokens.forUser(userId));

        AppException deleted = catchThrowableOfType(AppException.class, () -> resolver.resolve(deletedOrg));
        AppException unknown =
                catchThrowableOfType(AppException.class, () -> resolver.resolve("org-" + UUID.randomUUID()));

        assertThat(deleted).isExactlyInstanceOf(unknown.getClass());
        assertThat(deleted.getStatus()).isEqualTo(unknown.getStatus());
        assertThat(deleted.getErrorCode()).isEqualTo(unknown.getErrorCode());
        assertThat(deleted.getClientMessage()).isEqualTo(unknown.getClientMessage());
        assertThat(deleted.getMessage()).isEqualTo(unknown.getMessage());
        assertThat(deleted.getMeta()).isEqualTo(unknown.getMeta());
        assertThat(deleted.getValidationErrors()).isEqualTo(unknown.getValidationErrors());
    }

    @Test
    void theDecisionIsMemoizedWithinARequestAndNotAcrossRequests() {
        String orgId = fixtures.newOrg();
        String userId = fixtures.newMember(orgId, "admin", "ACTIVE");
        authenticate(Tokens.forUser(userId));

        AccessContext first = resolver.resolve(orgId);
        fixtures.setRole(orgId, userId, "viewer");

        assertThat(resolver.resolve(orgId)).isSameAs(first);

        newRequest();
        assertThat(resolver.resolve(orgId).role()).isEqualTo(Role.VIEWER);

        fixtures.setStatus(orgId, userId, "REMOVED");
        newRequest();
        assertThatThrownBy(() -> resolver.resolve(orgId)).isInstanceOf(NotAMemberException.class);
    }

    @Test
    void theMemoIsKeyedByOrg() {
        String orgA = fixtures.newOrg();
        String orgB = fixtures.newOrg();
        String userId = fixtures.newMember(orgA, "owner", "ACTIVE");
        fixtures.addMember(orgB, userId, "viewer", "ACTIVE");
        authenticate(Tokens.forUser(userId));

        assertThat(resolver.resolve(orgA).role()).isEqualTo(Role.OWNER);
        assertThat(resolver.resolve(orgB).role()).isEqualTo(Role.VIEWER);
    }

    @Test
    void everyDenialReasonIsRegisteredBeforeTheFirstDenial() {
        for (String reason : new String[] {
            AccessResolver.REASON_ORG_DELETED,
            AccessResolver.REASON_NO_MEMBERSHIP,
            AccessResolver.REASON_NOT_A_MEMBER,
            AccessResolver.REASON_INSUFFICIENT_ROLE
        }) {
            assertThat(counter(reason)).as(reason).isNotNull();
        }
    }

    private void assertDenied(String orgId, Class<? extends AppException> expected, String reason) {
        double before = counter(reason).count();

        assertThatThrownBy(() -> resolver.resolve(orgId)).isExactlyInstanceOf(expected);

        assertThat(counter(reason).count()).isEqualTo(before + 1);
    }

    private Counter counter(String reason) {
        return meters.find(AccessResolver.AUTHZ_DENIED)
                .tag(AccessResolver.TAG_REASON, reason)
                .counter();
    }

    private static void authenticate(Tokens token) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token.jwt()));
    }

    private static void newRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Meters {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
