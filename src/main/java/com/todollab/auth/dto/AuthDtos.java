package com.todollab.auth.dto;

import com.todollab.auth.model.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "Nome é obrigatório")
            @Size(min = 2, max = 120, message = "Nome deve ter entre 2 e 120 caracteres")
            String name,

            @NotBlank(message = "E-mail é obrigatório")
            @Email(message = "E-mail inválido")
            @Size(max = 255, message = "E-mail deve ter no máximo 255 caracteres")
            String email,

            @NotBlank(message = "Senha é obrigatória")
            @Size(min = 8, max = 100, message = "Senha deve ter entre 8 e 100 caracteres")
            String password,

            String inviteToken
    ) {
        public RegisterRequest {
            name = name != null ? name.trim() : null;
            email = email != null ? email.trim() : null;
            inviteToken = inviteToken != null ? inviteToken.trim() : null;
        }
    }

    public record VerifyEmailRequest(
            @NotBlank(message = "E-mail é obrigatório")
            @Email(message = "E-mail inválido")
            String email,

            @NotBlank(message = "Código é obrigatório")
            @Pattern(regexp = "^\\d{6}$", message = "Código deve conter 6 dígitos numéricos")
            String code
    ) {
        public VerifyEmailRequest {
            email = email != null ? email.trim() : null;
            code = code != null ? code.trim() : null;
        }
    }

    public record ResendVerificationRequest(
            @NotBlank(message = "E-mail é obrigatório")
            @Email(message = "E-mail inválido")
            String email
    ) {
        public ResendVerificationRequest {
            email = email != null ? email.trim() : null;
        }
    }

    public record LoginRequest(
            @NotBlank(message = "E-mail é obrigatório")
            @Email(message = "E-mail inválido")
            String email,

            @NotBlank(message = "Senha é obrigatória")
            String password
    ) {
        public LoginRequest {
            email = email != null ? email.trim() : null;
        }
    }

    public record ForgotPasswordRequest(
            @NotBlank(message = "E-mail é obrigatório")
            @Email(message = "E-mail inválido")
            String email
    ) {
        public ForgotPasswordRequest {
            email = email != null ? email.trim() : null;
        }
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "E-mail é obrigatório")
            @Email(message = "E-mail inválido")
            String email,

            @NotBlank(message = "Código é obrigatório")
            @Pattern(regexp = "^\\d{6}$", message = "Código deve conter 6 dígitos numéricos")
            String code,

            @NotBlank(message = "Nova senha é obrigatória")
            @Size(min = 8, max = 100, message = "Nova senha deve ter entre 8 e 100 caracteres")
            String newPassword
    ) {
        public ResetPasswordRequest {
            email = email != null ? email.trim() : null;
            code = code != null ? code.trim() : null;
        }
    }

    public record UserSummary(
            UUID id,
            String name,
            String email,
            String avatarColor,
            OffsetDateTime createdAt
    ) {
        public static UserSummary from(User user) {
            return new UserSummary(
                    user.getId(),
                    user.getName(),
                    user.getEmail(),
                    user.getAvatarColor(),
                    user.getCreatedAt()
            );
        }
    }

    public record AuthResponse(
            String accessToken,
            UserSummary user
    ) {
    }

    public record RegisterResponse(
            UserSummary user,
            String accessToken,
            UUID joinedBoardId,
            boolean emailVerificationRequired
    ) {
    }
}
