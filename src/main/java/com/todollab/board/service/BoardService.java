package com.todollab.board.service;

import com.todollab.auth.model.User;
import com.todollab.auth.repository.UserRepository;
import com.todollab.board.dto.BoardDtos.BoardDetailResponse;
import com.todollab.board.dto.BoardDtos.BoardMemberResponse;
import com.todollab.board.dto.BoardDtos.BoardResponse;
import com.todollab.board.dto.BoardDtos.BoardSummaryResponse;
import com.todollab.board.dto.BoardDtos.ColumnResponse;
import com.todollab.board.dto.BoardDtos.CreateBoardRequest;
import com.todollab.board.dto.BoardDtos.FavoriteBoardRequest;
import com.todollab.board.dto.BoardDtos.FavoriteBoardResponse;
import com.todollab.board.dto.BoardDtos.MemberPreview;
import com.todollab.board.dto.BoardDtos.UpdateBoardRequest;
import com.todollab.board.model.Board;
import com.todollab.board.model.BoardColumn;
import com.todollab.board.model.BoardMember;
import com.todollab.board.model.BoardRole;
import com.todollab.board.repository.BoardColumnRepository;
import com.todollab.board.repository.BoardMemberRepository;
import com.todollab.board.repository.BoardRepository;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.realtime.BoardDomainEvent;
import com.todollab.realtime.BoardEventType;
import com.todollab.task.dto.TaskDtos.TagResponse;
import com.todollab.task.dto.TaskDtos.TaskResponse;
import com.todollab.task.repository.TaskRepository;
import com.todollab.task.service.BoardTagService;
import com.todollab.task.service.TaskService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BoardService {

    private static final int PREVIEW_LIMIT = 4;

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final BoardColumnRepository boardColumnRepository;
    private final UserRepository userRepository;
    private final BoardAccessService boardAccessService;
    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final BoardTagService boardTagService;
    private final ApplicationEventPublisher eventPublisher;

    public BoardService(
            BoardRepository boardRepository,
            BoardMemberRepository boardMemberRepository,
            BoardColumnRepository boardColumnRepository,
            UserRepository userRepository,
            BoardAccessService boardAccessService,
            TaskRepository taskRepository,
            TaskService taskService,
            BoardTagService boardTagService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.boardRepository = boardRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.boardColumnRepository = boardColumnRepository;
        this.userRepository = userRepository;
        this.boardAccessService = boardAccessService;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.boardTagService = boardTagService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<BoardSummaryResponse> listBoards(UUID userId) {
        List<BoardMember> memberships = boardMemberRepository.findAllByUserIdOrderByJoinedAtDesc(userId);
        if (memberships.isEmpty()) {
            return List.of();
        }

        List<UUID> boardIds = memberships.stream()
                .map(m -> m.getBoard().getId())
                .toList();

        Map<UUID, List<BoardMember>> membersByBoardId = boardMemberRepository
                .findAllByBoardIdInOrderByJoinedAtAsc(boardIds)
                .stream()
                .collect(Collectors.groupingBy(m -> m.getBoard().getId()));

        Map<UUID, long[]> taskCountsByBoardId = new HashMap<>();
        for (Object[] row : taskRepository.countTasksGroupedByBoardAndCompleted(boardIds)) {
            UUID boardId = (UUID) row[0];
            boolean completed = (Boolean) row[1];
            long count = ((Number) row[2]).longValue();
            long[] counts = taskCountsByBoardId.computeIfAbsent(boardId, ignored -> new long[2]);
            if (completed) {
                counts[1] += count;
            } else {
                counts[0] += count;
            }
        }

        return memberships.stream()
                .map(membership -> {
                    Board board = membership.getBoard();
                    List<BoardMember> boardMembers = membersByBoardId.getOrDefault(board.getId(), List.of());
                    List<MemberPreview> preview = boardMembers.stream()
                            .limit(PREVIEW_LIMIT)
                            .map(m -> MemberPreview.from(m.getUser()))
                            .toList();
                    long[] counts = taskCountsByBoardId.getOrDefault(board.getId(), new long[2]);

                    return new BoardSummaryResponse(
                            board.getId(),
                            board.getName(),
                            board.getDescription(),
                            board.getColor(),
                            board.getIcon(),
                            board.getDefaultView(),
                            membership.getRole(),
                            membership.isFavorite(),
                            boardMembers.size(),
                            counts[0],
                            counts[1],
                            preview,
                            board.getCreatedAt(),
                            board.getUpdatedAt()
                    );
                })
                .toList();
    }

    @Transactional
    public BoardDetailResponse createBoard(UUID userId, CreateBoardRequest request) {
        User owner = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        ErrorCode.UNAUTHORIZED,
                        "Autenticação necessária ou token inválido."
                ));

        String description = request.description() != null && !request.description().isBlank()
                ? request.description()
                : null;

        Board board = new Board(
                request.name(),
                description,
                request.color(),
                request.icon(),
                request.defaultView(),
                owner.getId()
        );
        Board savedBoard = boardRepository.saveAndFlush(board);

        BoardMember ownerMember = boardMemberRepository.saveAndFlush(
                new BoardMember(savedBoard, owner, BoardRole.OWNER)
        );

        List<BoardColumn> defaultColumns = boardColumnRepository.saveAllAndFlush(List.of(
                new BoardColumn(savedBoard, "A fazer", 1000.0, false),
                new BoardColumn(savedBoard, "Em andamento", 2000.0, false),
                new BoardColumn(savedBoard, "Concluído", 3000.0, true)
        ));

        return new BoardDetailResponse(
                BoardResponse.from(savedBoard),
                ownerMember.getRole(),
                ownerMember.isFavorite(),
                defaultColumns.stream().map(ColumnResponse::from).toList(),
                List.of(),
                List.of(),
                List.of(BoardMemberResponse.from(ownerMember))
        );
    }

    @Transactional(readOnly = true)
    public BoardDetailResponse getBoard(UUID boardId, UUID userId) {
        BoardMember membership = boardAccessService.requireMember(boardId, userId);
        Board board = membership.getBoard();

        List<ColumnResponse> columns = boardColumnRepository.findAllByBoardIdOrderByPositionAsc(boardId)
                .stream()
                .map(ColumnResponse::from)
                .toList();

        List<BoardMemberResponse> members = boardMemberRepository.findAllByBoardIdOrderByJoinedAtAsc(boardId)
                .stream()
                .map(BoardMemberResponse::from)
                .toList();

        List<TaskResponse> tasks = taskService.listTasksForBoard(boardId);
        List<TagResponse> tags = boardTagService.listTagsForBoard(boardId);

        return new BoardDetailResponse(
                BoardResponse.from(board),
                membership.getRole(),
                membership.isFavorite(),
                columns,
                tasks,
                tags,
                members
        );
    }

    @Transactional
    public BoardResponse updateBoard(UUID boardId, UUID userId, UpdateBoardRequest request) {
        BoardMember membership = boardAccessService.requireOwner(boardId, userId);
        Board board = membership.getBoard();

        if (request.name() != null) {
            board.setName(request.name());
        }
        if (request.description() != null) {
            board.setDescription(request.description().isBlank() ? null : request.description());
        }
        if (request.color() != null) {
            board.setColor(request.color());
        }
        if (request.icon() != null) {
            board.setIcon(request.icon());
        }
        if (request.defaultView() != null) {
            board.setDefaultView(request.defaultView());
        }
        board.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        BoardResponse response = BoardResponse.from(boardRepository.saveAndFlush(board));
        eventPublisher.publishEvent(BoardDomainEvent.of(
                BoardEventType.BOARD_UPDATED,
                boardId,
                userId,
                response
        ));
        return response;
    }

    @Transactional
    public void deleteBoard(UUID boardId, UUID userId) {
        boardAccessService.requireOwner(boardId, userId);
        boardRepository.deleteBoardById(boardId);
        eventPublisher.publishEvent(BoardDomainEvent.of(
                BoardEventType.BOARD_DELETED,
                boardId,
                userId,
                Map.of("boardId", boardId)
        ));
    }

    @Transactional
    public FavoriteBoardResponse toggleFavorite(UUID boardId, UUID userId, FavoriteBoardRequest request) {
        BoardMember membership = boardAccessService.requireMember(boardId, userId);
        membership.setFavorite(request.isFavorite());
        BoardMember saved = boardMemberRepository.saveAndFlush(membership);
        return new FavoriteBoardResponse(boardId, saved.isFavorite());
    }
}
