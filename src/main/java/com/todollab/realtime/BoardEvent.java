package com.todollab.realtime;

import java.time.Instant;
import java.util.UUID;

public record BoardEvent(
        BoardEventType type,
        UUID boardId,
        UUID actorId,
        Instant timestamp,
        Object payload
) {
    public static BoardEvent of(BoardEventType type, UUID boardId, UUID actorId, Object payload) {
        return new BoardEvent(type, boardId, actorId, Instant.now(), payload);
    }
}
