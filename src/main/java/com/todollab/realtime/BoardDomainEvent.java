package com.todollab.realtime;

import java.util.UUID;

public record BoardDomainEvent(BoardEvent event) {

    public static BoardDomainEvent of(BoardEventType type, UUID boardId, UUID actorId, Object payload) {
        return new BoardDomainEvent(BoardEvent.of(type, boardId, actorId, payload));
    }
}
