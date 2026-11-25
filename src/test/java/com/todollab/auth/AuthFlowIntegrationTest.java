package com.todollab.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todollab.auth.service.RefreshTokenService;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.common.util.HashUtils;
import com.todollab.support.AbstractIntegrationTest;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    private static final Pattern SIX_DIGIT_CODE_PATTERN = Pattern.compile("(?<!#)\\b(\\d{6})\\b");

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Value("${api.security.token.secret}")
    private String jwtSecret;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM verification_codes");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM users");
        when(javaMailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
    }

    @Test
    void registerNormalizesEmailAndBlocksDuplicateAndUnverifiedLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Alice Silva",
                                "email", "  Alice@Example.COM  ",
                                "password", "Password123!"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.user.name").value("Alice Silva"))
                .andExpect(jsonPath("$.user.avatarColor").value("#0C2439"))
                .andExpect(jsonPath("$.emailVerificationRequired").value(true))
                .andExpect(jsonPath("$.accessToken").isEmpty())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("passwordHash")
                        .doesNotContain("Password123!"));

        String storedEmail = jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE email = ?",
                String.class,
                "alice@example.com"
        );
        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM users WHERE email = ?",
                Boolean.class,
                "alice@example.com"
        );
        assertThat(storedEmail).isEqualTo("alice@example.com");
        assertThat(isActive).isFalse();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Alice Duplicate",
                                "email", "alice@example.com",
                                "password", "Password123!"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "ALICE@example.com",
                                "password", "Password123!"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void verifyEmailActivatesAccountAndRejectsCodeReuse() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Bob Souza",
                                "email", "bob@example.com",
                                "password", "Password123!"
                        ))))
                .andExpect(status().isCreated());

        String code = captureLatestCode(1);

        MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "  BOB@Example.COM ",
                                "code", code
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("bob@example.com"))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();

        String responseBody = verifyResult.getResponse().getContentAsString();
        assertThat(responseBody).doesNotContain("passwordHash").doesNotContain("Password123!");

        String setCookie = verifyResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains("refresh_token=")
                .contains("HttpOnly")
                .contains("Path=/api/v1/auth")
                .contains("SameSite=Lax");

        Boolean isActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM users WHERE email = ?",
                Boolean.class,
                "bob@example.com"
        );
        assertThat(isActive).isTrue();

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "bob@example.com",
                                "code", code
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
    }

    @Test
    void resendVerificationEnforces60SecondCooldown() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Carol Dias",
                                "email", "carol@example.com",
                                "password", "Password123!"
                        ))))
                .andExpect(status().isCreated());

        String firstCode = captureLatestCode(1);

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "carol@example.com"
                        ))))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESEND_COOLDOWN"));

        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?",
                UUID.class,
                "carol@example.com"
        );
        jdbcTemplate.update(
                "UPDATE verification_codes SET created_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(65)),
                userId
        );

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "  CAROL@Example.COM "
                        ))))
                .andExpect(status().isOk());

        String secondCode = captureLatestCode(2);

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "carol@example.com",
                                "code", firstCode
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "carol@example.com",
                                "code", secondCode
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void refreshRotatesTokenAndReplayRevokesAllUserSessions() throws Exception {
        String initialRefreshToken = registerAndVerify("Danilo Lima", "danilo@example.com", "Password123!");

        MvcResult rotatedResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", initialRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("danilo@example.com"))
                .andReturn();

        String rotatedRefreshToken = extractCookieValue(rotatedResult.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(rotatedRefreshToken).isNotBlank().isNotEqualTo(initialRefreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", initialRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", rotatedRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void forgotAndResetPasswordUpdatesPasswordAndRevokesRefreshTokens() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "unknown@example.com"
                        ))))
                .andExpect(status().isOk());

        verify(javaMailSender, never()).send(any(MimeMessage.class));

        String oldRefreshToken = registerAndVerify("Eva Costa", "eva@example.com", "OldPassword123!");

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "  EVA@Example.COM  "
                        ))))
                .andExpect(status().isOk());

        String resetCode = captureLatestCode(2);

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "eva@example.com",
                                "code", resetCode,
                                "newPassword", "NewPassword456!"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", oldRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "eva@example.com",
                                "password", "OldPassword123!"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "eva@example.com",
                                "password", "NewPassword456!"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void malformedOrExpiredJwtReturns401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer malformed.token.here"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        String expiredJwt = JWT.create()
                .withIssuer("todollab-auth")
                .withSubject("expired@example.com")
                .withClaim("uid", UUID.randomUUID().toString())
                .withExpiresAt(Instant.now().minusSeconds(60))
                .sign(Algorithm.HMAC256(jwtSecret));

        mockMvc.perform(get("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredJwt))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
        String refreshToken = registerAndVerify("Fabio Melo", "fabio@example.com", "Password123!");

        MvcResult logoutResult = mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isNoContent())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();

        String clearCookie = logoutResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(clearCookie)
                .contains("refresh_token=")
                .contains("Max-Age=0")
                .contains("HttpOnly");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void activeJwtPassesSecurityFilterWhileInactiveUserAndMissingRefreshCookieAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Gabi Nunes",
                                "email", "gabi@example.com",
                                "password", "Password123!"
                        ))))
                .andExpect(status().isCreated());

        UUID gabiId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?",
                UUID.class,
                "gabi@example.com"
        );
        String unverifiedToken = JWT.create()
                .withIssuer("todollab-auth")
                .withSubject("gabi@example.com")
                .withClaim("uid", gabiId.toString())
                .withExpiresAt(Instant.now().plusSeconds(300))
                .sign(Algorithm.HMAC256(jwtSecret));

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + unverifiedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        String verificationCode = captureLatestCode(1);
        MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "gabi@example.com",
                                "code", verificationCode
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String activeAccessToken = objectMapper.readTree(verifyResult.getResponse().getContentAsString())
                .get("accessToken").asText();

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + activeAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("gabi@example.com"));
    }

    @Test
    void expiredVerificationCodeIsRejectedAndCorsAllowsConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Hugo Pires",
                                "email", "hugo@example.com",
                                "password", "Password123!"
                        ))))
                .andExpect(status().isCreated());

        String code = captureLatestCode(1);
        UUID hugoId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?",
                UUID.class,
                "hugo@example.com"
        );
        jdbcTemplate.update(
                "UPDATE verification_codes SET expires_at = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(10)),
                hugoId
        );

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "hugo@example.com",
                                "code", code
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
    }

    @Test
    void expiredRefreshTokenDoesNotRevokeOtherActiveSessionsWhenRetried() throws Exception {
        String expiredSessionToken = registerAndVerify("Iris Rocha", "iris@example.com", "Password123!");

        MvcResult secondLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "iris@example.com",
                                "password", "Password123!"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String activeSessionToken = extractCookieValue(secondLogin.getResponse().getHeader(HttpHeaders.SET_COOKIE));

        jdbcTemplate.update(
                "UPDATE refresh_tokens SET expires_at = ? WHERE token_hash = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                HashUtils.sha256Hex(expiredSessionToken)
        );

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", expiredSessionToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", expiredSessionToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", activeSessionToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @Test
    void concurrentRefreshRotationAllowsOnlyOneSuccess() throws Exception {
        String refreshToken = registerAndVerify("Joao Vitor", "joao@example.com", "Password123!");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> attempt = () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    refreshTokenService.rotateRefreshToken(refreshToken);
                    return true;
                } catch (ApiException ex) {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
                    return false;
                }
            };

            List<Future<Boolean>> futures = new ArrayList<>();
            futures.add(executor.submit(attempt));
            futures.add(executor.submit(attempt));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private String registerAndVerify(String name, String email, String password) throws Exception {
        int existingSendCount = org.mockito.Mockito.mockingDetails(javaMailSender).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("send") && inv.getArguments().length == 1
                        && inv.getArguments()[0] instanceof MimeMessage)
                .toList()
                .size();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "email", email,
                                "password", password
                        ))))
                .andExpect(status().isCreated());

        String code = captureLatestCode(existingSendCount + 1);

        MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "code", code
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(verifyResult.getResponse().getContentAsString());
        assertThat(body.get("accessToken").asText()).isNotBlank();
        return extractCookieValue(verifyResult.getResponse().getHeader(HttpHeaders.SET_COOKIE));
    }

    private String captureLatestCode(int expectedTotalSent) throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender, atLeastOnce()).send(captor.capture());
        List<MimeMessage> messages = captor.getAllValues();
        assertThat(messages).hasSize(expectedTotalSent);
        String body = extractText(messages.get(messages.size() - 1).getContent());
        Matcher matcher = SIX_DIGIT_CODE_PATTERN.matcher(body);
        assertThat(matcher.find()).as("Expected 6-digit code in email body: %s", body).isTrue();
        return matcher.group(1);
    }

    private String extractText(Object content) throws Exception {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof Multipart multipart) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                sb.append(extractText(part.getContent())).append('\n');
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    private String extractCookieValue(String setCookieHeader) {
        assertThat(setCookieHeader).isNotBlank().contains("refresh_token=");
        String firstSegment = setCookieHeader.split(";", 2)[0];
        return firstSegment.substring("refresh_token=".length());
    }
}
