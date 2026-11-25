package com.todollab.board.service;

import com.todollab.board.dto.BoardDtos.BoardMemberResponse;
import com.todollab.board.dto.BoardDtos.TransferOwnershipRequest;
import com.todollab.board.dto.BoardDtos.UpdateMemberRoleRequest;
import com.todollab.board.model.Board;
import com.todollab.board.model.BoardMember;
import com.todollab.board.model.BoardRole;
import com.todollab.board.repository.BoardMemberRepository;
import com.todollab.board.repository.BoardRepository;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.realtime.BoardDomainEvent;
import com.todollab.realtime.BoardEventType;
import com.todollab.task.repository.TaskRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BoardMemberService {

    private final BoardMemberRepository boardMemberRepository;
    private final BoardRepository boardRepository;
    private final BoardAccessService boardAccessService;
    private final TaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BoardMemberService(
            BoardMemberRepository boardMemberRepository,
            BoardRepository boardRepository,
            BoardAccessService boardAccessService,
            TaskRepository taskRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.boardMemberRepository = boardMemberRepository;
        this.boardRepository = boardRepository;
        this.boardAccessService = boardAccessService;
        this.taskRepository = taskRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<BoardMemberResponse> listMembers(UUID boardId, UUID actorId) {
        boardAccessService.requireMember(boardId, actorId);
        return boardMemberRepository.findAllByBoardIdOrderByJoinedAtAsc(boardId)
                .stream()
                .map(BoardMemberResponse::from)
                .toList();
    }

    @Transactional
    public BoardMemberResponse updateMemberRole(
            UUID boardId,
            UUID actorId,
            UUID targetUserId,
            UpdateMemberRoleRequest request
    ) {
        lockBoardOrThrow(boardId);
        boardAccessService.requireOwner(boardId, actorId);

        if (targetUserId.equals(actorId)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_ERROR,
                    "Não é possível alterar o próprio papel. Utilize a transferência de propriedade."
            );
        }

        if (request.role() == BoardRole.OWNER) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_ERROR,
                    "Para promover um membro a proprietário, utilize a transferência de propriedade."
            );
        }

        BoardMember targetMember = findBoardMemberOrThrow(boardId, targetUserId);
        if (targetMember.getRole() == BoardRole.OWNER) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_ERROR,
                    "O papel do proprietário não pode ser alterado diretamente."
            );
        }

        targetMember.setRole(request.role());
        BoardMember saved = boardMemberRepository.saveAndFlush(targetMember);
        BoardMemberResponse response = BoardMemberResponse.from(saved);
        eventPublisher.publishEvent(BoardDomainEvent.of(
                BoardEventType.MEMBER_CHANGED,
                boardId,
                actorId,
                Map.of("action", "ROLE_UPDATED", "member", response)
        ));
        return response;
    }

    @Transactional
    public List<BoardMemberResponse> transferOwnership(
            UUID boardId,
            UUID actorId,
            TransferOwnershipRequest request
    ) {
        Board board = lockBoardOrThrow(boardId);
        BoardMember currentOwner = boardAccessService.requireOwner(boardId, actorId);
        UUID newOwnerId = request.newOwnerId();

        if (newOwnerId.equals(actorId)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_ERROR,
                    "O novo proprietário deve ser outro membro do quadro."
            );
        }

        BoardMember newOwner = findBoardMemberOrThrow(boardId, newOwnerId);

        // Flush the demotion before promoting the new owner so uq_board_members_single_owner holds
        currentOwner.setRole(BoardRole.EDITOR);
        boardMemberRepository.saveAndFlush(currentOwner);

        newOwner.setRole(BoardRole.OWNER);
        boardMemberRepository.saveAndFlush(newOwner);

        board.setOwnerId(newOwnerId);
        board.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        boardRepository.saveAndFlush(board);

        List<BoardMemberResponse> members = boardMemberRepository.findAllByBoardIdOrderByJoinedAtAsc(boardId)
                .stream()
                .map(BoardMemberResponse::from)
                .toList();
        eventPublisher.publishEvent(BoardDomainEvent.of(
                BoardEventType.MEMBER_CHANGED,
                boardId,
                actorId,
                Map.of("action", "OWNERSHIP_TRANSFERRED", "newOwnerId", newOwnerId, "members", members)
        ));
        return members;
    }

    @Transactional
    public void removeOrLeaveMember(UUID boardId, UUID actorId, UUID targetUserId) {
        lockBoardOrThrow(boardId);
        BoardMember actorMember = boardAccessService.requireMember(boardId, actorId);

        if (targetUserId.equals(actorId)) {
            if (actorMember.getRole() == BoardRole.OWNER) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        ErrorCode.OWNER_CANNOT_LEAVE,
                        "O proprietário não pode sair do quadro. Transfira a propriedade antes de sair."
                );
            }
            deleteMemberAndClearAssignees(boardId, actorId, actorMember, "LEFT");
            return;
        }

        if (actorMember.getRole() != BoardRole.OWNER) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.FORBIDDEN_ROLE,
                    "Apenas o proprietário do quadro pode remover membros."
            );
        }

        BoardMember targetMember = findBoardMemberOrThrow(boardId, targetUserId);
        if (targetMember.getRole() == BoardRole.OWNER) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.OWNER_CANNOT_LEAVE,
                    "Não é possível remover o proprietário do quadro."
            );
        }

        deleteMemberAndClearAssignees(boardId, actorId, targetMember, "REMOVED");
    }

    private Board lockBoardOrThrow(UUID boardId) {
        return boardRepository.findByIdForUpdate(boardId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.BOARD_NOT_FOUND,
                        "Quadro não encontrado."
                ));
    }

    private void deleteMemberAndClearAssignees(UUID boardId, UUID actorId, BoardMember member, String action) {
        UUID removedUserId = member.getUser().getId();
        boardMemberRepository.delete(member);
        boardMemberRepository.flush();
        taskRepository.clearAssigneeForBoard(boardId, removedUserId);
        eventPublisher.publishEvent(BoardDomainEvent.of(
                BoardEventType.MEMBER_CHANGED,
                boardId,
                actorId,
                Map.of("action", action, "removedUserId", removedUserId)
        ));
    }

    private BoardMember findBoardMemberOrThrow(UUID boardId, UUID userId) {
        return boardMemberRepository.findByBoardIdAndUserId(boardId, userId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.MEMBER_NOT_FOUND,
                        "Membro não encontrado neste quadro."
                ));
    }
}
