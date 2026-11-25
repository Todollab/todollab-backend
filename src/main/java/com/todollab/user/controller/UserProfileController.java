package com.todollab.user.controller;

import com.todollab.auth.security.UserPrincipal;
import com.todollab.task.dto.TaskDtos.MyTaskResponse;
import com.todollab.task.model.TaskPriority;
import com.todollab.task.service.TaskService;
import com.todollab.user.dto.UserProfileDtos.ChangePasswordRequest;
import com.todollab.user.dto.UserProfileDtos.UpdateProfileRequest;
import com.todollab.user.dto.UserProfileDtos.UserProfileResponse;
import com.todollab.user.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final TaskService taskService;

    public UserProfileController(UserProfileService userProfileService, TaskService taskService) {
        this.userProfileService = userProfileService;
        this.taskService = taskService;
    }

    @GetMapping
    public ResponseEntity<UserProfileResponse> getProfile(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(userProfileService.getProfile(principal.id()));
    }

    @GetMapping("/tasks")
    public ResponseEntity<List<MyTaskResponse>> getMyTasks(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Boolean completed,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(required = false) String due
    ) {
        return ResponseEntity.ok(taskService.getMyTasks(principal.id(), completed, priority, due));
    }

    @PatchMapping
    public ResponseEntity<UserProfileResponse> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ResponseEntity.ok(userProfileService.updateProfile(principal.id(), request));
    }

    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        userProfileService.changePassword(principal.id(), request);
        return ResponseEntity.noContent().build();
    }
}
