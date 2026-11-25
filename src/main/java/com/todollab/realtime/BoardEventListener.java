package com.todollab.realtime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BoardEventListener {

    private final BoardEventPublisher boardEventPublisher;

    public BoardEventListener(BoardEventPublisher boardEventPublisher) {
        this.boardEventPublisher = boardEventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBoardDomainEvent(BoardDomainEvent domainEvent) {
        boardEventPublisher.publish(domainEvent.event());
    }
}
