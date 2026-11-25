package com.todollab.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todollab.auth.model.User;
import com.todollab.auth.repository.UserRepository;
import com.todollab.auth.service.TokenService;
import com.todollab.board.dto.BoardDtos.BoardDetailResponse;
import com.todollab.board.dto.BoardDtos.BoardResponse;
import com.todollab.board.dto.BoardDtos.ColumnResponse;
import com.todollab.task.dto.TaskDtos.TaskMoveResponse;
import com.todollab.task.dto.TaskDtos.TaskResponse;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BoardRealtimeIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private SimpUserRegistry simpUserRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @MockitoBean
    private JavaMailSender javaMailSender;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        restTemplate.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        when(javaMailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage((Session) null));

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter(objectMapper));
    }

    @AfterEach
    void tearDown() {
        if (stompClient != null) {
            stompClient.stop();
        }
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
    void authenticatedBoardMemberReceivesTaskCreatedEventViaStompAfterCommit() throws Exception {
        User owner = createActiveUser("Alice Dona", "alice@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);

        BoardDetailResponse createdBoard = createBoard(ownerToken, "Quadro Tempo Real");
        UUID boardId = createdBoard.board().id();
        UUID todoColumnId = createdBoard.columns().get(0).id();

        BlockingQueue<BoardEvent> receivedEvents = new LinkedBlockingQueue<>();
        StompSession session = connectAndSubscribe(ownerToken, owner.getEmail(), boardId, receivedEvents);

        try {
            ResponseEntity<TaskResponse> createTaskResponse = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks",
                    HttpMethod.POST,
                    new HttpEntity<>(
                            Map.of(
                                    "columnId", todoColumnId,
                                    "title", "Configurar WebSocket",
                                    "description", "Validar entrega STOMP",
                                    "priority", "HIGH",
                                    "dueDate", "2026-12-10"
                            ),
                            authHeaders(ownerToken)
                    ),
                    TaskResponse.class,
                    boardId
            );

            assertThat(createTaskResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            TaskResponse createdTask = createTaskResponse.getBody();
            assertThat(createdTask).isNotNull();

            BoardEvent event = receivedEvents.poll(5, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            assertThat(event.type()).isEqualTo(BoardEventType.TASK_CREATED);
            assertThat(event.boardId()).isEqualTo(boardId);
            assertThat(event.actorId()).isEqualTo(owner.getId());
            assertThat(event.timestamp()).isNotNull();

            TaskResponse eventTask = objectMapper.convertValue(event.payload(), TaskResponse.class);
            assertThat(eventTask.id()).isEqualTo(createdTask.id());
            assertThat(eventTask.title()).isEqualTo("Configurar WebSocket");
            assertThat(eventTask.columnId()).isEqualTo(todoColumnId);
            assertThat(eventTask.dueDate()).isEqualTo(createdTask.dueDate());
        } finally {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
    }

    @Test
    void allBoardMutationsEmitExpectedEventTypesToMemberSubscriber() throws Exception {
        User owner = createActiveUser("Alice Dona", "alice@example.com");
        User invitedEditor = createActiveUser("Bruno Editor", "bruno@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);
        String editorToken = tokenService.generateAccessToken(invitedEditor);

        BoardDetailResponse createdBoard = createBoard(ownerToken, "Quadro Completo");
        UUID boardId = createdBoard.board().id();
        UUID todoColId = createdBoard.columns().get(0).id();
        UUID secondColId = createdBoard.columns().get(1).id();

        BlockingQueue<BoardEvent> receivedEvents = new LinkedBlockingQueue<>();
        StompSession ownerSession = connectAndSubscribe(ownerToken, owner.getEmail(), boardId, receivedEvents);

        try {
            // 1. Invite accept -> MEMBER_CHANGED
            ResponseEntity<JsonNode> inviteResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/invites/email",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("email", "bruno@example.com", "role", "EDITOR"), authHeaders(ownerToken)),
                    JsonNode.class,
                    boardId
            );
            assertThat(inviteResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String inviteToken = inviteResp.getBody().get("token").asText();

            ResponseEntity<JsonNode> acceptResp = restTemplate.exchange(
                    "/api/v1/invites/{token}/accept",
                    HttpMethod.POST,
                    new HttpEntity<>(authHeaders(editorToken)),
                    JsonNode.class,
                    inviteToken
            );
            assertThat(acceptResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            BoardEvent memberJoined = pollEventOfType(receivedEvents, BoardEventType.MEMBER_CHANGED);
            assertThat(memberJoined.actorId()).isEqualTo(invitedEditor.getId());

            // 2. Update board -> BOARD_UPDATED
            ResponseEntity<BoardResponse> boardPatchResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}",
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("name", "Quadro Atualizado"), authHeaders(ownerToken)),
                    BoardResponse.class,
                    boardId
            );
            assertThat(boardPatchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            BoardEvent boardUpdated = pollEventOfType(receivedEvents, BoardEventType.BOARD_UPDATED);
            BoardResponse updatedBoardPayload = objectMapper.convertValue(boardUpdated.payload(), BoardResponse.class);
            assertThat(updatedBoardPayload.name()).isEqualTo("Quadro Atualizado");

            // 3. Column create, update, move, rebalance, delete
            ResponseEntity<ColumnResponse> colCreateResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/columns",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("name", "Revisão", "isDoneColumn", false), authHeaders(editorToken)),
                    ColumnResponse.class,
                    boardId
            );
            assertThat(colCreateResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID reviewColId = colCreateResp.getBody().id();
            pollEventOfType(receivedEvents, BoardEventType.COLUMN_CREATED);

            ResponseEntity<ColumnResponse> colUpdateResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/columns/{columnId}",
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("name", "Em Revisão"), authHeaders(editorToken)),
                    ColumnResponse.class,
                    boardId,
                    reviewColId
            );
            assertThat(colUpdateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            pollEventOfType(receivedEvents, BoardEventType.COLUMN_UPDATED);

            ResponseEntity<List<ColumnResponse>> colMoveResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/columns/{columnId}/move",
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("position", 1500.0), authHeaders(editorToken)),
                    new ParameterizedTypeReference<>() {},
                    boardId,
                    reviewColId
            );
            assertThat(colMoveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            pollEventOfType(receivedEvents, BoardEventType.COLUMN_MOVED);

            ResponseEntity<List<ColumnResponse>> colRebalanceResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/columns/{columnId}/move",
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("position", 1000.000001), authHeaders(editorToken)),
                    new ParameterizedTypeReference<>() {},
                    boardId,
                    reviewColId
            );
            assertThat(colRebalanceResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            pollEventOfType(receivedEvents, BoardEventType.COLUMN_REORDERED);

            ResponseEntity<Void> colDeleteResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/columns/{columnId}",
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(editorToken)),
                    Void.class,
                    boardId,
                    reviewColId
            );
            assertThat(colDeleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            pollEventOfType(receivedEvents, BoardEventType.COLUMN_DELETED);

            // 4. Tag CRUD -> TAG_CHANGED
            ResponseEntity<JsonNode> tagCreateResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tags",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("name", "Backend", "color", "#FF4B4B"), authHeaders(editorToken)),
                    JsonNode.class,
                    boardId
            );
            assertThat(tagCreateResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID tagId = UUID.fromString(tagCreateResp.getBody().get("id").asText());
            pollEventOfType(receivedEvents, BoardEventType.TAG_CHANGED);

            ResponseEntity<JsonNode> tagUpdateResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tags/{tagId}",
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("name", "API"), authHeaders(editorToken)),
                    JsonNode.class,
                    boardId,
                    tagId
            );
            assertThat(tagUpdateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            pollEventOfType(receivedEvents, BoardEventType.TAG_CHANGED);

            // 5. Task create, update, toggle-complete, move, rebalance, subtasks, delete
            ResponseEntity<TaskResponse> task1Resp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("columnId", todoColId, "title", "Tarefa 1"), authHeaders(editorToken)),
                    TaskResponse.class,
                    boardId
            );
            UUID task1Id = task1Resp.getBody().id();
            pollEventOfType(receivedEvents, BoardEventType.TASK_CREATED);

            ResponseEntity<TaskResponse> task2Resp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("columnId", secondColId, "title", "Tarefa 2"), authHeaders(editorToken)),
                    TaskResponse.class,
                    boardId
            );
            UUID task2Id = task2Resp.getBody().id();
            pollEventOfType(receivedEvents, BoardEventType.TASK_CREATED);

            ResponseEntity<TaskResponse> taskUpdateResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks/{taskId}",
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("title", "Tarefa 1 Editada"), authHeaders(editorToken)),
                    TaskResponse.class,
                    boardId,
                    task1Id
            );
            assertThat(taskUpdateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            pollEventOfType(receivedEvents, BoardEventType.TASK_UPDATED);

            ResponseEntity<TaskResponse> toggleResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks/{taskId}/toggle-complete",
                    HttpMethod.PATCH,
                    new HttpEntity<>(authHeaders(editorToken)),
                    TaskResponse.class,
                    boardId,
                    task1Id
            );
            assertThat(toggleResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            pollEventOfType(receivedEvents, BoardEventType.TASK_UPDATED);

            ResponseEntity<TaskMoveResponse> taskMoveResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks/{taskId}/move",
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("targetColumnId", secondColId, "position", 500.0), authHeaders(editorToken)),
                    TaskMoveResponse.class,
                    boardId,
                    task1Id
            );
            assertThat(taskMoveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            pollEventOfType(receivedEvents, BoardEventType.TASK_MOVED);

            ResponseEntity<TaskMoveResponse> taskRebalanceResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks/{taskId}/move",
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("targetColumnId", secondColId, "position", 1000.000001), authHeaders(editorToken)),
                    TaskMoveResponse.class,
                    boardId,
                    task1Id
            );
            assertThat(taskRebalanceResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(taskRebalanceResp.getBody().rebalanced()).isTrue();
            pollEventOfType(receivedEvents, BoardEventType.COLUMN_REORDERED);

            // Subtask create, update, delete -> TASK_UPDATED
            ResponseEntity<JsonNode> subtaskCreateResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks/{taskId}/subtasks",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("title", "Sub 1"), authHeaders(editorToken)),
                    JsonNode.class,
                    boardId,
                    task2Id
            );
            assertThat(subtaskCreateResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID subtaskId = UUID.fromString(subtaskCreateResp.getBody().get("id").asText());
            pollEventOfType(receivedEvents, BoardEventType.TASK_UPDATED);

            ResponseEntity<JsonNode> subtaskPatchResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks/{taskId}/subtasks/{subtaskId}",
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("done", true), authHeaders(editorToken)),
                    JsonNode.class,
                    boardId,
                    task2Id,
                    subtaskId
            );
            assertThat(subtaskPatchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            pollEventOfType(receivedEvents, BoardEventType.TASK_UPDATED);

            ResponseEntity<Void> subtaskDeleteResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks/{taskId}/subtasks/{subtaskId}",
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(editorToken)),
                    Void.class,
                    boardId,
                    task2Id,
                    subtaskId
            );
            assertThat(subtaskDeleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            pollEventOfType(receivedEvents, BoardEventType.TASK_UPDATED);

            ResponseEntity<Void> taskDeleteResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks/{taskId}",
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(editorToken)),
                    Void.class,
                    boardId,
                    task2Id
            );
            assertThat(taskDeleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            BoardEvent taskDeleted = pollEventOfType(receivedEvents, BoardEventType.TASK_DELETED);
            Map<?, ?> taskDeletedPayload = objectMapper.convertValue(taskDeleted.payload(), Map.class);
            assertThat(taskDeletedPayload.get("taskId")).isEqualTo(task2Id.toString());

            ResponseEntity<Void> tagDeleteResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tags/{tagId}",
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(editorToken)),
                    Void.class,
                    boardId,
                    tagId
            );
            assertThat(tagDeleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            pollEventOfType(receivedEvents, BoardEventType.TAG_CHANGED);

            // 6. Member role update, transfer ownership, remove member (includes removedUserId), register auto-onboard, delete board
            ResponseEntity<JsonNode> roleUpdateResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/members/{userId}",
                    HttpMethod.PATCH,
                    new HttpEntity<>(Map.of("role", "VIEWER"), authHeaders(ownerToken)),
                    JsonNode.class,
                    boardId,
                    invitedEditor.getId()
            );
            assertThat(roleUpdateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            pollEventOfType(receivedEvents, BoardEventType.MEMBER_CHANGED);

            ResponseEntity<JsonNode> transferResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/transfer-ownership",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("newOwnerId", invitedEditor.getId()), authHeaders(ownerToken)),
                    JsonNode.class,
                    boardId
            );
            assertThat(transferResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            pollEventOfType(receivedEvents, BoardEventType.MEMBER_CHANGED);

            // Transfer back so original owner can remove invitedEditor and delete the board
            ResponseEntity<JsonNode> transferBackResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/transfer-ownership",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("newOwnerId", owner.getId()), authHeaders(editorToken)),
                    JsonNode.class,
                    boardId
            );
            assertThat(transferBackResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            pollEventOfType(receivedEvents, BoardEventType.MEMBER_CHANGED);

            ResponseEntity<Void> removeMemberResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/members/{userId}",
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(ownerToken)),
                    Void.class,
                    boardId,
                    invitedEditor.getId()
            );
            assertThat(removeMemberResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            BoardEvent memberRemoved = pollEventOfType(receivedEvents, BoardEventType.MEMBER_CHANGED);
            Map<?, ?> memberRemovedPayload = objectMapper.convertValue(memberRemoved.payload(), Map.class);
            assertThat(memberRemovedPayload.get("removedUserId")).isEqualTo(invitedEditor.getId().toString());

            // Auto-onboarding via /auth/register with email inviteToken -> MEMBER_CHANGED
            ResponseEntity<JsonNode> carlaInviteResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/invites/email",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("email", "carla@example.com", "role", "VIEWER"), authHeaders(ownerToken)),
                    JsonNode.class,
                    boardId
            );
            assertThat(carlaInviteResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String carlaInviteToken = carlaInviteResp.getBody().get("token").asText();

            ResponseEntity<JsonNode> registerResp = restTemplate.postForEntity(
                    "/api/v1/auth/register",
                    Map.of(
                            "name", "Carla Convidada",
                            "email", "carla@example.com",
                            "password", "Password123!",
                            "inviteToken", carlaInviteToken
                    ),
                    JsonNode.class
            );
            assertThat(registerResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            BoardEvent carlaJoined = pollEventOfType(receivedEvents, BoardEventType.MEMBER_CHANGED);
            assertThat(carlaJoined.boardId()).isEqualTo(boardId);

            ResponseEntity<Void> deleteBoardResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}",
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(ownerToken)),
                    Void.class,
                    boardId
            );
            assertThat(deleteBoardResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            BoardEvent boardDeleted = pollEventOfType(receivedEvents, BoardEventType.BOARD_DELETED);
            Map<?, ?> boardDeletedPayload = objectMapper.convertValue(boardDeleted.payload(), Map.class);
            assertThat(boardDeletedPayload.get("boardId")).isEqualTo(boardId.toString());
        } finally {
            if (ownerSession.isConnected()) {
                ownerSession.disconnect();
            }
        }
    }

    @Test
    void failedMutationRollsBackAndDoesNotPublishEventAndNonMemberCannotSubscribe() throws Exception {
        User owner = createActiveUser("Alice Dona", "alice@example.com");
        User outsider = createActiveUser("Diego Externo", "diego@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);
        String outsiderToken = tokenService.generateAccessToken(outsider);

        BoardDetailResponse createdBoard = createBoard(ownerToken, "Quadro Seguro");
        UUID boardId = createdBoard.board().id();

        // Connect without Authorization header -> rejected
        StompHeaders missingAuth = new StompHeaders();
        CompletableFuture<StompSession> invalidConnectFuture = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                missingAuth,
                new StompSessionHandlerAdapter() {}
        );
        assertThatThrownBy(() -> invalidConnectFuture.get(5, TimeUnit.SECONDS))
                .isInstanceOf(Exception.class);

        // Outsider connects with valid JWT, tries to subscribe to owner's board -> rejected and receives no events
        BlockingQueue<BoardEvent> outsiderEvents = new LinkedBlockingQueue<>();
        CompletableFuture<Void> outsiderError = new CompletableFuture<>();
        StompHeaders outsiderConnectHeaders = new StompHeaders();
        outsiderConnectHeaders.add(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken);
        StompSession outsiderSession = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                outsiderConnectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(
                            StompSession session,
                            StompCommand command,
                            StompHeaders headers,
                            byte[] payload,
                            Throwable exception
                    ) {
                        outsiderError.complete(null);
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        outsiderError.complete(null);
                    }
                }
        ).get(5, TimeUnit.SECONDS);

        BlockingQueue<BoardEvent> ownerEvents = new LinkedBlockingQueue<>();
        StompSession ownerSession = connectAndSubscribe(ownerToken, owner.getEmail(), boardId, ownerEvents);

        try {
            outsiderSession.subscribe("/topic/boards/" + boardId, new BoardEventFrameHandler(outsiderEvents));
            // Server actively rejects unauthorized SUBSCRIBE frame with STOMP ERROR / disconnect
            outsiderError.get(5, TimeUnit.SECONDS);

            // Authenticated member attempting STOMP SEND injection into /topic/boards/{boardId} is rejected
            CompletableFuture<Void> attackerError = new CompletableFuture<>();
            StompHeaders attackerHeaders = new StompHeaders();
            attackerHeaders.add(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken);
            StompSession attackerSession = stompClient.connectAsync(
                    "ws://localhost:" + port + "/ws",
                    new WebSocketHttpHeaders(),
                    attackerHeaders,
                    new StompSessionHandlerAdapter() {
                        @Override
                        public void handleException(
                                StompSession session,
                                StompCommand command,
                                StompHeaders headers,
                                byte[] payload,
                                Throwable exception
                        ) {
                            attackerError.complete(null);
                        }

                        @Override
                        public void handleTransportError(StompSession session, Throwable exception) {
                            attackerError.complete(null);
                        }
                    }
            ).get(5, TimeUnit.SECONDS);
            try {
                attackerSession.send(
                        "/topic/boards/" + boardId,
                        BoardEvent.of(BoardEventType.TASK_CREATED, boardId, outsider.getId(), Map.of("spoof", true))
                );
                attackerError.get(5, TimeUnit.SECONDS);
                assertThat(ownerEvents.poll(400, TimeUnit.MILLISECONDS)).isNull();
            } finally {
                if (attackerSession.isConnected()) {
                    attackerSession.disconnect();
                }
            }

            // Failed mutation (invalid assigneeId) must NOT emit an event
            ResponseEntity<String> failedTaskResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks",
                    HttpMethod.POST,
                    new HttpEntity<>(
                            Map.of("title", "Inválida", "assigneeId", outsider.getId()),
                            authHeaders(ownerToken)
                    ),
                    String.class,
                    boardId
            );
            assertThat(failedTaskResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ownerEvents.poll(400, TimeUnit.MILLISECONDS)).isNull();

            // True post-publish rollback: publishEvent inside a transaction that rolls back afterwards MUST NOT emit
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
                applicationEventPublisher.publishEvent(BoardDomainEvent.of(
                        BoardEventType.BOARD_UPDATED,
                        boardId,
                        owner.getId(),
                        Map.of("name", "Rolled Back")
                ));
                throw new IllegalStateException("Forced rollback after publishEvent");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(ownerEvents.poll(400, TimeUnit.MILLISECONDS)).isNull();

            // Successful mutation reaches owner, never outsider
            ResponseEntity<TaskResponse> validTaskResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("title", "Tarefa Válida"), authHeaders(ownerToken)),
                    TaskResponse.class,
                    boardId
            );
            assertThat(validTaskResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(pollEventOfType(ownerEvents, BoardEventType.TASK_CREATED)).isNotNull();
            assertThat(outsiderEvents.poll(500, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            if (outsiderSession.isConnected()) {
                outsiderSession.disconnect();
            }
            if (ownerSession.isConnected()) {
                ownerSession.disconnect();
            }
        }
    }

    @Test
    void removedMemberReceivesSelfRemovalNotificationAndStopsReceivingSubsequentEvents() throws Exception {
        User owner = createActiveUser("Alice Dona", "alice@example.com");
        User member = createActiveUser("Bruno Editor", "bruno@example.com");
        String ownerToken = tokenService.generateAccessToken(owner);
        String memberToken = tokenService.generateAccessToken(member);

        BoardDetailResponse createdBoard = createBoard(ownerToken, "Quadro Remoção");
        UUID boardId = createdBoard.board().id();

        ResponseEntity<JsonNode> inviteResp = restTemplate.exchange(
                "/api/v1/boards/{boardId}/invites/email",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "bruno@example.com", "role", "EDITOR"), authHeaders(ownerToken)),
                JsonNode.class,
                boardId
        );
        assertThat(inviteResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String inviteToken = inviteResp.getBody().get("token").asText();

        ResponseEntity<JsonNode> acceptResp = restTemplate.exchange(
                "/api/v1/invites/{token}/accept",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(memberToken)),
                JsonNode.class,
                inviteToken
        );
        assertThat(acceptResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        BlockingQueue<BoardEvent> ownerEvents = new LinkedBlockingQueue<>();
        BlockingQueue<BoardEvent> memberEvents = new LinkedBlockingQueue<>();
        StompSession ownerSession = connectAndSubscribe(ownerToken, owner.getEmail(), boardId, ownerEvents);
        StompSession memberSession = connectAndSubscribe(memberToken, member.getEmail(), boardId, memberEvents);

        try {
            // Owner removes Bruno while Bruno's STOMP subscription stays open
            ResponseEntity<Void> removeResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/members/{userId}",
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(ownerToken)),
                    Void.class,
                    boardId,
                    member.getId()
            );
            assertThat(removeResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            BoardEvent ownerRemovalEvent = pollEventOfType(ownerEvents, BoardEventType.MEMBER_CHANGED);
            assertThat(ownerRemovalEvent.boardId()).isEqualTo(boardId);

            // Removed member still receives their self-removal notification
            BoardEvent memberRemovalEvent = pollEventOfType(memberEvents, BoardEventType.MEMBER_CHANGED);
            Map<?, ?> memberRemovalPayload = objectMapper.convertValue(memberRemovalEvent.payload(), Map.class);
            assertThat(memberRemovalPayload.get("removedUserId")).isEqualTo(member.getId().toString());

            // Subsequent board task creation is received by owner, but dropped for the already-removed member
            ResponseEntity<TaskResponse> taskResp = restTemplate.exchange(
                    "/api/v1/boards/{boardId}/tasks",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("title", "Tarefa Privada Pós-Remoção"), authHeaders(ownerToken)),
                    TaskResponse.class,
                    boardId
            );
            assertThat(taskResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(pollEventOfType(ownerEvents, BoardEventType.TASK_CREATED)).isNotNull();
            assertThat(memberEvents.poll(500, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            if (memberSession.isConnected()) {
                memberSession.disconnect();
            }
            if (ownerSession.isConnected()) {
                ownerSession.disconnect();
            }
        }
    }

    @Test
    void openApiDocsSmokeCheckReturns200WithCoreV1Paths() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getBody())
                .contains("/api/v1/auth/login")
                .contains("/api/v1/boards");
    }

    private StompSession connectAndSubscribe(
            String jwt,
            String userEmail,
            UUID boardId,
            BlockingQueue<BoardEvent> receivedEvents
    ) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);

        StompSession session = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {}
        ).get(5, TimeUnit.SECONDS);

        String destination = "/topic/boards/" + boardId;
        session.subscribe(destination, new BoardEventFrameHandler(receivedEvents));
        awaitSubscriptionRegistered(userEmail, destination);
        return session;
    }

    private void awaitSubscriptionRegistered(String userEmail, String destination) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            SimpUser user = simpUserRegistry.getUser(userEmail);
            if (user != null && user.getSessions().stream()
                    .flatMap(s -> s.getSubscriptions().stream())
                    .anyMatch(sub -> destination.equals(sub.getDestination()))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for STOMP subscription to " + destination);
    }

    private BoardEvent pollEventOfType(BlockingQueue<BoardEvent> queue, BoardEventType expectedType) throws InterruptedException {
        BoardEvent event = queue.poll(5, TimeUnit.SECONDS);
        assertThat(event)
                .as("Expected BoardEvent %s within timeout", expectedType)
                .isNotNull();
        assertThat(event.type()).isEqualTo(expectedType);
        return event;
    }

    private BoardDetailResponse createBoard(String token, String name) {
        ResponseEntity<BoardDetailResponse> response = restTemplate.exchange(
                "/api/v1/boards",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "name", name,
                                "color", "#0C2439",
                                "icon", "kanban",
                                "defaultView", "KANBAN"
                        ),
                        authHeaders(token)
                ),
                BoardDetailResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private User createActiveUser(String name, String email) {
        User user = new User(name, email, passwordEncoder.encode("Password123!"), true);
        return userRepository.saveAndFlush(user);
    }

    private static final class BoardEventFrameHandler implements StompFrameHandler {

        private final BlockingQueue<BoardEvent> receivedEvents;

        private BoardEventFrameHandler(BlockingQueue<BoardEvent> receivedEvents) {
            this.receivedEvents = receivedEvents;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return BoardEvent.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            if (payload instanceof BoardEvent event) {
                receivedEvents.offer(event);
            }
        }
    }
}
