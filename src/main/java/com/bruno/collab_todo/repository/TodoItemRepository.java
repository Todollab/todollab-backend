package com.bruno.collab_todo.repository;

import com.bruno.collab_todo.model.TodoItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TodoItemRepository extends JpaRepository<TodoItem, UUID> {}
