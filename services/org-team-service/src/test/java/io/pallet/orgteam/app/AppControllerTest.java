package io.pallet.orgteam.app;

import static io.pallet.common.test.security.PalletJwtRequestPostProcessors.orgJwt;
import static org.assertj.core.api.Assertions.assertThat;
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
import io.pallet.orgteam.app.AppExceptions.AppNotFoundException;
import io.pallet.orgteam.app.AppExceptions.InvalidRegionException;
import io.pallet.orgteam.member.MemberExceptions.InvalidSortException;
import io.pallet.orgteam.member.Role;
import io.pallet.orgteam.security.AccessContext;
import io.pallet.orgteam.security.AccessEvaluator;
import io.pallet.orgteam.security.AccessExceptions.InsufficientRoleException;
import io.pallet.orgteam.security.AccessResolver;
import io.pallet.orgteam.team.TeamExceptions.SlugTakenException;
import io.pallet.orgteam.team.TeamExceptions.TeamNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@ControllerTest(AppController.class)
@Import(AppControllerTest.MethodSecurity.class)
class AppControllerTest {

    private static final String BASE = "/org-team/orgs/{orgId}/apps";
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final UUID APP_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final AppDto APP =
            new AppDto(APP_ID, "Web", "web", CloudProvider.AWS, "us-east-1", TEAM_ID, NOW, NOW);
    private static final String VALID_CREATE = "{\"name\":\"Web\",\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\"}";

    @EnableMethodSecurity
    static class MethodSecurity {}

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AppService appService;

    @MockitoBean
    private AccessResolver accessResolver;

    @MockitoBean(name = "access")
    private AccessEvaluator access;

    @BeforeEach
    void allowEveryRole() {
        given(access.atLeast(anyString(), anyString())).willReturn(true);
        given(accessResolver.resolve("org-1")).willReturn(new AccessContext("org-1", "dev-1", Role.DEVELOPER, NOW));
    }

    @Test
    void createReturnsTheAppWith201AndNeverExposesInternals() throws Exception {
        given(appService.create(eq("org-1"), eq("dev-1"), any(CreateAppRequest.class)))
                .willReturn(APP);

        json(post(BASE, "org-1"), VALID_CREATE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(APP_ID.toString()))
                .andExpect(jsonPath("$.data.cloudProvider").value("AWS"))
                .andExpect(jsonPath("$.data.region").value("us-east-1"))
                .andExpect(jsonPath("$.data.teamId").value(TEAM_ID.toString()))
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.orgId").doesNotExist());
        verify(access).atLeast("org-1", "DEVELOPER");
    }

    @Test
    void createBindsEveryField() throws Exception {
        given(appService.create(any(), any(), any())).willReturn(APP);

        json(
                        post(BASE, "org-1"),
                        "{\"name\":\"Web\",\"slug\":\"web\",\"cloudProvider\":\"GCP\",\"region\":\"us-central1\",\"teamId\":\""
                                + TEAM_ID + "\"}")
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateAppRequest> captured = ArgumentCaptor.forClass(CreateAppRequest.class);
        verify(appService).create(eq("org-1"), eq("dev-1"), captured.capture());
        assertThat(captured.getValue())
                .isEqualTo(new CreateAppRequest("Web", "web", CloudProvider.GCP, "us-central1", TEAM_ID));
    }

