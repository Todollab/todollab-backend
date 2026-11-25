package com.todollab.task.service;

import com.todollab.board.service.BoardAccessService;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.common.util.PositionService;
import com.todollab.task.dto.TaskDtos.CreateSubtaskRequest;
import com.todollab.task.dto.TaskDtos.SubtaskResponse;
import com.todollab.task.dto.TaskDtos.UpdateSubtaskRequest;
import com.todollab.task.model.Task;
import com.todollab.task.model.TaskSubtask;
import com.todollab.task.repository.TaskRepository;
import com.todollab.task.repository.TaskSubtaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class TaskSubtaskService {

    private final TaskRepository taskRepository;
    private final TaskSubtaskRepository taskSubtaskRepository;
    private final BoardAccessService boardAccessService;

    public TaskSubtaskService(
            TaskRepository taskRepository,
            TaskSubtaskRepository taskSubtaskRepository,
            BoardAccessService boardAccessService
    ) {
        this.taskRepository = taskRepository;
        this.taskSubtaskRepository = taskSubtaskRepository;
        this.boardAccessService = boardAccessService;
    }

    @Transactional
    public SubtaskResponse createSubtask(UUID boardId, UUID taskId, UUID userId, CreateSubtaskRequest request) {
        boardAccessService.requireEditor(boardId, userId);
        Task task = findTaskOrThrow(taskId, boardId);

        double position = PositionService.appendPosition(taskSubtaskRepository.findMaxPositionByTaskId(taskId));
        TaskSubtask subtask = new TaskSubtask(task, request.title(), position);
        return SubtaskResponse.from(taskSubtaskRepository.saveAndFlush(subtask));
    }

    @Transactional
    public SubtaskResponse updateSubtask(
            UUID boardId,
            UUID taskId,
            UUID subtaskId,
            UUID userId,
            UpdateSubtaskRequest request
    ) {
        boardAccessService.requireEditor(boardId, userId);
        findTaskOrThrow(taskId, boardId);

        TaskSubtask subtask = taskSubtaskRepository.findByIdAndTaskIdAndBoardId(subtaskId, taskId, boardId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.SUBTASK_NOT_FOUND,
                        "Subtarefa não encontrada."
                ));

        if (request.title() != null) {
            subtask.setTitle(request.title());
        }
        if (request.done() != null) {
            subtask.setDone(request.done());
        }
        if (request.position() != null) {
            if (!Double.isFinite(request.position())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.VALIDATION_ERROR,
                        "Posição deve ser um número válido."
                );
            }
            subtask.setPosition(request.position());

            List<TaskSubtask> subtasks = new ArrayList<>(taskSubtaskRepository.findAllByTaskIdOrderByPositionAsc(taskId));
            subtasks.sort(
                    Comparator.comparingDouble(TaskSubtask::getPosition)
                            .thenComparing(s -> s.getId().equals(subtaskId) ? 0 : 1)
                            .thenComparing(TaskSubtask::getCreatedAt)
                            .thenComparing(TaskSubtask::getId)
            );
            List<Double> positions = subtasks.stream().map(TaskSubtask::getPosition).toList();
            if (PositionService.needsRebalance(positions)) {
                List<Double> rebalanced = PositionService.rebalancePositions(subtasks.size());
                for (int i = 0; i < subtasks.size(); i++) {
                    subtasks.get(i).setPosition(rebalanced.get(i));
                }
                taskSubtaskRepository.saveAllAndFlush(subtasks);
                return SubtaskResponse.from(subtask);
            }
        }

        return SubtaskResponse.from(taskSubtaskRepository.saveAndFlush(subtask));
    }

    @Transactional
    public void deleteSubtask(UUID boardId, UUID taskId, UUID subtaskId, UUID userId) {
        boardAccessService.requireEditor(boardId, userId);
        findTaskOrThrow(taskId, boardId);

        TaskSubtask subtask = taskSubtaskRepository.findByIdAndTaskIdAndBoardId(subtaskId, taskId, boardId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.SUBTASK_NOT_FOUND,
                        "Subtarefa não encontrada."
                ));
        taskSubtaskRepository.delete(subtask);
        taskSubtaskRepository.flush();
    }

    private Task findTaskOrThrow(UUID taskId, UUID boardId) {
        return taskRepository.findByIdAndBoardId(taskId, boardId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.TASK_NOT_FOUND,
                        "Tarefa não encontrada."
                ));
    }
}
