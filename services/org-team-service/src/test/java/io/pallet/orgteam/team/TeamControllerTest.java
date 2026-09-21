package io.pallet.orgteam.team;

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
import io.pallet.orgteam.member.MemberDto;
import io.pallet.orgteam.member.MemberExceptions.InvalidSortException;
import io.pallet.orgteam.member.MemberExceptions.MemberNotFoundException;
import io.pallet.orgteam.member.MembershipStatus;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.security.AccessContext;
import io.pallet.orgteam.security.AccessEvaluator;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessResolver;
import io.pallet.orgteam.team.TeamExceptions.AlreadyInTeamException;
import io.pallet.orgteam.team.TeamExceptions.SlugTakenException;
import io.pallet.orgteam.team.TeamExceptions.TeamNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ControllerTest(TeamController.class)
@Import(TeamControllerTest.MethodSecurity.class)
class TeamControllerTest {

    private static final String BASE = "/org-team/orgs/{orgId}/teams";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final TeamDto TEAM = new TeamDto(TEAM_ID, "Platform", "platform", 2, NOW, NOW);
    private static final MemberDto MEMBER =
            new MemberDto("dev-1", "dev@example.com", "Dev", Role.DEVELOPER, MembershipStatus.ACTIVE, NOW);

    @EnableMethodSecurity
    static class MethodSecurity {}

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TeamService teamService;

    @MockitoBean
    private AccessResolver accessResolver;

    @MockitoBean(name = "access")
    private AccessEvaluator access;

    @BeforeEach
    void allowEveryRole() {
        given(access.atLeast(anyString(), anyString())).willReturn(true);
        given(accessResolver.resolve("org-1")).willReturn(new AccessContext("org-1", "admin-1", Role.ADMIN, NOW));
    }

