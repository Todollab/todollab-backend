package com.todollab.auth.repository;

import com.todollab.auth.model.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken rt
               SET rt.revokedAt = :now
             WHERE rt.user.id = :userId
               AND rt.revokedAt IS NULL
            """)
    int revokeAllActiveByUserId(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);
}
