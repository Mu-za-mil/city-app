package com.cityapp.notification.service;

import com.cityapp.common.exception.AppException;
import com.cityapp.common.response.PageResponse;
import com.cityapp.notification.dto.NotificationResponse;
import com.cityapp.notification.entity.Notification;
import com.cityapp.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class
InAppNotificationService {

    private final NotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getNotifications(
            Long userId, boolean unreadOnly, Pageable pageable) {

        Page<Notification> page = unreadOnly
                ? notificationRepository
                .findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, pageable)
                : notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, pageable);

        return PageResponse.from(page.map(n -> NotificationResponse.builder()
                .id(n.getId())
                .title(n.getTitle())
                .body(n.getBody())
                .type(n.getType())
                .referenceId(n.getReferenceId())
                .referenceType(n.getReferenceType())
                .read(n.getReadAt() != null)
                .createdAt(n.getCreatedAt())
                .build()));
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markAsRead(Long notificationId, Long userId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> AppException.notFound(
                        "Notification not found: " + notificationId));

        if (!notification.getUserId().equals(userId)) {
            throw AppException.forbidden("Cannot mark another user's notification as read");
        }

        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notificationRepository.save(notification);
        }
    }

    @Transactional
    public int markAllAsRead(Long userId) {
        return notificationRepository.markAllAsRead(userId);
    }
}
