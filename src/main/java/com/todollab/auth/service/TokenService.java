package com.todollab.auth.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.todollab.auth.model.User;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TokenService {

    public static final String ISSUER = "todollab-auth";
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);

    private final Algorithm algorithm;

    public TokenService(@Value("${api.security.token.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret must not be blank");
        }
        this.algorithm = Algorithm.HMAC256(secret);
    }

    public String generateAccessToken(User user) {
        try {
            Instant now = Instant.now();
            return JWT.create()
                    .withIssuer(ISSUER)
                    .withSubject(user.getEmail())
                    .withClaim("uid", user.getId().toString())
                    .withIssuedAt(now)
                    .withExpiresAt(now.plus(ACCESS_TOKEN_TTL))
                    .sign(algorithm);
        } catch (JWTCreationException ex) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCode.INTERNAL_ERROR,
                    "Não foi possível gerar o token de acesso."
            );
        }
    }

    public Optional<TokenClaims> parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            DecodedJWT jwt = JWT.require(algorithm)
                    .withIssuer(ISSUER)
                    .build()
                    .verify(token);

            String email = jwt.getSubject();
            String uid = jwt.getClaim("uid").asString();
            if (email == null || email.isBlank() || uid == null || uid.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new TokenClaims(UUID.fromString(uid), email));
        } catch (JWTVerificationException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public record TokenClaims(UUID userId, String email) {
    }
}
