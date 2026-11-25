package com.todollab.auth.controller;

import com.todollab.auth.dto.AuthDtos.AuthResponse;
import com.todollab.auth.dto.AuthDtos.ForgotPasswordRequest;
import com.todollab.auth.dto.AuthDtos.LoginRequest;
import com.todollab.auth.dto.AuthDtos.RegisterRequest;
import com.todollab.auth.dto.AuthDtos.RegisterResponse;
import com.todollab.auth.dto.AuthDtos.ResendVerificationRequest;
import com.todollab.auth.dto.AuthDtos.ResetPasswordRequest;
import com.todollab.auth.dto.AuthDtos.VerifyEmailRequest;
import com.todollab.auth.service.AuthService;
import com.todollab.auth.service.AuthService.AuthResult;
import com.todollab.auth.service.AuthService.RegisterResult;
import com.todollab.auth.service.RefreshTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(AuthService authService, RefreshTokenService refreshTokenService) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResult result = authService.register(request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (result.refreshToken() != null) {
            builder.header(
                    HttpHeaders.SET_COOKIE,
                    refreshTokenService.buildRefreshCookie(result.refreshToken()).toString()
            );
        }
        return builder.body(result.response());
    }

    @PostMapping("/verify-email")
    public ResponseEntity<AuthResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        AuthResult result = authService.verifyEmail(request);
        return respondWithSession(result);
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(request);
        return respondWithSession(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = RefreshTokenService.REFRESH_COOKIE_NAME, required = false) String refreshToken
    ) {
        AuthResult result = authService.refresh(refreshToken);
        return respondWithSession(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenService.REFRESH_COOKIE_NAME, required = false) String refreshToken
    ) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenService.buildClearCookie().toString())
                .build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok().build();
    }

    private ResponseEntity<AuthResponse> respondWithSession(AuthResult result) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshTokenService.buildRefreshCookie(result.refreshToken()).toString()
                )
                .body(result.response());
    }
}