    @Test
    void aViewerIsForbiddenFromCreatingBeforeTheServiceRuns() throws Exception {
        doThrow(new InsufficientRoleException()).when(access).atLeast("org-1", "DEVELOPER");

        json(post(BASE, "org-1"), VALID_CREATE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        verifyNoInteractions(appService);
    }

    @Test
    void createRejectsInvalidBodies() throws Exception {
        assertBadRequest(post(BASE, "org-1"), "{}");
        assertBadRequest(post(BASE, "org-1"), "{\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\"}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"  \",\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\"}");
        assertBadRequest(
                post(BASE, "org-1"),
                "{\"name\":\"" + "n".repeat(101) + "\",\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\"}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"Web\",\"region\":\"us-east-1\"}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"Web\",\"cloudProvider\":\"AWS\"}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"Web\",\"cloudProvider\":\"AWS\",\"region\":\" \"}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"Web\",\"cloudProvider\":\"AZURE\",\"region\":\"westus\"}");
        assertBadRequest(post(BASE, "org-1"), "{\"name\":\"Web\",\"cloudProvider\":\"aws\",\"region\":\"us-east-1\"}");
        assertBadRequest(
                post(BASE, "org-1"),
                "{\"name\":\"Web\",\"slug\":\"Not Valid\",\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\"}");
        assertBadRequest(
                post(BASE, "org-1"),
                "{\"name\":\"Web\",\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\",\"teamId\":\"nope\"}");
        assertBadRequest(
                post(BASE, "org-1"),
                "{\"name\":\"Web\",\"cloudProvider\":\"AWS\",\"region\":\"us-east-1\",\"orgId\":\"org-2\"}");
    }

    @Test
    void anUnlistedRegionIsInvalidRegion() throws Exception {
        given(appService.create(any(), any(), any())).willThrow(new InvalidRegionException(CloudProvider.AWS));

        json(post(BASE, "org-1"), "{\"name\":\"Web\",\"cloudProvider\":\"AWS\",\"region\":\"nowhere\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REGION"));
    }

