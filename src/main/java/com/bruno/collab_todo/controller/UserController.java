package com.bruno.collab_todo.controller;

import com.bruno.collab_todo.model.User;
import com.bruno.collab_todo.repository.UserRepository;
import com.bruno.collab_todo.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping("/user")
@RestController
public class UserController {

    @Autowired
    private UserRepository repository;

    @Autowired
    private EmailService emailService;

    @GetMapping
    public List<User> getAllUsers() {
        return repository.findAll();
    }

    @PostMapping
    public void registerUser(@RequestBody User user) {
        emailService.sendEmail(user.getEmail(), "Código de cadastro", "07612");

        repository.save(user);
    }
}
