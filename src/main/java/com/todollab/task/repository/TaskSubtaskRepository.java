package com.todollab.task.repository;

import com.todollab.task.model.TaskSubtask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskSubtaskRepository extends JpaRepository<TaskSubtask, UUID> {

    @Query("""
            SELECT s FROM TaskSubtask s
            WHERE s.task.id = :taskId
            ORDER BY s.position ASC, s.createdAt ASC, s.id ASC
            """)
    List<TaskSubtask> findAllByTaskIdOrderByPositionAsc(@Param("taskId") UUID taskId);

    @Query("""
            SELECT s FROM TaskSubtask s
            WHERE s.task.id IN :taskIds
            ORDER BY s.position ASC, s.createdAt ASC, s.id ASC
            """)
    List<TaskSubtask> findAllByTaskIdInOrderByPositionAsc(@Param("taskIds") Collection<UUID> taskIds);

    @Query("""
            SELECT s FROM TaskSubtask s
            JOIN FETCH s.task t
            WHERE s.id = :subtaskId AND t.id = :taskId AND t.board.id = :boardId
            """)
    Optional<TaskSubtask> findByIdAndTaskIdAndBoardId(
            @Param("subtaskId") UUID subtaskId,
            @Param("taskId") UUID taskId,
            @Param("boardId") UUID boardId
    );

    @Query("SELECT MAX(s.position) FROM TaskSubtask s WHERE s.task.id = :taskId")
    Double findMaxPositionByTaskId(@Param("taskId") UUID taskId);

    @Query("""
            SELECT s.task.id, s.done, COUNT(s)
            FROM TaskSubtask s
            WHERE s.task.id IN :taskIds
            GROUP BY s.task.id, s.done
            """)
    List<Object[]> countSubtasksGroupedByTaskAndDone(@Param("taskIds") Collection<UUID> taskIds);
}
