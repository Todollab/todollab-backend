package com.todollab.board.repository;

import com.todollab.board.model.BoardMember;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoardMemberRepository extends JpaRepository<BoardMember, UUID> {

    boolean existsByBoardIdAndUserId(UUID boardId, UUID userId);

    @EntityGraph(attributePaths = {"board", "user"})
    Optional<BoardMember> findByBoardIdAndUserId(UUID boardId, UUID userId);

    @EntityGraph(attributePaths = {"user"})
    List<BoardMember> findAllByBoardIdOrderByJoinedAtAsc(UUID boardId);

    @EntityGraph(attributePaths = {"board"})
    List<BoardMember> findAllByUserIdOrderByJoinedAtDesc(UUID userId);

    @EntityGraph(attributePaths = {"user"})
    List<BoardMember> findAllByBoardIdInOrderByJoinedAtAsc(Collection<UUID> boardIds);
}
