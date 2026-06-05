package com.cityapp.auth.repository;

import com.cityapp.auth.entity.RefreshToken;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    List<RefreshToken> findByUserIdAndRevokedFalseOrderByLastUsedAtDesc(Long userId);

    @Modifying
    @Query("""
        UPDATE RefreshToken rt
        SET rt.revoked = true, rt.revokedAt = CURRENT_TIMESTAMP, rt.revokeReason = :reason
        WHERE rt.user.id = :userId AND rt.revoked = false
        """)
    int revokeAllForUser(@Param("userId") Long userId, @Param("reason") String reason);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.expiresAt < :cutoff OR rt.revoked = true")
    int deleteExpiredAndRevoked(@Param("cutoff") Instant cutoff);

    @Query("""
        SELECT COUNT(rt) FROM RefreshToken rt
        WHERE rt.user.id = :userId AND rt.revoked = false AND rt.expiresAt > :now
        """)
    long countActiveSessions(@Param("userId") Long userId, @Param("now") Instant now);
}
