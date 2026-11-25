package com.todollab.invite.controller;

import com.todollab.auth.security.UserPrincipal;
import com.todollab.invite.dto.InviteDtos.BoardInviteResponse;
import com.todollab.invite.dto.InviteDtos.CreateEmailInviteRequest;
import com.todollab.invite.dto.InviteDtos.CreateLinkInviteRequest;
import com.todollab.invite.service.BoardInviteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/boards/{boardId}/invites")
public class BoardInviteController {

    private final BoardInviteService boardInviteService;

    public BoardInviteController(BoardInviteService boardInviteService) {
        this.boardInviteService = boardInviteService;
    }

    @GetMapping
    public ResponseEntity<List<BoardInviteResponse>> listInvites(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId
    ) {
        return ResponseEntity.ok(boardInviteService.listBoardInvites(boardId, principal.id()));
    }

    @PostMapping("/email")
    public ResponseEntity<BoardInviteResponse> createEmailInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @Valid @RequestBody CreateEmailInviteRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(boardInviteService.createEmailInvite(boardId, principal.id(), request));
    }

    @PostMapping("/link")
    public ResponseEntity<BoardInviteResponse> createLinkInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @Valid @RequestBody CreateLinkInviteRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(boardInviteService.createLinkInvite(boardId, principal.id(), request));
    }

    @DeleteMapping("/{inviteId}")
    public ResponseEntity<Void> revokeInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @PathVariable UUID inviteId
    ) {
        boardInviteService.revokeInvite(boardId, principal.id(), inviteId);
        return ResponseEntity.noContent().build();
    }
}
