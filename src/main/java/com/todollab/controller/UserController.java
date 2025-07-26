package com.todollab.controller;

import com.todollab.dto.LoginRequestDTO;
import com.todollab.dto.RegisterDTO;
import com.todollab.model.User;
import com.todollab.repository.UserRepository;
import com.todollab.service.EmailService;
import com.todollab.service.TokenService;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

    @Autowired
    private TokenService tokenService;

    @Autowired
    private AuthenticationManager authenticationManager;

    int code = 0;

    @GetMapping
    public List<User> getAllUsers() {
        return repository.findAll();
    }

    @PostMapping
    public ResponseEntity<?> registerUser(@RequestBody RegisterDTO data) throws MessagingException {

        if (data.email() == null || this.repository.findByEmail(data.email()).isPresent()) {
            return ResponseEntity.status(400).build();
        }

        Random random = new Random();

        int min = 1000;
        int max = 9999;
        int customRangeNumber = random.nextInt(max - min + 1) + min;

        code = customRangeNumber;
        emailService.sendEmail(data.email(), "Código de cadastro", data.name(), customRangeNumber);

        String encodedPassword = new BCryptPasswordEncoder().encode(data.password());

        User newUser = new User(data.name(), data.email(), encodedPassword);

        repository.save(newUser);

        return ResponseEntity.status(201).body(newUser);
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

    @PostMapping("/login")
    public ResponseEntity<?> loginUser(@RequestBody LoginRequestDTO data) {
        var usernamePassword = new UsernamePasswordAuthenticationToken(
                data.email(),
                data.password()
        );
        var auth = this.authenticationManager.authenticate(usernamePassword);

        var user = (User) auth.getPrincipal();
        var token = tokenService.generateToken(user);

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("user", user);

        return ResponseEntity.ok(response);
    }
}
