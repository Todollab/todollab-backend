package com.bruno.collab_todo.controller;

import com.bruno.collab_todo.model.TodoList;
import com.bruno.collab_todo.repository.TodoListRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RequestMapping("/todo-list")
@RestController
public class TodoListController {

    @Autowired
    private TodoListRepository repository;

    @PostMapping
    public ResponseEntity<TodoList> createTodoList(@RequestBody TodoList todoList) {
        todoList.setCreated_at(OffsetDateTime.now());
        return ResponseEntity.ok(repository.save(todoList));
    }
}
