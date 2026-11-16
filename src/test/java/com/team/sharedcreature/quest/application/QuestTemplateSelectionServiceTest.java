package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.domain.QuestTemplate;
import com.team.sharedcreature.quest.domain.QuestTemplateVersion;
import com.team.sharedcreature.quest.infrastructure.QuestTemplateRepository;
import com.team.sharedcreature.quest.infrastructure.QuestTemplateVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 일일·주간 퀘스트의 난이도별 활성 카탈로그 순회와 스냅샷 추출을 검증합니다.
 * DB 대신 Repository Mock과 실제 엔티티 필드값을 사용해 순수 선정 규칙만 검사합니다.
 * 4-F-3에서 기간 영속화·재실행·동시성 검증을 추가할 예정입니다.
 */
@ExtendWith(MockitoExtension.class)
class QuestTemplateSelectionServiceTest {

    private static final Instant FIRST_START = Instant.parse("2026-11-01T15:00:00Z");

    @Mock private QuestTemplateRepository templateRepository;
    @Mock private QuestTemplateVersionRepository versionRepository;

    private QuestTemplateSelectionService service;
    private final List<QuestTemplate> catalog = new ArrayList<>();
    private final Map<Long, QuestTemplateVersion> latestVersions = new HashMap<>();

    /**
     * 서비스의 Repository 의존성을 Mock으로 연결합니다.
     * 날짜 계산은 이전 4-F-1에서 검증된 실제 Calendar를 사용합니다.
     * 각 테스트의 카탈로그와 버전 데이터는 독립적으로 준비합니다.
     */
    @BeforeEach
    void setUp() {
        service = new QuestTemplateSelectionService(templateRepository, versionRepository);
    }

    /**
     * 일일 슬롯은 하·중·상 각 하나이며 오프셋 0·1·2를 적용합니다.
     * 회전 인덱스 0에서 각각 첫째·둘째·셋째 템플릿을 선택합니다.
     * Snapshot과 템플릿·버전 FK가 모두 준비돼야 합니다.
     */
    @Test
    void dailyFirstDaySelectsOneOfEachDifficultyWithOffsets() {
        prepareCatalog("DAILY", 5, 4, 3);
        stubLatestVersions();

        var batch = service.select(window("DAILY", 0));

        // 슬롯은 난이도 순서로 부여하고 버전 스냅샷을 반환합니다.
        assertEquals(3, batch.slots().size());
        assertEquals(List.of("D-L-01", "D-M-02", "D-H-03"), codes(batch));
        assertEquals(List.of(1, 2, 3), slotNumbers(batch));
        assertEquals(List.of("LOW", "MEDIUM", "HIGH"), difficulties(batch));
        assertEquals("D-M-02 v2", batch.slots().get(1).titleSnapshot());
        assertEquals("{}", batch.slots().get(1).verificationParametersSnapshot());
        assertEquals(Long.valueOf(15L), batch.slots().get(1).creatureExperienceSnapshot());
        assertEquals(Long.valueOf(6L), batch.slots().get(1).contributionScoreSnapshot());
    }

