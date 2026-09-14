package io.pallet.notification.api;

import io.pallet.common.api.ApiResponse;
import io.pallet.common.api.PageQuery;
import io.pallet.common.api.PageResponse;
import io.pallet.common.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notification/notifications")
@Tag(name = "Notifications")
class NotificationController {

    private final NotificationQueryService queryService;

    NotificationController(NotificationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "List the caller's in-app notifications")
    ApiResponse<PageResponse<NotificationDto>> list(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Filter to only unread notifications, or ALL") @RequestParam(defaultValue = "ALL")
                    ReadStatus status,
            @ModelAttribute PageQuery pageQuery) {
        PageResponse<NotificationDto> page =
                queryService.list(jwt.getSubject(), pageQuery, status == ReadStatus.UNREAD);
        return ApiResponse.ok("Notifications retrieved", page);
    }

    @GetMapping("/{deliveryId}")
    @Operation(summary = "Get one of the caller's in-app notifications by delivery id")
    ApiResponse<NotificationDto> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deliveryId) {
        NotificationDto notification = queryService
                .get(jwt.getSubject(), deliveryId)
                .orElseThrow(() -> new NotFoundException("Notification", deliveryId));
        return ApiResponse.ok("Notification retrieved", notification);
    }

    @PatchMapping("/{deliveryId}/read")
    @Operation(summary = "Mark one notification as read")
    ApiResponse<Void> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deliveryId) {
        queryService
                .get(jwt.getSubject(), deliveryId)
                .orElseThrow(() -> new NotFoundException("Notification", deliveryId));
        queryService.markRead(jwt.getSubject(), deliveryId);
        return ApiResponse.ok("Notification marked as read");
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all of the caller's notifications as read")
    ApiResponse<Void> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        queryService.markAllRead(jwt.getSubject());
        return ApiResponse.ok("All notifications marked as read");
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Count the caller's unread notifications")
    ApiResponse<Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok("Unread count retrieved", queryService.unreadCount(jwt.getSubject()));
    }

    enum ReadStatus {
        ALL,
        UNREAD
    }
}