    @Test
    void createReturnsTheTeamWith201() throws Exception {
        given(teamService.create("org-1", "admin-1", "Platform", "platform")).willReturn(TEAM);

        json(post(BASE, "org-1"), "{\"name\":\"Platform\",\"slug\":\"platform\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(TEAM_ID.toString()))
                .andExpect(jsonPath("$.data.slug").value("platform"))
                .andExpect(jsonPath("$.data.memberCount").value(2))
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.orgId").doesNotExist());
        verify(access).atLeast("org-1", "ADMIN");
    }

    @Test
    void createWithoutASlugPassesNull() throws Exception {
        given(teamService.create(eq("org-1"), eq("admin-1"), eq("Platform"), eq(null)))
                .willReturn(TEAM);

        json(post(BASE, "org-1"), "{\"name\":\"Platform\"}").andExpect(status().isCreated());
    }

    @Test
    void createIsForbiddenForARoleBelowAdminBeforeTheServiceRuns() throws Exception {
        doThrow(new InsufficientRoleException()).when(access).atLeast("org-1", "ADMIN");

        json(post(BASE, "org-1"), "{\"name\":\"Platform\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        verifyNoInteractions(teamService);
    }

    @Test
    void createRejectsInvalidBodies() throws Exception {
        assertBadRequest(post(BASE, "org-1"), "{}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"   \"}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"" + "n".repeat(101) + "\"}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"ok\",\"slug\":\"Not Valid\"}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"ok\",\"slug\":\"-edge\"}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"ok\",\"slug\":\"" + "s".repeat(64) + "\"}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"ok\",\"orgId\":\"org-2\"}");
    }

    @Test
    void aTakenSlugIsAConflict() throws Exception {
        given(teamService.create(any(), any(), any(), any())).willThrow(new SlugTakenException());

        json(post(BASE, "org-1"), "{\"name\":\"Platform\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLUG_TAKEN"));
    }

    @Test
    void listWrapsThePageAndBindsPaging() throws Exception {
        given(teamService.list(eq("org-1"), any(PageQuery.class)))
                .willReturn(new PageResponse<>(List.of(TEAM), 1, 5, 6, 2, false, true));

        mvc.perform(get(BASE, "org-1")
                        .with(orgJwt("org-1"))
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].name").value("Platform"))
                .andExpect(jsonPath("$.data.totalElements").value(6));
        verify(teamService).list("org-1", new PageQuery(1, 5, "createdAt,desc"));
        verify(access).atLeast("org-1", "VIEWER");
    }

    @Test
    void anUnsupportedSortIsABadRequest() throws Exception {
        given(teamService.list(any(), any())).willThrow(new InvalidSortException("slug"));

        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1")).param("sort", "slug"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SORT"));
    }

    @Test
    void getReturnsTheTeamOrNotFound() throws Exception {
        UUID other = UUID.randomUUID();
        given(teamService.get("org-1", TEAM_ID)).willReturn(TEAM);
        given(teamService.get("org-1", other)).willThrow(new TeamNotFoundException());

        mvc.perform(get(BASE + "/{teamId}", "org-1", TEAM_ID).with(orgJwt("org-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Platform"));
        mvc.perform(get(BASE + "/{teamId}", "org-1", other).with(orgJwt("org-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TEAM_NOT_FOUND"));
    }

    @Test
    void aMalformedTeamIdIsABadRequest() throws Exception {
        mvc.perform(get(BASE + "/{teamId}", "org-1", "not-a-uuid").with(orgJwt("org-1")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(teamService);
    }

    @Test
    void patchRenamesTheTeam() throws Exception {
        given(teamService.rename("org-1", "admin-1", TEAM_ID, "Infra"))
                .willReturn(new TeamDto(TEAM_ID, "Infra", "platform", 2, NOW, NOW));

        json(patch(BASE + "/{teamId}", "org-1", TEAM_ID), "{\"name\":\"Infra\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Infra"))
                .andExpect(jsonPath("$.data.slug").value("platform"));
    }

    @Test
    void patchRejectsTheSlugAndAnyOtherUnknownField() throws Exception {
        assertBadRequest(patch(BASE + "/{teamId}", "org-1", TEAM_ID), "{\"name\":\"Infra\",\"slug\":\"infra\"}");
        assertBadRequest(patch(BASE + "/{teamId}", "org-1", TEAM_ID), "{\"slug\":\"infra\"}");
        assertBadRequest(patch(BASE + "/{teamId}", "org-1", TEAM_ID), "{\"name\":\"Infra\",\"orgId\":\"org-2\"}");
        assertBadRequest(patch(BASE + "/{teamId}", "org-1", TEAM_ID), "{}");
        assertBadRequest(patch(BASE + "/{teamId}", "org-1", TEAM_ID), "{\"name\":\"\"}");
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mvc.perform(delete(BASE + "/{teamId}", "org-1", TEAM_ID).with(orgJwt("org-1")))
                .andExpect(status().isNoContent());
        verify(teamService).delete("org-1", "admin-1", TEAM_ID);
        verify(access).atLeast("org-1", "ADMIN");
    }

    @Test
    void listMembersWrapsThePage() throws Exception {
        given(teamService.listMembers(eq("org-1"), eq(TEAM_ID), any(PageQuery.class)))
                .willReturn(new PageResponse<>(List.of(MEMBER), 0, 20, 1, 1, true, true));

        mvc.perform(get(BASE + "/{teamId}/members", "org-1", TEAM_ID).with(orgJwt("org-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].userId").value("dev-1"))
                .andExpect(jsonPath("$.data.content[0].role").value("DEVELOPER"));
        verify(access).atLeast("org-1", "VIEWER");
    }

    @Test
    void addMemberReturns201WithTheMember() throws Exception {
        given(teamService.addMember("org-1", "admin-1", TEAM_ID, "dev-1")).willReturn(MEMBER);

        json(post(BASE + "/{teamId}/members", "org-1", TEAM_ID), "{\"userId\":\"dev-1\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").value("dev-1"));
        verify(access).atLeast("org-1", "ADMIN");
    }

    @Test
    void addMemberConflictsAndNotFoundAreRendered() throws Exception {
        given(teamService.addMember("org-1", "admin-1", TEAM_ID, "dup")).willThrow(new AlreadyInTeamException());
        given(teamService.addMember("org-1", "admin-1", TEAM_ID, "ghost")).willThrow(new MemberNotFoundException());

        json(post(BASE + "/{teamId}/members", "org-1", TEAM_ID), "{\"userId\":\"dup\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALREADY_IN_TEAM"));
        json(post(BASE + "/{teamId}/members", "org-1", TEAM_ID), "{\"userId\":\"ghost\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void addMemberRejectsInvalidBodies() throws Exception {
        String url = BASE + "/{teamId}/members";
        assertBadRequest(post(url, "org-1", TEAM_ID), "{}");
        assertBadRequest(post(url, "org-1", TEAM_ID), "{\"userId\":\"\"}");
        assertBadRequest(post(url, "org-1", TEAM_ID), "{\"userId\":\"dev-1\",\"role\":\"OWNER\"}");
    }

    @Test
    void removeMemberReturnsNoContentOrNotFound() throws Exception {
        doThrow(new MemberNotFoundException()).when(teamService).removeMember("org-1", "admin-1", TEAM_ID, "ghost");

        mvc.perform(delete(BASE + "/{teamId}/members/{userId}", "org-1", TEAM_ID, "dev-1")
                        .with(orgJwt("org-1")))
                .andExpect(status().isNoContent());
        mvc.perform(delete(BASE + "/{teamId}/members/{userId}", "org-1", TEAM_ID, "ghost")
                        .with(orgJwt("org-1")))
                .andExpect(status().isNotFound());
        verify(teamService).removeMember("org-1", "admin-1", TEAM_ID, "dev-1");
    }

    private ResultActions json(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(orgJwt("org-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void assertBadRequest(MockHttpServletRequestBuilder request, String body) throws Exception {
        json(request, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(teamService);
    }
}
