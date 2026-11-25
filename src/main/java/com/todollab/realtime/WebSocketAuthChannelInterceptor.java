package com.todollab.realtime;

import com.todollab.auth.model.User;
import com.todollab.auth.repository.UserRepository;
import com.todollab.auth.security.UserPrincipal;
import com.todollab.auth.service.TokenService;
import com.todollab.board.repository.BoardMemberRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern BOARD_TOPIC_PATTERN = Pattern.compile("^/topic/boards/([0-9a-fA-F-]{36})$");

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final BoardMemberRepository boardMemberRepository;

    public WebSocketAuthChannelInterceptor(
            TokenService tokenService,
            UserRepository userRepository,
            BoardMemberRepository boardMemberRepository
    ) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.boardMemberRepository = boardMemberRepository;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (accessor.getCommand() == StompCommand.CONNECT) {
            return handleConnect(message, accessor);
        }
        if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            handleSubscribe(accessor);
        }
        return message;
    }

    private Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
        String token = extractBearerToken(accessor);
        User user = tokenService.parseAccessToken(token)
                .flatMap(claims -> userRepository.findById(claims.userId()))
                .filter(User::isActive)
                .orElseThrow(() -> new AccessDeniedException("Token de autenticação inválido ou usuário inativo."));

        UserPrincipal principal = UserPrincipal.from(user);
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
}
