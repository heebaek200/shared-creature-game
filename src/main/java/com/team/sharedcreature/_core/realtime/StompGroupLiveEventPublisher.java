package com.team.sharedcreature._core.realtime;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** 그룹 이벤트 Envelope을 생성해 Simple Broker로 전송합니다. */
@Slf4j
@Component
public class StompGroupLiveEventPublisher implements GroupLiveEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final GroupLiveStreamService streamService;

    /** STOMP 전송기와 그룹 Stream 상태를 주입받습니다. */
    public StompGroupLiveEventPublisher(
            SimpMessagingTemplate messagingTemplate,
            GroupLiveStreamService streamService
    ) {
        this.messagingTemplate = messagingTemplate;
        this.streamService = streamService;
    }

    /** 전송 실패가 이미 Commit된 업무 결과를 되돌리지 않도록 실패를 기록하고 종료합니다. */
    @Override
    public void publish(Long groupId, String eventType, Instant occurredAt, Object payload) {
        long sequence = streamService.nextSequence(groupId);
        GroupLiveEvent event = new GroupLiveEvent(
                UUID.randomUUID(),
                streamService.streamInstanceId(),
                sequence,
                eventType,
                groupId,
                occurredAt,
                payload
        );
        try {
            messagingTemplate.convertAndSend("/topic/groups/" + groupId + "/live", event);
        } catch (RuntimeException exception) {
            log.error(
                    "그룹 실시간 이벤트 전송 실패. groupId={}, eventType={}, sequence={}",
                    groupId,
                    eventType,
                    sequence,
                    exception
            );
        }
    }
}
