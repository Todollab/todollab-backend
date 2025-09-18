package com.todollab.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.todollab.model.TodoList;
import com.todollab.repository.TodoListRepository;

@Service
public class TodoListService {
    private final TodoListRepository todoListRepository;

    public TodoListService(TodoListRepository todoListRepository) {
        this.todoListRepository = todoListRepository;
    }

    public List<TodoList> findAll() {
        List<TodoList> todoLists = todoListRepository.findAll();
        return todoLists;
    }

    public TodoList findById(UUID todoListId) {
        return todoListRepository.findById(todoListId)
            .orElseThrow(() -> new IllegalArgumentException("Todo list não encontrada"));
    }

    public TodoList createTodoList(TodoList todoList) {
        todoList.setCreated_at(OffsetDateTime.now());
        TodoList list = todoListRepository.save(todoList);
        return list;
    }

    public void delete(UUID todoListId) {
        TodoList todoList = todoListRepository.findById(todoListId)
            .orElseThrow(() -> new IllegalArgumentException("Todo list não encontrada"));

        todoListRepository.delete(todoList);
    }
}