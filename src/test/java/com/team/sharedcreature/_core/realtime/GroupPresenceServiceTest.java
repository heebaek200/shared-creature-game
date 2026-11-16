package com.team.sharedcreature._core.realtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupPresenceServiceTest {

    private final GroupPresenceService service = new GroupPresenceService();

    @Test
    void 같은_사용자의_첫_연결과_마지막_해제를_구분한다() {
        GroupPresenceService.Registration first = service.register("ws-1", "http-1", "sub-1", 10L, 20L);
        GroupPresenceService.Registration second = service.register("ws-2", "http-1", "sub-2", 10L, 20L);

        assertThat(first.firstSession()).isTrue();
        assertThat(second.firstSession()).isFalse();
        assertThat(service.activeUserIds(20L)).containsExactly(10L);
        assertThat(service.removeSession("ws-1").orElseThrow().lastSession()).isFalse();
        assertThat(service.removeSession("ws-2").orElseThrow().lastSession()).isTrue();
        assertThat(service.activeUserIds(20L)).isEmpty();
    }

    @Test
    void 하나의_WebSocket은_다른_그룹에_중복_등록할_수_없다() {
        service.register("ws-1", "http-1", "sub-1", 10L, 20L);

        assertThatThrownBy(() -> service.register("ws-1", "http-1", "sub-2", 10L, 20L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 사용자_권한_회수는_모든_WebSocket_ID를_반환한다() {
        service.register("ws-1", "http-1", "sub-1", 10L, 20L);
        service.register("ws-2", "http-2", "sub-2", 10L, 20L);
        service.register("ws-3", "http-3", "sub-3", 11L, 20L);

        List<GroupPresenceService.Removal> removals = service.removeUser(10L);

        assertThat(removals).extracting(GroupPresenceService.Removal::webSocketSessionId)
                .containsExactlyInAnyOrder("ws-1", "ws-2");
        assertThat(removals).filteredOn(GroupPresenceService.Removal::lastSession).hasSize(1);
        assertThat(service.activeUserIds(20L)).containsExactly(11L);
    }

    @Test
    void 다른_구독_ID로는_Presence를_해제할_수_없다() {
        service.register("ws-1", "http-1", "sub-1", 10L, 20L);

        assertThatThrownBy(() -> service.removeSubscription("ws-1", "sub-2"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.activeUserIds(20L)).containsExactly(10L);
    }
}
