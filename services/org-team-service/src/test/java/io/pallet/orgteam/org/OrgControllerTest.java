package io.pallet.orgteam.org;

import static io.pallet.common.test.security.PalletJwtRequestPostProcessors.orgJwt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.test.annotations.ControllerTest;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.security.AccessContext;
import io.pallet.orgteam.security.AccessEvaluator;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessExceptions.OrgNotFoundException;
import io.pallet.orgteam.security.AccessExceptions.ReauthenticationRequiredException;
import io.pallet.orgteam.security.AccessResolver;
import io.pallet.orgteam.security.RecentAuthentication;
import java.time.Instant;
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

    private static final String PATH = "/org-team/orgs/{orgId}";
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
        return new OrgDto("org-1", name, "acme", OrgStatus.ACTIVE, "user-1", CREATED, new OrgDto.Counts(3, 2, 1));
    }

    @Test
    void getReturnsTheEnvelopeWithCounts() throws Exception {
        given(orgService.get("org-1")).willReturn(dto("Acme"));

        mvc.perform(get(PATH, "org-1").with(orgJwt("org-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orgId").value("org-1"))
                .andExpect(jsonPath("$.data.slug").value("acme"))
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
        given(accessResolver.resolve("org-1")).willReturn(new AccessContext("org-1", "user-9", Role.ADMIN, CREATED));
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
    void patchRejectsABlankOverlongOrControlCharacterName() throws Exception {
        assertBadRequest("{\"name\":\"   \"}");
        assertBadRequest("{}");
        assertBadRequest("{\"name\":\"" + "n".repeat(256) + "\"}");
        assertBadRequest("{\"name\":\"Ac\\u0007me\"}");
    }

    @Test
    void deleteRequiresOwnerRecentAuthenticationAndTheSlugInThatOrder() throws Exception {
        AccessContext owner = new AccessContext("org-1", "user-1", Role.OWNER, CREATED);
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
        AccessContext owner = new AccessContext("org-1", "user-1", Role.OWNER, CREATED);
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
        given(accessResolver.resolve("org-1")).willReturn(new AccessContext("org-1", "user-1", Role.OWNER, CREATED));
        doThrow(new ConfirmationMismatchException()).when(deletionService).delete("org-1", "user-1", null);

        mvc.perform(delete(PATH, "org-1").with(orgJwt("org-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CONFIRMATION_MISMATCH"));
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
