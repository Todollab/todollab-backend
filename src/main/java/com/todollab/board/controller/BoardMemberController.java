package com.todollab.board.controller;

import com.todollab.auth.security.UserPrincipal;
import com.todollab.board.dto.BoardDtos.BoardMemberResponse;
import com.todollab.board.dto.BoardDtos.TransferOwnershipRequest;
import com.todollab.board.dto.BoardDtos.UpdateMemberRoleRequest;
import com.todollab.board.service.BoardMemberService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/boards/{boardId}")
public class BoardMemberController {

    private final BoardMemberService boardMemberService;

    public BoardMemberController(BoardMemberService boardMemberService) {
        this.boardMemberService = boardMemberService;
    }

    @GetMapping("/members")
    public ResponseEntity<List<BoardMemberResponse>> listMembers(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId
    ) {
        return ResponseEntity.ok(boardMemberService.listMembers(boardId, principal.id()));
    }

    @PatchMapping("/members/{userId}")
    public ResponseEntity<BoardMemberResponse> updateMemberRole(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateMemberRoleRequest request
    ) {
        return ResponseEntity.ok(boardMemberService.updateMemberRole(boardId, principal.id(), userId, request));
    }

    @DeleteMapping("/members/{userId}")
    public ResponseEntity<Void> removeOrLeaveMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID userId
    ) {
        boardMemberService.removeOrLeaveMember(boardId, principal.id(), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/transfer-ownership")
    public ResponseEntity<List<BoardMemberResponse>> transferOwnership(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @Valid @RequestBody TransferOwnershipRequest request
    ) {
        return ResponseEntity.ok(boardMemberService.transferOwnership(boardId, principal.id(), request));
    }
}
