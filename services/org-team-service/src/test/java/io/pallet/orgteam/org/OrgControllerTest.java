package io.pallet.orgteam.org;

import static io.pallet.common.test.security.PalletJwtRequestPostProcessors.orgJwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.test.annotations.ControllerTest;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.org.OrgExceptions.OrgSlugTakenException;
import io.pallet.orgteam.org.OrgExceptions.PersonalOrgImmutableException;
import io.pallet.orgteam.security.AccessContext;
import io.pallet.orgteam.security.AccessEvaluator;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import io.pallet.orgteam.security.AccessExceptions.ReauthenticationRequiredException;
import io.pallet.orgteam.security.AccessResolver;
import io.pallet.orgteam.security.RecentAuthentication;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ControllerTest(OrgController.class)
@Import(OrgControllerTest.MethodSecurity.class)
class OrgControllerTest {

    private static final String COLLECTION = "/org-team/orgs";
    private static final String PATH = COLLECTION + "/{orgId}";
    private static final Instant CREATED = Instant.parse("2026-03-01T10:00:00Z");

    @EnableMethodSecurity
    static class MethodSecurity {}

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OrgService orgService;

    @MockitoBean
    private OrgDeletionService deletionService;

    @MockitoBean
    private RecentAuthentication recentAuthentication;

    @MockitoBean
    private AccessResolver accessResolver;

    @MockitoBean(name = "access")
    private AccessEvaluator access;

    @BeforeEach
    void allowEveryRole() {
        given(access.atLeast(anyString(), anyString())).willReturn(true);
        given(access.isOwner(anyString())).willReturn(true);
    }

    private static OrgDto dto(String name) {
        return new OrgDto(
                "org-1", name, "acme", OrgKind.TEAM, OrgStatus.ACTIVE, "user-1", CREATED, new OrgDto.Counts(3, 2, 1));
    }