    @Test
    void aTakenSlugAndAMissingTeamAreRendered() throws Exception {
        given(appService.create(any(), any(), any()))
                .willThrow(new SlugTakenException())
                .willThrow(new TeamNotFoundException());

        json(post(BASE, "org-1"), VALID_CREATE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SLUG_TAKEN"));
        json(post(BASE, "org-1"), VALID_CREATE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TEAM_NOT_FOUND"));
    }

    @Test
    void listWrapsThePageBindsFiltersAndPaging() throws Exception {
        given(appService.list(eq("org-1"), eq(TEAM_ID), eq(CloudProvider.GCP), any(PageQuery.class)))
                .willReturn(new PageResponse<>(List.of(APP), 1, 5, 6, 2, false, true));

        mvc.perform(get(BASE, "org-1")
                        .with(orgJwt("org-1"))
                        .param("teamId", TEAM_ID.toString())
                        .param("cloudProvider", "GCP")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].slug").value("web"))
                .andExpect(jsonPath("$.data.totalElements").value(6));
        verify(appService).list("org-1", TEAM_ID, CloudProvider.GCP, new PageQuery(1, 5, "name,asc"));
        verify(access).atLeast("org-1", "VIEWER");
    }

    @Test
    void listFiltersAreOptionalAndBadOnesAreRejected() throws Exception {
        given(appService.list(any(), any(), any(), any()))
                .willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true, true));

        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1"))).andExpect(status().isOk());
        verify(appService).list(eq("org-1"), eq(null), eq(null), any(PageQuery.class));

        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1")).param("cloudProvider", "AZURE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1")).param("teamId", "nope"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnsupportedSortIsABadRequest() throws Exception {
        given(appService.list(any(), any(), any(), any())).willThrow(new InvalidSortException("region"));

        mvc.perform(get(BASE, "org-1").with(orgJwt("org-1")).param("sort", "region"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_SORT"));
    }

    @Test
    void getReturnsTheAppOrNotFound() throws Exception {
        UUID other = UUID.randomUUID();
        given(appService.get("org-1", APP_ID)).willReturn(APP);
        given(appService.get("org-1", other)).willThrow(new AppNotFoundException());

        mvc.perform(get(BASE + "/{appId}", "org-1", APP_ID).with(orgJwt("org-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Web"));
        mvc.perform(get(BASE + "/{appId}", "org-1", other).with(orgJwt("org-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("APP_NOT_FOUND"));
        mvc.perform(get(BASE + "/{appId}", "org-1", "not-a-uuid").with(orgJwt("org-1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchUpdatesTheNameAndRequiresDeveloper() throws Exception {
        given(appService.update(eq("org-1"), eq("dev-1"), eq(APP_ID), any(UpdateAppRequest.class)))
                .willReturn(APP);

        json(patch(BASE + "/{appId}", "org-1", APP_ID), "{\"name\":\"Storefront\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("web"));
        verify(access).atLeast("org-1", "DEVELOPER");
        UpdateAppRequest request = capturedUpdate();
        assertThat(request.name()).isEqualTo("Storefront");
        assertThat(request.hasTeamId()).isFalse();
    }

    @Test
    void patchDistinguishesAbsentNullAndPresentTeamId() throws Exception {
        given(appService.update(any(), any(), any(), any())).willReturn(APP);

        json(patch(BASE + "/{appId}", "org-1", APP_ID), "{\"name\":\"x\"}").andExpect(status().isOk());
        json(patch(BASE + "/{appId}", "org-1", APP_ID), "{\"teamId\":null}").andExpect(status().isOk());
        json(patch(BASE + "/{appId}", "org-1", APP_ID), "{\"teamId\":\"" + TEAM_ID + "\"}")
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateAppRequest> captured = ArgumentCaptor.forClass(UpdateAppRequest.class);
        verify(appService, org.mockito.Mockito.times(3)).update(any(), any(), any(), captured.capture());
        List<UpdateAppRequest> requests = captured.getAllValues();
        assertThat(requests.get(0).hasTeamId()).isFalse();
        assertThat(requests.get(1).hasTeamId()).isTrue();
        assertThat(requests.get(1).teamId()).isNull();
        assertThat(requests.get(2).hasTeamId()).isTrue();
        assertThat(requests.get(2).teamId()).isEqualTo(TEAM_ID);
    }

    @Test
    void patchRejectsThePlacementFieldsTheSlugAndAnyOtherUnknownField() throws Exception {
        String url = BASE + "/{appId}";
        assertBadRequest(patch(url, "org-1", APP_ID), "{\"region\":\"eu-west-1\"}");
        assertBadRequest(patch(url, "org-1", APP_ID), "{\"cloudProvider\":\"GCP\"}");
        assertBadRequest(patch(url, "org-1", APP_ID), "{\"slug\":\"other\"}");
        assertBadRequest(patch(url, "org-1", APP_ID), "{\"name\":\"ok\",\"region\":\"eu-west-1\"}");
        assertBadRequest(patch(url, "org-1", APP_ID), "{\"name\":\"ok\",\"orgId\":\"org-2\"}");
        assertBadRequest(patch(url, "org-1", APP_ID), "{\"status\":\"DELETED\"}");
    }

    @Test
    void patchRejectsInvalidValues() throws Exception {
        String url = BASE + "/{appId}";
        assertBadRequest(patch(url, "org-1", APP_ID), "{\"name\":\"\"}");
        assertBadRequest(patch(url, "org-1", APP_ID), "{\"name\":\"   \"}");
        assertBadRequest(patch(url, "org-1", APP_ID), "{\"name\":\"" + "n".repeat(101) + "\"}");
        assertBadRequest(patch(url, "org-1", APP_ID), "{\"teamId\":\"nope\"}");
    }

    @Test
    void deleteRequiresAdminAndReturnsNoContent() throws Exception {
        mvc.perform(delete(BASE + "/{appId}", "org-1", APP_ID).with(orgJwt("org-1")))
                .andExpect(status().isNoContent());
        verify(appService).delete("org-1", "dev-1", APP_ID);
        verify(access).atLeast("org-1", "ADMIN");
    }

    @Test
    void aDeveloperIsForbiddenFromDeletingBeforeTheServiceRuns() throws Exception {
        doThrow(new InsufficientRoleException()).when(access).atLeast("org-1", "ADMIN");

        mvc.perform(delete(BASE + "/{appId}", "org-1", APP_ID).with(orgJwt("org-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_ROLE"));
        verifyNoInteractions(appService);
    }

    @Test
    void deleteOfAnUnknownAppIsNotFound() throws Exception {
        doThrow(new AppNotFoundException()).when(appService).delete("org-1", "dev-1", APP_ID);

        mvc.perform(delete(BASE + "/{appId}", "org-1", APP_ID).with(orgJwt("org-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("APP_NOT_FOUND"));
    }

    private UpdateAppRequest capturedUpdate() {
        ArgumentCaptor<UpdateAppRequest> captured = ArgumentCaptor.forClass(UpdateAppRequest.class);
        verify(appService).update(any(), any(), any(), captured.capture());
        return captured.getValue();
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
        verifyNoInteractions(appService);
    }
}
