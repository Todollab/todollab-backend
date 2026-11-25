package com.todollab.board;

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

class BoardAndColumnIntegrationTest extends AbstractIntegrationTest {

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
    void createBoardAutoCreatesOwnerAndThreeDefaultColumns() throws Exception {
        mockMvc.perform(get("/api/v1/boards"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        User owner = createActiveUser("Alice Dona", "alice@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        mockMvc.perform(post("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "   ",
                                "color", "invalid"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.name").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.color").isNotEmpty());

        MvcResult createdResult = mockMvc.perform(post("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "  Projeto V1  ",
                                "description", "Planejamento completo",
                                "color", "#FF4B4B",
                                "icon", "rocket",
                                "defaultView", "KANBAN"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.board.id").isNotEmpty())
                .andExpect(jsonPath("$.board.name").value("Projeto V1"))
                .andExpect(jsonPath("$.board.description").value("Planejamento completo"))
                .andExpect(jsonPath("$.board.color").value("#FF4B4B"))
                .andExpect(jsonPath("$.board.icon").value("rocket"))
                .andExpect(jsonPath("$.board.defaultView").value("KANBAN"))
                .andExpect(jsonPath("$.board.ownerId").value(owner.getId().toString()))
                .andExpect(jsonPath("$.myRole").value("OWNER"))
                .andExpect(jsonPath("$.isFavorite").value(false))
                .andExpect(jsonPath("$.columns.length()").value(3))
                .andExpect(jsonPath("$.columns[0].name").value("A fazer"))
                .andExpect(jsonPath("$.columns[0].position").value(1000.0))
                .andExpect(jsonPath("$.columns[0].isDoneColumn").value(false))
                .andExpect(jsonPath("$.columns[1].name").value("Em andamento"))
                .andExpect(jsonPath("$.columns[1].position").value(2000.0))
                .andExpect(jsonPath("$.columns[1].isDoneColumn").value(false))
                .andExpect(jsonPath("$.columns[2].name").value("Concluído"))
                .andExpect(jsonPath("$.columns[2].position").value(3000.0))
                .andExpect(jsonPath("$.columns[2].isDoneColumn").value(true))
                .andExpect(jsonPath("$.tasks.length()").value(0))
                .andExpect(jsonPath("$.tags.length()").value(0))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].userId").value(owner.getId().toString()))
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createdResult.getResponse().getContentAsString());
        String boardId = createdJson.get("board").get("id").asText();

        mockMvc.perform(post("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Quadro Padrão"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.board.color").value("#0C2439"))
                .andExpect(jsonPath("$.board.icon").value("kanban"))
                .andExpect(jsonPath("$.board.defaultView").value("LIST"));

        mockMvc.perform(get("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Quadro Padrão"))
                .andExpect(jsonPath("$[1].id").value(boardId))
                .andExpect(jsonPath("$[1].myRole").value("OWNER"))
                .andExpect(jsonPath("$[1].isFavorite").value(false))
                .andExpect(jsonPath("$[1].memberCount").value(1))
                .andExpect(jsonPath("$[1].openTasksCount").value(0))
                .andExpect(jsonPath("$[1].completedTasksCount").value(0))
                .andExpect(jsonPath("$[1].membersPreview.length()").value(1))
                .andExpect(jsonPath("$[1].membersPreview[0].id").value(owner.getId().toString()))
                .andExpect(jsonPath("$[1].membersPreview[0].name").value("Alice Dona"))
                .andExpect(jsonPath("$[1].membersPreview[0].avatarColor").value("#0C2439"));
    }

    @Test
    void roleMatrixEnforcedForNonMemberViewerEditorOwner() throws Exception {
        User owner = createActiveUser("Owner", "owner@example.com");
        User editor = createActiveUser("Editor", "editor@example.com");
        User viewer = createActiveUser("Viewer", "viewer@example.com");
        User outsider = createActiveUser("Outsider", "outsider@example.com");

        String ownerToken = tokenService.generateAccessToken(owner);
        String editorToken = tokenService.generateAccessToken(editor);
        String viewerToken = tokenService.generateAccessToken(viewer);
        String outsiderToken = tokenService.generateAccessToken(outsider);

        MvcResult createResult = mockMvc.perform(post("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Quadro Equipe"))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID boardId = UUID.fromString(createJson.get("board").get("id").asText());
        String firstColumnId = createJson.get("columns").get(0).get("id").asText();

        Board board = boardRepository.findById(boardId).orElseThrow();
        boardMemberRepository.saveAndFlush(new BoardMember(board, editor, BoardRole.EDITOR));
        boardMemberRepository.saveAndFlush(new BoardMember(board, viewer, BoardRole.VIEWER));

        // Non-member receives 404 BOARD_NOT_FOUND without leaking board existence
        mockMvc.perform(get("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/boards/{boardId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Hacked"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/boards/{boardId}/columns", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Extra"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));

        // VIEWER can read and toggle personal favorite, but cannot mutate columns or board
        mockMvc.perform(get("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("VIEWER"))
                .andExpect(jsonPath("$.members.length()").value(3));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/favorite", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("isFavorite", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isFavorite").value(true));

        mockMvc.perform(get("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].isFavorite").value(true));

        mockMvc.perform(get("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].isFavorite").value(false));

        mockMvc.perform(post("/api/v1/boards/{boardId}/columns", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Bloqueada"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/columns/{columnId}", boardId, firstColumnId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Bloqueada"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Bloqueado"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        // EDITOR can create, edit, and delete columns, but cannot edit or delete board
        MvcResult colResult = mockMvc.perform(post("/api/v1/boards/{boardId}/columns", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Revisão",
                                "isDoneColumn", false
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Revisão"))
                .andExpect(jsonPath("$.position").value(4000.0))
                .andExpect(jsonPath("$.isDoneColumn").value(false))
                .andReturn();

        String newColumnId = objectMapper.readTree(colResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch("/api/v1/boards/{boardId}/columns/{columnId}", boardId, newColumnId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Aprovação",
                                "isDoneColumn", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Aprovação"))
                .andExpect(jsonPath("$.isDoneColumn").value(true));

        mockMvc.perform(delete("/api/v1/boards/{boardId}/columns/{columnId}", boardId, newColumnId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Sem Permissão"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(delete("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        // OWNER can update and delete the board
        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Quadro Atualizado",
                                "color", "#1E3A54",
                                "icon", "check-square",
                                "defaultView", "KANBAN"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Quadro Atualizado"))
                .andExpect(jsonPath("$.color").value("#1E3A54"))
                .andExpect(jsonPath("$.icon").value("check-square"))
                .andExpect(jsonPath("$.defaultView").value("KANBAN"));

        mockMvc.perform(delete("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));
    }

    @Test
    void deleteLastColumnReturns400LastColumnDeleteForbidden() throws Exception {
        User owner = createActiveUser("Bruno", "bruno@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        MvcResult createResult = mockMvc.perform(post("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Quadro Colunas"))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String boardId = json.get("board").get("id").asText();
        String col1 = json.get("columns").get(0).get("id").asText();
        String col2 = json.get("columns").get(1).get("id").asText();
        String col3 = json.get("columns").get(2).get("id").asText();

        mockMvc.perform(delete("/api/v1/boards/{boardId}/columns/{columnId}", boardId, UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COLUMN_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/boards/{boardId}/columns/{columnId}", boardId, col1)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/boards/{boardId}/columns/{columnId}", boardId, col2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/boards/{boardId}/columns/{columnId}", boardId, col3)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("LAST_COLUMN_DELETE_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(1))
                .andExpect(jsonPath("$.columns[0].id").value(col3));
    }

    @Test
    void moveColumnWithTinyGapTriggersRebalance() throws Exception {
        User owner = createActiveUser("Clara", "clara@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        MvcResult createResult = mockMvc.perform(post("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Quadro Ordem"))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String boardId = json.get("board").get("id").asText();
        String col1 = json.get("columns").get(0).get("id").asText(); // A fazer (1000.0)
        String col2 = json.get("columns").get(1).get("id").asText(); // Em andamento (2000.0)
        String col3 = json.get("columns").get(2).get("id").asText(); // Concluído (3000.0)

        // Normal move without rebalance
        mockMvc.perform(patch("/api/v1/boards/{boardId}/columns/{columnId}/move", boardId, col3)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("position", 1500.0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(col1))
                .andExpect(jsonPath("$[0].position").value(1000.0))
                .andExpect(jsonPath("$[1].id").value(col3))
                .andExpect(jsonPath("$[1].position").value(1500.0))
                .andExpect(jsonPath("$[2].id").value(col2))
                .andExpect(jsonPath("$[2].position").value(2000.0));

        // Tiny gap (< 1e-5) triggers rebalance preserving intended order: [col1, col2, col3]
        mockMvc.perform(patch("/api/v1/boards/{boardId}/columns/{columnId}/move", boardId, col2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("position", 1000.000001))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(col1))
                .andExpect(jsonPath("$[0].position").value(1000.0))
                .andExpect(jsonPath("$[1].id").value(col2))
                .andExpect(jsonPath("$[1].position").value(2000.0))
                .andExpect(jsonPath("$[2].id").value(col3))
                .andExpect(jsonPath("$[2].position").value(3000.0));

        mockMvc.perform(get("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].id").value(col1))
                .andExpect(jsonPath("$.columns[0].position").value(1000.0))
                .andExpect(jsonPath("$.columns[1].id").value(col2))
                .andExpect(jsonPath("$.columns[1].position").value(2000.0))
                .andExpect(jsonPath("$.columns[2].id").value(col3))
                .andExpect(jsonPath("$.columns[2].position").value(3000.0));

        // Moving to non-positive position triggers rebalance placing moved column first
        mockMvc.perform(patch("/api/v1/boards/{boardId}/columns/{columnId}/move", boardId, col3)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("position", 0.0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(col3))
                .andExpect(jsonPath("$[0].position").value(1000.0))
                .andExpect(jsonPath("$[1].id").value(col1))
                .andExpect(jsonPath("$[1].position").value(2000.0))
                .andExpect(jsonPath("$[2].id").value(col2))
                .andExpect(jsonPath("$[2].position").value(3000.0));

        assertThat(boardRepository.findById(UUID.fromString(boardId))).isPresent();
    }

    private User createActiveUser(String name, String email) {
        User user = new User(name, email, passwordEncoder.encode("Password123!"), true);
        return userRepository.saveAndFlush(user);
    }
}
