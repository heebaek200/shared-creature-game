package com.team.sharedcreature.admin.application;

import com.team.sharedcreature.admin.api.OperationalWarningResponse;
import com.team.sharedcreature.admin.domain.OperationalWarningSeverity;
import com.team.sharedcreature.admin.domain.OperationalWarningType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class OperationalWarningServiceTest {

    @Test
    void typeFilterUsesTheExistingApiCodes() {
        var service = spy(new OperationalWarningService(mock(jakarta.persistence.EntityManager.class), Clock.systemUTC()));
        var photoWarning = new OperationalWarningResponse(
                "PHOTO_DELETION_FAILED:PHOTO_SUBMISSION:1",
                OperationalWarningType.PHOTO_DELETION_FAILED,
                OperationalWarningSeverity.WARNING, "PHOTO_SUBMISSION", 1L, "photo warning", Instant.EPOCH);
        var seasonWarning = new OperationalWarningResponse(
                "SEASON_STATE_INVALID:SEASON:2",
                OperationalWarningType.SEASON_STATE_INVALID,
                OperationalWarningSeverity.CRITICAL, "SEASON", 2L, "season warning", Instant.EPOCH.plusSeconds(1));
        doReturn(List.of(photoWarning, seasonWarning)).when(service).currentWarnings();

        assertThat(service.list(OperationalWarningType.PHOTO_DELETION_FAILED, 0, 20).getContent())
                .containsExactly(photoWarning);
        assertThat(service.list(OperationalWarningType.REFUND_UNCERTAIN, 0, 20)).isEmpty();
        assertThat(service.list(null, 0, 20).getContent()).containsExactly(seasonWarning, photoWarning);
    }
}
