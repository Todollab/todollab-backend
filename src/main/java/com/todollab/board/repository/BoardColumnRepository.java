package com.todollab.board.repository;

import com.todollab.board.model.BoardColumn;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoardColumnRepository extends JpaRepository<BoardColumn, UUID> {

    @EntityGraph(attributePaths = {"board"})
    List<BoardColumn> findAllByBoardIdOrderByPositionAsc(UUID boardId);

    @EntityGraph(attributePaths = {"board"})
    Optional<BoardColumn> findByIdAndBoardId(UUID id, UUID boardId);

    long countByBoardId(UUID boardId);

    @Query("SELECT MAX(c.position) FROM BoardColumn c WHERE c.board.id = :boardId")
    Double findMaxPositionByBoardId(@Param("boardId") UUID boardId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM BoardColumn c WHERE c.id = :columnId AND c.board.id = :boardId")
    int deleteColumnByIdAndBoardId(@Param("columnId") UUID columnId, @Param("boardId") UUID boardId);
}
