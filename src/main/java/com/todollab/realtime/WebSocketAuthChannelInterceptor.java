package com.todollab.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todollab.auth.model.User;
import com.todollab.auth.repository.UserRepository;
import com.todollab.auth.security.UserPrincipal;
import com.todollab.auth.service.TokenService;
import com.todollab.board.repository.BoardMemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
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
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern BOARD_TOPIC_PATTERN = Pattern.compile("^/topic/boards/([0-9a-fA-F-]{36})$");

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, UUID> sessionUsers = new ConcurrentHashMap<>();

    public WebSocketAuthChannelInterceptor(
            TokenService tokenService,
            UserRepository userRepository,
            BoardMemberRepository boardMemberRepository
    ) {
        this(tokenService, userRepository, boardMemberRepository, new ObjectMapper().findAndRegisterModules());
    }

    @Autowired
    public WebSocketAuthChannelInterceptor(
            TokenService tokenService,
            UserRepository userRepository,
            BoardMemberRepository boardMemberRepository,
            ObjectMapper objectMapper
    ) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            return handleConnect(message, accessor);
        }
        if (command == StompCommand.SUBSCRIBE) {
            handleSubscribe(accessor);
            return message;
        }
        if (command == StompCommand.SEND || command == StompCommand.MESSAGE) {
            throw new AccessDeniedException("Envio direto de mensagens STOMP não é permitido.");
        }
        if (command == StompCommand.DISCONNECT && accessor.getSessionId() != null) {
            sessionUsers.remove(accessor.getSessionId());
        }
        return message;
    }

    public ChannelInterceptor outboundChannelInterceptor() {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                return filterOutboundMessage(message);
            }
        };
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        if (event.getSessionId() != null) {
            sessionUsers.remove(event.getSessionId());
        }
    }

    private Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
        String token = extractBearerToken(accessor);
        User user = tokenService.parseAccessToken(token)
                .flatMap(claims -> userRepository.findById(claims.userId()))
                .filter(User::isActive)
                .orElseThrow(() -> new AccessDeniedException("Token de autenticação inválido ou usuário inativo."));

        UserPrincipal principal = UserPrincipal.from(user);
        if (accessor.getSessionId() != null) {
            sessionUsers.put(accessor.getSessionId(), principal.id());
        }
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());

        if (accessor.isMutable()) {
            accessor.setUser(authentication);
            return message;
        }

        StompHeaderAccessor writableAccessor = StompHeaderAccessor.wrap(message);
        writableAccessor.setUser(authentication);
        writableAccessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(message.getPayload(), writableAccessor.getMessageHeaders());
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        UserPrincipal principal = extractAuthenticatedPrincipal(accessor.getUser());
        UUID boardId = extractAuthorizedBoardId(accessor.getDestination());
        if (!boardMemberRepository.existsByBoardIdAndUserId(boardId, principal.id())) {
            throw new AccessDeniedException("Você não tem permissão para acompanhar este quadro.");
        }
        if (accessor.getSessionId() != null) {
            sessionUsers.putIfAbsent(accessor.getSessionId(), principal.id());
        }
    }

    private Message<?> filterOutboundMessage(Message<?> message) {
        SimpMessageHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, SimpMessageHeaderAccessor.class);
        if (accessor == null || accessor.getMessageType() != SimpMessageType.MESSAGE) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            return null;
        }
        Matcher matcher = BOARD_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            return message;
        }

        UUID boardId;
        try {
            boardId = UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException ex) {
            return null;
        }

        UUID userId = resolveOutboundUserId(accessor);
        if (userId == null) {
            return null;
        }

        OutboundEventMeta eventMeta = extractEventMeta(message.getPayload());
        if (eventMeta != null) {
            if (eventMeta.type() == BoardEventType.BOARD_DELETED) {
                return message;
            }
            if (eventMeta.type() == BoardEventType.MEMBER_CHANGED) {
                if (userId.equals(eventMeta.removedUserId())) {
                    return message;
                }
                if (eventMeta.removedUserId() == null && eventMeta.action() == null) {
                    return message;
                }
            }
        }

        return boardMemberRepository.existsByBoardIdAndUserId(boardId, userId) ? message : null;
    }

    private UUID resolveOutboundUserId(SimpMessageHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof Authentication authentication
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.active()) {
            return principal.id();
        }
        String sessionId = accessor.getSessionId();
        return sessionId != null ? sessionUsers.get(sessionId) : null;
    }

    private OutboundEventMeta extractEventMeta(Object payload) {
        if (payload instanceof BoardEvent event) {
            String action = null;
            UUID removedUserId = null;
            if (event.payload() instanceof Map<?, ?> map) {
                Object actionVal = map.get("action");
                action = actionVal != null ? actionVal.toString() : null;
                removedUserId = parseUuid(map.get("removedUserId"));
            }
            return new OutboundEventMeta(event.type(), action, removedUserId);
        }

        JsonNode root = readJsonTree(payload);
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode typeNode = root.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            return null;
        }
        BoardEventType eventType;
        try {
            eventType = BoardEventType.valueOf(typeNode.asText());
        } catch (IllegalArgumentException ex) {
            return null;
        }

        JsonNode payloadNode = root.get("payload");
        String action = null;
        UUID removedUserId = null;
        if (payloadNode != null && payloadNode.isObject()) {
            JsonNode actionNode = payloadNode.get("action");
            if (actionNode != null && actionNode.isTextual()) {
                action = actionNode.asText();
            }
            JsonNode removedNode = payloadNode.get("removedUserId");
            if (removedNode != null && removedNode.isTextual()) {
                removedUserId = parseUuid(removedNode.asText());
            }
        }
        return new OutboundEventMeta(eventType, action, removedUserId);
    }

    private JsonNode readJsonTree(Object payload) {
        try {
            if (payload instanceof byte[] bytes) {
                return objectMapper.readTree(bytes);
            }
            if (payload instanceof String json) {
                return objectMapper.readTree(json);
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private UUID parseUuid(Object raw) {
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String str && !str.isBlank()) {
            try {
                return UUID.fromString(str);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private String extractBearerToken(StompHeaderAccessor accessor) {
        String header = findAuthorizationHeader(accessor);
        if (header == null || header.length() < BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new AccessDeniedException("Cabeçalho de autorização ausente ou inválido.");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new AccessDeniedException("Token de autorização ausente.");
        }
        return token;
    }

    private String findAuthorizationHeader(StompHeaderAccessor accessor) {
        String direct = accessor.getFirstNativeHeader("Authorization");
        if (direct != null) {
            return direct;
        }
        Map<String, List<String>> nativeHeaders = accessor.toNativeHeaderMap();
        for (Map.Entry<String, List<String>> entry : nativeHeaders.entrySet()) {
            if ("authorization".equalsIgnoreCase(entry.getKey())
                    && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private UserPrincipal extractAuthenticatedPrincipal(Principal user) {
        if (user instanceof Authentication authentication
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.active()) {
            return principal;
        }
        throw new AccessDeniedException("Sessão WebSocket não autenticada.");
    }

    private UUID extractAuthorizedBoardId(String destination) {
        if (destination == null) {
            throw new AccessDeniedException("Destino STOMP inválido.");
        }
        Matcher matcher = BOARD_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            throw new AccessDeniedException("Destino STOMP não permitido.");
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("Identificador de quadro inválido no destino STOMP.");
        }
    }

    private record OutboundEventMeta(BoardEventType type, String action, UUID removedUserId) {
    }
}
