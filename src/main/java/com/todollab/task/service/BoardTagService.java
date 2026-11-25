package com.todollab.task.service;

import com.todollab.board.model.BoardMember;
import com.todollab.board.service.BoardAccessService;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.realtime.BoardDomainEvent;
import com.todollab.realtime.BoardEventType;
import com.todollab.task.dto.TaskDtos.CreateTagRequest;
import com.todollab.task.dto.TaskDtos.TagResponse;
import com.todollab.task.dto.TaskDtos.UpdateTagRequest;
import com.todollab.task.model.BoardTag;
import com.todollab.task.repository.BoardTagRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BoardTagService {

    private final BoardTagRepository boardTagRepository;
    private final BoardAccessService boardAccessService;
    private final ApplicationEventPublisher eventPublisher;

    public BoardTagService(
            BoardTagRepository boardTagRepository,
            BoardAccessService boardAccessService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.boardTagRepository = boardTagRepository;
        this.boardAccessService = boardAccessService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<TagResponse> listTagsForBoard(UUID boardId) {
        return boardTagRepository.findAllByBoardIdOrderByNameAsc(boardId).stream()
                .map(TagResponse::from)
                .toList();
    }

    @Transactional
    public TagResponse createTag(UUID boardId, UUID userId, CreateTagRequest request) {
        BoardMember membership = boardAccessService.requireEditor(boardId, userId);

        if (boardTagRepository.existsByBoardIdAndNameIgnoreCase(boardId, request.name())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    ErrorCode.TAG_ALREADY_EXISTS,
                    "Já existe uma tag com este nome neste quadro."
            );
        }

        BoardTag tag = new BoardTag(membership.getBoard(), request.name(), request.color());
        TagResponse response = TagResponse.from(boardTagRepository.saveAndFlush(tag));
        eventPublisher.publishEvent(BoardDomainEvent.of(
                BoardEventType.TAG_CHANGED,
                boardId,
                userId,
                Map.of("action", "CREATED", "tag", response)
        ));
        return response;
    }

    @Transactional
    public TagResponse updateTag(UUID boardId, UUID tagId, UUID userId, UpdateTagRequest request) {
        boardAccessService.requireEditor(boardId, userId);
        BoardTag tag = boardTagRepository.findByIdAndBoardId(tagId, boardId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.TAG_NOT_FOUND,
                        "Tag não encontrada."
                ));

        if (request.name() != null) {
            if (boardTagRepository.existsByBoardIdAndNameIgnoreCaseAndIdNot(boardId, request.name(), tagId)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        ErrorCode.TAG_ALREADY_EXISTS,
                        "Já existe uma tag com este nome neste quadro."
                );
            }
            tag.setName(request.name());
        }
        if (request.color() != null) {
            tag.setColor(request.color());
        }

        TagResponse response = TagResponse.from(boardTagRepository.saveAndFlush(tag));
        eventPublisher.publishEvent(BoardDomainEvent.of(
                BoardEventType.TAG_CHANGED,
                boardId,
                userId,
                Map.of("action", "UPDATED", "tag", response)
        ));
        return response;
    }

    @Transactional
    public void deleteTag(UUID boardId, UUID tagId, UUID userId) {
        boardAccessService.requireEditor(boardId, userId);
        int deleted = boardTagRepository.deleteTagByIdAndBoardId(tagId, boardId);
        if (deleted == 0) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    ErrorCode.TAG_NOT_FOUND,
                    "Tag não encontrada."
            );
        }
        eventPublisher.publishEvent(BoardDomainEvent.of(
                BoardEventType.TAG_CHANGED,
                boardId,
                userId,
                Map.of("action", "DELETED", "tagId", tagId)
        ));
    }
}
