package com.todollab.board.repository;

import com.todollab.board.model.Board;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface BoardRepository extends JpaRepository<Board, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Board b WHERE b.id = :boardId")
    void deleteBoardById(@Param("boardId") UUID boardId);
}
