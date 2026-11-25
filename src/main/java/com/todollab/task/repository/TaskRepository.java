package com.todollab.task.repository;

import com.todollab.task.model.Task;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Task t SET t.assignee = null WHERE t.board.id = :boardId AND t.assignee.id = :userId")
    int clearAssigneeForBoard(@Param("boardId") UUID boardId, @Param("userId") UUID userId);

    long countByColumnId(UUID columnId);

    @EntityGraph(attributePaths = {"board", "column", "assignee", "tags"})
    @Query("SELECT t FROM Task t WHERE t.column.id = :columnId ORDER BY t.position ASC, t.createdAt ASC, t.id ASC")
    List<Task> findAllByColumnIdOrderByPositionAsc(@Param("columnId") UUID columnId);

    @EntityGraph(attributePaths = {"board", "column", "assignee", "tags"})
    @Query("SELECT t FROM Task t WHERE t.board.id = :boardId ORDER BY t.position ASC, t.createdAt ASC, t.id ASC")
    List<Task> findAllByBoardIdOrderByPositionAsc(@Param("boardId") UUID boardId);

    @Query("SELECT MAX(t.position) FROM Task t WHERE t.column.id = :columnId")
    Double findMaxPositionByColumnId(@Param("columnId") UUID columnId);

    @EntityGraph(attributePaths = {"board", "column", "assignee", "tags"})
    Optional<Task> findByIdAndBoardId(UUID id, UUID boardId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Task t WHERE t.id = :taskId AND t.board.id = :boardId")
    int deleteTaskByIdAndBoardId(@Param("taskId") UUID taskId, @Param("boardId") UUID boardId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Task t WHERE t.column.id = :columnId")
    int deleteAllByColumnId(@Param("columnId") UUID columnId);

    @Query("""
            SELECT t.board.id, t.completed, COUNT(t)
            FROM Task t
            WHERE t.board.id IN :boardIds
            GROUP BY t.board.id, t.completed
            """)
    List<Object[]> countTasksGroupedByBoardAndCompleted(@Param("boardIds") Collection<UUID> boardIds);
}
