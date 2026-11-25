package com.todollab.invite.dto;

import com.todollab.board.model.BoardRole;
import com.todollab.invite.model.InviteType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class InviteDtos {

    private InviteDtos() {
    }

    public record CreateEmailInviteRequest(
            @NotBlank(message = "E-mail é obrigatório")
            @Email(message = "E-mail inválido")
            @Size(max = 255, message = "E-mail deve ter no máximo 255 caracteres")
            String email,

            @NotNull(message = "Papel é obrigatório")
            BoardRole role
    ) {
        public CreateEmailInviteRequest {
            email = email != null ? email.trim() : null;
        }
    }

    public record CreateLinkInviteRequest(
            @NotNull(message = "Papel é obrigatório")
            BoardRole role,

            @Min(value = 1, message = "Validade deve ser de pelo menos 1 dia")
            @Max(value = 365, message = "Validade máxima é de 365 dias")
            Integer expiresInDays,

            @Min(value = 1, message = "Limite de usos deve ser de pelo menos 1")
            Integer maxUses
    ) {
    }

    public record BoardInviteResponse(
            UUID id,
            UUID boardId,
            InviteType type,
            String email,
            BoardRole role,
            String token,
            String inviteUrl,
            Integer maxUses,
            int usesCount,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt
    ) {
    }

    public record InvitePreviewResponse(
            String boardName,
            String boardColor,
            String boardIcon,
            String inviterName,
            BoardRole role,
            InviteType type,
            String targetEmailMasked,
            boolean valid,
            String reason
    ) {
    }

    public record AcceptInviteResponse(
            UUID boardId,
            BoardRole role
    ) {
    }

    public record PendingInviteResponse(
            UUID id,
            String token,
            UUID boardId,
            String boardName,
            String boardColor,
            String boardIcon,
            String inviterName,
            BoardRole role,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt
    ) {
    }
}
