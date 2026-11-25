package com.todollab.board.controller;

import com.todollab.auth.security.UserPrincipal;
import com.todollab.board.dto.BoardDtos.BoardDetailResponse;
import com.todollab.board.dto.BoardDtos.BoardResponse;
import com.todollab.board.dto.BoardDtos.BoardSummaryResponse;
import com.todollab.board.dto.BoardDtos.CreateBoardRequest;
import com.todollab.board.dto.BoardDtos.FavoriteBoardRequest;
import com.todollab.board.dto.BoardDtos.FavoriteBoardResponse;
import com.todollab.board.dto.BoardDtos.UpdateBoardRequest;
import com.todollab.board.service.BoardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/boards")
public class BoardController {

    private final BoardService boardService;

    public BoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @GetMapping
    public ResponseEntity<List<BoardSummaryResponse>> listBoards(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(boardService.listBoards(principal.id()));
    }

    @PostMapping
    public ResponseEntity<BoardDetailResponse> createBoard(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateBoardRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(boardService.createBoard(principal.id(), request));
    }

    @GetMapping("/{boardId}")
    public ResponseEntity<BoardDetailResponse> getBoard(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId
    ) {
        return ResponseEntity.ok(boardService.getBoard(boardId, principal.id()));
    }

    @PatchMapping("/{boardId}")
    public ResponseEntity<BoardResponse> updateBoard(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @Valid @RequestBody UpdateBoardRequest request
    ) {
        return ResponseEntity.ok(boardService.updateBoard(boardId, principal.id(), request));
    }

    @DeleteMapping("/{boardId}")
    public ResponseEntity<Void> deleteBoard(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId
    ) {
        boardService.deleteBoard(boardId, principal.id());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{boardId}/favorite")
    public ResponseEntity<FavoriteBoardResponse> toggleFavorite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID boardId,
            @Valid @RequestBody FavoriteBoardRequest request
    ) {
        return ResponseEntity.ok(boardService.toggleFavorite(boardId, principal.id(), request));
    }
}
