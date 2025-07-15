package com.bruno.collab_todo.controller;

import com.bruno.collab_todo.model.User;
import com.bruno.collab_todo.repository.UserRepository;
import com.bruno.collab_todo.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RequestMapping("/user")
@RestController
public class UserController {

    @Autowired
    private UserRepository repository;

    @Autowired
    private EmailService emailService;

    int code = 0;

    @GetMapping
    public List<User> getAllUsers() {
        return repository.findAll();
    }

    @PostMapping
    public void registerUser(@RequestBody User user) {
        Random random = new Random();

        int min = 1000;
        int max = 9999;
        int customRangeNumber = random.nextInt(max - min + 1) + min;

        code = customRangeNumber;
        emailService.sendEmail(user.getEmail(), "Código de cadastro", customRangeNumber);

        repository.save(user);
    }

    @PostMapping("/activate")
    public ResponseEntity<String> activateUser(@RequestBody Map<String, Object> body) {
        String userId = (String) body.get("userId");
        int codeNumber = (int) body.get("code");

        Optional<User> user = repository.findById(UUID.fromString(userId));

        if (!user.get().isActive()) {

            if (code == codeNumber) {
                user.get().setActive(true);
                repository.save(user.get());

                return ResponseEntity.ok("Usuário validado com sucesso!");
            } else {
                return ResponseEntity.ok("Códigos de validação não coincidem");
            }
        } else {
            return ResponseEntity.ok("Usuário já ativado!");
        }
    }
}
