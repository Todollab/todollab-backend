package com.todollab.service;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import com.todollab.model.TodoList;
import com.todollab.repository.TodoListRepository;

@Service
public class TodoListService {
    private final TodoListRepository todoListRepository;

    public TodoListService(TodoListRepository todoListRepository) {
        this.todoListRepository = todoListRepository;
    }

    public TodoList createTodoList(TodoList todoList) {
        todoList.setCreated_at(OffsetDateTime.now());
        TodoList list = todoListRepository.save(todoList);
        return list;
    }
}