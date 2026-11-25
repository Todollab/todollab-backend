package com.todollab.task.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "task_subtasks")
public class TaskSubtask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private boolean done = false;

    @Column(nullable = false)
    private double position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected TaskSubtask() {
    }

    public TaskSubtask(Task task, String title, double position) {
        this.task = task;
        this.title = title;
        this.done = false;
        this.position = position;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public UUID getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public double getPosition() {
        return position;
    }

    public void setPosition(double position) {
        this.position = position;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
