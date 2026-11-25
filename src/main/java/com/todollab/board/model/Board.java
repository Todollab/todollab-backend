package com.todollab.board.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "boards")
public class Board {

    public static final String DEFAULT_COLOR = "#0C2439";
    public static final String DEFAULT_ICON = "kanban";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 7)
    private String color = DEFAULT_COLOR;

    @Column(nullable = false, length = 40)
    private String icon = DEFAULT_ICON;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_view", nullable = false, length = 16)
    private BoardView defaultView = BoardView.LIST;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Board() {
    }

    public Board(
            String name,
            String description,
            String color,
            String icon,
            BoardView defaultView,
            UUID ownerId
    ) {
        this.name = name;
        this.description = description;
        this.color = color != null && !color.isBlank() ? color : DEFAULT_COLOR;
        this.icon = icon != null && !icon.isBlank() ? icon : DEFAULT_ICON;
        this.defaultView = defaultView != null ? defaultView : BoardView.LIST;
        this.ownerId = ownerId;
    }

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.color == null || this.color.isBlank()) {
            this.color = DEFAULT_COLOR;
        }
        if (this.icon == null || this.icon.isBlank()) {
            this.icon = DEFAULT_ICON;
        }
        if (this.defaultView == null) {
            this.defaultView = BoardView.LIST;
        }
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public BoardView getDefaultView() {
        return defaultView;
    }

    public void setDefaultView(BoardView defaultView) {
        this.defaultView = defaultView;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
