package com.cityapp.notification.controller;

import com.cityapp.common.exception.AppException;
import com.cityapp.common.response.ApiResponse;
import com.cityapp.common.response.PageResponse;
import com.cityapp.notification.dto.NotificationResponse;
import com.cityapp.notification.service.InAppNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * In-app notification endpoints. Controller methods consume the stable
 * authenticated user ID rather than depending on the notification JPA User
 * entity. Current numeric database IDs are adapted at the persistence boundary.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final InAppNotificationService notificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<NotificationResponse>>> getNotifications(
            @AuthenticationPrincipal(expression = "userId") String userId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly) {

        Pageable pageable = PageRequest.of(page, size,
                Sort.by("createdAt").descending());

        return ResponseEntity.ok(ApiResponse.ok(
                notificationService.getNotifications(
                        toDatabaseUserId(userId), unreadOnly, pageable)));
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCount(
            @AuthenticationPrincipal(expression = "userId") String userId) {
        long count = notificationService.getUnreadCount(toDatabaseUserId(userId));
        return ResponseEntity.ok(ApiResponse.ok(Map.of("unreadCount", count)));
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<Void>> markRead(
            @AuthenticationPrincipal(expression = "userId") String userId,
            @PathVariable Long notificationId) {
        notificationService.markAsRead(notificationId, toDatabaseUserId(userId));
        return ResponseEntity.ok(ApiResponse.ok("Notification marked as read"));
    }

    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllRead(
            @AuthenticationPrincipal(expression = "userId") String userId) {
        int count = notificationService.markAllAsRead(toDatabaseUserId(userId));
        return ResponseEntity.ok(ApiResponse.ok(
                Map.of("markedRead", count)));
    }

    private static Long toDatabaseUserId(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException | NullPointerException ex) {
            throw AppException.forbidden("Authenticated user identity is invalid");
        }
    }
}
