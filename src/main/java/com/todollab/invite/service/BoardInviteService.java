package com.todollab.invite.service;

import com.todollab.auth.model.User;
import com.todollab.auth.repository.UserRepository;
import com.todollab.auth.service.EmailService;
import com.todollab.auth.service.InviteAutoAcceptHook;
import com.todollab.board.dto.BoardDtos.BoardMemberResponse;
import com.todollab.board.model.BoardMember;
import com.todollab.board.model.BoardRole;
import com.todollab.board.repository.BoardMemberRepository;
import com.todollab.board.service.BoardAccessService;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.common.util.EmailNormalizer;
import com.todollab.common.util.HashUtils;
import com.todollab.invite.dto.InviteDtos.AcceptInviteResponse;
import com.todollab.invite.dto.InviteDtos.BoardInviteResponse;
import com.todollab.invite.dto.InviteDtos.CreateEmailInviteRequest;
import com.todollab.invite.dto.InviteDtos.CreateLinkInviteRequest;
import com.todollab.invite.dto.InviteDtos.InvitePreviewResponse;
import com.todollab.invite.dto.InviteDtos.PendingInviteResponse;
import com.todollab.invite.model.BoardInvite;
import com.todollab.invite.model.InviteType;
import com.todollab.invite.repository.BoardInviteRepository;
import com.todollab.realtime.BoardDomainEvent;
import com.todollab.realtime.BoardEventType;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class BoardInviteService implements InviteAutoAcceptHook {

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final long EMAIL_INVITE_EXPIRY_DAYS = 7;

    private final BoardInviteRepository boardInviteRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final UserRepository userRepository;
    private final BoardAccessService boardAccessService;
    private final EmailService emailService;
    private final ApplicationEventPublisher eventPublisher;
    private final String frontendUrl;

    public BoardInviteService(
            BoardInviteRepository boardInviteRepository,
            BoardMemberRepository boardMemberRepository,
            UserRepository userRepository,
            BoardAccessService boardAccessService,
            EmailService emailService,
            ApplicationEventPublisher eventPublisher,
            @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl
    ) {
        this.boardInviteRepository = boardInviteRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.userRepository = userRepository;
        this.boardAccessService = boardAccessService;
        this.emailService = emailService;
        this.eventPublisher = eventPublisher;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public List<BoardInviteResponse> listBoardInvites(UUID boardId, UUID actorId) {
        boardAccessService.requireOwner(boardId, actorId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return boardInviteRepository.findActiveByBoardId(boardId, now)
                .stream()
                .map(this::toBoardInviteResponse)
                .toList();
    }

    @Transactional
    public BoardInviteResponse createEmailInvite(
            UUID boardId,
            UUID actorId,
            CreateEmailInviteRequest request
    ) {
        BoardMember ownerMembership = boardAccessService.requireOwner(boardId, actorId);
        validateInvitableRole(request.role());
        String normalizedEmail = EmailNormalizer.normalize(request.email());

        userRepository.findByEmail(normalizedEmail).ifPresent(existingUser -> {
            if (boardMemberRepository.existsByBoardIdAndUserId(boardId, existingUser.getId())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        ErrorCode.ALREADY_BOARD_MEMBER,
                        "Este usuário já é membro do quadro."
                );
            }
        });

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plusDays(EMAIL_INVITE_EXPIRY_DAYS);

        BoardInvite invite = boardInviteRepository
                .findPendingEmailInvitesForBoardAndEmail(boardId, normalizedEmail, now)
                .stream()
                .findFirst()
                .map(existing -> {
                    existing.setRole(request.role());
                    existing.setExpiresAt(expiresAt);
                    existing.setInvitedBy(ownerMembership.getUser());
                    return boardInviteRepository.saveAndFlush(existing);
                })
                .orElseGet(() -> boardInviteRepository.saveAndFlush(new BoardInvite(
                        ownerMembership.getBoard(),
                        ownerMembership.getUser(),
                        InviteType.EMAIL,
                        normalizedEmail,
                        request.role(),
                        HashUtils.generateSecureToken(TOKEN_BYTE_LENGTH),
                        1,
                        expiresAt
                )));

        emailService.sendBoardInvite(
                normalizedEmail,
                ownerMembership.getUser().getName(),
                ownerMembership.getBoard().getName(),
                translateRoleLabel(invite.getRole()),
                buildInviteUrl(invite.getToken())
        );

        return toBoardInviteResponse(invite);
    }

    @Transactional
    public BoardInviteResponse createLinkInvite(
            UUID boardId,
            UUID actorId,
            CreateLinkInviteRequest request
    ) {
        BoardMember ownerMembership = boardAccessService.requireOwner(boardId, actorId);
        validateInvitableRole(request.role());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = request.expiresInDays() != null
                ? now.plusDays(request.expiresInDays())
                : null;

        BoardInvite invite = boardInviteRepository.saveAndFlush(new BoardInvite(
                ownerMembership.getBoard(),
                ownerMembership.getUser(),
                InviteType.LINK,
                null,
                request.role(),
                HashUtils.generateSecureToken(TOKEN_BYTE_LENGTH),
                request.maxUses(),
                expiresAt
        ));

        return toBoardInviteResponse(invite);
    }

    @Transactional
    public void revokeInvite(UUID boardId, UUID actorId, UUID inviteId) {
        boardAccessService.requireOwner(boardId, actorId);
        BoardInvite invite = boardInviteRepository.findByIdAndBoardId(inviteId, boardId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.INVITE_NOT_FOUND,
                        "Convite não encontrado."
                ));

        if (invite.getRevokedAt() == null) {
            invite.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
            boardInviteRepository.saveAndFlush(invite);
        }
    }

    @Transactional(readOnly = true)
    public InvitePreviewResponse previewInvite(String token) {
        BoardInvite invite = boardInviteRepository.findWithDetailsByToken(token)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.INVITE_NOT_FOUND,
                        "Convite não encontrado."
                ));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String reason = invalidReason(invite, now);
        String maskedEmail = invite.getType() == InviteType.EMAIL
                ? maskEmail(invite.getEmail())
                : null;

        return new InvitePreviewResponse(
                invite.getBoard().getName(),
                invite.getBoard().getColor(),
                invite.getBoard().getIcon(),
                invite.getInvitedBy().getName(),
                invite.getRole(),
                invite.getType(),
                maskedEmail,
                reason == null,
                reason
        );
    }

    @Transactional
    public AcceptInviteResponse acceptInvite(String token, UUID userId) {
        BoardInvite invite = findInviteForUpdateOrThrow(token);
        User user = findUserOrThrow(userId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        String lifecycleError = invalidLifecycleReason(invite, now);
        if (lifecycleError != null) {
            throw inviteInvalidException(lifecycleError);
        }

        if (invite.getType() == InviteType.EMAIL && !user.getEmail().equals(invite.getEmail())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.INVITE_EMAIL_MISMATCH,
                    "Este convite foi enviado para outro endereço de e-mail."
            );
        }

        UUID boardId = invite.getBoard().getId();
        Optional<BoardMember> existingMember = boardMemberRepository.findByBoardIdAndUserId(boardId, userId);
        if (existingMember.isPresent()) {
            return new AcceptInviteResponse(boardId, existingMember.get().getRole());
        }

        if (invite.isExhausted()) {
            throw inviteInvalidException("Este convite atingiu o limite máximo de usos.");
        }

        BoardMember savedMember = boardMemberRepository.saveAndFlush(new BoardMember(invite.getBoard(), user, invite.getRole()));
        invite.incrementUsesCount();
        boardInviteRepository.saveAndFlush(invite);

        eventPublisher.publishEvent(BoardDomainEvent.of(
                BoardEventType.MEMBER_CHANGED,
                boardId,
                userId,
                Map.of("action", "JOINED", "member", BoardMemberResponse.from(savedMember))
        ));

        return new AcceptInviteResponse(boardId, invite.getRole());
    }

    @Transactional
    public void declineInvite(String token, UUID userId) {
        BoardInvite invite = findInviteForUpdateOrThrow(token);
        User user = findUserOrThrow(userId);

        if (invite.getType() != InviteType.EMAIL || !user.getEmail().equals(invite.getEmail())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.INVITE_EMAIL_MISMATCH,
                    "Apenas o destinatário do convite por e-mail pode recusá-lo."
            );
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String reason = invalidReason(invite, now);
        if (reason != null) {
            throw inviteInvalidException(reason);
        }

        invite.setDeclinedAt(now);
        boardInviteRepository.saveAndFlush(invite);
    }

    @Transactional(readOnly = true)
    public List<PendingInviteResponse> listPendingInvitesForUser(UUID userId, String email) {
        String normalizedEmail = EmailNormalizer.normalize(email);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return boardInviteRepository.findPendingEmailInvitesForUser(normalizedEmail, userId, now)
                .stream()
                .map(invite -> new PendingInviteResponse(
                        invite.getId(),
                        invite.getToken(),
                        invite.getBoard().getId(),
                        invite.getBoard().getName(),
                        invite.getBoard().getColor(),
                        invite.getBoard().getIcon(),
                        invite.getInvitedBy().getName(),
                        invite.getRole(),
                        invite.getExpiresAt(),
                        invite.getCreatedAt()
                ))
                .toList();
    }

    @Override
    @Transactional
    public boolean canAutoActivateOnRegister(String inviteToken, String normalizedEmail) {
        if (inviteToken == null || inviteToken.isBlank() || normalizedEmail == null || normalizedEmail.isBlank()) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return boardInviteRepository.findByTokenForUpdate(inviteToken.trim())
                .map(invite -> matchesAutoActivate(invite, normalizedEmail, now))
                .orElse(false);
    }

    @Override
    @Transactional
    public Optional<UUID> acceptOnRegister(String inviteToken, User user) {
        if (inviteToken == null || inviteToken.isBlank() || user == null) {
            return Optional.empty();
        }
        Optional<BoardInvite> inviteOpt = boardInviteRepository.findByTokenForUpdate(inviteToken.trim());
        if (inviteOpt.isEmpty()) {
            return Optional.empty();
        }

        BoardInvite invite = inviteOpt.get();
        Hibernate.initialize(invite.getBoard());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!matchesAutoActivate(invite, user.getEmail(), now)) {
            return Optional.empty();
        }

        UUID boardId = invite.getBoard().getId();
        if (!boardMemberRepository.existsByBoardIdAndUserId(boardId, user.getId())) {
            BoardMember savedMember = boardMemberRepository.saveAndFlush(new BoardMember(invite.getBoard(), user, invite.getRole()));
            eventPublisher.publishEvent(BoardDomainEvent.of(
                    BoardEventType.MEMBER_CHANGED,
                    boardId,
                    user.getId(),
                    Map.of("action", "JOINED", "member", BoardMemberResponse.from(savedMember))
            ));
        }
        invite.incrementUsesCount();
        boardInviteRepository.saveAndFlush(invite);

        return Optional.of(boardId);
    }

    private boolean matchesAutoActivate(BoardInvite invite, String normalizedEmail, OffsetDateTime now) {
        return invite.getType() == InviteType.EMAIL
                && invite.isActiveAt(now)
                && normalizedEmail.equals(invite.getEmail());
    }

    private BoardInvite findInviteForUpdateOrThrow(String token) {
        BoardInvite invite = boardInviteRepository.findByTokenForUpdate(token)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.INVITE_NOT_FOUND,
                        "Convite não encontrado."
                ));
        Hibernate.initialize(invite.getBoard());
        Hibernate.initialize(invite.getInvitedBy());
        return invite;
    }

    private User findUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        ErrorCode.UNAUTHORIZED,
                        "Autenticação necessária ou token inválido."
                ));
    }

    private void validateInvitableRole(BoardRole role) {
        if (role == BoardRole.OWNER) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_ERROR,
                    "Não é possível convidar um usuário diretamente como proprietário."
            );
        }
    }

    private String invalidReason(BoardInvite invite, OffsetDateTime now) {
        String lifecycleError = invalidLifecycleReason(invite, now);
        if (lifecycleError != null) {
            return lifecycleError;
        }
        if (invite.isExhausted()) {
            return "Este convite atingiu o limite máximo de usos.";
        }
        return null;
    }

    private String invalidLifecycleReason(BoardInvite invite, OffsetDateTime now) {
        if (invite.getRevokedAt() != null) {
            return "Este convite foi revogado.";
        }
        if (invite.getDeclinedAt() != null) {
            return "Este convite foi recusado.";
        }
        if (invite.isExpired(now)) {
            return "Este convite expirou.";
        }
        return null;
    }

    private ApiException inviteInvalidException(String detail) {
        return new ApiException(HttpStatus.GONE, ErrorCode.INVITE_INVALID, detail);
    }

    private BoardInviteResponse toBoardInviteResponse(BoardInvite invite) {
        return new BoardInviteResponse(
                invite.getId(),
                invite.getBoard().getId(),
                invite.getType(),
                invite.getEmail(),
                invite.getRole(),
                invite.getToken(),
                buildInviteUrl(invite.getToken()),
                invite.getMaxUses(),
                invite.getUsesCount(),
                invite.getExpiresAt(),
                invite.getCreatedAt()
        );
    }

    private String buildInviteUrl(String token) {
        return frontendUrl + "/invite/" + token;
    }

    private String translateRoleLabel(BoardRole role) {
        return role == BoardRole.EDITOR ? "Editor" : "Visualizador";
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        String domain = email.substring(atIndex + 1);
        return email.charAt(0) + "***@" + domain;
    }
}
