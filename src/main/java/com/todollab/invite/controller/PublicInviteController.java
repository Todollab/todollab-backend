package com.todollab.invite.controller;

import com.todollab.auth.security.UserPrincipal;
import com.todollab.invite.dto.InviteDtos.AcceptInviteResponse;
import com.todollab.invite.dto.InviteDtos.InvitePreviewResponse;
import com.todollab.invite.service.BoardInviteService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invites")
public class PublicInviteController {

    private final BoardInviteService boardInviteService;

    public PublicInviteController(BoardInviteService boardInviteService) {
        this.boardInviteService = boardInviteService;
    }

    @GetMapping("/{token}/preview")
    public ResponseEntity<InvitePreviewResponse> previewInvite(@PathVariable String token) {
        return ResponseEntity.ok(boardInviteService.previewInvite(token));
    }

    @PostMapping("/{token}/accept")
    public ResponseEntity<AcceptInviteResponse> acceptInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String token
    ) {
        return ResponseEntity.ok(boardInviteService.acceptInvite(token, principal.id()));
    }

    @PostMapping("/{token}/decline")
    public ResponseEntity<Void> declineInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String token
    ) {
        boardInviteService.declineInvite(token, principal.id());
        return ResponseEntity.noContent().build();
    }
}
