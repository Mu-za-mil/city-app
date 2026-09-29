package com.cityapp.notification.repository;

import com.cityapp.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // Unread notifications (newest first)
    Page<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(
            Long userId, Pageable pageable);

    // All notifications (for history tab)
    Page<Notification> findByUserIdOrderByCreatedAtDesc(
            Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    @Modifying
    @Query("""
        UPDATE Notification n SET n.readAt = CURRENT_TIMESTAMP
        WHERE n.userId = :userId AND n.readAt IS NULL
        """)
    int markAllAsRead(@Param("userId") Long userId);
}
