package io.pallet.orgteam.member;

import static io.pallet.common.test.security.PalletJwtRequestPostProcessors.orgJwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
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
import io.pallet.orgteam.member.MemberExceptions.InvalidSortException;
import io.pallet.orgteam.member.MemberExceptions.MemberNotFoundException;
import io.pallet.orgteam.security.AccessContext;
import io.pallet.orgteam.security.AccessEvaluator;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessExceptions.InvalidRoleTransitionException;
import io.pallet.orgteam.security.AccessExceptions.LastOwnerException;
import io.pallet.orgteam.security.AccessExceptions.ReauthenticationRequiredException;
import io.pallet.orgteam.security.AccessResolver;
import io.pallet.orgteam.security.RecentAuthentication;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ControllerTest(MemberController.class)
@Import(MemberControllerTest.MethodSecurity.class)
class MemberControllerTest {

    private static final String BASE = "/org-team/orgs/{orgId}/members";
    private static final Instant JOINED = Instant.parse("2026-03-01T10:00:00Z");
    private static final MemberDto OWNER_DTO =
            new MemberDto("owner-1", "owner@example.com", "Owner", Role.OWNER, MembershipStatus.ACTIVE, JOINED);
    private static final MemberDto DEV_DTO =
            new MemberDto("dev-1", "dev@example.com", "Dev", Role.DEVELOPER, MembershipStatus.ACTIVE, JOINED);

    @EnableMethodSecurity
    static class MethodSecurity {}

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private AccessResolver accessResolver;

    @MockitoBean
    private RecentAuthentication recentAuthentication;

    @MockitoBean(name = "access")
    private AccessEvaluator access;

    @BeforeEach
    void allowEveryRole() {
        given(access.atLeast(anyString(), anyString())).willReturn(true);
        given(access.isOwner(anyString())).willReturn(true);
        given(accessResolver.resolve("org-1")).willReturn(new AccessContext("org-1", "owner-1", Role.OWNER, JOINED));
    }

