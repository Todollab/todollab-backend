package com.todollab.board.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.todollab.auth.model.User;
import com.todollab.board.model.Board;
import com.todollab.board.model.BoardColumn;
import com.todollab.board.model.BoardMember;
import com.todollab.board.model.BoardRole;
import com.todollab.board.model.BoardView;
import com.todollab.task.dto.TaskDtos.TagResponse;
import com.todollab.task.dto.TaskDtos.TaskResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class BoardDtos {

    private BoardDtos() {
    }

    public record MemberPreview(
            UUID id,
            String name,
            String avatarColor
    ) {
        public static MemberPreview from(User user) {
            return new MemberPreview(
                    user.getId(),
                    user.getName(),
                    user.getAvatarColor()
            );
        }
    }

    public record BoardMemberResponse(
            UUID id,
            UUID userId,
            String name,
            String email,
            String avatarColor,
            BoardRole role,
            OffsetDateTime joinedAt
    ) {
        public static BoardMemberResponse from(BoardMember member) {
            User user = member.getUser();
            return new BoardMemberResponse(
                    member.getId(),
                    user.getId(),
                    user.getName(),
                    user.getEmail(),
                    user.getAvatarColor(),
                    member.getRole(),
                    member.getJoinedAt()
            );
        }
    }

    public record ColumnResponse(
            UUID id,
            UUID boardId,
            String name,
            double position,
            @JsonProperty("isDoneColumn") boolean isDoneColumn,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static ColumnResponse from(BoardColumn column) {
            return new ColumnResponse(
                    column.getId(),
                    column.getBoard().getId(),
                    column.getName(),
                    column.getPosition(),
                    column.isDoneColumn(),
                    column.getCreatedAt(),
                    column.getUpdatedAt()
            );
        }
    }

    public record BoardResponse(
            UUID id,
            String name,
            String description,
            String color,
            String icon,
            BoardView defaultView,
            UUID ownerId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static BoardResponse from(Board board) {
            return new BoardResponse(
                    board.getId(),
                    board.getName(),
                    board.getDescription(),
                    board.getColor(),
                    board.getIcon(),
                    board.getDefaultView(),
                    board.getOwnerId(),
                    board.getCreatedAt(),
                    board.getUpdatedAt()
            );
        }
    }

    public record BoardSummaryResponse(
            UUID id,
            String name,
            String description,
            String color,
            String icon,
            BoardView defaultView,
            BoardRole myRole,
            @JsonProperty("isFavorite") boolean isFavorite,
            int memberCount,
            long openTasksCount,
            long completedTasksCount,
            List<MemberPreview> membersPreview,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record BoardDetailResponse(
            BoardResponse board,
            BoardRole myRole,
            @JsonProperty("isFavorite") boolean isFavorite,
            List<ColumnResponse> columns,
            List<TaskResponse> tasks,
            List<TagResponse> tags,
            List<BoardMemberResponse> members
    ) {
    }

    public record CreateBoardRequest(
            @NotBlank(message = "Nome do quadro é obrigatório")
            @Size(max = 120, message = "Nome do quadro deve ter no máximo 120 caracteres")
            String name,

            String description,

            @Pattern(
                    regexp = "^#[0-9A-Fa-f]{6}$",
                    message = "Cor do quadro deve estar no formato hexadecimal #RRGGBB"
            )
            String color,

            @Size(max = 40, message = "Ícone deve ter no máximo 40 caracteres")
            String icon,

            BoardView defaultView
    ) {
        public CreateBoardRequest {
            name = name != null ? name.trim() : null;
            description = description != null ? description.trim() : null;
            color = color != null ? color.trim() : null;
            icon = icon != null ? icon.trim() : null;
        }
    }

    public record UpdateBoardRequest(
            @Size(min = 1, max = 120, message = "Nome do quadro deve ter entre 1 e 120 caracteres")
            String name,

            String description,

            @Pattern(
                    regexp = "^#[0-9A-Fa-f]{6}$",
                    message = "Cor do quadro deve estar no formato hexadecimal #RRGGBB"
            )
            String color,

            @Size(min = 1, max = 40, message = "Ícone deve ter entre 1 e 40 caracteres")
            String icon,

            BoardView defaultView
    ) {
        public UpdateBoardRequest {
            name = name != null ? name.trim() : null;
            description = description != null ? description.trim() : null;
            color = color != null ? color.trim() : null;
            icon = icon != null ? icon.trim() : null;
        }
    }

    public record FavoriteBoardRequest(
            @NotNull(message = "Favorito é obrigatório")
            @JsonProperty("isFavorite") Boolean isFavorite
    ) {
    }

    public record FavoriteBoardResponse(
            UUID boardId,
            @JsonProperty("isFavorite") boolean isFavorite
    ) {
    }

    public record CreateColumnRequest(
            @NotBlank(message = "Nome da coluna é obrigatório")
            @Size(max = 80, message = "Nome da coluna deve ter no máximo 80 caracteres")
            String name,

            @JsonProperty("isDoneColumn") Boolean isDoneColumn
    ) {
        public CreateColumnRequest {
            name = name != null ? name.trim() : null;
        }
    }

    public record UpdateColumnRequest(
            @Size(min = 1, max = 80, message = "Nome da coluna deve ter entre 1 e 80 caracteres")
            String name,

            @JsonProperty("isDoneColumn") Boolean isDoneColumn
    ) {
        public UpdateColumnRequest {
            name = name != null ? name.trim() : null;
        }
    }

    public record MoveColumnRequest(
            @NotNull(message = "Posição é obrigatória")
            Double position
    ) {
    }

    public record UpdateMemberRoleRequest(
            @NotNull(message = "Papel é obrigatório")
            BoardRole role
    ) {
    }

    public record TransferOwnershipRequest(
            @NotNull(message = "Novo proprietário é obrigatório")
            UUID newOwnerId
    ) {
    }
}
