package com.todollab.auth.service;

import com.todollab.auth.dto.AuthDtos.AuthResponse;
import com.todollab.auth.dto.AuthDtos.ForgotPasswordRequest;
import com.todollab.auth.dto.AuthDtos.LoginRequest;
import com.todollab.auth.dto.AuthDtos.RegisterRequest;
import com.todollab.auth.dto.AuthDtos.RegisterResponse;
import com.todollab.auth.dto.AuthDtos.ResendVerificationRequest;
import com.todollab.auth.dto.AuthDtos.ResetPasswordRequest;
import com.todollab.auth.dto.AuthDtos.UserSummary;
import com.todollab.auth.dto.AuthDtos.VerifyEmailRequest;
import com.todollab.auth.model.User;
import com.todollab.auth.model.VerificationCodeType;
import com.todollab.auth.repository.UserRepository;
import com.todollab.auth.service.RefreshTokenService.RotatedRefreshToken;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.common.util.EmailNormalizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final VerificationCodeService verificationCodeService;
    private final EmailService emailService;
    private final ObjectProvider<InviteAutoAcceptHook> inviteAutoAcceptHookProvider;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            RefreshTokenService refreshTokenService,
            VerificationCodeService verificationCodeService,
            EmailService emailService,
            ObjectProvider<InviteAutoAcceptHook> inviteAutoAcceptHookProvider
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.verificationCodeService = verificationCodeService;
        this.emailService = emailService;
        this.inviteAutoAcceptHookProvider = inviteAutoAcceptHookProvider;
    }

    @Transactional
    public RegisterResult register(RegisterRequest request) {
        String normalizedEmail = EmailNormalizer.normalize(request.email());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    ErrorCode.EMAIL_ALREADY_EXISTS,
                    "Já existe uma conta cadastrada com este e-mail."
            );
        }

        InviteAutoAcceptHook inviteHook = inviteAutoAcceptHookProvider.getIfAvailable();
        String inviteToken = (request.inviteToken() != null && !request.inviteToken().isBlank())
                ? request.inviteToken().trim()
                : null;
        boolean autoActivate = inviteToken != null
                && inviteHook != null
                && inviteHook.canAutoActivateOnRegister(inviteToken, normalizedEmail);

        User user = new User(
                request.name().trim(),
                normalizedEmail,
                passwordEncoder.encode(request.password()),
                autoActivate
        );
        userRepository.saveAndFlush(user);

        if (autoActivate) {
            UUID joinedBoardId = inviteHook.acceptOnRegister(inviteToken, user).orElse(null);
            String accessToken = tokenService.generateAccessToken(user);
            String refreshToken = refreshTokenService.issueRefreshToken(user);
            return new RegisterResult(
                    new RegisterResponse(UserSummary.from(user), accessToken, joinedBoardId, false),
                    refreshToken
            );
        }

        String code = verificationCodeService.issueCode(user, VerificationCodeType.EMAIL_VERIFICATION);
        emailService.sendVerificationCode(user.getEmail(), user.getName(), code);

        return new RegisterResult(
                new RegisterResponse(UserSummary.from(user), null, null, true),
                null
        );
    }

    @Transactional
    public AuthResult verifyEmail(VerifyEmailRequest request) {
        String normalizedEmail = EmailNormalizer.normalize(request.email());
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.INVALID_VERIFICATION_CODE,
                        "Código de verificação inválido ou expirado."
                ));

        if (user.isActive()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.INVALID_VERIFICATION_CODE,
                    "Código de verificação inválido ou expirado."
            );
        }

        verificationCodeService.verifyAndConsume(
                user,
                VerificationCodeType.EMAIL_VERIFICATION,
                request.code()
        );

        user.setActive(true);
        userRepository.save(user);

        return issueSession(user);
    }

    @Transactional
    public void resendVerification(ResendVerificationRequest request) {
        String normalizedEmail = EmailNormalizer.normalize(request.email());
        Optional<User> userOpt = userRepository.findByEmail(normalizedEmail);
        if (userOpt.isEmpty() || userOpt.get().isActive()) {
            return;
        }

        User user = userOpt.get();
        String code = verificationCodeService.resendEmailVerificationCode(user);
        emailService.sendVerificationCode(user.getEmail(), user.getName(), code);
    }

    @Transactional
    public AuthResult login(LoginRequest request) {
        String normalizedEmail = EmailNormalizer.normalize(request.email());
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(this::invalidCredentialsException);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentialsException();
        }

        if (!user.isActive()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.EMAIL_NOT_VERIFIED,
                    "Confirme seu e-mail antes de fazer login."
            );
        }

        return issueSession(user);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResult refresh(String rawRefreshToken) {
        RotatedRefreshToken rotated = refreshTokenService.rotateRefreshToken(rawRefreshToken);
        User user = rotated.user();
        if (!user.isActive()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.EMAIL_NOT_VERIFIED,
                    "Confirme seu e-mail antes de acessar sua conta."
            );
        }

        String accessToken = tokenService.generateAccessToken(user);
        return new AuthResult(new AuthResponse(accessToken, UserSummary.from(user)), rotated.newRawToken());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeToken(rawRefreshToken);
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String normalizedEmail = EmailNormalizer.normalize(request.email());
        userRepository.findByEmail(normalizedEmail).ifPresent(user -> {
            String code = verificationCodeService.issueCode(user, VerificationCodeType.PASSWORD_RESET);
            emailService.sendPasswordResetCode(user.getEmail(), user.getName(), code);
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String normalizedEmail = EmailNormalizer.normalize(request.email());
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.INVALID_VERIFICATION_CODE,
                        "Código de verificação inválido ou expirado."
                ));

        verificationCodeService.verifyAndConsume(
                user,
                VerificationCodeType.PASSWORD_RESET,
                request.code()
        );

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.saveAndFlush(user);
        refreshTokenService.revokeAllForUser(user.getId());
    }

    private AuthResult issueSession(User user) {
        String accessToken = tokenService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issueRefreshToken(user);
        return new AuthResult(new AuthResponse(accessToken, UserSummary.from(user)), refreshToken);
    }

    private ApiException invalidCredentialsException() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                ErrorCode.INVALID_CREDENTIALS,
                "E-mail ou senha inválidos."
        );
    }

    public record AuthResult(AuthResponse response, String refreshToken) {
    }

    public record RegisterResult(RegisterResponse response, String refreshToken) {
    }
}
