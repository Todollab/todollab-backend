package com.todollab.task.controller;

import com.todollab.auth.security.UserPrincipal;
import com.todollab.task.dto.TaskDtos.CreateTagRequest;
import com.todollab.task.dto.TaskDtos.TagResponse;
import com.todollab.task.dto.TaskDtos.UpdateTagRequest;
import com.todollab.task.service.BoardTagService;
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
@RequestMapping("/api/v1/boards/{boardId}/tags")
public class BoardTagController {

    private final BoardTagService boardTagService;

    public BoardTagController(BoardTagService boardTagService) {
        this.boardTagService = boardTagService;
    }

    @PostMapping
    public ResponseEntity<TagResponse> createTag(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @Valid @RequestBody CreateTagRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(boardTagService.createTag(boardId, principal.id(), request));
    }

    @PatchMapping("/{tagId}")
    public ResponseEntity<TagResponse> updateTag(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID tagId,
            @Valid @RequestBody UpdateTagRequest request
    ) {
        return ResponseEntity.ok(boardTagService.updateTag(boardId, tagId, principal.id(), request));
    }

    @DeleteMapping("/{tagId}")
    public ResponseEntity<Void> deleteTag(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID tagId
    ) {
        boardTagService.deleteTag(boardId, tagId, principal.id());
        return ResponseEntity.noContent().build();
    }
}
