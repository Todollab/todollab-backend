package com.todollab.auth;

import com.todollab.auth.dto.AuthDtos.RegisterRequest;
import com.todollab.auth.model.User;
import com.todollab.auth.repository.UserRepository;
import com.todollab.auth.service.AuthService;
import com.todollab.auth.service.AuthService.RegisterResult;
import com.todollab.auth.service.EmailService;
import com.todollab.auth.service.InviteAutoAcceptHook;
import com.todollab.auth.service.RefreshTokenService;
import com.todollab.auth.service.TokenService;
import com.todollab.auth.service.VerificationCodeService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceAndInviteHookTest {

    @Test
    void sendBoardInviteEscapesHtmlAndIncludesBoardRoleAndInviteLink() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        EmailService emailService = new EmailService(mailSender, "no-reply@todollab.test");

        emailService.sendBoardInvite(
                "guest@example.com",
                "Alice <script>",
                "Projeto & Sprint",
                "Editor",
                "http://localhost:5173/invite/token-123"
        );

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        String body = (String) message.getContent();

        assertThat(message.getSubject()).contains("Projeto & Sprint");
        assertThat(body)
                .contains("Alice &lt;script&gt;")
                .contains("Projeto &amp; Sprint")
                .contains("Editor")
                .contains("http://localhost:5173/invite/token-123")
                .doesNotContain("<script>");
    }

    @Test
    void registerWithMatchingInviteAutoAcceptHookActivatesAndIssuesSession() {
        UserRepository userRepository = mock(UserRepository.class);
        TokenService tokenService = mock(TokenService.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        EmailService emailService = mock(EmailService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<InviteAutoAcceptHook> hookProvider = mock(ObjectProvider.class);

        UUID boardId = UUID.randomUUID();
        InviteAutoAcceptHook hook = new InviteAutoAcceptHook() {
            @Override
            public boolean canAutoActivateOnRegister(String inviteToken, String normalizedEmail) {
                return "valid-invite".equals(inviteToken) && "invited@example.com".equals(normalizedEmail);
            }

            @Override
            public Optional<UUID> acceptOnRegister(String inviteToken, User user) {
                return Optional.of(boardId);
            }
        };

        when(hookProvider.getIfAvailable()).thenReturn(hook);
        when(userRepository.existsByEmail("invited@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenService.generateAccessToken(any(User.class))).thenReturn("jwt-access-token");
        when(refreshTokenService.issueRefreshToken(any(User.class))).thenReturn("raw-refresh-token");

        AuthService authService = new AuthService(
                userRepository,
                new BCryptPasswordEncoder(),
                tokenService,
                refreshTokenService,
                verificationCodeService,
                emailService,
                hookProvider
        );

        RegisterResult result = authService.register(new RegisterRequest(
                "Invited User",
                "  Invited@Example.COM ",
                "Password123!",
                "valid-invite"
        ));

        assertThat(result.refreshToken()).isEqualTo("raw-refresh-token");
        assertThat(result.response().accessToken()).isEqualTo("jwt-access-token");
        assertThat(result.response().joinedBoardId()).isEqualTo(boardId);
        assertThat(result.response().emailVerificationRequired()).isFalse();
        assertThat(result.response().user().email()).isEqualTo("invited@example.com");
        verify(emailService, never()).sendVerificationCode(any(), any(), any());
    }
}
