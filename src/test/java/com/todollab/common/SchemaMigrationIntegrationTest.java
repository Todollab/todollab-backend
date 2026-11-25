package com.todollab.common;

import com.todollab.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "users",
            "verification_codes",
            "refresh_tokens",
            "boards",
            "board_members",
            "board_invites",
            "board_columns",
            "tasks",
            "task_subtasks",
            "board_tags",
            "task_tags"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesAllElevenV1Tables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT LOWER(table_name) FROM information_schema.tables WHERE LOWER(table_schema) = 'public'",
                String.class
        );

        assertThat(tables).containsAll(EXPECTED_TABLES);

        List<String> appliedVersions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank",
                String.class
        );
        assertThat(appliedVersions).containsExactly("1");
    }

    @Test
    void schemaEnforcesUniqueConstraintsAndCascadeRules() {
        Timestamp now = Timestamp.from(Instant.now());
        UUID ownerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID boardId = UUID.randomUUID();
        UUID columnId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, name, email, password_hash, is_active, created_at, updated_at) VALUES (?, ?, ?, ?, TRUE, ?, ?)",
                ownerId, "Owner", "owner-" + ownerId + "@example.com", "hash", now, now
        );
        jdbcTemplate.update(
                "INSERT INTO users (id, name, email, password_hash, is_active, created_at, updated_at) VALUES (?, ?, ?, ?, TRUE, ?, ?)",
                memberId, "Member", "member-" + memberId + "@example.com", "hash", now, now
        );
        jdbcTemplate.update(
                "INSERT INTO boards (id, name, owner_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                boardId, "Board", ownerId, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO board_members (id, board_id, user_id, role, joined_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), boardId, ownerId, "OWNER", now
        );
        jdbcTemplate.update(
                "INSERT INTO board_members (id, board_id, user_id, role, joined_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), boardId, memberId, "EDITOR", now
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO board_members (id, board_id, user_id, role, joined_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), boardId, memberId, "VIEWER", now
        )).isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update(
                "INSERT INTO board_columns (id, board_id, name, position, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                columnId, boardId, "A fazer", 1000.0, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO tasks (id, board_id, column_id, title, assignee_id, position, created_by_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, boardId, columnId, "Task 1", memberId, 1000.0, ownerId, now, now
        );
        jdbcTemplate.update(
                "INSERT INTO board_tags (id, board_id, name, color) VALUES (?, ?, ?, ?)",
                tagId, boardId, "Urgente", "#FF4B4B"
        );
        jdbcTemplate.update(
                "INSERT INTO task_tags (task_id, tag_id) VALUES (?, ?)",
                taskId, tagId
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO board_tags (id, board_id, name, color) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), boardId, "Urgente", "#0C2439"
        )).isInstanceOf(DataIntegrityViolationException.class);

        // Removing assignee user sets tasks.assignee_id to NULL (ON DELETE SET NULL)
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", memberId);
        UUID assigneeAfterDelete = jdbcTemplate.queryForObject(
                "SELECT assignee_id FROM tasks WHERE id = ?",
                UUID.class,
                taskId
        );
        assertThat(assigneeAfterDelete).isNull();

        // Removing board cascades to columns, tasks, tags, members
        jdbcTemplate.update("DELETE FROM boards WHERE id = ?", boardId);
        Integer remainingTasks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE board_id = ?",
                Integer.class,
                boardId
        );
        assertThat(remainingTasks).isZero();

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerId);
    }
}
