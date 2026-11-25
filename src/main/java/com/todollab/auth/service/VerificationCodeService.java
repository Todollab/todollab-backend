package com.todollab.auth.service;

import com.todollab.auth.model.User;
import com.todollab.auth.model.VerificationCode;
import com.todollab.auth.model.VerificationCodeType;
import com.todollab.auth.repository.VerificationCodeRepository;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.common.util.HashUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class VerificationCodeService {

    public static final Duration EMAIL_VERIFICATION_TTL = Duration.ofMinutes(15);
    public static final Duration PASSWORD_RESET_TTL = Duration.ofMinutes(30);
    public static final long RESEND_COOLDOWN_SECONDS = 60;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final VerificationCodeRepository verificationCodeRepository;

    public VerificationCodeService(VerificationCodeRepository verificationCodeRepository) {
        this.verificationCodeRepository = verificationCodeRepository;
    }

    @Transactional
    public String issueCode(User user, VerificationCodeType type) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        verificationCodeRepository.invalidateUnusedCodes(user.getId(), type, now);

        String rawCode = generateSixDigitCode();
        Duration ttl = type == VerificationCodeType.EMAIL_VERIFICATION
                ? EMAIL_VERIFICATION_TTL
                : PASSWORD_RESET_TTL;

        VerificationCode entity = new VerificationCode(
                user,
                HashUtils.sha256Hex(rawCode),
                type,
                now.plus(ttl),
                now
        );
        verificationCodeRepository.save(entity);
        return rawCode;
    }

    @Transactional
    public String resendEmailVerificationCode(User user) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        verificationCodeRepository.findTopByUserIdAndTypeOrderByCreatedAtDesc(
                        user.getId(),
                        VerificationCodeType.EMAIL_VERIFICATION
                )
                .ifPresent(latest -> {
                    if (latest.getCreatedAt().plusSeconds(RESEND_COOLDOWN_SECONDS).isAfter(now)) {
                        throw new ApiException(
                                HttpStatus.TOO_MANY_REQUESTS,
                                ErrorCode.RESEND_COOLDOWN,
                                "Aguarde 60 segundos antes de solicitar um novo código de verificação."
                        );
                    }
                });

        return issueCode(user, VerificationCodeType.EMAIL_VERIFICATION);
    }

    @Transactional
    public void verifyAndConsume(User user, VerificationCodeType type, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw invalidCodeException();
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String expectedHash = HashUtils.sha256Hex(rawCode.trim());

        List<VerificationCode> unusedCodes = verificationCodeRepository
                .findAllByUserIdAndTypeAndUsedAtIsNullOrderByCreatedAtDesc(user.getId(), type);

        VerificationCode matched = unusedCodes.stream()
                .filter(code -> code.getExpiresAt().isAfter(now))
                .filter(code -> constantTimeEquals(code.getCodeHash(), expectedHash))
                .findFirst()
                .orElseThrow(this::invalidCodeException);

        matched.setUsedAt(now);
        verificationCodeRepository.save(matched);
    }

    private String generateSixDigitCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }

    private ApiException invalidCodeException() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                ErrorCode.INVALID_VERIFICATION_CODE,
                "Código de verificação inválido ou expirado."
        );
    }
}