    @Test
    void listWrapsThePageInTheEnvelope() throws Exception {
        given(memberService.list(eq("org-1"), eq(Role.OWNER), any(MemberFilter.class), any(PageQuery.class)))
                .willReturn(new PageResponse<>(List.of(OWNER_DTO, DEV_DTO), 0, 20, 2, 1, true, true));

        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].userId").value("owner-1"))
                .andExpect(jsonPath("$.data.content[0].role").value("OWNER"))
                .andExpect(jsonPath("$.data.content[0].version").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].removedBy").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").value(2));
        verify(access).atLeast("org-1", "VIEWER");
    }

    @Test
    void listBindsFilterAndPagingParameters() throws Exception {
        given(memberService.list(any(), any(), any(), any()))
                .willReturn(new PageResponse<>(List.of(), 1, 5, 0, 0, false, true));

        mvc.perform(get(BASE, "org-1")
                        .with(orgJwt("org-1"))
                        .param("status", "REMOVED")
                        .param("role", "ADMIN")
                        .param("q", "ali")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "displayName,desc"))
                .andExpect(status().isOk());

        verify(memberService)
                .list(
                        eq("org-1"),
                        eq(Role.OWNER),
                        eq(new MemberFilter(MembershipStatus.REMOVED, Role.ADMIN, "ali")),
                        eq(new PageQuery(1, 5, "displayName,desc")));
    }

    @Test
    void anUnsupportedSortIsABadRequest() throws Exception {
        given(memberService.list(any(), any(), any(), any())).willThrow(new InvalidSortException("email"));

        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1")).param("sort", "email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SORT"));
    }

    @Test
    void listingRemovedMembersAsAViewerIsForbidden() throws Exception {
        given(memberService.list(any(), any(), any(), any())).willThrow(new InsufficientRoleException());

        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1")).param("status", "REMOVED"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
    }

    @Test
    void anUnknownStatusOrRoleFilterIsABadRequest() throws Exception {
        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1")).param("status", "GONE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1")).param("role", "SUPERUSER"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(memberService);
    }

    @Test
    void meIsNotShadowedByTheUserIdRoute() throws Exception {
        given(memberService.get("org-1", "owner-1")).willReturn(OWNER_DTO);

        mvc.perform(get(BASE + "/me", "org-1").with(orgJwt("org-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("owner-1"))
                .andExpect(jsonPath("$.data.role").value("OWNER"));
        verify(memberService).get("org-1", "owner-1");
    }

    @Test
    void getByUserIdReturnsTheMemberOrNotFound() throws Exception {
        given(memberService.get("org-1", "dev-1")).willReturn(DEV_DTO);
        given(memberService.get("org-1", "ghost")).willThrow(new MemberNotFoundException());

        mvc.perform(get(BASE + "/{userId}", "org-1", "dev-1").with(orgJwt("org-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("dev@example.com"));
        mvc.perform(get(BASE + "/{userId}", "org-1", "ghost").with(orgJwt("org-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void patchChangesTheRoleAsTheOwner() throws Exception {
        given(memberService.changeRole("org-1", "owner-1", "dev-1", Role.ADMIN))
                .willReturn(
                        new MemberDto("dev-1", "dev@example.com", "Dev", Role.ADMIN, MembershipStatus.ACTIVE, JOINED));

        mvc.perform(patch(BASE + "/{userId}", "org-1", "dev-1")
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));
        verify(access).isOwner("org-1");
    }

    @Test
    void patchBelowOwnerIsForbiddenBeforeTheServiceRuns() throws Exception {
        doThrow(new InsufficientRoleException()).when(access).isOwner("org-1");

        mvc.perform(patch(BASE + "/{userId}", "org-1", "dev-1")
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(memberService);
    }

    @Test
    void patchToOwnerIsAnInvalidRoleTransition() throws Exception {
        given(memberService.changeRole("org-1", "owner-1", "dev-1", Role.OWNER))
                .willThrow(new InvalidRoleTransitionException());

        mvc.perform(patch(BASE + "/{userId}", "org-1", "dev-1")
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ROLE_TRANSITION"));
    }

    @Test
    void patchRejectsUnknownFieldsAndMissingOrUnknownRoles() throws Exception {
        assertPatchBadRequest("{\"role\":\"ADMIN\",\"email\":\"x@example.com\"}");
        assertPatchBadRequest("{\"role\":\"ADMIN\",\"status\":\"REMOVED\"}");
        assertPatchBadRequest("{}");
        assertPatchBadRequest("{\"role\":null}");
        assertPatchBadRequest("{\"role\":\"SUPERUSER\"}");
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mvc.perform(delete(BASE + "/{userId}", "org-1", "dev-1").with(orgJwt("org-1")))
                .andExpect(status().isNoContent());
        verify(memberService).remove("org-1", "owner-1", "dev-1");
    }

    @Test
    void deleteOfTheOwnerIsLastOwner() throws Exception {
        doThrow(new LastOwnerException()).when(memberService).remove("org-1", "owner-1", "owner-1");

        mvc.perform(delete(BASE + "/{userId}", "org-1", "owner-1").with(orgJwt("org-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("LAST_OWNER"));
    }

    @Test
    void transferRequiresRecentAuthenticationBeforeAnyWork() throws Exception {
        doThrow(new ReauthenticationRequiredException())
                .when(recentAuthentication)
                .require(any());

        mvc.perform(post(BASE + "/{userId}/transfer-ownership", "org-1", "dev-1")
                        .with(orgJwt("org-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REAUTHENTICATION_REQUIRED"));
        verifyNoInteractions(memberService);
    }

    @Test
    void transferReturnsTheNewOwner() throws Exception {
        given(memberService.transferOwnership("org-1", "owner-1", "dev-1"))
                .willReturn(
                        new MemberDto("dev-1", "dev@example.com", "Dev", Role.OWNER, MembershipStatus.ACTIVE, JOINED));

        mvc.perform(post(BASE + "/{userId}/transfer-ownership", "org-1", "dev-1")
                        .with(orgJwt("org-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("dev-1"))
                .andExpect(jsonPath("$.data.role").value("OWNER"));
        verify(access).isOwner("org-1");
    }

    private void assertPatchBadRequest(String body) throws Exception {
        mvc.perform(patch(BASE + "/{userId}", "org-1", "dev-1")
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(memberService);
    }
}
