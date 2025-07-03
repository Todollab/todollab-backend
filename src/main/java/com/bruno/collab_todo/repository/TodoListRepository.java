package com.bruno.collab_todo.repository;

import com.bruno.collab_todo.model.TodoList;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TodoListRepository extends JpaRepository<TodoList, UUID> {
}
