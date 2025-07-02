package com.bruno.collab_todo.controller;

import com.bruno.collab_todo.model.User;
import com.bruno.collab_todo.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping("/user")
@RestController
public class UserController {

    @Autowired
    private UserRepository repository;

    @GetMapping
    public List<User> getAllUsers() {
        return repository.findAll();
    }

    @PostMapping
    public void registerUser(@RequestBody User user) {
        repository.save(user);
    }
}
