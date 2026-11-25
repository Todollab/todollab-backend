package com.todollab.board.service;

import com.todollab.board.model.BoardMember;
import com.todollab.board.model.BoardRole;
import com.todollab.board.repository.BoardMemberRepository;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BoardAccessService {

    private final BoardMemberRepository boardMemberRepository;

    public BoardAccessService(BoardMemberRepository boardMemberRepository) {
        this.boardMemberRepository = boardMemberRepository;
    }

    @Transactional(readOnly = true)
    public BoardMember requireMember(UUID boardId, UUID userId) {
        return boardMemberRepository.findByBoardIdAndUserId(boardId, userId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.BOARD_NOT_FOUND,
                        "Quadro não encontrado."
                ));
    }

    @Transactional(readOnly = true)
    public BoardMember requireEditor(UUID boardId, UUID userId) {
        BoardMember member = requireMember(boardId, userId);
        if (member.getRole() != BoardRole.OWNER && member.getRole() != BoardRole.EDITOR) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.FORBIDDEN_ROLE,
                    "Você não tem permissão para editar este quadro."
            );
        }
        return member;
    }

    @Transactional(readOnly = true)
    public BoardMember requireOwner(UUID boardId, UUID userId) {
        BoardMember member = requireMember(boardId, userId);
        if (member.getRole() != BoardRole.OWNER) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.FORBIDDEN_ROLE,
                    "Apenas o proprietário do quadro pode executar esta ação."
            );
        }
        return member;
    }

    @Transactional(readOnly = true)
    public boolean isMember(UUID boardId, UUID userId) {
        return boardMemberRepository.existsByBoardIdAndUserId(boardId, userId);
    }
}
