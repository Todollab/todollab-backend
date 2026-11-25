package com.todollab.auth.service;

import com.todollab.auth.model.RefreshToken;
import com.todollab.auth.model.User;
import com.todollab.auth.repository.RefreshTokenRepository;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.common.util.HashUtils;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class RefreshTokenService {

    public static final String REFRESH_COOKIE_NAME = "refresh_token";
    public static final String REFRESH_COOKIE_PATH = "/api/v1/auth";
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);
    private static final int TOKEN_BYTE_LENGTH = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final boolean cookieSecure;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            @Value("${app.cookie.secure:false}") boolean cookieSecure
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.cookieSecure = cookieSecure;
    }

    @Transactional
    public String issueRefreshToken(User user) {
        String rawToken = HashUtils.generateSecureToken(TOKEN_BYTE_LENGTH);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        RefreshToken tokenEntity = new RefreshToken(
                user,
                HashUtils.sha256Hex(rawToken),
                now.plus(REFRESH_TOKEN_TTL),
                now
        );
        refreshTokenRepository.save(tokenEntity);
        return rawToken;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public RotatedRefreshToken rotateRefreshToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidRefreshTokenException();
        }

        String tokenHash = HashUtils.sha256Hex(rawToken);
        RefreshToken existing = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(this::invalidRefreshTokenException);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        User user = existing.getUser();
        Hibernate.initialize(user);

        if (existing.getRevokedAt() != null) {
            revokeAllForUser(user.getId());
            throw invalidRefreshTokenException();
        }

        if (!existing.getExpiresAt().isAfter(now)) {
            throw invalidRefreshTokenException();
        }

        existing.setRevokedAt(now);
        refreshTokenRepository.save(existing);

        String newRawToken = issueRefreshToken(user);
        return new RotatedRefreshToken(user, newRawToken);
    }

    @Transactional
    public void revokeToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        String tokenHash = HashUtils.sha256Hex(rawToken);
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
                refreshTokenRepository.save(token);
            }
        });
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.revokeAllActiveByUserId(userId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    public ResponseCookie buildRefreshCookie(String rawToken) {
        return baseCookieBuilder(rawToken)
                .maxAge(REFRESH_TOKEN_TTL)
                .build();
    }

    public ResponseCookie buildClearCookie() {
        return baseCookieBuilder("")
                .maxAge(0)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookieBuilder(String value) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH);
    }

    private ApiException invalidRefreshTokenException() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                ErrorCode.INVALID_REFRESH_TOKEN,
                "Sessão inválida ou expirada. Faça login novamente."
        );
    }

    public record RotatedRefreshToken(User user, String newRawToken) {
    }
}
