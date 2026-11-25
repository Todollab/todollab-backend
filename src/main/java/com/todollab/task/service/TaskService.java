package com.todollab.task.service;

import com.todollab.auth.model.User;
import com.todollab.auth.repository.UserRepository;
import com.todollab.board.model.BoardColumn;
import com.todollab.board.model.BoardMember;
import com.todollab.board.repository.BoardColumnRepository;
import com.todollab.board.service.BoardAccessService;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.common.util.PositionService;
import com.todollab.realtime.BoardDomainEvent;
import com.todollab.realtime.BoardEventType;
import com.todollab.task.dto.TaskDtos.CreateTaskRequest;
import com.todollab.task.dto.TaskDtos.MoveTaskRequest;
import com.todollab.task.dto.TaskDtos.MyTaskResponse;
import com.todollab.task.dto.TaskDtos.SubtaskResponse;
import com.todollab.task.dto.TaskDtos.TaskMoveResponse;
import com.todollab.task.dto.TaskDtos.TaskPositionItem;
import com.todollab.task.dto.TaskDtos.TaskResponse;
import com.todollab.task.dto.TaskDtos.UpdateTaskRequest;
import com.todollab.task.model.BoardTag;
import com.todollab.task.model.Task;
import com.todollab.task.model.TaskPriority;
import com.todollab.task.repository.BoardTagRepository;
import com.todollab.task.repository.TaskRepository;
import com.todollab.task.repository.TaskSubtaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskSubtaskRepository taskSubtaskRepository;
    private final BoardTagRepository boardTagRepository;
    private final BoardColumnRepository boardColumnRepository;
    private final UserRepository userRepository;
    private final BoardAccessService boardAccessService;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    public TaskService(
            TaskRepository taskRepository,
            TaskSubtaskRepository taskSubtaskRepository,
            BoardTagRepository boardTagRepository,
            BoardColumnRepository boardColumnRepository,
            UserRepository userRepository,
            BoardAccessService boardAccessService,
            EntityManager entityManager,
            ApplicationEventPublisher eventPublisher
    ) {
        this.taskRepository = taskRepository;
        this.taskSubtaskRepository = taskSubtaskRepository;
        this.boardTagRepository = boardTagRepository;
        this.boardColumnRepository = boardColumnRepository;
        this.userRepository = userRepository;
        this.boardAccessService = boardAccessService;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TaskResponse createTask(UUID boardId, UUID userId, CreateTaskRequest request) {
        BoardMember membership = boardAccessService.requireEditor(boardId, userId);

        BoardColumn column = resolveTargetColumnForCreate(boardId, request.columnId());
        User assignee = resolveAssigneeIfPresent(boardId, request.assigneeId());
        Set<BoardTag> tags = resolveTagsForBoard(boardId, request.tagIds());

        double position = PositionService.appendPosition(taskRepository.findMaxPositionByColumnId(column.getId()));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean completed = column.isDoneColumn();
        OffsetDateTime completedAt = completed ? now : null;
        String description = request.description() != null && !request.description().isBlank()
                ? request.description()
                : null;

        Task task = new Task(
                membership.getBoard(),
                column,
                request.title(),
                description,
                request.priority() != null ? request.priority() : TaskPriority.MEDIUM,
                request.dueDate(),
                assignee,
                completed,
                completedAt,
                position,
                userId
        );
        task.replaceTags(tags);

        Task saved = taskRepository.saveAndFlush(task);
        TaskResponse response = toTaskResponse(saved, List.of());
        eventPublisher.publishEvent(BoardDomainEvent.of(BoardEventType.TASK_CREATED, boardId, userId, response));
        return response;
    }

    @Transactional
    public TaskResponse updateTask(UUID boardId, UUID userId, UUID taskId, UpdateTaskRequest request) {
        boardAccessService.requireEditor(boardId, userId);
        Task task = findTaskOrThrow(taskId, boardId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        if (request.getTitle() != null) {
            task.setTitle(request.getTitle());
        }
        if (request.isDescriptionProvided()) {
            String desc = request.getDescription();
            task.setDescription(desc != null && !desc.isBlank() ? desc : null);
        }
        if (request.getPriority() != null) {
            task.setPriority(request.getPriority());
        }
        if (Boolean.TRUE.equals(request.getClearDueDate())) {
            task.setDueDate(null);
        } else if (request.isDueDateProvided()) {
            task.setDueDate(request.getDueDate());
        }
        if (Boolean.TRUE.equals(request.getClearAssignee())) {
            task.setAssignee(null);
        } else if (request.isAssigneeIdProvided()) {
            task.setAssignee(resolveAssigneeIfPresent(boardId, request.getAssigneeId()));
        }
        if (request.getTagIds() != null) {
            task.replaceTags(resolveTagsForBoard(boardId, request.getTagIds()));
        }
        if (request.getColumnId() != null && !request.getColumnId().equals(task.getColumn().getId())) {
            BoardColumn targetColumn = findColumnOrThrow(request.getColumnId(), boardId);
            double newPos = PositionService.appendPosition(taskRepository.findMaxPositionByColumnId(targetColumn.getId()));
            task.setColumn(targetColumn);
            task.setPosition(newPos);
            syncCompletionWithColumn(task, targetColumn, now);
        }

        task.setUpdatedAt(now);
        Task saved = taskRepository.saveAndFlush(task);
        List<SubtaskResponse> subtasks = loadSubtasksForTask(saved.getId());
        TaskResponse response = toTaskResponse(saved, subtasks);
        eventPublisher.publishEvent(BoardDomainEvent.of(BoardEventType.TASK_UPDATED, boardId, userId, response));
        return response;
    }

    @Transactional
    public TaskMoveResponse moveTask(UUID boardId, UUID userId, UUID taskId, MoveTaskRequest request) {
        boardAccessService.requireEditor(boardId, userId);
        if (!Double.isFinite(request.position())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_ERROR,
                    "Posição deve ser um número válido."
            );
        }

        Task task = findTaskOrThrow(taskId, boardId);
        BoardColumn targetColumn = findColumnOrThrow(request.targetColumnId(), boardId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        List<Task> columnTasks = new ArrayList<>(taskRepository.findAllByColumnIdOrderByPositionAsc(targetColumn.getId()));
        if (columnTasks.stream().noneMatch(t -> t.getId().equals(taskId))) {
            columnTasks.add(task);
        }

        task.setColumn(targetColumn);
        task.setPosition(request.position());
        task.setUpdatedAt(now);
        syncCompletionWithColumn(task, targetColumn, now);

        columnTasks.sort(
                Comparator.comparingDouble(Task::getPosition)
                        .thenComparing(t -> t.getId().equals(taskId) ? 0 : 1)
                        .thenComparing(Task::getCreatedAt)
                        .thenComparing(Task::getId)
        );

        List<Double> positions = columnTasks.stream().map(Task::getPosition).toList();
        boolean rebalanced = PositionService.needsRebalance(positions);
        if (rebalanced) {
            List<Double> rebalancedPositions = PositionService.rebalancePositions(columnTasks.size());
            for (int i = 0; i < columnTasks.size(); i++) {
                columnTasks.get(i).setPosition(rebalancedPositions.get(i));
                columnTasks.get(i).setUpdatedAt(now);
            }
            taskRepository.saveAllAndFlush(columnTasks);
        } else {
            taskRepository.saveAndFlush(task);
        }

        List<TaskPositionItem> columnPositions = columnTasks.stream()
                .map(t -> new TaskPositionItem(t.getId(), t.getPosition()))
                .toList();

        TaskResponse taskResponse = toTaskResponse(task, loadSubtasksForTask(task.getId()));
        TaskMoveResponse response = new TaskMoveResponse(taskResponse, rebalanced, columnPositions);
        BoardEventType eventType = rebalanced ? BoardEventType.COLUMN_REORDERED : BoardEventType.TASK_MOVED;
        eventPublisher.publishEvent(BoardDomainEvent.of(eventType, boardId, userId, response));
        return response;
    }

    @Transactional
    public TaskResponse toggleComplete(UUID boardId, UUID userId, UUID taskId) {
        boardAccessService.requireEditor(boardId, userId);
        Task task = findTaskOrThrow(taskId, boardId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        boolean newCompleted = !task.isCompleted();
        List<BoardColumn> columns = boardColumnRepository.findAllByBoardIdOrderByPositionAsc(boardId);

        if (newCompleted) {
            task.setCompleted(true);
            task.setCompletedAt(now);
            if (!task.getColumn().isDoneColumn()) {
                columns.stream()
                        .filter(BoardColumn::isDoneColumn)
                        .findFirst()
                        .ifPresent(doneColumn -> moveToEndOfColumn(task, doneColumn));
            }
        } else {
            task.setCompleted(false);
            task.setCompletedAt(null);
            if (task.getColumn().isDoneColumn()) {
                columns.stream()
                        .filter(col -> !col.isDoneColumn())
                        .findFirst()
                        .ifPresent(openColumn -> moveToEndOfColumn(task, openColumn));
            }
        }

        task.setUpdatedAt(now);
        Task saved = taskRepository.saveAndFlush(task);
        TaskResponse response = toTaskResponse(saved, loadSubtasksForTask(saved.getId()));
        eventPublisher.publishEvent(BoardDomainEvent.of(BoardEventType.TASK_UPDATED, boardId, userId, response));
        return response;
    }

    @Transactional
    public void deleteTask(UUID boardId, UUID userId, UUID taskId) {
        boardAccessService.requireEditor(boardId, userId);
        findTaskOrThrow(taskId, boardId);
        taskRepository.deleteTaskByIdAndBoardId(taskId, boardId);
        eventPublisher.publishEvent(BoardDomainEvent.of(
                BoardEventType.TASK_DELETED,
                boardId,
                userId,
                Map.of("taskId", taskId)
        ));
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> listTasksForBoard(UUID boardId) {
        List<Task> tasks = taskRepository.findAllByBoardIdOrderByPositionAsc(boardId);
        if (tasks.isEmpty()) {
            return List.of();
        }

        List<UUID> taskIds = tasks.stream().map(Task::getId).toList();
        Map<UUID, List<SubtaskResponse>> subtasksByTaskId = taskSubtaskRepository
                .findAllByTaskIdInOrderByPositionAsc(taskIds)
                .stream()
                .collect(Collectors.groupingBy(
                        s -> s.getTask().getId(),
                        Collectors.mapping(SubtaskResponse::from, Collectors.toList())
                ));

        return tasks.stream()
                .map(task -> toTaskResponse(task, subtasksByTaskId.getOrDefault(task.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyTaskResponse> getMyTasks(
            UUID userId,
            Boolean completed,
            TaskPriority priority,
            String due
    ) {
        String dueFilter = due != null && !due.isBlank() ? due.trim().toLowerCase(Locale.ROOT) : null;
        if (dueFilter != null
                && !dueFilter.equals("overdue")
                && !dueFilter.equals("today")
                && !dueFilter.equals("upcoming")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_ERROR,
                    "Filtro 'due' deve ser overdue, today ou upcoming."
            );
        }

        StringBuilder jpql = new StringBuilder("""
                SELECT t FROM Task t
                JOIN FETCH t.board b
                JOIN FETCH t.column c
                WHERE t.assignee.id = :userId
                  AND EXISTS (
                      SELECT 1 FROM BoardMember bm
                      WHERE bm.board.id = b.id AND bm.user.id = :userId
                  )
                """);
        if (completed != null) {
            jpql.append(" AND t.completed = :completed");
        }
        if (priority != null) {
            jpql.append(" AND t.priority = :priority");
        }
        if ("overdue".equals(dueFilter)) {
            jpql.append(" AND t.dueDate IS NOT NULL AND t.dueDate < :today");
        } else if ("today".equals(dueFilter)) {
            jpql.append(" AND t.dueDate = :today");
        } else if ("upcoming".equals(dueFilter)) {
            jpql.append(" AND t.dueDate IS NOT NULL AND t.dueDate > :today");
        }
        jpql.append(" ORDER BY t.dueDate ASC NULLS LAST, t.createdAt DESC, t.id DESC");

        TypedQuery<Task> query = entityManager.createQuery(jpql.toString(), Task.class);
        query.setParameter("userId", userId);
        if (completed != null) {
            query.setParameter("completed", completed);
        }
        if (priority != null) {
            query.setParameter("priority", priority);
        }
        if (dueFilter != null) {
            query.setParameter("today", LocalDate.now(ZoneOffset.UTC));
        }

        List<Task> tasks = query.getResultList();
        if (tasks.isEmpty()) {
            return List.of();
        }

        List<UUID> taskIds = tasks.stream().map(Task::getId).toList();
        Map<UUID, int[]> subtaskCounts = new HashMap<>();
        for (Object[] row : taskSubtaskRepository.countSubtasksGroupedByTaskAndDone(taskIds)) {
            UUID taskId = (UUID) row[0];
            boolean done = (Boolean) row[1];
            int count = ((Number) row[2]).intValue();
            int[] counts = subtaskCounts.computeIfAbsent(taskId, ignored -> new int[2]);
            counts[0] += count;
            if (done) {
                counts[1] += count;
            }
        }

        return tasks.stream()
                .map(task -> {
                    int[] counts = subtaskCounts.getOrDefault(task.getId(), new int[2]);
                    return new MyTaskResponse(
                            task.getId(),
                            task.getBoard().getId(),
                            task.getBoard().getName(),
                            task.getBoard().getColor(),
                            task.getColumn().getId(),
                            task.getColumn().getName(),
                            task.getTitle(),
                            task.getPriority(),
                            task.getDueDate(),
                            task.isCompleted(),
                            counts[0],
                            counts[1]
                    );
                })
                .toList();
    }

    public TaskResponse toTaskResponse(Task task, List<SubtaskResponse> subtasks) {
        List<UUID> tagIds = task.getTags().stream()
                .sorted(Comparator.comparing(BoardTag::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(BoardTag::getId))
                .map(BoardTag::getId)
                .toList();

        return new TaskResponse(
                task.getId(),
                task.getBoard().getId(),
                task.getColumn().getId(),
                task.getTitle(),
                task.getDescription(),
                task.getPriority(),
                task.getDueDate(),
                task.getAssignee() != null ? task.getAssignee().getId() : null,
                task.isCompleted(),
                task.getCompletedAt(),
                task.getPosition(),
                task.getCreatedById(),
                subtasks,
                tagIds,
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private List<SubtaskResponse> loadSubtasksForTask(UUID taskId) {
        return taskSubtaskRepository.findAllByTaskIdOrderByPositionAsc(taskId).stream()
                .map(SubtaskResponse::from)
                .toList();
    }

    private BoardColumn resolveTargetColumnForCreate(UUID boardId, UUID columnId) {
        if (columnId != null) {
            return findColumnOrThrow(columnId, boardId);
        }
        List<BoardColumn> columns = boardColumnRepository.findAllByBoardIdOrderByPositionAsc(boardId);
        if (columns.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    ErrorCode.COLUMN_NOT_FOUND,
                    "Coluna não encontrada."
            );
        }
        return columns.stream()
                .filter(col -> !col.isDoneColumn())
                .findFirst()
                .orElse(columns.get(0));
    }

    private User resolveAssigneeIfPresent(UUID boardId, UUID assigneeId) {
        if (assigneeId == null) {
            return null;
        }
        if (!boardAccessService.isMember(boardId, assigneeId)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.ASSIGNEE_NOT_MEMBER,
                    "O responsável selecionado deve ser membro do quadro."
            );
        }
        return userRepository.findById(assigneeId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.ASSIGNEE_NOT_MEMBER,
                        "O responsável selecionado deve ser membro do quadro."
                ));
    }

    private Set<BoardTag> resolveTagsForBoard(UUID boardId, List<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> distinctIds = new LinkedHashSet<>(tagIds);
        List<BoardTag> foundTags = boardTagRepository.findAllByIdInAndBoardId(distinctIds, boardId);
        if (foundTags.size() != distinctIds.size()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    ErrorCode.TAG_NOT_FOUND,
                    "Tag não encontrada neste quadro."
            );
        }
        return new LinkedHashSet<>(foundTags);
    }

    private void moveToEndOfColumn(Task task, BoardColumn targetColumn) {
        double newPos = PositionService.appendPosition(taskRepository.findMaxPositionByColumnId(targetColumn.getId()));
        task.setColumn(targetColumn);
        task.setPosition(newPos);
    }

    private void syncCompletionWithColumn(Task task, BoardColumn targetColumn, OffsetDateTime now) {
        if (targetColumn.isDoneColumn()) {
            task.setCompleted(true);
            if (task.getCompletedAt() == null) {
                task.setCompletedAt(now);
            }
        } else {
            task.setCompleted(false);
            task.setCompletedAt(null);
        }
    }

    private Task findTaskOrThrow(UUID taskId, UUID boardId) {
        return taskRepository.findByIdAndBoardId(taskId, boardId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.TASK_NOT_FOUND,
                        "Tarefa não encontrada."
                ));
    }

    private BoardColumn findColumnOrThrow(UUID columnId, UUID boardId) {
        return boardColumnRepository.findByIdAndBoardId(columnId, boardId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.COLUMN_NOT_FOUND,
                        "Coluna não encontrada."
                ));
    }
}
