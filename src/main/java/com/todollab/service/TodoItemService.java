package com.todollab.service;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import com.todollab.dto.TodoItemDTO;
import com.todollab.model.TodoItem;
import com.todollab.model.TodoList;
import com.todollab.repository.TodoItemRepository;
import com.todollab.repository.TodoListRepository;

@Service
public class TodoItemService {
    private final TodoItemRepository todoItemRepository;
    private final TodoListRepository todoListRepository;

    public TodoItemService(TodoItemRepository todoItemRepository, TodoListRepository todoListRepository) {
        this.todoItemRepository = todoItemRepository;
        this.todoListRepository = todoListRepository;
    }

    public TodoItem createTodoItem(TodoItemDTO dto) {
        TodoList todoList = todoListRepository.findById(dto.todoListId)
            .orElseThrow(() -> new IllegalArgumentException("Todo list não encontrada"));

        TodoItem item = new TodoItem();
        item.setContent(dto.content);
        item.setTodoList(todoList);
        item.setDone(false);
        item.setCreated_at(OffsetDateTime.now());
        item.setUpdated_at(OffsetDateTime.now());

        return todoItemRepository.save(item);
    }
}