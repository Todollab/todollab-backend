package com.todollab.task.controller;

import com.todollab.auth.security.UserPrincipal;
import com.todollab.task.dto.TaskDtos.CreateSubtaskRequest;
import com.todollab.task.dto.TaskDtos.SubtaskResponse;
import com.todollab.task.dto.TaskDtos.UpdateSubtaskRequest;
import com.todollab.task.service.TaskSubtaskService;
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
@RequestMapping("/api/v1/boards/{boardId}/tasks/{taskId}/subtasks")
public class TaskSubtaskController {

    private final TaskSubtaskService taskSubtaskService;

    public TaskSubtaskController(TaskSubtaskService taskSubtaskService) {
        this.taskSubtaskService = taskSubtaskService;
    }

    @PostMapping
    public ResponseEntity<SubtaskResponse> createSubtask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID taskId,
            @Valid @RequestBody CreateSubtaskRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskSubtaskService.createSubtask(boardId, taskId, principal.id(), request));
    }

    @PatchMapping("/{subtaskId}")
    public ResponseEntity<SubtaskResponse> updateSubtask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID taskId,
            @PathVariable UUID subtaskId,
            @Valid @RequestBody UpdateSubtaskRequest request
    ) {
        return ResponseEntity.ok(taskSubtaskService.updateSubtask(boardId, taskId, subtaskId, principal.id(), request));
    }

    @DeleteMapping("/{subtaskId}")
    public ResponseEntity<Void> deleteSubtask(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID taskId,
            @PathVariable UUID subtaskId
    ) {
        taskSubtaskService.deleteSubtask(boardId, taskId, subtaskId, principal.id());
        return ResponseEntity.noContent().build();
    }
}
