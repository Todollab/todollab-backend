package com.todollab.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.todollab.model.TodoList;
import com.todollab.service.TodoListService;


@RequestMapping("/todo-list")
@RestController
public class TodoListController {

    private final TodoListService todoListService;

    public TodoListController(TodoListService todoListService) {
        this.todoListService = todoListService;
    }

    @GetMapping
    public ResponseEntity<List<TodoList>> findAll() {
        List<TodoList> todoLists = todoListService.findAll();
        return ResponseEntity.ok(todoLists);
    }

    @GetMapping("/{id}/items")
    public ResponseEntity<?> getTodoItemsByList(@PathVariable("id") UUID listId) {
        try {
            TodoList todoList = todoListService.findById(listId);
            return ResponseEntity.ok(todoList.getItems());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
    

    @PostMapping
    public ResponseEntity<?> createTodoList(@RequestBody TodoList todoList) {
        try {
            TodoList list = todoListService.createTodoList(todoList);
            URI location = URI.create("/todo-list/" + list.getId());
            return ResponseEntity.created(location).body(list);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }  
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTodoList(@PathVariable("id") UUID todoListId) {
        todoListService.delete(todoListId);

        return ResponseEntity.noContent().build();
    }
}