package com.todollab.invite.repository;

import com.todollab.invite.model.BoardInvite;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoardInviteRepository extends JpaRepository<BoardInvite, UUID> {

    Optional<BoardInvite> findByToken(String token);

    @EntityGraph(attributePaths = {"board", "invitedBy"})
    Optional<BoardInvite> findWithDetailsByToken(String token);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM BoardInvite i WHERE i.token = :token")
    Optional<BoardInvite> findByTokenForUpdate(@Param("token") String token);

    Optional<BoardInvite> findByIdAndBoardId(UUID id, UUID boardId);

    @EntityGraph(attributePaths = {"board", "invitedBy"})
    @Query("""
            SELECT i FROM BoardInvite i
            WHERE i.board.id = :boardId
              AND i.revokedAt IS NULL
              AND i.declinedAt IS NULL
              AND (i.expiresAt IS NULL OR i.expiresAt > :now)
              AND (i.maxUses IS NULL OR i.usesCount < i.maxUses)
            ORDER BY i.createdAt DESC
            """)
    List<BoardInvite> findActiveByBoardId(@Param("boardId") UUID boardId, @Param("now") OffsetDateTime now);

    @EntityGraph(attributePaths = {"board", "invitedBy"})
    @Query("""
            SELECT i FROM BoardInvite i
            WHERE i.board.id = :boardId
              AND i.type = com.todollab.invite.model.InviteType.EMAIL
              AND i.email = :email
              AND i.revokedAt IS NULL
              AND i.declinedAt IS NULL
              AND (i.expiresAt IS NULL OR i.expiresAt > :now)
              AND (i.maxUses IS NULL OR i.usesCount < i.maxUses)
            ORDER BY i.createdAt DESC
            """)
    List<BoardInvite> findPendingEmailInvitesForBoardAndEmail(
            @Param("boardId") UUID boardId,
            @Param("email") String email,
            @Param("now") OffsetDateTime now
    );

    @EntityGraph(attributePaths = {"board", "invitedBy"})
    @Query("""
            SELECT i FROM BoardInvite i
            WHERE i.type = com.todollab.invite.model.InviteType.EMAIL
              AND i.email = :email
              AND i.revokedAt IS NULL
              AND i.declinedAt IS NULL
              AND (i.expiresAt IS NULL OR i.expiresAt > :now)
              AND (i.maxUses IS NULL OR i.usesCount < i.maxUses)
              AND NOT EXISTS (
                  SELECT 1 FROM BoardMember bm
                  WHERE bm.board.id = i.board.id
                    AND bm.user.id = :userId
              )
            ORDER BY i.createdAt DESC
            """)
    List<BoardInvite> findPendingEmailInvitesForUser(
            @Param("email") String email,
            @Param("userId") UUID userId,
            @Param("now") OffsetDateTime now
    );
}
