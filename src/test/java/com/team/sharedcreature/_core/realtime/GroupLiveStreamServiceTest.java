package com.team.sharedcreature._core.realtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupLiveStreamServiceTest {

    @Test
    void 그룹별로_독립된_순번을_발급한다() {
        GroupLiveStreamService service = new GroupLiveStreamService();

        assertThat(service.currentSequence(1L)).isZero();
        assertThat(service.nextSequence(1L)).isEqualTo(1L);
        assertThat(service.nextSequence(1L)).isEqualTo(2L);
        assertThat(service.nextSequence(2L)).isEqualTo(1L);
        assertThat(service.currentSequence(1L)).isEqualTo(2L);
    }
}
