package com.todollab.controller;

import com.todollab.dto.TodoItemDTO;
import com.todollab.model.TodoItem;
import com.todollab.model.TodoList;
import com.todollab.repository.TodoItemRepository;
import com.todollab.repository.TodoListRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Optional;

@RequestMapping("/todo-item")
@RestController
public class TodoItemController {

    @Autowired
    private TodoItemRepository todoItemRepository;

    @Autowired
    private TodoListRepository todoListRepository;

    @PostMapping
    public ResponseEntity<?> createTodoItem(@RequestBody TodoItemDTO dto) {
        Optional<TodoList> list = todoListRepository.findById(dto.todoListId);

        if (list.isEmpty()) {
            return ResponseEntity.badRequest().body("Todo list não encontrada");
        }

        TodoItem item = new TodoItem();
        item.setContent(dto.content);
        item.setTodoList(list.get());
        item.setDone(false);
        item.setCreated_at(OffsetDateTime.now());
        item.setUpdated_at(OffsetDateTime.now());

        todoItemRepository.save(item);
        return ResponseEntity.ok(item);
    }
}
