package com.todollab.task.repository;

import com.todollab.task.model.BoardTag;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoardTagRepository extends JpaRepository<BoardTag, UUID> {

    @EntityGraph(attributePaths = {"board"})
    List<BoardTag> findAllByBoardIdOrderByNameAsc(UUID boardId);

    @EntityGraph(attributePaths = {"board"})
    Optional<BoardTag> findByIdAndBoardId(UUID id, UUID boardId);

    @EntityGraph(attributePaths = {"board"})
    List<BoardTag> findAllByIdInAndBoardId(Collection<UUID> ids, UUID boardId);

    boolean existsByBoardIdAndNameIgnoreCase(UUID boardId, String name);

    boolean existsByBoardIdAndNameIgnoreCaseAndIdNot(UUID boardId, String name, UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM BoardTag t WHERE t.id = :tagId AND t.board.id = :boardId")
    int deleteTagByIdAndBoardId(@Param("tagId") UUID tagId, @Param("boardId") UUID boardId);
}
