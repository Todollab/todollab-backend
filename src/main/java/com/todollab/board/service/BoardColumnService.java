package com.todollab.board.service;

import com.todollab.board.dto.BoardDtos.ColumnResponse;
import com.todollab.board.dto.BoardDtos.CreateColumnRequest;
import com.todollab.board.dto.BoardDtos.MoveColumnRequest;
import com.todollab.board.dto.BoardDtos.UpdateColumnRequest;
import com.todollab.board.model.BoardColumn;
import com.todollab.board.model.BoardMember;
import com.todollab.board.repository.BoardColumnRepository;
import com.todollab.common.exception.ApiException;
import com.todollab.common.exception.ErrorCode;
import com.todollab.common.util.PositionService;
import com.todollab.task.model.Task;
import com.todollab.task.repository.TaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class BoardColumnService {

    private final BoardColumnRepository boardColumnRepository;
    private final BoardAccessService boardAccessService;
    private final TaskRepository taskRepository;

    public BoardColumnService(
            BoardColumnRepository boardColumnRepository,
            BoardAccessService boardAccessService,
            TaskRepository taskRepository
    ) {
        this.boardColumnRepository = boardColumnRepository;
        this.boardAccessService = boardAccessService;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public ColumnResponse createColumn(UUID boardId, UUID userId, CreateColumnRequest request) {
        BoardMember membership = boardAccessService.requireEditor(boardId, userId);
        Double maxPosition = boardColumnRepository.findMaxPositionByBoardId(boardId);
        double position = PositionService.appendPosition(maxPosition);

        BoardColumn column = new BoardColumn(
                membership.getBoard(),
                request.name(),
                position,
                Boolean.TRUE.equals(request.isDoneColumn())
        );

        return ColumnResponse.from(boardColumnRepository.saveAndFlush(column));
    }

    @Transactional
    public ColumnResponse updateColumn(UUID boardId, UUID userId, UUID columnId, UpdateColumnRequest request) {
        boardAccessService.requireEditor(boardId, userId);
        BoardColumn column = findColumnOrThrow(columnId, boardId);

        if (request.name() != null) {
            column.setName(request.name());
        }
        if (request.isDoneColumn() != null) {
            column.setDoneColumn(request.isDoneColumn());
        }
        column.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        return ColumnResponse.from(boardColumnRepository.saveAndFlush(column));
    }

    @Transactional
    public List<ColumnResponse> moveColumn(UUID boardId, UUID userId, UUID columnId, MoveColumnRequest request) {
        boardAccessService.requireEditor(boardId, userId);
        if (!Double.isFinite(request.position())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_ERROR,
                    "Posição deve ser um número válido."
            );
        }

        findColumnOrThrow(columnId, boardId);

        List<BoardColumn> columns = new ArrayList<>(boardColumnRepository.findAllByBoardIdOrderByPositionAsc(boardId));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        BoardColumn movedColumn = columns.stream()
                .filter(c -> c.getId().equals(columnId))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.COLUMN_NOT_FOUND,
                        "Coluna não encontrada."
                ));

        movedColumn.setPosition(request.position());
        movedColumn.setUpdatedAt(now);

        columns.sort(
                Comparator.comparingDouble(BoardColumn::getPosition)
                        .thenComparing(c -> c.getId().equals(columnId) ? 0 : 1)
                        .thenComparing(BoardColumn::getCreatedAt)
                        .thenComparing(BoardColumn::getId)
        );

        List<Double> positions = columns.stream().map(BoardColumn::getPosition).toList();
        if (PositionService.needsRebalance(positions)) {
            List<Double> rebalanced = PositionService.rebalancePositions(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                columns.get(i).setPosition(rebalanced.get(i));
                columns.get(i).setUpdatedAt(now);
            }
            boardColumnRepository.saveAllAndFlush(columns);
        } else {
            boardColumnRepository.saveAndFlush(movedColumn);
        }

        return columns.stream().map(ColumnResponse::from).toList();
    }

    @Transactional
    public void deleteColumn(UUID boardId, UUID userId, UUID columnId, UUID targetColumnId, boolean deleteTasks) {
        boardAccessService.requireEditor(boardId, userId);
        findColumnOrThrow(columnId, boardId);

        if (boardColumnRepository.countByBoardId(boardId) <= 1) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.LAST_COLUMN_DELETE_FORBIDDEN,
                    "O quadro deve possuir pelo menos uma coluna."
            );
        }

        if (targetColumnId != null && deleteTasks) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    ErrorCode.VALIDATION_ERROR,
                    "Informe apenas targetColumnId ou deleteTasks=true, não ambos."
            );
        }

        long taskCount = taskRepository.countByColumnId(columnId);
        if (taskCount > 0) {
            if (targetColumnId != null) {
                if (targetColumnId.equals(columnId)) {
                    throw new ApiException(
                            HttpStatus.BAD_REQUEST,
                            ErrorCode.VALIDATION_ERROR,
                            "A coluna de destino deve ser diferente da coluna excluída."
                    );
                }
                BoardColumn targetColumn = findColumnOrThrow(targetColumnId, boardId);
                List<Task> tasksToMigrate = taskRepository.findAllByColumnIdOrderByPositionAsc(columnId);
                double nextPos = PositionService.appendPosition(taskRepository.findMaxPositionByColumnId(targetColumnId));
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

                for (Task task : tasksToMigrate) {
                    task.setColumn(targetColumn);
                    task.setPosition(nextPos);
                    nextPos += PositionService.DEFAULT_STEP;
                    if (targetColumn.isDoneColumn()) {
                        task.setCompleted(true);
                        if (task.getCompletedAt() == null) {
                            task.setCompletedAt(now);
                        }
                    } else {
                        task.setCompleted(false);
                        task.setCompletedAt(null);
                    }
                    task.setUpdatedAt(now);
                }
                taskRepository.saveAllAndFlush(tasksToMigrate);
            } else if (deleteTasks) {
                taskRepository.deleteAllByColumnId(columnId);
            } else {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        ErrorCode.COLUMN_NOT_EMPTY,
                        "A coluna possui tarefas. Mova as tarefas para outra coluna ou confirme a exclusão."
                );
            }
        }

        boardColumnRepository.deleteColumnByIdAndBoardId(columnId, boardId);
    }

    private BoardColumn findColumnOrThrow(UUID columnId, UUID boardId) {
        return boardColumnRepository.findByIdAndBoardId(columnId, boardId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.COLUMN_NOT_FOUND,
                        "Coluna não encontrada."
                ));
    }
}