    @Test
    void getReturnsTheEnvelopeWithCounts() throws Exception {
        given(orgService.get("org-1")).willReturn(dto("Acme"));

        mvc.perform(get(PATH, "org-1").with(orgJwt("org-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orgId").value("org-1"))
                .andExpect(jsonPath("$.data.slug").value("acme"))
                .andExpect(jsonPath("$.data.kind").value("TEAM"))
                .andExpect(jsonPath("$.data.ownerUserId").value("user-1"))
                .andExpect(jsonPath("$.data.counts.members").value(3))
                .andExpect(jsonPath("$.data.counts.teams").value(2))
                .andExpect(jsonPath("$.data.counts.apps").value(1))
                .andExpect(jsonPath("$.data.version").doesNotExist());
        verify(access).atLeast("org-1", "VIEWER");
    }

    @Test
    void getOfAnotherOrgIsNotFound() throws Exception {
        doThrow(new OrgNotFoundException()).when(access).atLeast("org-2", "VIEWER");

        mvc.perform(get(PATH, "org-2").with(orgJwt("org-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORG_NOT_FOUND"));
        verifyNoInteractions(orgService);
    }

    @Test
    void patchRenamesAsAnAdmin() throws Exception {
        given(accessResolver.resolve("org-1"))
                .willReturn(new AccessContext("org-1", "user-9", Role.ADMIN, CREATED, null, null));
        given(orgService.rename("org-1", "user-9", "Acme Inc")).willReturn(dto("Acme Inc"));

        mvc.perform(patch(PATH, "org-1")
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Acme Inc  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Acme Inc"));
        verify(access).atLeast("org-1", "ADMIN");
    }

    @Test
    void patchBelowAdminIsForbidden() throws Exception {
        doThrow(new InsufficientRoleException()).when(access).atLeast("org-1", "ADMIN");

        mvc.perform(patch(PATH, "org-1")
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme Inc\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        verifyNoInteractions(orgService);
    }

    @Test
    void patchRejectsAnUnknownField() throws Exception {
        assertBadRequest("{\"name\":\"Acme\",\"slug\":\"x\"}");
        assertBadRequest("{\"ownerUserId\":\"user-2\"}");
        assertBadRequest("{\"name\":\"Acme\",\"status\":\"DELETED\"}");
    }

    @Test
    void patchRejectsAKindField() throws Exception {
        assertBadRequest("{\"kind\":\"TEAM\"}");
        assertBadRequest("{\"name\":\"Acme\",\"kind\":\"PERSONAL\"}");
    }

    @Test
    void patchRejectsABlankOverlongOrControlCharacterName() throws Exception {
        assertBadRequest("{\"name\":\"   \"}");
        assertBadRequest("{}");
        assertBadRequest("{\"name\":\"" + "n".repeat(256) + "\"}");
        assertBadRequest("{\"name\":\"Ac\\u0007me\"}");
    }

    @Test
    void deleteRequiresOwnerRecentAuthenticationAndTheSlugInThatOrder() throws Exception {
        AccessContext owner = new AccessContext("org-1", "user-1", Role.OWNER, CREATED, null, null);
        given(accessResolver.resolve("org-1")).willReturn(owner);

        mvc.perform(delete(PATH, "org-1").with(orgJwt("org-1")).header("X-Confirm-Slug", "acme"))
                .andExpect(status().isNoContent());

        InOrder order = inOrder(access, recentAuthentication, deletionService);
        order.verify(access).isOwner("org-1");
        order.verify(recentAuthentication).require(owner);
        order.verify(deletionService).delete("org-1", "user-1", "acme");
    }

    @Test
    void deleteBelowOwnerIsForbiddenBeforeAnythingElseRuns() throws Exception {
        doThrow(new InsufficientRoleException()).when(access).isOwner("org-1");

        mvc.perform(delete(PATH, "org-1").with(orgJwt("org-1")).header("X-Confirm-Slug", "acme"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        verifyNoInteractions(deletionService, recentAuthentication);
    }

    @Test
    void deleteWithAStaleAuthenticationIsForbiddenAndDeletesNothing() throws Exception {
        AccessContext owner = new AccessContext("org-1", "user-1", Role.OWNER, CREATED, null, null);
        given(accessResolver.resolve("org-1")).willReturn(owner);
        doThrow(new ReauthenticationRequiredException())
                .when(recentAuthentication)
                .require(owner);

        mvc.perform(delete(PATH, "org-1").with(orgJwt("org-1")).header("X-Confirm-Slug", "acme"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REAUTHENTICATION_REQUIRED"));
        verifyNoInteractions(deletionService);
    }

    @Test
    void deleteWithoutTheHeaderReachesTheServiceWhichRefusesIt() throws Exception {
        given(accessResolver.resolve("org-1"))
                .willReturn(new AccessContext("org-1", "user-1", Role.OWNER, CREATED, null, null));
        doThrow(new ConfirmationMismatchException()).when(deletionService).delete("org-1", "user-1", null);

        mvc.perform(delete(PATH, "org-1").with(orgJwt("org-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CONFIRMATION_MISMATCH"));
    }

    @Test
    void postCreatesATeamOrgForTheCallerWithoutAnOrgGate() throws Exception {
        AccessContext caller = new AccessContext(null, "user-9", null, CREATED, "ada@example.com", "Ada");
        given(accessResolver.caller()).willReturn(caller);
        given(orgService.createTeamOrg(caller, "Acme Inc", null)).willReturn(dto("Acme Inc"));

        mvc.perform(post(COLLECTION)
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Acme Inc  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Acme Inc"))
                .andExpect(jsonPath("$.data.kind").value("TEAM"));
        verifyNoInteractions(access);
    }

    @Test
    void postPassesAnExplicitSlugThrough() throws Exception {
        AccessContext caller = new AccessContext(null, "user-9", null, CREATED, "ada@example.com", "Ada");
        given(accessResolver.caller()).willReturn(caller);
        given(orgService.createTeamOrg(caller, "Acme", "acme-hq")).willReturn(dto("Acme"));

        mvc.perform(post(COLLECTION)
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"slug\":\"acme-hq\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void postWithATakenSlugIsAConflict() throws Exception {
        given(accessResolver.caller())
                .willReturn(new AccessContext(null, "user-9", null, CREATED, "ada@example.com", "Ada"));
        given(orgService.createTeamOrg(any(), eq("Acme"), eq("acme"))).willThrow(new OrgSlugTakenException());

        mvc.perform(post(COLLECTION)
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"slug\":\"acme\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLUG_TAKEN"));
    }

    @Test
    void postRejectsABlankOverlongOrControlCharacterName() throws Exception {
        assertCreateBadRequest("{}");
        assertCreateBadRequest("{\"name\":\"   \"}");
        assertCreateBadRequest("{\"name\":\"" + "n".repeat(256) + "\"}");
        assertCreateBadRequest("{\"name\":\"Ac\\u0007me\"}");
    }

    @Test
    void postRejectsASlugThatIsNotADnsLabel() throws Exception {
        assertCreateBadRequest("{\"name\":\"Acme\",\"slug\":\"Acme\"}");
        assertCreateBadRequest("{\"name\":\"Acme\",\"slug\":\"-acme\"}");
        assertCreateBadRequest("{\"name\":\"Acme\",\"slug\":\"ac_me\"}");
        assertCreateBadRequest("{\"name\":\"Acme\",\"slug\":\"" + "a".repeat(64) + "\"}");
    }

    @Test
    void postRejectsAnUnknownFieldIncludingKind() throws Exception {
        assertCreateBadRequest("{\"name\":\"Acme\",\"kind\":\"PERSONAL\"}");
        assertCreateBadRequest("{\"name\":\"Acme\",\"ownerUserId\":\"user-2\"}");
    }

    @Test
    void listReturnsTheCallersOrgsWithTheirRoleInEach() throws Exception {
        given(accessResolver.caller())
                .willReturn(new AccessContext(null, "user-9", null, CREATED, "ada@example.com", "Ada"));
        List<OrgSummaryDto> mine = List.of(
                new OrgSummaryDto("org-1", "Ada", "ada", OrgKind.PERSONAL, Role.OWNER),
                new OrgSummaryDto("org-2", "Acme", "acme", OrgKind.TEAM, Role.VIEWER));
        given(orgService.listMyOrgs("user-9", new PageQuery(0, 20, null)))
                .willReturn(new PageResponse<>(mine, 0, 20, 2, 1, true, true));

        mvc.perform(get(COLLECTION).param("page", "0").param("size", "20").with(orgJwt("org-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].orgId").value("org-1"))
                .andExpect(jsonPath("$.data.content[0].kind").value("PERSONAL"))
                .andExpect(jsonPath("$.data.content[0].myRole").value("OWNER"))
                .andExpect(jsonPath("$.data.content[1].kind").value("TEAM"))
                .andExpect(jsonPath("$.data.content[1].myRole").value("VIEWER"))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        verifyNoInteractions(access);
    }

    @Test
    void deleteOfAPersonalOrgIsAConflict() throws Exception {
        given(accessResolver.resolve("org-1"))
                .willReturn(new AccessContext("org-1", "user-1", Role.OWNER, CREATED, null, null));
        doThrow(new PersonalOrgImmutableException()).when(deletionService).delete("org-1", "user-1", "acme");

        mvc.perform(delete(PATH, "org-1").with(orgJwt("org-1")).header("X-Confirm-Slug", "acme"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PERSONAL_ORG_IMMUTABLE"));
    }

    private void assertCreateBadRequest(String body) throws Exception {
        mvc.perform(post(COLLECTION)
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(orgService);
    }

    private void assertBadRequest(String body) throws Exception {
        mvc.perform(patch(PATH, "org-1")
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(orgService);
    }
}