    /**
     * 주간 슬롯은 하 3개, 중 2개, 상 2개입니다.
     * 동일 난이도 내에서는 회전 시작 이후 연속 템플릿을 배정합니다.
     * 일곱 슬롯의 템플릿 ID가 모두 달라야 DB UNIQUE와 충돌하지 않습니다.
     */
    @Test
    void weeklyFirstWeekSelectsSevenDistinctTemplates() {
        prepareCatalog("WEEKLY", 6, 4, 4);
        stubLatestVersions();

        var batch = service.select(window("WEEKLY", 0));

        // 기준 주차 0의 난이도별 시작 오프셋은 0·1·2입니다.
        assertEquals(List.of("W-L-01", "W-L-02", "W-L-03",
                "W-M-02", "W-M-03", "W-H-03", "W-H-04"), codes(batch));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), slotNumbers(batch));
        assertEquals(7L, batch.slots().stream().map(QuestTemplateSelectionService.SelectedQuest::templateId)
                .distinct().count());
    }

    /**
     * 다음 월요일에는 난이도별 회전 시작 위치가 각각 한 칸 이동합니다.
     * 상 난이도는 마지막 원소를 지나면 첫 원소로 순환합니다.
     * 서버의 실행 시각과 무관하게 누적 주차 인덱스만 사용합니다.
     */
    @Test
    void nextWeekAdvancesEachDifficultyAndWrapsAtEnd() {
        prepareCatalog("WEEKLY", 6, 4, 4);
        stubLatestVersions();

        var batch = service.select(window("WEEKLY", 1));

        // HIGH 4개 중 시작점 3에서 두 개를 선택해 4→1로 되돌아옵니다.
        assertEquals(List.of("W-L-02", "W-L-03", "W-L-04",
                "W-M-03", "W-M-04", "W-H-04", "W-H-01"), codes(batch));
    }

    /**
     * 4주 시즌 전환으로 일일 순회 인덱스가 다시 0이 되지 않아야 합니다.
     * 최초 시즌부터 누적된 28일 인덱스로 각 난이도 배열을 순환합니다.
     * 실제 엔티티에서 만든 Snapshot 값만 반환합니다.
     */
    @Test
    void dailyRotationContinuesAcrossFourWeekSeasonBoundary() {
        prepareCatalog("DAILY", 5, 4, 3);
        stubLatestVersions();

        var batch = service.select(window("DAILY", 28));

        // 28을 각각 5·4·3으로 나눈 나머지와 오프셋을 적용합니다.
        assertEquals(List.of("D-L-04", "D-M-02", "D-H-01"), codes(batch));
        assertEquals(28L, batch.rotationIndex());
    }

    /**
     * 일일 LOW 카탈로그의 최소 활성 수는 5개입니다.
     * 실제 ACTIVE 검색 결과가 네 개뿐이면 결과의 일부를 반환하지 않습니다.
     * 실패 코드를 4-F-3의 기간 FAILED 상태 기록에 전달할 수 있어야 합니다.
     */
    @Test
    void dailyInsufficientLowCatalogFailsInsteadOfPartialAssignment() {
        prepareCatalog("DAILY", 4, 4, 3);

        var exception = assertThrows(QuestTemplateSelectionService.SelectionException.class,
                () -> service.select(window("DAILY", 0)));

        // 요구되는 최소 수는 선정 슬롯 수보다 큽니다.
        assertEquals(QuestTemplateSelectionService.FailureCode.INSUFFICIENT_ACTIVE_TEMPLATES,
                exception.failureCode());
        assertTrue(exception.getMessage().contains("DAILY/LOW"));
    }

    /**
     * 주간 HIGH는 두 슬롯을 배정하더라도 네 개 이상의 활성 후보가 필요합니다.
     * 셋만 있어도 두 개를 억지로 선택하지 않고 전체를 실패시킵니다.
     * 검증은 버전 조회 전에 끝나므로 불필요한 DB 작업이 없습니다.
     */
    @Test
    void weeklyInsufficientHighCatalogFails() {
        prepareCatalog("WEEKLY", 6, 4, 3);

        var exception = assertThrows(QuestTemplateSelectionService.SelectionException.class,
                () -> service.select(window("WEEKLY", 0)));

        // HIGH 최소 활성 수는 4개로 고정돼 있습니다.
        assertEquals(QuestTemplateSelectionService.FailureCode.INSUFFICIENT_ACTIVE_TEMPLATES,
                exception.failureCode());
        assertTrue(exception.getMessage().contains("WEEKLY/HIGH"));
    }

    /**
     * 배정 후보 하나에 최신 버전이 없으면 Snapshot 필드를 만들 수 없습니다.
     * 버전이 없는 퀘스트만 제외하면 슬롯 수와 난이도 계약이 깨집니다.
     * 따라서 선정 전체를 실패 처리하고 원인은 구분합니다.
     */
    @Test
    void missingLatestVersionFailsWholeSelection() {
        prepareCatalog("DAILY", 5, 4, 3);
        Long missingId = catalog.stream().filter(t -> "D-H-03".equals(t.getCode()))
                .findFirst().orElseThrow().getId();
        latestVersions.remove(missingId);
        stubLatestVersions();

        var exception = assertThrows(QuestTemplateSelectionService.SelectionException.class,
                () -> service.select(window("DAILY", 0)));

        // 최신 버전을 조회하지 못한 슬롯을 임의 기본값으로 메우지 않습니다.
        assertEquals(QuestTemplateSelectionService.FailureCode.MISSING_TEMPLATE_VERSION,
                exception.failureCode());
    }

    /**
     * 선정 결과는 버전의 문자열·점수 값을 복사한 불변 레코드입니다.
     * 선정 직후 관리자 수정이나 새 버전 발행이 발생해도 이전 결과는 바뀌지 않습니다.
     * 컬렉션 수정도 차단해야 후속 영속화에서 슬롯이 누락되지 않습니다.
     */
    @Test
    void selectedSnapshotDoesNotChangeWhenVersionEntityChanges() {
        prepareCatalog("DAILY", 5, 4, 3);
        stubLatestVersions();
        var batch = service.select(window("DAILY", 0));
        var selected = batch.slots().getFirst();

        // 선정 후 원본 버전 객체의 제목을 바꿔도 Snapshot은 유지됩니다.
        QuestTemplateVersion version = latestVersions.get(selected.templateId());
        ReflectionTestUtils.setField(version, "title", "변경된 최신 제목");
        assertEquals("D-L-01 v2", selected.titleSnapshot());
        assertThrows(UnsupportedOperationException.class, () -> batch.slots().clear());
    }

    /**
     * 같은 난이도에서 회전 순서가 중복되면 정렬 기준이 불명확해집니다.
     * DB UNIQUE 제약이 있어도 Repository Mock으로 손상된 목록을 재현합니다.
     * 순서가 불명확할 때 선택을 진행하지 않고 카탈로그 오류를 반환합니다.
     */
    @Test
    void duplicateRotationOrderFailsCatalogValidation() {
        prepareCatalog("DAILY", 5, 4, 3);
        QuestTemplate secondLow = catalog.stream()
                .filter(t -> "D-L-02".equals(t.getCode())).findFirst().orElseThrow();
        ReflectionTestUtils.setField(secondLow, "rotationOrder", 1);

        var exception = assertThrows(QuestTemplateSelectionService.SelectionException.class,
                () -> service.select(window("DAILY", 0)));

        // 정렬의 비결정성을 만드는 중복 카탈로그를 거부합니다.
        assertEquals(QuestTemplateSelectionService.FailureCode.INVALID_TEMPLATE_CATALOG,
                exception.failureCode());
    }

    /**
     * 선정기는 DAILY와 WEEKLY만 허용합니다.
     * 잘못된 기간 정보에서는 Repository에 접근하기 전에 거절해야 합니다.
     * 4-F-1과 마찬가지로 오타를 다른 기간으로 추정하지 않습니다.
     */
    @Test
    void unsupportedPeriodTypeIsRejected() {
        var invalid = new QuestPeriodCalendar.PeriodWindow(
                "MONTHLY", java.time.LocalDate.of(2026, 11, 2), FIRST_START,
                FIRST_START.plus(Duration.ofDays(28)), 0);

        // PERIOD 타입 검증을 조회 이전에 수행합니다.
        assertThrows(IllegalArgumentException.class, () -> service.select(invalid));
    }

    /**
     * 실제 4-F-1 계산기로 누적 날짜 및 주차를 산출합니다.
     * 회전 인덱스를 테스트 안에서 임의 계산하지 않도록 공통 도메인을 사용합니다.
     * DAILY는 일수, WEEKLY는 주차를 인자로 해석합니다.
     */
    private QuestPeriodCalendar.PeriodWindow window(String type, long index) {
        // 첫 시즌부터 해당 인덱스의 기간 시작 시각을 계산합니다.
        Instant now = FIRST_START.plus(Duration.ofDays(index * ("DAILY".equals(type) ? 1 : 7)));
        return new QuestPeriodCalendar().currentWindow(FIRST_START, now, type).orElseThrow();
    }

    /**
     * JPA 실체를 흉내 내는 기간·난이도별 활성 카탈로그를 구성합니다.
     * 실 서비스와 동일하게 ID·회전 순서·기간·상태 필드를 사용합니다.
     * 각 템플릿에는 검증용 최신 버전 2의 데이터도 준비합니다.
     */
    private void prepareCatalog(String type, int lowCount, int mediumCount, int highCount) {
        // 고정 난이도 순으로 각 rotationOrder 1부터 템플릿을 생성합니다.
        addDifficulty(type, "LOW", "L", lowCount, 1000);
        addDifficulty(type, "MEDIUM", "M", mediumCount, 2000);
        addDifficulty(type, "HIGH", "H", highCount, 3000);
        when(templateRepository.findActiveByPeriodType(type)).thenReturn(catalog);
    }

    /**
     * ACTIVE 템플릿과 최신 버전 엔티티의 필드를 구성합니다.
     * PK는 난이도별로 겹치지 않으며 회전 순서는 1부터 시작합니다.
     * 테스트는 ReflectionTestUtils로 DB가 채울 값을 직접 주입합니다.
     */
    private void addDifficulty(String period, String difficulty, String code, int count, long startId) {
        // 선정 후 변경 불변성을 검증하기 위해 Mockito Mock이 아닌 실체를 사용합니다.
        for (int i = 1; i <= count; i++) {
            QuestTemplate template = BeanUtils.instantiateClass(QuestTemplate.class);
            Long id = startId + i;
            String templateCode = "%s-%s-%02d".formatted(period.equals("DAILY") ? "D" : "W", code, i);
            ReflectionTestUtils.setField(template, "id", id);
            ReflectionTestUtils.setField(template, "code", templateCode);
            ReflectionTestUtils.setField(template, "periodType", period);
            ReflectionTestUtils.setField(template, "difficulty", difficulty);
            ReflectionTestUtils.setField(template, "status", "ACTIVE");
            ReflectionTestUtils.setField(template, "rotationOrder", i);
            catalog.add(template);

            // 버전 2를 최신으로 가정하고 Snapshot에 필요한 값을 모두 채웁니다.
            QuestTemplateVersion version = BeanUtils.instantiateClass(QuestTemplateVersion.class);
            ReflectionTestUtils.setField(version, "id", 10000L + id);
            ReflectionTestUtils.setField(version, "questTemplate", template);
            ReflectionTestUtils.setField(version, "versionNumber", 2);
            ReflectionTestUtils.setField(version, "title", templateCode + " v2");
            ReflectionTestUtils.setField(version, "description", "테스트 퀘스트");
            ReflectionTestUtils.setField(version, "verificationType", "SELF_CHECK");
            ReflectionTestUtils.setField(version, "verificationParameters", "{}");
            ReflectionTestUtils.setField(version, "creatureExperience", 15L);
            ReflectionTestUtils.setField(version, "contributionScore", 6L);
            latestVersions.put(id, version);
        }
    }

    /**
     * 실제 최신 버전 Repository 쿼리처럼 요청한 템플릿 ID만 반환합니다.
     * 일부 버전을 삭제한 테스트에서는 누락 상태를 그대로 재현합니다.
     * 버전 순서는 결정적이라고 가정하지 않아 서비스가 ID로 다시 매핑합니다.
     */
    private void stubLatestVersions() {
        // 반환 순서는 호출자 선택 순서와 관계없이 Map에서 취합니다.
        when(versionRepository.findLatestByTemplateIds(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> selectedIds = invocation.getArgument(0);
            return selectedIds.stream().map(latestVersions::get)
                    .filter(java.util.Objects::nonNull).toList();
        });
    }

    /**
     * 슬롯의 템플릿 코드를 순서대로 반환합니다.
     * 사람이 읽기 쉬운 값으로 순회 수식을 검증하기 위한 도우미입니다.
     * 원래의 ID 순서나 DB 행 순서는 결과 판단에 사용하지 않습니다.
     */
    private List<String> codes(QuestTemplateSelectionService.SelectionBatch batch) {
        // 배정 후보의 템플릿 코드만 꺼냅니다.
        return batch.slots().stream().map(QuestTemplateSelectionService.SelectedQuest::templateCode).toList();
    }

    /**
     * 배정 후보의 1부터 시작하는 슬롯 번호를 검증합니다.
     * 같은 난이도의 복수 슬롯도 번호가 건너뛰지 않아야 합니다.
     * 다음 단계에서 (periodId, slotNumber) UNIQUE 제약에 사용됩니다.
     */
    private List<Integer> slotNumbers(QuestTemplateSelectionService.SelectionBatch batch) {
        // 저장 순서를 보존해 슬롯 번호를 반환합니다.
        return batch.slots().stream().map(QuestTemplateSelectionService.SelectedQuest::slotNumber).toList();
    }

    /**
     * 배정 후보의 난이도 순서를 검증합니다.
     * 일일 1·1·1 규칙을 구성요소별로 명시하는 도우미입니다.
     * 버전 엔티티가 아닌 선택된 Snapshot 필드를 읽습니다.
     */
    private List<String> difficulties(QuestTemplateSelectionService.SelectionBatch batch) {
        // 엔티티 참조 없이 Snapshot 난이도만 반환합니다.
        return batch.slots().stream().map(QuestTemplateSelectionService.SelectedQuest::difficultySnapshot).toList();
    }
}
