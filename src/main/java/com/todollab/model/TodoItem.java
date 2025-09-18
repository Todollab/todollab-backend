package com.todollab.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonBackReference;

@Entity
@Data
@Table(name = "todo_items")
public class TodoItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "todo_list_id", nullable = false)
    @JsonBackReference
    private TodoList todoList;

    private String content;

    private Boolean done;

    private OffsetDateTime created_at;

    private OffsetDateTime updated_at;
}
