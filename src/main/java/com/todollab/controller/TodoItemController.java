package com.todollab.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.todollab.dto.TodoItemDTO;
import com.todollab.model.TodoItem;
import com.todollab.service.TodoItemService;


@RequestMapping("/todo-item")
@RestController
public class TodoItemController {

    private final TodoItemService todoItemService;

    public TodoItemController(TodoItemService todoItemService) {
        this.todoItemService = todoItemService;
    }

    @PostMapping
    public ResponseEntity<?> createTodoItem(@RequestBody TodoItemDTO dto) {
        try {
            TodoItem item = todoItemService.createTodoItem(dto);
            URI location = URI.create("/todo-item/" + item.getId());
            return ResponseEntity
                .created(location)
                .body(item);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTodoItem(@PathVariable("id") UUID todoItemId) {
        todoItemService.deleteTodoItem(todoItemId);

        return ResponseEntity.noContent().build();
    }
}