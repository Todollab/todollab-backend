package com.todollab.auth.repository;

import com.todollab.auth.model.VerificationCode;
import com.todollab.auth.model.VerificationCodeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {

    Optional<VerificationCode> findTopByUserIdAndTypeOrderByCreatedAtDesc(
            UUID userId,
            VerificationCodeType type
    );

    List<VerificationCode> findAllByUserIdAndTypeAndUsedAtIsNullOrderByCreatedAtDesc(
            UUID userId,
            VerificationCodeType type
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE VerificationCode vc
               SET vc.usedAt = :now
             WHERE vc.user.id = :userId
               AND vc.type = :type
               AND vc.usedAt IS NULL
            """)
    int invalidateUnusedCodes(
            @Param("userId") UUID userId,
            @Param("type") VerificationCodeType type,
            @Param("now") OffsetDateTime now
    );
}
