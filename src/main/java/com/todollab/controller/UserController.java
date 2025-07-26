package com.todollab.controller;

import com.todollab.model.User;
import com.todollab.repository.UserRepository;
import com.todollab.service.EmailService;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
    public ResponseEntity<?> registerUser(@RequestBody User user) throws MessagingException {

        if (repository.findByEmail(user.getEmail()).isPresent()) return ResponseEntity.status(400).build();

        Random random = new Random();

        int min = 1000;
        int max = 9999;
        int customRangeNumber = random.nextInt(max - min + 1) + min;

        code = customRangeNumber;
        emailService.sendEmail(user.getEmail(), "Código de cadastro", user.getName(), customRangeNumber);

        String encodedPassword = new BCryptPasswordEncoder().encode(user.getPassword());

        user.setPassword(encodedPassword);

        repository.save(user);

        return ResponseEntity.status(201).body(user);
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

    @GetMapping("/login")
    public ResponseEntity<?> loginUser(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String password = body.get("password");

        Optional<User> user = repository.findByEmail(email);

        if (user.isPresent()) {
            boolean matchPassword = new BCryptPasswordEncoder().matches(password, user.get().getPassword());

            return matchPassword
                    ? ResponseEntity.ok(user.get())
                    : ResponseEntity.status(403).build();
        } else {
            return ResponseEntity.badRequest().body("Credenciais inválidas");
        }
    }
}
