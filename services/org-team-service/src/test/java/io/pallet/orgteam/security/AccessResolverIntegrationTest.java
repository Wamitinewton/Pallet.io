package io.pallet.orgteam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.security.AccessExceptions.NotAMemberException;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@IntegrationTest
@Import(RedisTestContainerConfiguration.class)
class AccessResolverIntegrationTest {

    private static final String DRIFT_METRIC = "orgteam.authz.token_role_drift";

    @Autowired
    private AccessResolver resolver;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private OrgFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new OrgFixtures(jdbc);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        fixtures.cleanUp();
    }

    @Test
    void anActiveMemberResolvesToTheRoleOnTheirRow() {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "DEVELOPER", "ACTIVE");
        Instant authTime = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        authenticate(OrgTeamTestTokens.forMember(orgId, userId)
                .claimingRoles("developer")
                .authenticatedAt(authTime));

        AccessContext context = resolver.resolve(orgId);

        assertThat(context).isEqualTo(new AccessContext(orgId, userId, Role.DEVELOPER, authTime));
    }

    @Test
    void theRowWinsOverWhateverRoleTheTokenClaims() {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "VIEWER", "ACTIVE");
        authenticate(OrgTeamTestTokens.forMember(orgId, userId).claimingRoles("owner"));

        assertThat(resolver.resolve(orgId).role()).isEqualTo(Role.VIEWER);
    }

    @Test
    void aTokenForAnotherOrgIsIndistinguishableFromAnUnknownOrg() {
        String ownOrg = fixtures.newActiveOrg();
        String otherOrg = fixtures.newActiveOrg();
        String userId = fixtures.newMember(ownOrg, "OWNER", "ACTIVE");
        authenticate(OrgTeamTestTokens.forMember(ownOrg, userId));

        Throwable wrongOrg = catchOrgNotFound(otherOrg);
        Throwable unknownOrg = catchOrgNotFound("org-that-does-not-exist");

        assertThat(wrongOrg).isInstanceOf(OrgNotFoundException.class);
        assertThat(unknownOrg).isInstanceOf(OrgNotFoundException.class).hasMessage(wrongOrg.getMessage());
    }

    @Test
    void anUnknownOrgUnderAMatchingTokenIsNotFound() {
        authenticate(OrgTeamTestTokens.forMember("org-ghost", "user-1"));

        assertThatThrownBy(() -> resolver.resolve("org-ghost")).isInstanceOf(OrgNotFoundException.class);
    }

    @Test
    void aRemovedMemberIsNotAMember() {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "ADMIN", "REMOVED");
        authenticate(OrgTeamTestTokens.forMember(orgId, userId).claimingRoles("admin"));

        assertThatThrownBy(() -> resolver.resolve(orgId)).isInstanceOf(NotAMemberException.class);
    }

    @Test
    void aCallerWithNoRowInAnExistingOrgIsNotAMember() {
        String orgId = fixtures.newActiveOrg();
        authenticate(OrgTeamTestTokens.forMember(orgId, "user-with-no-row").claimingRoles("owner"));

        assertThatThrownBy(() -> resolver.resolve(orgId)).isInstanceOf(NotAMemberException.class);
    }

    @Test
    void aTokenWithoutASubjectIsNotAMember() {
        String orgId = fixtures.newActiveOrg();
        authenticate(OrgTeamTestTokens.forMember(orgId, "ignored").withoutSubject());

        assertThatThrownBy(() -> resolver.resolve(orgId)).isInstanceOf(NotAMemberException.class);
    }

    @Test
    void aMemberOfADeletedOrgIsNotFound() {
        String orgId = fixtures.newOrg("DELETED");
        String userId = fixtures.newMember(orgId, "OWNER", "ACTIVE");
        authenticate(OrgTeamTestTokens.forMember(orgId, userId).claimingRoles("owner"));

        assertThatThrownBy(() -> resolver.resolve(orgId)).isInstanceOf(OrgNotFoundException.class);
    }

    @Test
    void aMissingAuthTimeResolvesWithANullAuthTime() {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "OWNER", "ACTIVE");
        authenticate(OrgTeamTestTokens.forMember(orgId, userId).withoutAuthTime());

        assertThat(resolver.resolve(orgId).authTime()).isNull();
    }

    @Test
    void aRoleChangedBetweenTwoCallsIsSeenByTheSecond() {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "ADMIN", "ACTIVE");
        authenticate(OrgTeamTestTokens.forMember(orgId, userId).claimingRoles("admin"));

        assertThat(resolver.resolve(orgId).role()).isEqualTo(Role.ADMIN);
        fixtures.setRole(orgId, userId, "VIEWER");

        assertThat(resolver.resolve(orgId).role()).isEqualTo(Role.VIEWER);
    }

    @Test
    void aRemovalBetweenTwoCallsIsSeenByTheSecond() {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "ADMIN", "ACTIVE");
        authenticate(OrgTeamTestTokens.forMember(orgId, userId).claimingRoles("admin"));

        assertThat(resolver.resolve(orgId).role()).isEqualTo(Role.ADMIN);
        fixtures.setStatus(orgId, userId, "REMOVED");

        assertThatThrownBy(() -> resolver.resolve(orgId)).isInstanceOf(NotAMemberException.class);
    }

    @Test
    void withinOneRequestTheResolvedContextIsReusedWithoutASecondQuery() {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "ADMIN", "ACTIVE");
        authenticate(OrgTeamTestTokens.forMember(orgId, userId).claimingRoles("admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        AccessContext first = resolver.resolve(orgId);
        fixtures.setRole(orgId, userId, "VIEWER");

        assertThat(resolver.resolve(orgId)).isSameAs(first);
    }

    @Test
    void aMismatchBetweenTokenAndRowIncrementsTheDriftMetric() {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "VIEWER", "ACTIVE");
        authenticate(OrgTeamTestTokens.forMember(orgId, userId).claimingRoles("owner", "offline_access"));
        double before = driftCount();

        resolver.resolve(orgId);

        assertThat(driftCount()).isEqualTo(before + 1);
    }

    @Test
    void aTokenWithNoOrgRoleAtAllCountsAsDrift() {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "VIEWER", "ACTIVE");
        authenticate(OrgTeamTestTokens.forMember(orgId, userId).claimingRoles("offline_access"));
        double before = driftCount();

        resolver.resolve(orgId);

        assertThat(driftCount()).isEqualTo(before + 1);
    }

    @Test
    void aMatchingTokenLeavesTheDriftMetricAlone() {
        String orgId = fixtures.newActiveOrg();
        String userId = fixtures.newMember(orgId, "ADMIN", "ACTIVE");
        authenticate(OrgTeamTestTokens.forMember(orgId, userId).claimingRoles("developer", "admin", "offline_access"));
        double before = driftCount();

        resolver.resolve(orgId);

        assertThat(driftCount()).isEqualTo(before);
    }

    private Throwable catchOrgNotFound(String pathOrgId) {
        return catchThrowable(() -> resolver.resolve(pathOrgId));
    }

    private double driftCount() {
        return meters.get(DRIFT_METRIC).counter().count();
    }

    private static void authenticate(OrgTeamTestTokens token) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token.jwt()));
    }
}
