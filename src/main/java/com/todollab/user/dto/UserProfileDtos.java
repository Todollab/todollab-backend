package com.todollab.user.dto;

import com.todollab.auth.model.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class UserProfileDtos {

    private UserProfileDtos() {
    }

    public record UserProfileResponse(
            UUID id,
            String name,
            String email,
            String avatarColor,
            OffsetDateTime createdAt
    ) {
        public static UserProfileResponse from(User user) {
            return new UserProfileResponse(
                    user.getId(),
                    user.getName(),
                    user.getEmail(),
                    user.getAvatarColor(),
                    user.getCreatedAt()
            );
        }
    }

    public record UpdateProfileRequest(
            @Size(min = 2, max = 120, message = "Nome deve ter entre 2 e 120 caracteres")
            String name,

            @Pattern(
                    regexp = "^#[0-9A-Fa-f]{6}$",
                    message = "Cor do avatar deve estar no formato hexadecimal #RRGGBB"
            )
            String avatarColor
    ) {
        public UpdateProfileRequest {
            name = name != null ? name.trim() : null;
            avatarColor = avatarColor != null ? avatarColor.trim() : null;
        }
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Senha atual é obrigatória")
            String currentPassword,

            @NotBlank(message = "Nova senha é obrigatória")
            @Size(min = 8, max = 128, message = "Nova senha deve ter entre 8 e 128 caracteres")
            String newPassword
    ) {
    }
}
