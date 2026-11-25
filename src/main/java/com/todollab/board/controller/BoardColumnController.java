package com.todollab.board.controller;

import com.todollab.auth.security.UserPrincipal;
import com.todollab.board.dto.BoardDtos.ColumnResponse;
import com.todollab.board.dto.BoardDtos.CreateColumnRequest;
import com.todollab.board.dto.BoardDtos.MoveColumnRequest;
import com.todollab.board.dto.BoardDtos.UpdateColumnRequest;
import com.todollab.board.service.BoardColumnService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/boards/{boardId}/columns")
public class BoardColumnController {

    private final BoardColumnService boardColumnService;

    public BoardColumnController(BoardColumnService boardColumnService) {
        this.boardColumnService = boardColumnService;
    }

    @PostMapping
    public ResponseEntity<ColumnResponse> createColumn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @Valid @RequestBody CreateColumnRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(boardColumnService.createColumn(boardId, principal.id(), request));
    }

    @PatchMapping("/{columnId}")
    public ResponseEntity<ColumnResponse> updateColumn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID columnId,
            @Valid @RequestBody UpdateColumnRequest request
    ) {
        return ResponseEntity.ok(boardColumnService.updateColumn(boardId, principal.id(), columnId, request));
    }

    @PatchMapping("/{columnId}/move")
    public ResponseEntity<List<ColumnResponse>> moveColumn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID columnId,
            @Valid @RequestBody MoveColumnRequest request
    ) {
        return ResponseEntity.ok(boardColumnService.moveColumn(boardId, principal.id(), columnId, request));
    }

    @DeleteMapping("/{columnId}")
    public ResponseEntity<Void> deleteColumn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID columnId
    ) {
        boardColumnService.deleteColumn(boardId, principal.id(), columnId);
        return ResponseEntity.noContent().build();
    }
}
