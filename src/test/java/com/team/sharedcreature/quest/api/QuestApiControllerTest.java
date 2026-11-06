package com.team.sharedcreature.quest.api;

import com.team.sharedcreature._core.api.ApiResponse;
import com.team.sharedcreature._core.security.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestApiControllerTest {

    @Mock
    private QuestCompletionService questCompletionService;

    @Mock
    private CheckInService checkInService;

    private QuestController questController;
    private CheckInController checkInController;

    @BeforeEach
    void setUp() {
        questController = new QuestController(questCompletionService);
        checkInController = new CheckInController(checkInService);
    }

    @Test
    void 현재_퀘스트_조회는_로그인_사용자와_기간_유형을_서비스에_전달한다() {
        LoginUser loginUser = new LoginUser(7L, "테스트 사용자", Set.of("MEMBER"));
        CurrentQuestResponse quest = new CurrentQuestResponse(
                11L,
                21L,
                "DAILY",
                Instant.parse("2026-11-04T15:00:00Z"),
                Instant.parse("2026-11-05T15:00:00Z"),
                1,
                "D-L-01",
                "물 마시기",
                "물 한 잔 마십니다.",
                "LOW",
                "SELF_CHECK",
                100L,
                10L,
                false,
                null,
                null,
                new CurrentQuestResponse.ProgressResponse(0, 1),
                null
        );
        when(questCompletionService.listCurrentQuests(7L, "DAILY"))
                .thenReturn(List.of(quest));

        ApiResponse<List<CurrentQuestResponse>> response =
                questController.listCurrentQuests(loginUser, "DAILY");

        assertThat(response.data()).containsExactly(quest);
        verify(questCompletionService).listCurrentQuests(7L, "DAILY");
    }

    @Test
    void 퀘스트_완료는_로그인_사용자와_배정_ID를_서비스에_전달한다() {
        LoginUser loginUser = new LoginUser(7L, "테스트 사용자", Set.of("MEMBER"));
        QuestCompletionResponse completion = new QuestCompletionResponse(
                31L,
                11L,
                7L,
                Instant.parse("2026-11-05T01:00:00Z"),
                100L,
                10L,
                500L,
                "HATCHLING",
                10L
        );
        when(questCompletionService.completeQuestAssignment(7L, 11L))
                .thenReturn(completion);

        ApiResponse<QuestCompletionResponse> response =
                questController.completeQuestAssignment(loginUser, 11L);

        assertThat(response.data()).isSameAs(completion);
        verify(questCompletionService).completeQuestAssignment(7L, 11L);
    }

    @Test
    void 체크인은_로그인_사용자를_서비스에_전달하고_결과를_data로_감싼다() {
        LoginUser loginUser = new LoginUser(7L, "테스트 사용자", Set.of("MEMBER"));
        CheckInResponse checkIn = new CheckInResponse(
                41L,
                LocalDate.of(2026, 11, 5),
                Instant.parse("2026-11-05T01:00:00Z"),
                true,
                List.of(31L)
        );
        when(checkInService.createCheckIn(7L)).thenReturn(checkIn);

        ApiResponse<CheckInResponse> response = checkInController.createCheckIn(loginUser);

        assertThat(response.data()).isSameAs(checkIn);
        assertThat(response.data().getCheckInId()).isEqualTo(41L);
        assertThat(response.data().getCompletedQuestIds()).containsExactly(31L);
        verify(checkInService).createCheckIn(7L);
    }
}
