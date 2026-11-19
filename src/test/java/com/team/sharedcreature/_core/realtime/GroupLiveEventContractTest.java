package com.team.sharedcreature._core.realtime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GroupLiveEventContractTest {

    @ParameterizedTest
    @CsvSource({
            "PRESENCE_JOINED, PRESENCE_JOINED",
            "PRESENCE_LEFT, PRESENCE_LEFT",
            "AVATAR_EQUIPMENT_CHANGED, AVATAR_EQUIPMENT_CHANGED",
            "CREATURE_UPDATED, CREATURE_UPDATED",
            "CONTRIBUTION_UPDATED, CONTRIBUTION_UPDATED"
    })
    void publishesDocumentedEventTypeAsJsonString(GroupLiveEventType type, String expected) {
        var messaging = mock(SimpMessagingTemplate.class);
        var publisher = new StompGroupLiveEventPublisher(messaging, new GroupLiveStreamService());
        var event = ArgumentCaptor.forClass(GroupLiveEvent.class);

        publisher.publish(7L, type, Instant.EPOCH, Map.of());

        verify(messaging).convertAndSend(eq("/topic/groups/7/live"), event.capture());
        var mapper = new ObjectMapper();
        var eventType = mapper.readTree(mapper.writeValueAsString(event.getValue())).get("eventType");
        assertThat(eventType.isString()).isTrue();
        assertThat(eventType.asString()).isEqualTo(expected);
    }
}
