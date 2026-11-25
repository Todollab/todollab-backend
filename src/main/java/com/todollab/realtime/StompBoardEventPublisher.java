package com.todollab.realtime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class StompBoardEventPublisher implements BoardEventPublisher {

    private static final String BOARD_TOPIC_PREFIX = "/topic/boards/";

    private final SimpMessagingTemplate messagingTemplate;

    public StompBoardEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publish(BoardEvent event) {
        messagingTemplate.convertAndSend(BOARD_TOPIC_PREFIX + event.boardId(), event);
    }
}
