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
import com.todollab.board.model.Board;
import com.todollab.board.model.BoardRole;
import com.todollab.board.model.BoardView;
import com.todollab.board.repository.BoardMemberRepository;
import com.todollab.board.service.BoardAccessService;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.invite.model.BoardInvite;
import com.todollab.invite.model.InviteType;
import com.todollab.invite.repository.BoardInviteRepository;
import com.todollab.invite.service.BoardInviteService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void sendBoardInviteStripsCrLfFromSubject() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        EmailService emailService = new EmailService(mailSender, "no-reply@todollab.test");

        emailService.sendBoardInvite(
                "guest@example.com",
                "Alice",
                "Projeto\r\nBcc: evil@attacker.com\nX-Injected: yes\r",
                "Editor",
                "http://localhost:5173/invite/token-crlf"
        );

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();

        assertThat(message.getSubject())
                .isEqualTo("Todollab — Convite para o quadro ProjetoBcc: evil@attacker.comX-Injected: yes")
                .doesNotContain("\r")
                .doesNotContain("\n");
        assertThat(message.getHeader("Bcc")).isNull();
        assertThat(message.getHeader("X-Injected")).isNull();
    }

    @Test
    void registerThrowsGoneInviteInvalidWhenAutoActivateTrueButAcceptOnRegisterReturnsEmpty() {
        UserRepository userRepository = mock(UserRepository.class);
        TokenService tokenService = mock(TokenService.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        EmailService emailService = mock(EmailService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<InviteAutoAcceptHook> hookProvider = mock(ObjectProvider.class);

        InviteAutoAcceptHook hook = new InviteAutoAcceptHook() {
            @Override
            public boolean canAutoActivateOnRegister(String inviteToken, String normalizedEmail) {
                return true;
            }

            @Override
            public Optional<UUID> acceptOnRegister(String inviteToken, User user) {
                return Optional.empty();
            }
        };

        when(hookProvider.getIfAvailable()).thenReturn(hook);
        when(userRepository.existsByEmail("invited@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AuthService authService = new AuthService(
                userRepository,
                new BCryptPasswordEncoder(),
                tokenService,
                refreshTokenService,
                verificationCodeService,
                emailService,
                hookProvider
        );

        assertThatThrownBy(() -> authService.register(new RegisterRequest(
                "Invited User",
                "invited@example.com",
                "Password123!",
                "raced-invite"
        )))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.GONE);
                    assertThat(apiEx.getCode()).isEqualTo(ErrorCode.INVITE_INVALID);
                });

        verify(tokenService, never()).generateAccessToken(any());
        verify(refreshTokenService, never()).issueRefreshToken(any());
        verify(emailService, never()).sendVerificationCode(any(), any(), any());
    }

    @Test
    void canAutoActivateOnRegisterAcquiresRowLockViaFindByTokenForUpdate() throws Exception {
        BoardInviteRepository boardInviteRepository = mock(BoardInviteRepository.class);
        BoardMemberRepository boardMemberRepository = mock(BoardMemberRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        BoardAccessService boardAccessService = mock(BoardAccessService.class);
        EmailService emailService = mock(EmailService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        BoardInviteService service = new BoardInviteService(
                boardInviteRepository,
                boardMemberRepository,
                userRepository,
                boardAccessService,
                emailService,
                eventPublisher,
                "http://localhost:5173"
        );

        Method method = BoardInviteService.class.getMethod("canAutoActivateOnRegister", String.class, String.class);
        Transactional txAnnotation = method.getAnnotation(Transactional.class);
        assertThat(txAnnotation).isNotNull();
        assertThat(txAnnotation.readOnly()).isFalse();

        Board board = new Board("Quadro", null, "#0C2439", "kanban", BoardView.LIST, UUID.randomUUID());
        User inviter = new User("Alice", "alice@example.com", "hash", true);
        BoardInvite invite = new BoardInvite(
                board,
                inviter,
                InviteType.EMAIL,
                "guest@example.com",
                BoardRole.EDITOR,
                "lock-token",
                1,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
        );

        when(boardInviteRepository.findByTokenForUpdate("lock-token")).thenReturn(Optional.of(invite));

        boolean result = service.canAutoActivateOnRegister("  lock-token ", "guest@example.com");

        assertThat(result).isTrue();
        verify(boardInviteRepository).findByTokenForUpdate("lock-token");
        verify(boardInviteRepository, never()).findByToken(any());
    }
}
