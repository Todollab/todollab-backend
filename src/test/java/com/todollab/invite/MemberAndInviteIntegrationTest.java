package com.todollab.invite;

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
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemberAndInviteIntegrationTest extends AbstractIntegrationTest {

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
        when(javaMailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));
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
    void transferOwnershipAtomicallySwapsRolesAndUpdatesBoardOwnerId() throws Exception {
        User owner = createActiveUser("Alice Dona", "alice@example.com");
        User editor = createActiveUser("Bruno Editor", "bruno@example.com");
        User viewer = createActiveUser("Carla Leitora", "carla@example.com");
        User outsider = createActiveUser("Diego Externo", "diego@example.com");

        String ownerToken = tokenService.generateAccessToken(owner);
        String editorToken = tokenService.generateAccessToken(editor);
        String viewerToken = tokenService.generateAccessToken(viewer);
        String outsiderToken = tokenService.generateAccessToken(outsider);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Transferência");
        UUID boardId = UUID.fromString(boardJson.get("board").get("id").asText());
        Board board = boardRepository.findById(boardId).orElseThrow();
        boardMemberRepository.saveAndFlush(new BoardMember(board, editor, BoardRole.EDITOR));
        boardMemberRepository.saveAndFlush(new BoardMember(board, viewer, BoardRole.VIEWER));

        // Outsider cannot read or mutate members
        mockMvc.perform(get("/api/v1/boards/{boardId}/members", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));

        // Any member can list members
        mockMvc.perform(get("/api/v1/boards/{boardId}/members", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // Non-owner cannot change member roles or transfer ownership
        mockMvc.perform(patch("/api/v1/boards/{boardId}/members/{userId}", boardId, viewer.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "EDITOR"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(post("/api/v1/boards/{boardId}/transfer-ownership", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("newOwnerId", editor.getId().toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        // Owner can change viewer to editor and back, but cannot change own role or set OWNER via PATCH
        mockMvc.perform(patch("/api/v1/boards/{boardId}/members/{userId}", boardId, viewer.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "EDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(viewer.getId().toString()))
                .andExpect(jsonPath("$.role").value("EDITOR"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/members/{userId}", boardId, owner.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "EDITOR"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}/members/{userId}", boardId, viewer.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "OWNER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Transfer ownership validation: cannot transfer to self or non-member
        mockMvc.perform(post("/api/v1/boards/{boardId}/transfer-ownership", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("newOwnerId", owner.getId().toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/boards/{boardId}/transfer-ownership", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("newOwnerId", outsider.getId().toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));

        // Atomic ownership transfer to editor
        MvcResult transferResult = mockMvc.perform(post("/api/v1/boards/{boardId}/transfer-ownership", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("newOwnerId", editor.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn();

        JsonNode membersJson = objectMapper.readTree(transferResult.getResponse().getContentAsString());
        String oldOwnerRole = null;
        String newOwnerRole = null;
        for (JsonNode memberNode : membersJson) {
            String uid = memberNode.get("userId").asText();
            if (uid.equals(owner.getId().toString())) {
                oldOwnerRole = memberNode.get("role").asText();
            } else if (uid.equals(editor.getId().toString())) {
                newOwnerRole = memberNode.get("role").asText();
            }
        }
        assertThat(oldOwnerRole).isEqualTo("EDITOR");
        assertThat(newOwnerRole).isEqualTo("OWNER");

        Board updatedBoard = boardRepository.findById(boardId).orElseThrow();
        assertThat(updatedBoard.getOwnerId()).isEqualTo(editor.getId());

        // Old owner is now EDITOR and can no longer transfer ownership; new owner can update board
        mockMvc.perform(post("/api/v1/boards/{boardId}/transfer-ownership", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("newOwnerId", owner.getId().toString()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(patch("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Quadro Novo Dono"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(editor.getId().toString()))
                .andExpect(jsonPath("$.name").value("Quadro Novo Dono"));
    }

    @Test
    void ownerCannotLeaveAndRemovingMemberClearsTaskAssignees() throws Exception {
        User owner = createActiveUser("Alice", "alice-leave@example.com");
        User editor = createActiveUser("Bruno", "bruno-leave@example.com");
        User viewer = createActiveUser("Carla", "carla-leave@example.com");

        String ownerToken = tokenService.generateAccessToken(owner);
        String editorToken = tokenService.generateAccessToken(editor);
        String viewerToken = tokenService.generateAccessToken(viewer);

        JsonNode board1Json = createBoard(ownerToken, "Quadro Principal");
        UUID board1Id = UUID.fromString(board1Json.get("board").get("id").asText());
        String b1ColId = board1Json.get("columns").get(0).get("id").asText();
        Board board1 = boardRepository.findById(board1Id).orElseThrow();
        boardMemberRepository.saveAndFlush(new BoardMember(board1, editor, BoardRole.EDITOR));
        boardMemberRepository.saveAndFlush(new BoardMember(board1, viewer, BoardRole.VIEWER));

        // Second board where editor is also a member with an assigned task (must remain untouched)
        JsonNode board2Json = createBoard(editorToken, "Outro Quadro");
        UUID board2Id = UUID.fromString(board2Json.get("board").get("id").asText());
        String b2ColId = board2Json.get("columns").get(0).get("id").asText();

        String editorTaskB1 = createAssignedTask(ownerToken, board1Id.toString(), b1ColId, "Tarefa do Bruno", editor.getId());
        String viewerTaskB1 = createAssignedTask(ownerToken, board1Id.toString(), b1ColId, "Tarefa da Carla", viewer.getId());
        String ownerTaskB1 = createAssignedTask(ownerToken, board1Id.toString(), b1ColId, "Tarefa da Alice", owner.getId());
        String editorTaskB2 = createAssignedTask(editorToken, board2Id.toString(), b2ColId, "Tarefa Outro Quadro", editor.getId());

        // Owner cannot leave without transferring ownership
        mockMvc.perform(delete("/api/v1/boards/{boardId}/members/{userId}", board1Id, owner.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("OWNER_CANNOT_LEAVE"));

        // Non-owner cannot remove another member
        mockMvc.perform(delete("/api/v1/boards/{boardId}/members/{userId}", board1Id, viewer.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        // Owner removes editor -> 204 and clears editor's task assignees only on board1
        mockMvc.perform(delete("/api/v1/boards/{boardId}/members/{userId}", board1Id, editor.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/boards/{boardId}", board1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));

        // Viewer leaves board themselves -> 204 and clears viewer's task assignees on board1
        mockMvc.perform(delete("/api/v1/boards/{boardId}/members/{userId}", board1Id, viewer.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/boards/{boardId}/members", board1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(owner.getId().toString()));

        MvcResult board1Detail = mockMvc.perform(get("/api/v1/boards/{boardId}", board1Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode b1Tasks = objectMapper.readTree(board1Detail.getResponse().getContentAsString()).get("tasks");
        for (JsonNode taskNode : b1Tasks) {
            String tid = taskNode.get("id").asText();
            if (tid.equals(editorTaskB1) || tid.equals(viewerTaskB1)) {
                assertThat(taskNode.get("assigneeId").isNull()).isTrue();
            } else if (tid.equals(ownerTaskB1)) {
                assertThat(taskNode.get("assigneeId").asText()).isEqualTo(owner.getId().toString());
            }
        }

        // Board2 task still assigned to editor
        MvcResult board2Detail = mockMvc.perform(get("/api/v1/boards/{boardId}", board2Id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode b2Tasks = objectMapper.readTree(board2Detail.getResponse().getContentAsString()).get("tasks");
        assertThat(b2Tasks.get(0).get("id").asText()).isEqualTo(editorTaskB2);
        assertThat(b2Tasks.get(0).get("assigneeId").asText()).isEqualTo(editor.getId().toString());
    }

    @Test
    void emailInviteNormalizesAcceptsIdempotentlyAndBlocksWrongEmail() throws Exception {
        User owner = createActiveUser("Alice", "alice-inv@example.com");
        User bob = createActiveUser("Bob", "bob-inv@example.com");
        User charlie = createActiveUser("Charlie", "charlie-inv@example.com");

        String ownerToken = tokenService.generateAccessToken(owner);
        String bobToken = tokenService.generateAccessToken(bob);
        String charlieToken = tokenService.generateAccessToken(charlie);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Convites");
        String boardId = boardJson.get("board").get("id").asText();

        // Non-owner and outsider cannot manage or list board invites; role OWNER is rejected
        Board board = boardRepository.findById(UUID.fromString(boardId)).orElseThrow();
        User editorHelper = createActiveUser("Helper", "helper-inv@example.com");
        String editorHelperToken = tokenService.generateAccessToken(editorHelper);
        boardMemberRepository.saveAndFlush(new BoardMember(board, editorHelper, BoardRole.EDITOR));

        mockMvc.perform(get("/api/v1/boards/{boardId}/invites", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorHelperToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(post("/api/v1/boards/{boardId}/invites/email", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editorHelperToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "x@example.com", "role", "VIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));

        mockMvc.perform(post("/api/v1/boards/{boardId}/invites/link", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "VIEWER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/boards/{boardId}/invites/email", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "x@example.com", "role", "OWNER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Owner sends normalized email invite
        MvcResult createInviteRes = mockMvc.perform(post("/api/v1/boards/{boardId}/invites/email", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "  BOB-INV@Example.COM ",
                                "role", "EDITOR"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.boardId").value(boardId))
                .andExpect(jsonPath("$.type").value("EMAIL"))
                .andExpect(jsonPath("$.email").value("bob-inv@example.com"))
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.maxUses").value(1))
                .andExpect(jsonPath("$.usesCount").value(0))
                .andReturn();

        String token1 = objectMapper.readTree(createInviteRes.getResponse().getContentAsString()).get("token").asText();
        verifySentInviteEmail(1, "bob-inv@example.com", "http://localhost:5173/invite/" + token1);

        // Sending another pending invite to same normalized email updates role/expiry and re-sends email
        MvcResult resendInviteRes = mockMvc.perform(post("/api/v1/boards/{boardId}/invites/email", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "bob-inv@example.com",
                                "role", "VIEWER"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andReturn();

        String token2 = objectMapper.readTree(resendInviteRes.getResponse().getContentAsString()).get("token").asText();
        verifySentInviteEmail(2, "bob-inv@example.com", "http://localhost:5173/invite/" + token2);

        // Owner lists only 1 active invite
        mockMvc.perform(get("/api/v1/boards/{boardId}/invites", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("VIEWER"));

        // Bob sees pending invite on /users/me/invites, Charlie does not
        mockMvc.perform(get("/api/v1/users/me/invites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + charlieToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/users/me/invites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].token").value(token2))
                .andExpect(jsonPath("$[0].boardId").value(boardId))
                .andExpect(jsonPath("$[0].boardName").value("Quadro Convites"))
                .andExpect(jsonPath("$[0].boardColor").value("#0C2439"))
                .andExpect(jsonPath("$[0].boardIcon").value("kanban"))
                .andExpect(jsonPath("$[0].inviterName").value("Alice"))
                .andExpect(jsonPath("$[0].role").value("VIEWER"))
                .andExpect(jsonPath("$[0].expiresAt").isNotEmpty())
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());

        // Wrong email blocked with 403 INVITE_EMAIL_MISMATCH
        mockMvc.perform(post("/api/v1/invites/{token}/accept", token2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + charlieToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVITE_EMAIL_MISMATCH"));

        // Bob accepts invite
        mockMvc.perform(post("/api/v1/invites/{token}/accept", token2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardId").value(boardId))
                .andExpect(jsonPath("$.role").value("VIEWER"));

        // Bob accepts same invite idempotently
        mockMvc.perform(post("/api/v1/invites/{token}/accept", token2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardId").value(boardId))
                .andExpect(jsonPath("$.role").value("VIEWER"));

        Integer usesCount = jdbcTemplate.queryForObject(
                "SELECT uses_count FROM board_invites WHERE token = ?",
                Integer.class,
                token2
        );
        assertThat(usesCount).isEqualTo(1);

        // Pending lists are now empty
        mockMvc.perform(get("/api/v1/users/me/invites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/boards/{boardId}/invites", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Inviting an existing member fails with 409 ALREADY_BOARD_MEMBER
        mockMvc.perform(post("/api/v1/boards/{boardId}/invites/email", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "bob-inv@example.com",
                                "role", "EDITOR"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ALREADY_BOARD_MEMBER"));
    }

    @Test
    void declineEmailInviteSetsDeclinedAtRemovesFromPendingAndInvalidatesAccept() throws Exception {
        User owner = createActiveUser("Alice", "alice-dec@example.com");
        User bob = createActiveUser("Bob", "bob-dec@example.com");
        User charlie = createActiveUser("Charlie", "charlie-dec@example.com");

        String ownerToken = tokenService.generateAccessToken(owner);
        String bobToken = tokenService.generateAccessToken(bob);
        String charlieToken = tokenService.generateAccessToken(charlie);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Recusa");
        String boardId = boardJson.get("board").get("id").asText();

        MvcResult inviteRes = mockMvc.perform(post("/api/v1/boards/{boardId}/invites/email", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "bob-dec@example.com",
                                "role", "EDITOR"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String inviteToken = objectMapper.readTree(inviteRes.getResponse().getContentAsString()).get("token").asText();

        // Wrong user cannot decline
        mockMvc.perform(post("/api/v1/invites/{token}/decline", inviteToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + charlieToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITE_EMAIL_MISMATCH"));

        // Target user declines -> 204
        mockMvc.perform(post("/api/v1/invites/{token}/decline", inviteToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isNoContent());

        Timestamp declinedAt = jdbcTemplate.queryForObject(
                "SELECT declined_at FROM board_invites WHERE token = ?",
                Timestamp.class,
                inviteToken
        );
        assertThat(declinedAt).isNotNull();

        mockMvc.perform(get("/api/v1/users/me/invites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/boards/{boardId}/invites", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/v1/invites/{token}/accept", inviteToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITE_INVALID"));
    }

    @Test
    void linkInviteRespectsMaxUsesExpiryAndRevocation() throws Exception {
        User owner = createActiveUser("Alice", "alice-link@example.com");
        User user1 = createActiveUser("User1", "user1-link@example.com");
        User user2 = createActiveUser("User2", "user2-link@example.com");

        String ownerToken = tokenService.generateAccessToken(owner);
        String user1Token = tokenService.generateAccessToken(user1);
        String user2Token = tokenService.generateAccessToken(user2);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Links");
        String boardId = boardJson.get("board").get("id").asText();

        // 1. maxUses = 1
        MvcResult maxUsesRes = mockMvc.perform(post("/api/v1/boards/{boardId}/invites/link", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "role", "EDITOR",
                                "expiresInDays", 7,
                                "maxUses", 1
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("LINK"))
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.maxUses").value(1))
                .andExpect(jsonPath("$.usesCount").value(0))
                .andReturn();
        String limitedToken = objectMapper.readTree(maxUsesRes.getResponse().getContentAsString()).get("token").asText();

        // Link invite cannot be declined
        mockMvc.perform(post("/api/v1/invites/{token}/decline", limitedToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user1Token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITE_EMAIL_MISMATCH"));

        // User1 accepts (consumes the 1 use)
        mockMvc.perform(post("/api/v1/invites/{token}/accept", limitedToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardId").value(boardId))
                .andExpect(jsonPath("$.role").value("EDITOR"));

        // User1 accepts again idempotently
        mockMvc.perform(post("/api/v1/invites/{token}/accept", limitedToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardId").value(boardId))
                .andExpect(jsonPath("$.role").value("EDITOR"));

        // User2 cannot accept exhausted link invite
        mockMvc.perform(post("/api/v1/invites/{token}/accept", limitedToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user2Token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITE_INVALID"));

        // 2. Expiry
        MvcResult expiringRes = mockMvc.perform(post("/api/v1/boards/{boardId}/invites/link", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "role", "VIEWER",
                                "expiresInDays", 3
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String expiredToken = objectMapper.readTree(expiringRes.getResponse().getContentAsString()).get("token").asText();

        jdbcTemplate.update(
                "UPDATE board_invites SET expires_at = ? WHERE token = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                expiredToken
        );

        mockMvc.perform(post("/api/v1/invites/{token}/accept", expiredToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user2Token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITE_INVALID"));

        // 3. Revocation
        MvcResult revocableRes = mockMvc.perform(post("/api/v1/boards/{boardId}/invites/link", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "VIEWER"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode revocableJson = objectMapper.readTree(revocableRes.getResponse().getContentAsString());
        String revocableId = revocableJson.get("id").asText();
        String revocableToken = revocableJson.get("token").asText();

        // Only revocable link is currently active
        mockMvc.perform(get("/api/v1/boards/{boardId}/invites", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(revocableId));

        mockMvc.perform(delete("/api/v1/boards/{boardId}/invites/{inviteId}", boardId, revocableId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/boards/{boardId}/invites", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/v1/invites/{token}/accept", revocableToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user2Token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INVITE_INVALID"));
    }

    @Test
    void registerWithMatchingEmailInviteAutoActivatesAndJoinsBoardWhileLinkInviteRequiresVerification() throws Exception {
        User owner = createActiveUser("Alice", "alice-onboard@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Onboarding");
        String boardId = boardJson.get("board").get("id").asText();

        MvcResult emailInviteRes = mockMvc.perform(post("/api/v1/boards/{boardId}/invites/email", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "  NewMember@Example.COM ",
                                "role", "EDITOR"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String emailInviteToken = objectMapper.readTree(emailInviteRes.getResponse().getContentAsString()).get("token").asText();

        // Register with matching email + email invite token -> auto-activates & joins board immediately
        MvcResult autoRegisterRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Novo Membro",
                                "email", "newmember@example.com",
                                "password", "Password123!",
                                "inviteToken", emailInviteToken
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerificationRequired").value(false))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.joinedBoardId").value(boardId))
                .andExpect(jsonPath("$.user.email").value("newmember@example.com"))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();

        String memberAccessToken = objectMapper.readTree(autoRegisterRes.getResponse().getContentAsString())
                .get("accessToken").asText();

        // Still only 1 email sent so far (the board invite email, no verification code email)
        verify(javaMailSender, atLeastOnce()).send(any(MimeMessage.class));
        assertThat(countSentMimeMessages()).isEqualTo(1);

        // User immediately accesses the board as EDITOR
        mockMvc.perform(get("/api/v1/boards/{boardId}", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myRole").value("EDITOR"));

        // Registering with a LINK invite does NOT auto-activate and requires email verification
        MvcResult linkInviteRes = mockMvc.perform(post("/api/v1/boards/{boardId}/invites/link", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "VIEWER"))))
                .andExpect(status().isCreated())
                .andReturn();
        String linkInviteToken = objectMapper.readTree(linkInviteRes.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Usuario Link",
                                "email", "linkuser@example.com",
                                "password", "Password123!",
                                "inviteToken", linkInviteToken
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerificationRequired").value(true))
                .andExpect(jsonPath("$.accessToken").isEmpty())
                .andExpect(jsonPath("$.joinedBoardId").isEmpty());

        assertThat(countSentMimeMessages()).isEqualTo(2);
        Boolean linkUserActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM users WHERE email = ?",
                Boolean.class,
                "linkuser@example.com"
        );
        assertThat(linkUserActive).isFalse();

        // Registering with an EMAIL invite but mismatched email also requires verification
        MvcResult otherEmailInviteRes = mockMvc.perform(post("/api/v1/boards/{boardId}/invites/email", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "intended@example.com",
                                "role", "EDITOR"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String otherEmailToken = objectMapper.readTree(otherEmailInviteRes.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Outro Email",
                                "email", "different@example.com",
                                "password", "Password123!",
                                "inviteToken", otherEmailToken
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailVerificationRequired").value(true))
                .andExpect(jsonPath("$.accessToken").isEmpty())
                .andExpect(jsonPath("$.joinedBoardId").isEmpty());
    }

    @Test
    void publicPreviewMasksTargetEmailAndShowsInvalidReasonAfterRevoke() throws Exception {
        User owner = createActiveUser("Alice Dona", "alice-prev@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        JsonNode boardJson = createBoard(ownerToken, "Quadro Preview");
        String boardId = boardJson.get("board").get("id").asText();

        MvcResult emailInviteRes = mockMvc.perform(post("/api/v1/boards/{boardId}/invites/email", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "maria.clara@example.com",
                                "role", "EDITOR"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode emailInviteJson = objectMapper.readTree(emailInviteRes.getResponse().getContentAsString());
        String emailInviteId = emailInviteJson.get("id").asText();
        String emailToken = emailInviteJson.get("token").asText();

        MvcResult linkInviteRes = mockMvc.perform(post("/api/v1/boards/{boardId}/invites/link", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("role", "VIEWER"))))
                .andExpect(status().isCreated())
                .andReturn();
        String linkToken = objectMapper.readTree(linkInviteRes.getResponse().getContentAsString()).get("token").asText();

        // Unknown token returns 404 INVITE_NOT_FOUND
        mockMvc.perform(get("/api/v1/invites/{token}/preview", "unknown-token"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));

        // Anonymous preview of email invite masks full email
        MvcResult previewEmailRes = mockMvc.perform(get("/api/v1/invites/{token}/preview", emailToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardName").value("Quadro Preview"))
                .andExpect(jsonPath("$.boardColor").value("#0C2439"))
                .andExpect(jsonPath("$.boardIcon").value("kanban"))
                .andExpect(jsonPath("$.inviterName").value("Alice Dona"))
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.type").value("EMAIL"))
                .andExpect(jsonPath("$.targetEmailMasked").value("m***@example.com"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reason").isEmpty())
                .andReturn();

        assertThat(previewEmailRes.getResponse().getContentAsString()).doesNotContain("maria.clara@example.com");

        // Anonymous preview of link invite has null targetEmailMasked
        mockMvc.perform(get("/api/v1/invites/{token}/preview", linkToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("LINK"))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.targetEmailMasked").isEmpty())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.reason").isEmpty());

        // Revoke email invite -> preview returns valid=false with reason
        mockMvc.perform(delete("/api/v1/boards/{boardId}/invites/{inviteId}", boardId, emailInviteId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/invites/{token}/preview", emailToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").isNotEmpty())
                .andExpect(jsonPath("$.targetEmailMasked").value("m***@example.com"));

        // Cross-board revoke is rejected with 404 INVITE_NOT_FOUND
        JsonNode otherBoardJson = createBoard(ownerToken, "Outro Quadro Preview");
        String otherBoardId = otherBoardJson.get("board").get("id").asText();
        mockMvc.perform(delete("/api/v1/boards/{boardId}/invites/{inviteId}", otherBoardId, emailInviteId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
    }

    private User createActiveUser(String name, String email) {
        User user = new User(name, email, passwordEncoder.encode("Password123!"), true);
        return userRepository.saveAndFlush(user);
    }

    private JsonNode createBoard(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/boards")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String createAssignedTask(String token, String boardId, String columnId, String title, UUID assigneeId)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/boards/{boardId}/tasks", boardId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "columnId", columnId,
                                "title", title,
                                "assigneeId", assigneeId.toString()
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private int countSentMimeMessages() {
        return org.mockito.Mockito.mockingDetails(javaMailSender).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("send")
                        && inv.getArguments().length == 1
                        && inv.getArguments()[0] instanceof MimeMessage)
                .toList()
                .size();
    }

    private void verifySentInviteEmail(int expectedTotalSent, String expectedRecipient, String expectedInviteUrl)
            throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender, atLeastOnce()).send(captor.capture());
        List<MimeMessage> messages = captor.getAllValues();
        assertThat(messages).hasSize(expectedTotalSent);
        MimeMessage latest = messages.get(messages.size() - 1);
        assertThat(latest.getAllRecipients()).extracting(Object::toString).containsExactly(expectedRecipient);
        String body = extractText(latest.getContent());
        assertThat(body).contains(expectedInviteUrl);
    }

    private String extractText(Object content) throws Exception {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof Multipart multipart) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                sb.append(extractText(part.getContent())).append('\n');
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }
}
