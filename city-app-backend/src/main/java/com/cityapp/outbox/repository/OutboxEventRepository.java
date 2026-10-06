package com.cityapp.outbox.repository;

import com.cityapp.outbox.entity.OutboxEvent;
import com.cityapp.outbox.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
        SELECT *
          FROM outbox_events
         WHERE status = 'PENDING'
           AND available_at <= :now
         ORDER BY created_at, id
         LIMIT :batchSize
         FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> lockPendingEvents(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    @Modifying
    @Query("""
        update OutboxEvent e
           set e.status = :pending,
               e.lockedAt = null
         where e.status = :processing
           and e.lockedAt < :cutoff
        """)
    int recoverStaleEvents(@Param("processing") OutboxStatus processing,
                           @Param("pending") OutboxStatus pending,
                           @Param("cutoff") Instant cutoff);
}
