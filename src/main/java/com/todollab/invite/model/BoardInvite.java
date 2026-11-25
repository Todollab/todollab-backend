package com.todollab.invite.model;

import com.todollab.auth.model.User;
import com.todollab.board.model.Board;
import com.todollab.board.model.BoardRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "board_invites")
public class BoardInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by_id", nullable = false)
    private User invitedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InviteType type;

    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BoardRole role;

    @Column(nullable = false, unique = true, length = 128)
    private String token;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "uses_count", nullable = false)
    private int usesCount = 0;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "declined_at")
    private OffsetDateTime declinedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected BoardInvite() {
    }

    public BoardInvite(
            Board board,
            User invitedBy,
            InviteType type,
            String email,
            BoardRole role,
            String token,
            Integer maxUses,
            OffsetDateTime expiresAt
    ) {
        this.board = board;
        this.invitedBy = invitedBy;
        this.type = type;
        this.email = email;
        this.role = role;
        this.token = token;
        this.maxUses = maxUses;
        this.usesCount = 0;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public boolean isExpired(OffsetDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean isExhausted() {
        return maxUses != null && usesCount >= maxUses;
    }

    public boolean isActiveAt(OffsetDateTime now) {
        return revokedAt == null && declinedAt == null && !isExpired(now) && !isExhausted();
    }

    public void incrementUsesCount() {
        this.usesCount++;
    }

    public UUID getId() {
        return id;
    }

    public Board getBoard() {
        return board;
    }

    public User getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(User invitedBy) {
        this.invitedBy = invitedBy;
    }

    public InviteType getType() {
        return type;
    }

    public String getEmail() {
        return email;
    }

    public BoardRole getRole() {
        return role;
    }

    public void setRole(BoardRole role) {
        this.role = role;
    }

    public String getToken() {
        return token;
    }

    public Integer getMaxUses() {
        return maxUses;
    }

    public int getUsesCount() {
        return usesCount;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public OffsetDateTime getDeclinedAt() {
        return declinedAt;
    }

    public void setDeclinedAt(OffsetDateTime declinedAt) {
        this.declinedAt = declinedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
