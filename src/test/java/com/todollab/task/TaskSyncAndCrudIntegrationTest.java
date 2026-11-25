package com.todollab.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todollab.auth.model.User;
import com.todollab.auth.repository.UserRepository;
import com.todollab.auth.service.TokenService;
import com.todollab.board.model.Board;
import com.todollab.board.model.BoardMember;
import com.todollab.board.model.BoardRole;
import com.todollab.board.repository.BoardMemberRepository;
import com.todollab.board.repository.BoardRepository;
import com.todollab.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskSyncAndCrudIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private BoardMemberRepository boardMemberRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM task_tags");
        jdbcTemplate.update("DELETE FROM task_subtasks");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM board_tags");
        jdbcTemplate.update("DELETE FROM board_columns");
        jdbcTemplate.update("DELETE FROM board_invites");
        jdbcTemplate.update("DELETE FROM board_members");
        jdbcTemplate.update("DELETE FROM boards");
        jdbcTemplate.update("DELETE FROM verification_codes");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void toggleCompleteMovesTaskToDoneColumnAndBack() throws Exception {
        User owner = createActiveUser("Alice", "alice@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Sync", "#0C2439");
        String boardId = boardJson.get("board").get("id").asText();
        String todoColId = boardJson.get("columns").get(0).get("id").asText();
        String doneColId = boardJson.get("columns").get(2).get("id").asText();

        // Omitting columnId defaults to first non-done column
        MvcResult createTaskResult = mockMvc.perform(post("/api/v1/boards/{boardId}/tasks", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "  Configurar ambiente  ",
                                "description", "Docker e banco local",
                                "dueDate", "2026-12-01"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Configurar ambiente"))
                .andExpect(jsonPath("$.columnId").value(todoColId))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.completedAt").isEmpty())
                .andExpect(jsonPath("$.position").value(1000.0))
                .andExpect(jsonPath("$.dueDate").value("2026-12-01"))
                .andExpect(jsonPath("$.createdById").value(owner.getId().toString()))
                .andReturn();

        String taskId = objectMapper.readTree(createTaskResult.getResponse().getContentAsString()).get("id").asText();

        // Toggle complete -> completed=true, completedAt != null, moved to Concluído
        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}/toggle-complete", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.columnId").value(doneColId));

        // Toggle again -> completed=false, completedAt == null, moved back to A fazer
        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}/toggle-complete", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.completedAt").isEmpty())
                .andExpect(jsonPath("$.columnId").value(todoColId));

        // Partial PATCH clearing dueDate via clearDueDate=true and then explicit null
        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clearDueDate", true,
                                "priority", "HIGH"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").isEmpty())
                .andExpect(jsonPath("$.priority").value("HIGH"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("dueDate", "2026-12-10"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").value("2026-12-10"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dueDate\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").isEmpty());

        // Creating task directly in done column sets completed=true
        MvcResult doneTaskRes = mockMvc.perform(post("/api/v1/boards/{boardId}/tasks", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "columnId", doneColId,
                                "title", "Já concluída"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.columnId").value(doneColId))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andReturn();
        String doneTaskId = objectMapper.readTree(doneTaskRes.getResponse().getContentAsString()).get("id").asText();

        // Deleting a task returns 204 and removes it from board detail
        mockMvc.perform(delete("/api/v1/boards/{boardId}/tasks/{taskId}", boardId, doneTaskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].id").value(taskId));
    }

    @Test
    void moveTaskIntoAndOutOfDoneColumnSyncsCompletedFlag() throws Exception {
        User owner = createActiveUser("Bruno", "bruno@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Kanban", "#0C2439");
        String boardId = boardJson.get("board").get("id").asText();
        String todoColId = boardJson.get("columns").get(0).get("id").asText();
        String doingColId = boardJson.get("columns").get(1).get("id").asText();
        String doneColId = boardJson.get("columns").get(2).get("id").asText();

        String taskId = createTask(ownerToken, boardId, todoColId, "Mover card");

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}/move", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetColumnId", doneColId,
                                "position", 1000.0
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rebalanced").value(false))
                .andExpect(jsonPath("$.task.columnId").value(doneColId))
                .andExpect(jsonPath("$.task.completed").value(true))
                .andExpect(jsonPath("$.task.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.columnPositions.length()").value(1))
                .andExpect(jsonPath("$.columnPositions[0].id").value(taskId));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}/move", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetColumnId", doingColId,
                                "position", 1000.0
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rebalanced").value(false))
                .andExpect(jsonPath("$.task.columnId").value(doingColId))
                .andExpect(jsonPath("$.task.completed").value(false))
                .andExpect(jsonPath("$.task.completedAt").isEmpty());
    }

    @Test
    void assignTaskToNonMemberFailsWith400AssigneeNotMember() throws Exception {
        User owner = createActiveUser("Clara", "clara@example.com");
        User member = createActiveUser("Membro", "membro@example.com");
        User outsider = createActiveUser("Externo", "externo@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Equipe", "#0C2439");
        UUID boardId = UUID.fromString(boardJson.get("board").get("id").asText());
        String todoColId = boardJson.get("columns").get(0).get("id").asText();

        Board board = boardRepository.findById(boardId).orElseThrow();
        boardMemberRepository.saveAndFlush(new BoardMember(board, member, BoardRole.EDITOR));

        mockMvc.perform(post("/api/v1/boards/{boardId}/tasks", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "columnId", todoColId,
                                "title", "Tarefa inválida",
                                "assigneeId", outsider.getId().toString()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ASSIGNEE_NOT_MEMBER"));

        String taskId = createTask(ownerToken, boardId.toString(), todoColId, "Tarefa atribuível");

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "assigneeId", outsider.getId().toString()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSIGNEE_NOT_MEMBER"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "assigneeId", member.getId().toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(member.getId().toString()));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").isEmpty());
    }

    @Test
    void deleteNonEmptyColumnReturns409AndSupportsMigrationOrDeleteTasks() throws Exception {
        User owner = createActiveUser("Diego", "diego@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Colunas", "#0C2439");
        String boardId = boardJson.get("board").get("id").asText();
        String todoColId = boardJson.get("columns").get(0).get("id").asText();
        String doingColId = boardJson.get("columns").get(1).get("id").asText();
        String doneColId = boardJson.get("columns").get(2).get("id").asText();

        String task1 = createTask(ownerToken, boardId, todoColId, "Tarefa 1");
        String task2 = createTask(ownerToken, boardId, todoColId, "Tarefa 2");
        String task3 = createTask(ownerToken, boardId, doingColId, "Tarefa 3");

        // Deleting non-empty column without targetColumnId or deleteTasks=true fails with 409 COLUMN_NOT_EMPTY
        mockMvc.perform(delete("/api/v1/boards/{boardId}/columns/{columnId}", boardId, todoColId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COLUMN_NOT_EMPTY"));

        // Migrating to Concluído deletes source column, preserves task order, and syncs completed=true
        mockMvc.perform(delete("/api/v1/boards/{boardId}/columns/{columnId}", boardId, todoColId)
                        .param("targetColumnId", doneColId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(2))
                .andExpect(jsonPath("$.tasks.length()").value(3));

        // Deleting column with deleteTasks=true deletes column and its task (task3)
        mockMvc.perform(delete("/api/v1/boards/{boardId}/columns/{columnId}", boardId, doingColId)
                        .param("deleteTasks", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        MvcResult boardAfterDeletes = mockMvc.perform(get("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(1))
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andReturn();

        JsonNode afterJson = objectMapper.readTree(boardAfterDeletes.getResponse().getContentAsString());
        List<JsonNode> tasks = new ArrayList<>();
        afterJson.get("tasks").forEach(tasks::add);
        assertThat(tasks).extracting(t -> t.get("id").asText()).containsExactly(task1, task2);
        assertThat(tasks).extracting(t -> t.get("id").asText()).doesNotContain(task3);
        assertThat(tasks).allSatisfy(t -> {
            assertThat(t.get("columnId").asText()).isEqualTo(doneColId);
            assertThat(t.get("completed").asBoolean()).isTrue();
            assertThat(t.get("completedAt").isNull()).isFalse();
        });

        // Last remaining column still cannot be deleted even with deleteTasks=true
        mockMvc.perform(delete("/api/v1/boards/{boardId}/columns/{columnId}", boardId, doneColId)
                        .param("deleteTasks", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LAST_COLUMN_DELETE_FORBIDDEN"));
    }

    @Test
    void repeatedFractionalTaskMovesTriggerColumnRebalancePreservingOrder() throws Exception {
        User owner = createActiveUser("Eva", "eva@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Rebalance", "#0C2439");
        String boardId = boardJson.get("board").get("id").asText();
        String todoColId = boardJson.get("columns").get(0).get("id").asText();

        String firstTaskId = createTask(ownerToken, boardId, todoColId, "Primeira");
        String lastTaskId = createTask(ownerToken, boardId, todoColId, "Última");

        double firstPos = 1000.0;
        double secondPos = 2000.0;
        boolean rebalanceObserved = false;
        List<String> expectedInsertedOrder = new ArrayList<>();

        for (int i = 1; i <= 28; i++) {
            String newTaskId = createTask(ownerToken, boardId, todoColId, "Intermediária " + i);
            expectedInsertedOrder.add(0, newTaskId);

            double targetPos = (firstPos + secondPos) / 2.0;
            MvcResult moveResult = mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}/move", boardId, newTaskId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "targetColumnId", todoColId,
                                    "position", targetPos
                            ))))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode moveJson = objectMapper.readTree(moveResult.getResponse().getContentAsString());
            if (moveJson.get("rebalanced").asBoolean()) {
                rebalanceObserved = true;
                firstPos = moveJson.get("columnPositions").get(0).get("position").asDouble();
                secondPos = moveJson.get("columnPositions").get(1).get("position").asDouble();
                assertThat(firstPos).isEqualTo(1000.0);
                assertThat(secondPos).isEqualTo(2000.0);
            } else {
                secondPos = moveJson.get("task").get("position").asDouble();
            }
        }

        assertThat(rebalanceObserved).isTrue();

        List<String> expectedFullOrder = new ArrayList<>();
        expectedFullOrder.add(firstTaskId);
        expectedFullOrder.addAll(expectedInsertedOrder);
        expectedFullOrder.add(lastTaskId);

        MvcResult boardResult = mockMvc.perform(get("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode boardAfter = objectMapper.readTree(boardResult.getResponse().getContentAsString());
        List<String> actualIds = new ArrayList<>();
        List<Double> actualPositions = new ArrayList<>();
        for (JsonNode taskNode : boardAfter.get("tasks")) {
            actualIds.add(taskNode.get("id").asText());
            actualPositions.add(taskNode.get("position").asDouble());
        }

        assertThat(actualIds).containsExactlyElementsOf(expectedFullOrder);
        for (int i = 0; i < actualPositions.size() - 1; i++) {
            assertThat(actualPositions.get(i)).isLessThan(actualPositions.get(i + 1));
        }
    }

    @Test
    void viewerBlockedOnTaskSubtaskTagMutations() throws Exception {
        User owner = createActiveUser("Owner", "owner-perm@example.com");
        User editor = createActiveUser("Editor", "editor-perm@example.com");
        User viewer = createActiveUser("Viewer", "viewer-perm@example.com");
        User outsider = createActiveUser("Outsider", "outsider-perm@example.com");

        String ownerToken = tokenService.generateAccessToken(owner);
        String editorToken = tokenService.generateAccessToken(editor);
        String viewerToken = tokenService.generateAccessToken(viewer);
        String outsiderToken = tokenService.generateAccessToken(outsider);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Permissões", "#0C2439");
        UUID boardId = UUID.fromString(boardJson.get("board").get("id").asText());
        String todoColId = boardJson.get("columns").get(0).get("id").asText();
        String doingColId = boardJson.get("columns").get(1).get("id").asText();

        Board board = boardRepository.findById(boardId).orElseThrow();
        boardMemberRepository.saveAndFlush(new BoardMember(board, editor, BoardRole.EDITOR));
        boardMemberRepository.saveAndFlush(new BoardMember(board, viewer, BoardRole.VIEWER));

        String taskId = createTask(editorToken, boardId.toString(), todoColId, "Tarefa Editor");

        MvcResult subtaskRes = mockMvc.perform(post("/api/v1/boards/{boardId}/tasks/{taskId}/subtasks", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Sub 1"))))
                .andExpect(status().isCreated())
                .andReturn();
        String subtaskId = objectMapper.readTree(subtaskRes.getResponse().getContentAsString()).get("id").asText();

        MvcResult tagRes = mockMvc.perform(post("/api/v1/boards/{boardId}/tags", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Core", "color", "#FF4B4B"))))
                .andExpect(status().isCreated())
                .andReturn();
        String tagId = objectMapper.readTree(tagRes.getResponse().getContentAsString()).get("id").asText();

        // Outsider receives 404 BOARD_NOT_FOUND
        mockMvc.perform(post("/api/v1/boards/{boardId}/tasks", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Invasão"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));

        // Viewer can read board
        mockMvc.perform(get("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tags.length()").value(1));

        // Viewer blocked on all task, subtask, and tag mutations (403 FORBIDDEN_ROLE)
        mockMvc.perform(post("/api/v1/boards/{boardId}/tasks", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Bloqueada"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Bloqueada"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}/move", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetColumnId", doingColId, "position", 1000.0))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}/toggle-complete", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(delete("/api/v1/boards/{boardId}/tasks/{taskId}", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(post("/api/v1/boards/{boardId}/tasks/{taskId}/subtasks", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Sub bloqueada"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}/subtasks/{subtaskId}", boardId, taskId, subtaskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("done", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(delete("/api/v1/boards/{boardId}/tasks/{taskId}/subtasks/{subtaskId}", boardId, taskId, subtaskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(post("/api/v1/boards/{boardId}/tags", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Bloq", "color", "#0C2439"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tags/{tagId}", boardId, tagId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Bloq"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(delete("/api/v1/boards/{boardId}/tags/{tagId}", boardId, tagId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void subtasksTagsBoardHydrationAndMyTasksFiltersWork() throws Exception {
        User owner = createActiveUser("Helena", "helena@example.com");
        User editor = createActiveUser("Igor", "igor@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);
        String editorToken = tokenService.generateAccessToken(editor);

        JsonNode board1Json = createBoard(ownerToken, "Projeto Alpha", "#FF4B4B");
        UUID board1Id = UUID.fromString(board1Json.get("board").get("id").asText());
        String b1TodoCol = board1Json.get("columns").get(0).get("id").asText();
        String b1DoneCol = board1Json.get("columns").get(2).get("id").asText();

        Board board1 = boardRepository.findById(board1Id).orElseThrow();
        boardMemberRepository.saveAndFlush(new BoardMember(board1, editor, BoardRole.EDITOR));

        JsonNode board2Json = createBoard(editorToken, "Projeto Beta", "#1E3A54");
        String board2Id = board2Json.get("board").get("id").asText();
        String b2TodoCol = board2Json.get("columns").get(0).get("id").asText();

        // Tags CRUD + duplicate check
        MvcResult urgentTagRes = mockMvc.perform(post("/api/v1/boards/{boardId}/tags", board1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Urgente", "color", "#FF4B4B"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Urgente"))
                .andExpect(jsonPath("$.color").value("#FF4B4B"))
                .andReturn();
        String urgentTagId = objectMapper.readTree(urgentTagRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/boards/{boardId}/tags", board1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "urgente", "color", "#0C2439"))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TAG_ALREADY_EXISTS"));

        MvcResult backendTagRes = mockMvc.perform(post("/api/v1/boards/{boardId}/tags", board1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Backend", "color", "#0C2439"))))
                .andExpect(status().isCreated())
                .andReturn();
        String backendTagId = objectMapper.readTree(backendTagRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tags/{tagId}", board1Id, backendTagId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "API", "color", "#1E3A54"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("API"))
                .andExpect(jsonPath("$.color").value("#1E3A54"));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        String overdueHighId = createDetailedTask(editorToken, board1Id.toString(), b1TodoCol,
                "Atrasada Alta", "HIGH", today.minusDays(2).toString(), editor.getId(), List.of(urgentTagId));
        String doneOverdueHighId = createDetailedTask(editorToken, board1Id.toString(), b1DoneCol,
                "Concluída Atrasada", "HIGH", today.minusDays(1).toString(), editor.getId(), List.of());
        String todayHighId = createDetailedTask(editorToken, board1Id.toString(), b1TodoCol,
                "Hoje Alta", "HIGH", today.toString(), editor.getId(), List.of(urgentTagId, backendTagId));
        String upcomingMediumId = createDetailedTask(editorToken, board1Id.toString(), b1TodoCol,
                "Próxima Média", "MEDIUM", today.plusDays(4).toString(), editor.getId(), List.of());
        String noDateLowId = createDetailedTask(editorToken, board1Id.toString(), b1TodoCol,
                "Sem Prazo", "LOW", null, editor.getId(), List.of());
        // Assigned to owner (must not appear in editor's /users/me/tasks)
        createDetailedTask(ownerToken, board1Id.toString(), b1TodoCol,
                "Tarefa da Helena", "HIGH", today.toString(), owner.getId(), List.of());

        String board2UrgentId = createDetailedTask(editorToken, board2Id, b2TodoCol,
                "Beta Amanhã", "URGENT", today.plusDays(1).toString(), editor.getId(), List.of());

        // Subtasks CRUD on todayHighId
        MvcResult sub1Res = mockMvc.perform(post("/api/v1/boards/{boardId}/tasks/{taskId}/subtasks", board1Id, todayHighId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Subtarefa 1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1000.0))
                .andExpect(jsonPath("$.done").value(false))
                .andReturn();
        String sub1Id = objectMapper.readTree(sub1Res.getResponse().getContentAsString()).get("id").asText();

        MvcResult sub2Res = mockMvc.perform(post("/api/v1/boards/{boardId}/tasks/{taskId}/subtasks", board1Id, todayHighId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Subtarefa 2"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(2000.0))
                .andReturn();
        String sub2Id = objectMapper.readTree(sub2Res.getResponse().getContentAsString()).get("id").asText();

        MvcResult sub3Res = mockMvc.perform(post("/api/v1/boards/{boardId}/tasks/{taskId}/subtasks", board1Id, todayHighId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Subtarefa Temporária"))))
                .andExpect(status().isCreated())
                .andReturn();
        String sub3Id = objectMapper.readTree(sub3Res.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}/subtasks/{subtaskId}", board1Id, todayHighId, sub1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("done", true, "title", "Subtarefa 1 Feita"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.title").value("Subtarefa 1 Feita"));

        mockMvc.perform(delete("/api/v1/boards/{boardId}/tasks/{taskId}/subtasks/{subtaskId}", board1Id, todayHighId, sub3Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isNoContent());

        // Delete API tag and verify it disappears from tags and task tagIds
        mockMvc.perform(delete("/api/v1/boards/{boardId}/tags/{tagId}", board1Id, backendTagId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isNoContent());

        // Verify GET /boards/{boardId} hydration
        MvcResult detailRes = mockMvc.perform(get("/api/v1/boards/{boardId}", board1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0].id").value(urgentTagId))
                .andExpect(jsonPath("$.tasks.length()").value(6))
                .andReturn();

        JsonNode detailJson = objectMapper.readTree(detailRes.getResponse().getContentAsString());
        JsonNode todayTaskNode = null;
        for (JsonNode t : detailJson.get("tasks")) {
            if (t.get("id").asText().equals(todayHighId)) {
                todayTaskNode = t;
                break;
            }
        }
        assertThat(todayTaskNode).isNotNull();
        assertThat(todayTaskNode.get("subtasks").size()).isEqualTo(2);
        assertThat(todayTaskNode.get("subtasks").get(0).get("id").asText()).isEqualTo(sub1Id);
        assertThat(todayTaskNode.get("subtasks").get(1).get("id").asText()).isEqualTo(sub2Id);
        assertThat(todayTaskNode.get("tagIds").size()).isEqualTo(1);
        assertThat(todayTaskNode.get("tagIds").get(0).asText()).isEqualTo(urgentTagId);

        // Verify GET /boards summary counts
        MvcResult boardsListRes = mockMvc.perform(get("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        JsonNode boardsList = objectMapper.readTree(boardsListRes.getResponse().getContentAsString());
        for (JsonNode b : boardsList) {
            if (b.get("id").asText().equals(board1Id.toString())) {
                assertThat(b.get("openTasksCount").asLong()).isEqualTo(5L);
                assertThat(b.get("completedTasksCount").asLong()).isEqualTo(1L);
            } else if (b.get("id").asText().equals(board2Id)) {
                assertThat(b.get("openTasksCount").asLong()).isEqualTo(1L);
                assertThat(b.get("completedTasksCount").asLong()).isEqualTo(0L);
            }
        }

        // Verify GET /users/me/tasks ordering & filters
        mockMvc.perform(get("/api/v1/users/me/tasks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].id").value(overdueHighId))
                .andExpect(jsonPath("$[1].id").value(doneOverdueHighId))
                .andExpect(jsonPath("$[2].id").value(todayHighId))
                .andExpect(jsonPath("$[3].id").value(board2UrgentId))
                .andExpect(jsonPath("$[4].id").value(upcomingMediumId))
                .andExpect(jsonPath("$[5].id").value(noDateLowId));

        mockMvc.perform(get("/api/v1/users/me/tasks")
                        .param("due", "overdue")
                        .param("completed", "false")
                        .param("priority", "HIGH")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(overdueHighId));

        mockMvc.perform(get("/api/v1/users/me/tasks")
                        .param("due", "today")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(todayHighId))
                .andExpect(jsonPath("$[0].boardName").value("Projeto Alpha"))
                .andExpect(jsonPath("$[0].boardColor").value("#FF4B4B"))
                .andExpect(jsonPath("$[0].columnName").value("A fazer"))
                .andExpect(jsonPath("$[0].subtasksTotal").value(2))
                .andExpect(jsonPath("$[0].subtasksDone").value(1));

        mockMvc.perform(get("/api/v1/users/me/tasks")
                        .param("due", "upcoming")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(board2UrgentId))
                .andExpect(jsonPath("$[1].id").value(upcomingMediumId));

        mockMvc.perform(get("/api/v1/users/me/tasks")
                        .param("completed", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(doneOverdueHighId));

        mockMvc.perform(get("/api/v1/users/me/tasks")
                        .param("due", "invalid")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createAndUpdateTaskRejectNullElementsInTagIdsWith400ValidationError() throws Exception {
        User owner = createActiveUser("Lucas", "lucas-tags@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Tag Validation", "#0C2439");
        String boardId = boardJson.get("board").get("id").asText();
        String todoColId = boardJson.get("columns").get(0).get("id").asText();

        mockMvc.perform(post("/api/v1/boards/{boardId}/tasks", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Tarefa inválida\",\"tagIds\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors['tagIds[0]']").isNotEmpty());

        String taskId = createTask(ownerToken, boardId, todoColId, "Tarefa válida");

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors['tagIds[0]']").isNotEmpty());

        mockMvc.perform(patch("/api/v1/boards/{boardId}/tasks/{taskId}", boardId, taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tagIds.length()").value(0));
    }

    private JsonNode createBoard(String token, String name, String color) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "color", color
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String createTask(String token, String boardId, String columnId, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/boards/{boardId}/tasks", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "columnId", columnId,
                                "title", title
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createDetailedTask(
            String token,
            String boardId,
            String columnId,
            String title,
            String priority,
            String dueDate,
            UUID assigneeId,
            List<String> tagIds
    ) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("columnId", columnId);
        payload.put("title", title);
        payload.put("priority", priority);
        if (dueDate != null) {
            payload.put("dueDate", dueDate);
        }
        if (assigneeId != null) {
            payload.put("assigneeId", assigneeId.toString());
        }
        if (tagIds != null && !tagIds.isEmpty()) {
            payload.put("tagIds", tagIds);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/boards/{boardId}/tasks", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private User createActiveUser(String name, String email) {
        User user = new User(name, email, passwordEncoder.encode("Password123!"), true);
        return userRepository.saveAndFlush(user);
    }
}
