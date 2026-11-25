package com.todollab.board.model;

import com.todollab.auth.model.User;
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
@Table(name = "board_members")
public class BoardMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BoardRole role;

    @Column(name = "is_favorite", nullable = false)
    private boolean favorite = false;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    protected BoardMember() {
    }

    public BoardMember(Board board, User user, BoardRole role) {
        this(board, user, role, false, OffsetDateTime.now(ZoneOffset.UTC));
    }

    public BoardMember(
            Board board,
            User user,
            BoardRole role,
            boolean favorite,
            OffsetDateTime joinedAt
    ) {
        this.board = board;
        this.user = user;
        this.role = role;
        this.favorite = favorite;
        this.joinedAt = joinedAt;
    }

    @PrePersist
    void prePersist() {
        if (this.joinedAt == null) {
            this.joinedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public UUID getId() {
        return id;
    }

    public Board getBoard() {
        return board;
    }

    public User getUser() {
        return user;
    }

    public BoardRole getRole() {
        return role;
    }

    public void setRole(BoardRole role) {
        this.role = role;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public OffsetDateTime getJoinedAt() {
        return joinedAt;
    }
}
