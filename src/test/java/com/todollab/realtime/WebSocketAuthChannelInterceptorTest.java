package com.todollab.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.todollab.auth.model.User;
import com.todollab.auth.repository.UserRepository;
import com.todollab.auth.security.UserPrincipal;
import com.todollab.auth.service.TokenService;
import com.todollab.auth.service.TokenService.TokenClaims;
import com.todollab.board.repository.BoardMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthChannelInterceptorTest {

    @Mock
    private TokenService tokenService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BoardMemberRepository boardMemberRepository;

    @Mock
    private MessageChannel messageChannel;

    private WebSocketAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthChannelInterceptor(tokenService, userRepository, boardMemberRepository);
    }

    @Test
    void connectWithoutOrWithInvalidTokenThrowsAccessDenied() {
        Message<byte[]> missingHeader = stompMessage(StompCommand.CONNECT, null, null, null);
        assertThatThrownBy(() -> interceptor.preSend(missingHeader, messageChannel))
                .isInstanceOf(AccessDeniedException.class);

        Message<byte[]> nonBearer = stompMessage(StompCommand.CONNECT, "Basic dXNlcjpwYXNz", null, null);
        assertThatThrownBy(() -> interceptor.preSend(nonBearer, messageChannel))
                .isInstanceOf(AccessDeniedException.class);

        Message<byte[]> blankBearer = stompMessage(StompCommand.CONNECT, "Bearer   ", null, null);
        assertThatThrownBy(() -> interceptor.preSend(blankBearer, messageChannel))
                .isInstanceOf(AccessDeniedException.class);

        when(tokenService.parseAccessToken("bad.jwt.token")).thenReturn(Optional.empty());
        Message<byte[]> invalidJwt = stompMessage(StompCommand.CONNECT, "Bearer bad.jwt.token", null, null);
        assertThatThrownBy(() -> interceptor.preSend(invalidJwt, messageChannel))
                .isInstanceOf(AccessDeniedException.class);

        UUID inactiveUserId = UUID.randomUUID();
        User inactiveUser = createUser(inactiveUserId, "Inactive", "inactive@example.com", false);
        when(tokenService.parseAccessToken("inactive.jwt.token"))
                .thenReturn(Optional.of(new TokenClaims(inactiveUserId, "inactive@example.com")));
        when(userRepository.findById(inactiveUserId)).thenReturn(Optional.of(inactiveUser));
        Message<byte[]> inactiveConnect = stompMessage(StompCommand.CONNECT, "Bearer inactive.jwt.token", null, null);
        assertThatThrownBy(() -> interceptor.preSend(inactiveConnect, messageChannel))
                .isInstanceOf(AccessDeniedException.class);

        UUID unknownUserId = UUID.randomUUID();
        when(tokenService.parseAccessToken("unknown.jwt.token"))
                .thenReturn(Optional.of(new TokenClaims(unknownUserId, "unknown@example.com")));
        when(userRepository.findById(unknownUserId)).thenReturn(Optional.empty());
        Message<byte[]> unknownConnect = stompMessage(StompCommand.CONNECT, "Bearer unknown.jwt.token", null, null);
        assertThatThrownBy(() -> interceptor.preSend(unknownConnect, messageChannel))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(boardMemberRepository);
    }

    @Test
    void connectWithValidTokenSetsUserPrincipal() {
        UUID userId = UUID.randomUUID();
        User activeUser = createUser(userId, "Alice Silva", "alice@example.com", true);
        when(tokenService.parseAccessToken("valid.jwt.token"))
                .thenReturn(Optional.of(new TokenClaims(userId, "alice@example.com")));
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "bearer valid.jwt.token");
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, messageChannel);
        assertThat(result).isNotNull();

        StompHeaderAccessor resultAccessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(resultAccessor).isNotNull();
        assertThat(resultAccessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        UsernamePasswordAuthenticationToken auth = (UsernamePasswordAuthenticationToken) resultAccessor.getUser();
        assertThat(auth.getPrincipal()).isEqualTo(new UserPrincipal(userId, "alice@example.com", "Alice Silva", true));
    }

    @Test
    void subscribeToBoardTopicAllowedForMemberAndRejectedForNonMember() {
        UUID userId = UUID.randomUUID();
        UUID boardId = UUID.randomUUID();
        UserPrincipal principal = new UserPrincipal(userId, "alice@example.com", "Alice Silva", true);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());

        when(boardMemberRepository.existsByBoardIdAndUserId(boardId, userId)).thenReturn(true);
        Message<byte[]> allowed = stompMessage(StompCommand.SUBSCRIBE, null, "/topic/boards/" + boardId, auth);
        assertThat(interceptor.preSend(allowed, messageChannel)).isSameAs(allowed);
        verify(boardMemberRepository).existsByBoardIdAndUserId(boardId, userId);

        UUID otherBoardId = UUID.randomUUID();
        when(boardMemberRepository.existsByBoardIdAndUserId(otherBoardId, userId)).thenReturn(false);
        Message<byte[]> forbidden = stompMessage(StompCommand.SUBSCRIBE, null, "/topic/boards/" + otherBoardId, auth);
        assertThatThrownBy(() -> interceptor.preSend(forbidden, messageChannel))
                .isInstanceOf(AccessDeniedException.class);

        Message<byte[]> unauthenticated = stompMessage(StompCommand.SUBSCRIBE, null, "/topic/boards/" + boardId, null);
        assertThatThrownBy(() -> interceptor.preSend(unauthenticated, messageChannel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void subscribeToUnknownOrMalformedTopicRejected() {
        UUID userId = UUID.randomUUID();
        UserPrincipal principal = new UserPrincipal(userId, "alice@example.com", "Alice Silva", true);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());

        List<String> invalidDestinations = List.of(
                "/topic/other",
                "/topic/boards",
                "/topic/boards/",
                "/topic/boards/not-a-uuid",
                "/topic/boards/------------------------------------",
                "/topic/boards/" + UUID.randomUUID() + "/extra",
                "/queue/boards/" + UUID.randomUUID()
        );

        for (String destination : invalidDestinations) {
            Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, null, destination, auth);
            assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
                    .as("Expected rejection for destination %s", destination)
                    .isInstanceOf(AccessDeniedException.class);
        }

        Message<byte[]> nullDestination = stompMessage(StompCommand.SUBSCRIBE, null, null, auth);
        assertThatThrownBy(() -> interceptor.preSend(nullDestination, messageChannel))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(boardMemberRepository);
    }

    @Test
    void stomp12HandshakeFrameAuthenticatesValidTokenAndRejectsInvalidToken() {
        Message<byte[]> missingHeader = stompMessage(StompCommand.STOMP, null, null, null);
        assertThatThrownBy(() -> interceptor.preSend(missingHeader, messageChannel))
                .isInstanceOf(AccessDeniedException.class);

        UUID userId = UUID.randomUUID();
        User activeUser = createUser(userId, "Alice Silva", "alice@example.com", true);
        when(tokenService.parseAccessToken("valid.stomp.token"))
                .thenReturn(Optional.of(new TokenClaims(userId, "alice@example.com")));
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.STOMP);
        accessor.setNativeHeader("Authorization", "Bearer valid.stomp.token");
        accessor.setSessionId("stomp-session-1");
        accessor.setLeaveMutable(true);
        Message<byte[]> validStomp = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(validStomp, messageChannel);
        assertThat(result).isNotNull();
        StompHeaderAccessor resultAccessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(resultAccessor).isNotNull();
        assertThat(resultAccessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
    }

    @Test
    void sendCommandIsRejectedWithAccessDenied() {
        UUID userId = UUID.randomUUID();
        UUID boardId = UUID.randomUUID();
        UserPrincipal principal = new UserPrincipal(userId, "alice@example.com", "Alice Silva", true);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());

        Message<byte[]> authenticatedSend = stompMessage(StompCommand.SEND, null, "/topic/boards/" + boardId, auth);
        assertThatThrownBy(() -> interceptor.preSend(authenticatedSend, messageChannel))
                .isInstanceOf(AccessDeniedException.class);

        Message<byte[]> unauthenticatedSend = stompMessage(StompCommand.SEND, null, "/topic/boards/" + boardId, null);
        assertThatThrownBy(() -> interceptor.preSend(unauthenticatedSend, messageChannel))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(boardMemberRepository);
    }

    @Test
    void outboundMessageDroppedForRemovedMemberExceptSelfRemovalAndBoardDeleted() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID boardId = UUID.randomUUID();
        String sessionId = "ws-session-42";
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();

        User activeUser = createUser(userId, "Bruno Editor", "bruno@example.com", true);
        when(tokenService.parseAccessToken("member.jwt"))
                .thenReturn(Optional.of(new TokenClaims(userId, "bruno@example.com")));
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));

        StompHeaderAccessor connectAccessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        connectAccessor.setSessionId(sessionId);
        connectAccessor.setNativeHeader("Authorization", "Bearer member.jwt");
        connectAccessor.setLeaveMutable(true);
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], connectAccessor.getMessageHeaders()), messageChannel);

        ChannelInterceptor outbound = interceptor.outboundChannelInterceptor();
        String destination = "/topic/boards/" + boardId;

        byte[] taskCreatedBytes = mapper.writeValueAsString(
                BoardEvent.of(BoardEventType.TASK_CREATED, boardId, otherUserId, Map.of("title", "Secret"))
        ).getBytes(StandardCharsets.UTF_8);

        when(boardMemberRepository.existsByBoardIdAndUserId(boardId, userId)).thenReturn(true);
        Message<byte[]> allowedTaskMessage = outboundBrokerMessage(sessionId, destination, taskCreatedBytes);
        assertThat(outbound.preSend(allowedTaskMessage, messageChannel)).isSameAs(allowedTaskMessage);

        when(boardMemberRepository.existsByBoardIdAndUserId(boardId, userId)).thenReturn(false);
        Message<byte[]> droppedTaskMessage = outboundBrokerMessage(sessionId, destination, taskCreatedBytes);
        assertThat(outbound.preSend(droppedTaskMessage, messageChannel)).isNull();

        byte[] selfRemovedBytes = mapper.writeValueAsString(
                BoardEvent.of(BoardEventType.MEMBER_CHANGED, boardId, otherUserId,
                        Map.of("action", "REMOVED", "removedUserId", userId))
        ).getBytes(StandardCharsets.UTF_8);
        Message<byte[]> allowedSelfRemoval = outboundBrokerMessage(sessionId, destination, selfRemovedBytes);
        assertThat(outbound.preSend(allowedSelfRemoval, messageChannel)).isSameAs(allowedSelfRemoval);

        byte[] otherRoleChangedBytes = mapper.writeValueAsString(
                BoardEvent.of(BoardEventType.MEMBER_CHANGED, boardId, otherUserId,
                        Map.of("action", "ROLE_UPDATED", "member", Map.of("userId", otherUserId)))
        ).getBytes(StandardCharsets.UTF_8);
        Message<byte[]> droppedOtherMemberChange = outboundBrokerMessage(sessionId, destination, otherRoleChangedBytes);
        assertThat(outbound.preSend(droppedOtherMemberChange, messageChannel)).isNull();

        byte[] boardDeletedBytes = mapper.writeValueAsString(
                BoardEvent.of(BoardEventType.BOARD_DELETED, boardId, otherUserId, Map.of("boardId", boardId))
        ).getBytes(StandardCharsets.UTF_8);
        Message<byte[]> allowedBoardDeleted = outboundBrokerMessage(sessionId, destination, boardDeletedBytes);
        assertThat(outbound.preSend(allowedBoardDeleted, messageChannel)).isSameAs(allowedBoardDeleted);

        Message<byte[]> unknownSessionMessage = outboundBrokerMessage("unknown-session", destination, taskCreatedBytes);
        assertThat(outbound.preSend(unknownSessionMessage, messageChannel)).isNull();
    }

    private Message<byte[]> outboundBrokerMessage(String sessionId, String destination, byte[] payload) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
    }

    private Message<byte[]> stompMessage(
            StompCommand command,
            String authorizationHeader,
            String destination,
            UsernamePasswordAuthenticationToken user
    ) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private User createUser(UUID id, String name, String email, boolean active) {
        User user = new User(name, email, "hashed-password", active);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
