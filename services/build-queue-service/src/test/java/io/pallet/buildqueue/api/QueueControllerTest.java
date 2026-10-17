package io.pallet.buildqueue.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.pallet.buildqueue.api.dto.BuildDto;
import io.pallet.buildqueue.api.dto.QueueEntryDto;
import io.pallet.buildqueue.api.dto.QueueUsageDto;
import io.pallet.buildqueue.api.dto.QueueViewDto;
import io.pallet.buildqueue.api.dto.WaitingOn;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.cancel.CancellationService;
import io.pallet.buildqueue.security.AccessEvaluator;
import io.pallet.buildqueue.security.AccessExceptions.AppNotFoundException;
import io.pallet.buildqueue.security.AccessExceptions.BuildNotFoundException;
import io.pallet.buildqueue.security.AccessExceptions.OrgNotFoundException;
import io.pallet.buildqueue.security.AccessResolver;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.test.annotations.ControllerTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ControllerTest({QueueController.class, BuildController.class})
@Import(QueueControllerTest.MethodSecurity.class)
class QueueControllerTest {

    private static final String ORG = "org-1";
    private static final String APP = "app-1";
    private static final String QUEUE = "/build-queue/orgs/{orgId}/queue";
    private static final String HISTORY = "/build-queue/orgs/{orgId}/apps/{appId}/builds";
    private static final String BUILD = "/build-queue/orgs/{orgId}/builds/{buildId}";
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final UUID RUNNING_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID QUEUED_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final BuildDto RUNNING = build(RUNNING_ID, BuildStatus.RUNNING);
    private static final BuildDto QUEUED = build(QUEUED_ID, BuildStatus.QUEUED);
    private static final PageResponse<BuildDto> NO_BUILDS = new PageResponse<>(List.of(), 0, 20, 0, 0, true, true);

    @EnableMethodSecurity
    static class MethodSecurity {}

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QueueService queueService;

    @MockitoBean
    private CancellationService cancellations;

    @MockitoBean
    private AccessResolver accessResolver;

    @MockitoBean(name = "access")
    private AccessEvaluator access;

    @BeforeEach
    void allowEveryRole() {
        given(access.atLeast(anyString(), anyString())).willReturn(true);
    }

