package com.todollab.task.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.todollab.task.model.BoardTag;
import com.todollab.task.model.TaskPriority;
import com.todollab.task.model.TaskSubtask;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class TaskDtos {

    private TaskDtos() {
    }

    public record SubtaskResponse(
            UUID id,
            UUID taskId,
            String title,
            boolean done,
            double position,
            OffsetDateTime createdAt
    ) {
        public static SubtaskResponse from(TaskSubtask subtask) {
            return new SubtaskResponse(
                    subtask.getId(),
                    subtask.getTask().getId(),
                    subtask.getTitle(),
                    subtask.isDone(),
                    subtask.getPosition(),
                    subtask.getCreatedAt()
            );
        }
    }

    public record TagResponse(
            UUID id,
            UUID boardId,
            String name,
            String color
    ) {
        public static TagResponse from(BoardTag tag) {
            return new TagResponse(
                    tag.getId(),
                    tag.getBoard().getId(),
                    tag.getName(),
                    tag.getColor()
            );
        }
    }

    public record TaskResponse(
            UUID id,
            UUID boardId,
            UUID columnId,
            String title,
            String description,
            TaskPriority priority,
            LocalDate dueDate,
            UUID assigneeId,
            boolean completed,
            OffsetDateTime completedAt,
            double position,
            UUID createdById,
            List<SubtaskResponse> subtasks,
            List<UUID> tagIds,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record TaskPositionItem(
            UUID id,
            double position
    ) {
    }

    public record TaskMoveResponse(
            TaskResponse task,
            boolean rebalanced,
            List<TaskPositionItem> columnPositions
    ) {
    }

    public record MyTaskResponse(
            UUID id,
            UUID boardId,
            String boardName,
            String boardColor,
            UUID columnId,
            String columnName,
            String title,
            TaskPriority priority,
            LocalDate dueDate,
            boolean completed,
            int subtasksTotal,
            int subtasksDone
    ) {
    }

    public record CreateTaskRequest(
            UUID columnId,

            @NotBlank(message = "Título da tarefa é obrigatório")
            @Size(max = 200, message = "Título da tarefa deve ter no máximo 200 caracteres")
            String title,

            String description,
            TaskPriority priority,
            LocalDate dueDate,
            UUID assigneeId,
            List<UUID> tagIds
    ) {
        public CreateTaskRequest {
            title = title != null ? title.trim() : null;
            description = description != null ? description.trim() : null;
        }
    }

    public static final class UpdateTaskRequest {

        @Size(min = 1, max = 200, message = "Título da tarefa deve ter entre 1 e 200 caracteres")
        private String title;

        private String description;
        private boolean descriptionProvided;

        private UUID columnId;
        private TaskPriority priority;

        private LocalDate dueDate;
        private boolean dueDateProvided;
        private Boolean clearDueDate;

        private UUID assigneeId;
        private boolean assigneeIdProvided;
        private Boolean clearAssignee;

        private List<UUID> tagIds;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title != null ? title.trim() : null;
        }

        public String getDescription() {
            return description;
        }

        @JsonSetter("description")
        public void setDescription(String description) {
            this.description = description != null ? description.trim() : null;
            this.descriptionProvided = true;
        }

        @JsonIgnore
        public boolean isDescriptionProvided() {
            return descriptionProvided;
        }

        public UUID getColumnId() {
            return columnId;
        }

        public void setColumnId(UUID columnId) {
            this.columnId = columnId;
        }

        public TaskPriority getPriority() {
            return priority;
        }

        public void setPriority(TaskPriority priority) {
            this.priority = priority;
        }

        public LocalDate getDueDate() {
            return dueDate;
        }

        @JsonSetter("dueDate")
        public void setDueDate(LocalDate dueDate) {
            this.dueDate = dueDate;
            this.dueDateProvided = true;
        }

        @JsonIgnore
        public boolean isDueDateProvided() {
            return dueDateProvided;
        }

        public Boolean getClearDueDate() {
            return clearDueDate;
        }

        public void setClearDueDate(Boolean clearDueDate) {
            this.clearDueDate = clearDueDate;
        }

        public UUID getAssigneeId() {
            return assigneeId;
        }

        @JsonSetter("assigneeId")
        public void setAssigneeId(UUID assigneeId) {
            this.assigneeId = assigneeId;
            this.assigneeIdProvided = true;
        }

        @JsonIgnore
        public boolean isAssigneeIdProvided() {
            return assigneeIdProvided;
        }

        public Boolean getClearAssignee() {
            return clearAssignee;
        }

        public void setClearAssignee(Boolean clearAssignee) {
            this.clearAssignee = clearAssignee;
        }

        public List<UUID> getTagIds() {
            return tagIds;
        }

        public void setTagIds(List<UUID> tagIds) {
            this.tagIds = tagIds;
        }
    }

    public record MoveTaskRequest(
            @NotNull(message = "Coluna de destino é obrigatória")
            UUID targetColumnId,

            @NotNull(message = "Posição é obrigatória")
            Double position
    ) {
    }

    public record CreateSubtaskRequest(
            @NotBlank(message = "Título da subtarefa é obrigatório")
            @Size(max = 200, message = "Título da subtarefa deve ter no máximo 200 caracteres")
            String title
    ) {
        public CreateSubtaskRequest {
            title = title != null ? title.trim() : null;
        }
    }

    public record UpdateSubtaskRequest(
            @Size(min = 1, max = 200, message = "Título da subtarefa deve ter entre 1 e 200 caracteres")
            String title,
            Boolean done,
            Double position
    ) {
        public UpdateSubtaskRequest {
            title = title != null ? title.trim() : null;
        }
    }

    public record CreateTagRequest(
            @NotBlank(message = "Nome da tag é obrigatório")
            @Size(max = 40, message = "Nome da tag deve ter no máximo 40 caracteres")
            String name,

            @Pattern(
                    regexp = "^#[0-9A-Fa-f]{6}$",
                    message = "Cor da tag deve estar no formato hexadecimal #RRGGBB"
            )
            String color
    ) {
        public CreateTagRequest {
            name = name != null ? name.trim() : null;
            color = color != null ? color.trim() : null;
        }
    }

    public record UpdateTagRequest(
            @Size(min = 1, max = 40, message = "Nome da tag deve ter entre 1 e 40 caracteres")
            String name,

            @Pattern(
                    regexp = "^#[0-9A-Fa-f]{6}$",
                    message = "Cor da tag deve estar no formato hexadecimal #RRGGBB"
            )
            String color
    ) {
        public UpdateTagRequest {
            name = name != null ? name.trim() : null;
            color = color != null ? color.trim() : null;
        }
    }
}
