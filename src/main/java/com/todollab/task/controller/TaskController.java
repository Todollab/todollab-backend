package com.todollab.task.controller;

import com.todollab.auth.security.UserPrincipal;
import com.todollab.task.dto.TaskDtos.CreateTaskRequest;
import com.todollab.task.dto.TaskDtos.MoveTaskRequest;
import com.todollab.task.dto.TaskDtos.TaskMoveResponse;
import com.todollab.task.dto.TaskDtos.TaskResponse;
import com.todollab.task.dto.TaskDtos.UpdateTaskRequest;
import com.todollab.task.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/boards/{boardId}/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @Valid @RequestBody CreateTaskRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskService.createTask(boardId, principal.id(), request));
    }

    @PatchMapping("/{taskId}")
    public ResponseEntity<TaskResponse> updateTask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID taskId,
            @Valid @RequestBody UpdateTaskRequest request
    ) {
        return ResponseEntity.ok(taskService.updateTask(boardId, principal.id(), taskId, request));
    }

    @PatchMapping("/{taskId}/move")
    public ResponseEntity<TaskMoveResponse> moveTask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID taskId,
            @Valid @RequestBody MoveTaskRequest request
    ) {
        return ResponseEntity.ok(taskService.moveTask(boardId, principal.id(), taskId, request));
    }

    @PatchMapping("/{taskId}/toggle-complete")
    public ResponseEntity<TaskResponse> toggleComplete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID taskId
    ) {
        return ResponseEntity.ok(taskService.toggleComplete(boardId, principal.id(), taskId));
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> deleteTask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID taskId
    ) {
        taskService.deleteTask(boardId, principal.id(), taskId);
        return ResponseEntity.noContent().build();
    }
}