    @Test
    void theQueueViewWrapsUsageAndAPageOfEntries() throws Exception {
        QueueViewDto view = new QueueViewDto(
                new QueueUsageDto(1, 1, 1, 50, "default", false),
                new PageResponse<>(
                        List.of(
                                QueueEntryDto.inFlight(RUNNING),
                                QueueEntryDto.waiting(QUEUED, 1, WaitingOn.ORG_CONCURRENCY)),
                        0,
                        20,
                        2,
                        1,
                        true,
                        true));
        given(queueService.queue(ORG, PageRequest.of(0, PageQuery.DEFAULT_SIZE)))
                .willReturn(view);

        mvc.perform(get(QUEUE, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.usage.inFlight").value(1))
                .andExpect(jsonPath("$.data.usage.maxConcurrent").value(1))
                .andExpect(jsonPath("$.data.usage.queued").value(1))
                .andExpect(jsonPath("$.data.usage.maxQueued").value(50))
                .andExpect(jsonPath("$.data.usage.profile").value("default"))
                .andExpect(jsonPath("$.data.usage.dispatchPaused").value(false))
                .andExpect(jsonPath("$.data.builds.totalElements").value(2))
                .andExpect(jsonPath("$.data.builds.content[0].buildId").value(RUNNING_ID.toString()))
                .andExpect(jsonPath("$.data.builds.content[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data.builds.content[0].position").isEmpty())
                .andExpect(jsonPath("$.data.builds.content[0].waitingOn").isEmpty())
                .andExpect(jsonPath("$.data.builds.content[0].build").doesNotExist())
                .andExpect(jsonPath("$.data.builds.content[1].buildId").value(QUEUED_ID.toString()))
                .andExpect(jsonPath("$.data.builds.content[1].position").value(1))
                .andExpect(jsonPath("$.data.builds.content[1].waitingOn").value("ORG_CONCURRENCY"));
        verify(access).atLeast(ORG, "VIEWER");
    }

    @Test
    void pagingIsClampedAndASortIsIgnored() throws Exception {
        given(queueService.queue(any(), any())).willReturn(emptyView());
        given(queueService.history(any(), any(), any(), any())).willReturn(NO_BUILDS);

        mvc.perform(get(QUEUE, ORG).param("page", "-3").param("size", "500").param("sort", "branch,desc"))
                .andExpect(status().isOk());
        verify(queueService).queue(ORG, PageRequest.of(0, PageQuery.MAX_SIZE));

        mvc.perform(get(HISTORY, ORG, APP).param("page", "2").param("size", "0").param("sort", "nope"))
                .andExpect(status().isOk());
        verify(queueService).history(ORG, APP, Set.of(), PageRequest.of(2, PageQuery.DEFAULT_SIZE));
    }

    @Test
    void historyBindsEveryRequestedStatus() throws Exception {
        given(queueService.history(any(), any(), any(), any()))
                .willReturn(new PageResponse<>(List.of(QUEUED), 0, 20, 1, 1, true, true));

        mvc.perform(get(HISTORY, ORG, APP).param("status", "SUPERSEDED", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].buildId").value(QUEUED_ID.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        verify(queueService)
                .history(
                        ORG,
                        APP,
                        Set.of(BuildStatus.SUPERSEDED, BuildStatus.FAILED),
                        PageRequest.of(0, PageQuery.DEFAULT_SIZE));

        mvc.perform(get(HISTORY, ORG, APP).param("status", "QUEUED,RUNNING")).andExpect(status().isOk());
        verify(queueService)
                .history(
                        ORG,
                        APP,
                        Set.of(BuildStatus.QUEUED, BuildStatus.RUNNING),
                        PageRequest.of(0, PageQuery.DEFAULT_SIZE));
        verify(access, times(2)).atLeast(ORG, "VIEWER");
    }

    @Test
    void anUnknownStatusIsABadRequest() throws Exception {
        mvc.perform(get(HISTORY, ORG, APP).param("status", "SUCCEEDED", "DONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER_TYPE"));
        mvc.perform(get(HISTORY, ORG, APP).param("status", "queued")).andExpect(status().isBadRequest());
        verifyNoInteractions(queueService);
    }

    @Test
    void historyOfAnotherOrgsAppIsNotFound() throws Exception {
        given(queueService.history(any(), any(), any(), any())).willThrow(new AppNotFoundException());

        mvc.perform(get(HISTORY, ORG, "app-elsewhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("APP_NOT_FOUND"));
    }

    @Test
    void aSingleBuildIsReturnedOrNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        given(queueService.build(ORG, RUNNING_ID)).willReturn(RUNNING);
        given(queueService.build(ORG, unknown)).willThrow(new BuildNotFoundException());

        mvc.perform(get(BUILD, ORG, RUNNING_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.buildId").value(RUNNING_ID.toString()))
                .andExpect(jsonPath("$.data.attempt").value(1))
                .andExpect(jsonPath("$.data.installationId").doesNotExist())
                .andExpect(jsonPath("$.data.orgId").doesNotExist());
        verify(access).atLeast(ORG, "VIEWER");

        mvc.perform(get(BUILD, ORG, unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BUILD_NOT_FOUND"));
        mvc.perform(get(BUILD, ORG, "not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void theGateRunsBeforeAnyRead() throws Exception {
        doThrow(new OrgNotFoundException()).when(access).atLeast(ORG, "VIEWER");

        mvc.perform(get(QUEUE, ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ORG_NOT_FOUND"));
        mvc.perform(get(HISTORY, ORG, APP)).andExpect(status().isNotFound());
        mvc.perform(get(BUILD, ORG, RUNNING_ID)).andExpect(status().isNotFound());
        verifyNoInteractions(queueService);
    }

    private static QueueViewDto emptyView() {
        return new QueueViewDto(
                new QueueUsageDto(0, 1, 0, 50, "default", false),
                new PageResponse<>(List.of(), 0, 20, 0, 0, true, true));
    }

    private static BuildDto build(UUID buildId, BuildStatus status) {
        boolean inFlight = status.isInFlight();
        return new BuildDto(
                buildId,
                APP,
                "main",
                "0123456789abcdef0123456789abcdef01234567",
                "WEBHOOK",
                status,
                null,
                null,
                inFlight ? 1 : 0,
                NOW,
                inFlight ? NOW : null,
                status == BuildStatus.RUNNING ? NOW : null,
                null,
                "Fix the thing",
                "octocat");
    }
}
