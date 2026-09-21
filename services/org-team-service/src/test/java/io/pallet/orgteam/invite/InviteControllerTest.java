package io.pallet.orgteam.invite;

import static io.pallet.common.test.security.PalletJwtRequestPostProcessors.orgJwt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.error.TooManyRequestsException;
import io.pallet.common.test.annotations.ControllerTest;
import io.pallet.orgteam.invite.InviteExceptions.AlreadyAMemberException;
import io.pallet.orgteam.invite.InviteExceptions.InviteAlreadyPendingException;
import io.pallet.orgteam.invite.InviteExceptions.InviteNotFoundException;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.security.AccessContext;
import io.pallet.orgteam.security.AccessEvaluator;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessResolver;
import java.time.Duration;
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

@ControllerTest(InviteController.class)
@Import(InviteControllerTest.MethodSecurity.class)
class InviteControllerTest {

    private static final String BASE = "/org-team/orgs/{orgId}/invites";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final UUID INVITE_ID = UUID.fromString("0b6f7a52-0000-4000-8000-000000000001");
    private static final InviteDto DTO = new InviteDto(
            INVITE_ID,
            "jane@example.com",
            Role.DEVELOPER,
            InviteStatus.PENDING,
            "owner-1",
            1,
            NOW.plusSeconds(3600),
            NOW);

    @EnableMethodSecurity
    static class MethodSecurity {}

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private InviteService inviteService;

    @MockitoBean
    private AccessResolver accessResolver;

    @MockitoBean(name = "access")
    private AccessEvaluator access;

    @BeforeEach
    void allowEveryRole() {
        given(access.atLeast(anyString(), anyString())).willReturn(true);
        given(accessResolver.resolve("org-1")).willReturn(new AccessContext("org-1", "owner-1", Role.OWNER, NOW));
    }

    @Test
    void createReturns201WithTheInviteAndNeitherTokenNorAcceptUrl() throws Exception {
        given(inviteService.create("org-1", "owner-1", "jane@example.com", Role.DEVELOPER))
                .willReturn(DTO);

        mvc.perform(post(BASE, "org-1")
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"jane@example.com\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(INVITE_ID.toString()))
                .andExpect(jsonPath("$.data.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.sendCount").value(1))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.acceptUrl").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("eyJ"))));
        verify(access).atLeast("org-1", "ADMIN");
    }

    @Test
    void createRejectsInvalidBodiesWithoutCallingTheService() throws Exception {
        for (String body : List.of(
                "{\"role\":\"VIEWER\"}",
                "{\"email\":\"\",\"role\":\"VIEWER\"}",
                "{\"email\":\"not-an-email\",\"role\":\"VIEWER\"}",
                "{\"email\":\"" + "a".repeat(250) + "@example.com\",\"role\":\"VIEWER\"}",
                "{\"email\":\"jane@example.com\"}",
                "{\"email\":\"jane@example.com\",\"role\":\"SUPERUSER\"}",
                "{\"email\":\"jane@example.com\",\"role\":\"VIEWER\",\"status\":\"ACCEPTED\"}",
                "not json")) {
            mvc.perform(post(BASE, "org-1")
                            .with(orgJwt("org-1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(inviteService);
    }

    @Test
    void createConflictsAreRenderedByTheSharedHandler() throws Exception {
        given(inviteService.create(any(), any(), any(), any())).willThrow(new InviteAlreadyPendingException());

        mvc.perform(post(BASE, "org-1")
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"jane@example.com\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVITE_ALREADY_PENDING"));

        willThrow(new AlreadyAMemberException()).given(inviteService).create(any(), any(), any(), any());

        mvc.perform(post(BASE, "org-1")
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"jane@example.com\",\"role\":\"VIEWER\"}"))
                .andExpect(jsonPath("$.error").value("ALREADY_A_MEMBER"));
    }

    @Test
    void anInsufficientRoleIsForbiddenBeforeTheServiceRuns() throws Exception {
        given(access.atLeast("org-1", "ADMIN")).willThrow(new InsufficientRoleException());

        mvc.perform(post(BASE, "org-1")
                        .with(orgJwt("org-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"jane@example.com\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1"))).andExpect(status().isForbidden());
        verifyNoInteractions(inviteService);
    }

    @Test
    void listBindsTheStatusFilterAndPaging() throws Exception {
        given(inviteService.list(any(), any(), any()))
                .willReturn(new PageResponse<>(List.of(DTO), 1, 5, 6, 2, false, false));

        mvc.perform(get(BASE, "org-1")
                        .with(orgJwt("org-1"))
                        .param("status", "EXPIRED")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "expiresAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value(INVITE_ID.toString()))
                .andExpect(jsonPath("$.data.content[0].token").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").value(6));

        verify(inviteService).list(eq("org-1"), eq(InviteStatus.EXPIRED), eq(new PageQuery(1, 5, "expiresAt,asc")));
    }

    @Test
    void anUnknownStatusFilterIsABadRequest() throws Exception {
        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1")).param("status", "GONE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resendReturnsTheInviteAndSurfacesTheCooldown() throws Exception {
        given(inviteService.resend("org-1", "owner-1", INVITE_ID)).willReturn(DTO);

        mvc.perform(post(BASE + "/{inviteId}/resend", "org-1", INVITE_ID).with(orgJwt("org-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(INVITE_ID.toString()))
                .andExpect(jsonPath("$.data.acceptUrl").doesNotExist());

        willThrow(new TooManyRequestsException("Sent recently", Duration.ofSeconds(240)))
                .given(inviteService)
                .resend(any(), any(), any());

        mvc.perform(post(BASE + "/{inviteId}/resend", "org-1", INVITE_ID).with(orgJwt("org-1")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aMalformedInviteIdIsABadRequest() throws Exception {
        mvc.perform(post(BASE + "/{inviteId}/resend", "org-1", "not-a-uuid").with(orgJwt("org-1")))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(BASE + "/{inviteId}", "org-1", "not-a-uuid").with(orgJwt("org-1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revokeReturns204AndAnUnknownInviteIs404() throws Exception {
        mvc.perform(delete(BASE + "/{inviteId}", "org-1", INVITE_ID).with(orgJwt("org-1")))
                .andExpect(status().isNoContent());
        verify(inviteService).revoke("org-1", "owner-1", INVITE_ID);

        willThrow(new InviteNotFoundException()).given(inviteService).revoke(any(), any(), any());

        mvc.perform(delete(BASE + "/{inviteId}", "org-1", INVITE_ID).with(orgJwt("org-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("INVITE_NOT_FOUND"));
    }
}
