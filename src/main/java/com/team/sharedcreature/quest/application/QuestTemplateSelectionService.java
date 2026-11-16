package com.team.sharedcreature.quest.application;

import com.team.sharedcreature.quest.domain.QuestTemplate;
import com.team.sharedcreature.quest.domain.QuestTemplateVersion;
import com.team.sharedcreature.quest.infrastructure.QuestTemplateRepository;
import com.team.sharedcreature.quest.infrastructure.QuestTemplateVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 확정된 KST 일일·주간 기간에 배정할 활성 퀘스트 템플릿을 결정합니다.
 * 최초 시즌부터의 누적 인덱스와 난이도별 오프셋을 이용해 결정적으로 순회합니다.
 * DB에는 기록하지 않으며 저장·실패 기록은 다음 단계의 기간 정상화 서비스가 담당합니다.
 */
@Service
public class QuestTemplateSelectionService {

    private static final List<DifficultyRule> DAILY_RULES = List.of(
            new DifficultyRule("LOW", 5, 1, 0),
            new DifficultyRule("MEDIUM", 4, 1, 1),
            new DifficultyRule("HIGH", 3, 1, 2)
    );
    private static final List<DifficultyRule> WEEKLY_RULES = List.of(
            new DifficultyRule("LOW", 6, 3, 0),
            new DifficultyRule("MEDIUM", 4, 2, 1),
            new DifficultyRule("HIGH", 4, 2, 2)
    );

    private final QuestTemplateRepository templateRepository;
    private final QuestTemplateVersionRepository versionRepository;

    /**
     * 활성 카탈로그와 템플릿 최신 버전을 읽는 Repository를 주입합니다.
     * 기간 계산기는 이미 호출자가 검증한 PeriodWindow를 전달받으므로 다시 실행하지 않습니다.
     * 별도 시즌 조회나 잠금을 수행하지 않아 후속 배정 트랜잭션에 조합할 수 있습니다.
     */
    public QuestTemplateSelectionService(
            QuestTemplateRepository templateRepository,
            QuestTemplateVersionRepository versionRepository
    ) {
        this.templateRepository = templateRepository;
        this.versionRepository = versionRepository;
    }

    /**
     * 기간에 필요한 슬롯의 템플릿과 배정 시점 버전 데이터를 결정합니다.
     * 활성 템플릿 최소 수를 충족하지 못하면 일부 슬롯만 반환하지 않고 실패합니다.
     * 다음 단계에서는 반환된 Snapshot 데이터를 한 트랜잭션에서 영속화해야 합니다.
     *
     * @param window 4-F-1에서 검증된 KST 퀘스트 기간과 누적 회전 인덱스
     * @return 슬롯 번호가 1부터 연속되는 불변 배정 후보 목록
     */
    @Transactional(readOnly = true)
    public SelectionBatch select(QuestPeriodCalendar.PeriodWindow window) {
        Objects.requireNonNull(window, "window");
        List<DifficultyRule> rules = rulesFor(window.periodType());
        if (window.rotationIndex() < 0) {
            throw new IllegalArgumentException("회전 인덱스는 0 이상이어야 합니다.");
        }

        // ACTIVE 카탈로그만 조회하고, 버전 목록은 실제 선택된 템플릿 ID로 제한합니다.
        List<QuestTemplate> candidates = Objects.requireNonNull(
                templateRepository.findActiveByPeriodType(window.periodType()), "candidates");
        Map<String, List<QuestTemplate>> byDifficulty = groupAndValidate(candidates, window.periodType());
        List<QuestTemplate> selected = new ArrayList<>();

        for (DifficultyRule rule : rules) {
            List<QuestTemplate> catalog = byDifficulty.getOrDefault(rule.difficulty(), List.of());
            if (catalog.size() < rule.minimumActive()) {
                throw new SelectionException(
                        FailureCode.INSUFFICIENT_ACTIVE_TEMPLATES,
                        "%s/%s 활성 템플릿 부족: %d개, 최소 %d개".formatted(
                                window.periodType(), rule.difficulty(), catalog.size(), rule.minimumActive()));
            }

            // 매 기간 인덱스가 1씩 증가하고 같은 난이도 내 복수 슬롯은 연속 순회합니다.
            int start = (int) ((window.rotationIndex() % catalog.size() + rule.offset()) % catalog.size());
            for (int slot = 0; slot < rule.slots(); slot++) {
                selected.add(catalog.get((start + slot) % catalog.size()));
            }
        }

        // 한 기간에서 같은 템플릿을 두 슬롯에 배정하면 UNIQUE 제약과 충돌합니다.
        Set<Long> selectedIds = new LinkedHashSet<>();
        for (QuestTemplate template : selected) {
            if (!selectedIds.add(template.getId())) {
                throw new SelectionException(FailureCode.INVALID_TEMPLATE_CATALOG,
                        "한 기간에 동일 템플릿이 중복 선정됐습니다: " + template.getId());
            }
        }

        Map<Long, QuestTemplateVersion> versions = latestVersions(selectedIds);
        List<SelectedQuest> slots = new ArrayList<>(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            QuestTemplate template = selected.get(i);
            QuestTemplateVersion version = versions.get(template.getId());
            if (version == null) {
                throw new SelectionException(FailureCode.MISSING_TEMPLATE_VERSION,
                        "최신 버전이 없는 템플릿: " + template.getId());
            }

            // 반환 시 버전 내용을 복사해서 후속 템플릿 변경으로부터 Snapshot을 분리합니다.
            slots.add(new SelectedQuest(
                    i + 1,
                    template.getId(),
                    version.getId(),
                    template.getCode(),
                    template.getDifficulty(),
                    version.getTitle(),
                    version.getDescription(),
                    version.getVerificationType(),
                    version.getVerificationParameters(),
                    version.getCreatureExperience(),
                    version.getContributionScore()
            ));
        }
        return new SelectionBatch(window.periodType(), window.rotationIndex(), List.copyOf(slots));
    }

    /**
     * 기간 유형별 슬롯 수와 최소 활성 카탈로그 수를 고정 규칙으로 반환합니다.
     * DAILY 1·1·1, WEEKLY 3·2·2 구성이며 난이도 순서가 슬롯 순서입니다.
     * 알 수 없는 기간 문자열은 임의로 DAILY로 간주하지 않고 거절합니다.
     */
    private List<DifficultyRule> rulesFor(String periodType) {
        return switch (periodType) {
            case "DAILY" -> DAILY_RULES;
            case "WEEKLY" -> WEEKLY_RULES;
            default -> throw new IllegalArgumentException("지원하지 않는 기간 유형: " + periodType);
        };
    }

    /**
     * 조회 결과를 난이도별로 묶고 rotationOrder·ID 순서로 정렬합니다.
     * 한 난이도에서 회전 순서가 중복되면 비결정적인 배정을 방지합니다.
     * Repository의 필터링 결과가 깨진 경우에는 운영상 카탈로그 오류로 보고합니다.
     */
    private Map<String, List<QuestTemplate>> groupAndValidate(
            List<QuestTemplate> candidates, String periodType
    ) {
        Map<String, List<QuestTemplate>> grouped = new HashMap<>();
        Set<Long> templateIds = new HashSet<>();
        for (QuestTemplate template : candidates) {
            if (template == null || template.getId() == null || template.getRotationOrder() == null
                    || !periodType.equals(template.getPeriodType())
                    || !"ACTIVE".equals(template.getStatus())
                    || template.getDifficulty() == null
                    || !Set.of("LOW", "MEDIUM", "HIGH").contains(template.getDifficulty())) {
                throw new SelectionException(FailureCode.INVALID_TEMPLATE_CATALOG,
                        "활성 퀘스트 카탈로그의 기간·난이도·회전 값이 잘못됐습니다.");
            }
            if (!templateIds.add(template.getId())) {
                throw new SelectionException(FailureCode.INVALID_TEMPLATE_CATALOG,
                        "중복 템플릿 ID: " + template.getId());
            }
            grouped.computeIfAbsent(template.getDifficulty(), ignored -> new ArrayList<>()).add(template);
        }

        // 유일한 rotationOrder는 데이터베이스 제약뿐 아니라 여기에서도 검증합니다.
        for (List<QuestTemplate> catalog : grouped.values()) {
            catalog.sort(Comparator.comparing(QuestTemplate::getRotationOrder)
                    .thenComparing(QuestTemplate::getId));
            for (int i = 1; i < catalog.size(); i++) {
                if (catalog.get(i - 1).getRotationOrder().equals(catalog.get(i).getRotationOrder())) {
                    throw new SelectionException(FailureCode.INVALID_TEMPLATE_CATALOG,
                            "같은 난이도에서 rotationOrder가 중복됐습니다.");
                }
            }
        }
        return grouped;
    }

    /**
     * 선택된 템플릿의 최신 버전만 조회하여 템플릿 ID별로 매핑합니다.
     * Repository는 MAX(versionNumber)를 기준으로 한 템플릿당 한 버전만 반환합니다.
     * 누락 또는 중복 결과는 Snapshot의 무결성이 깨지기 전에 실패시킵니다.
     */
    private Map<Long, QuestTemplateVersion> latestVersions(Set<Long> selectedIds) {
        List<QuestTemplateVersion> fetched = Objects.requireNonNull(
                versionRepository.findLatestByTemplateIds(selectedIds), "versions");
        Map<Long, QuestTemplateVersion> versions = new HashMap<>();
        for (QuestTemplateVersion version : fetched) {
            if (version == null || version.getId() == null || version.getQuestTemplate() == null
                    || version.getQuestTemplate().getId() == null) {
                throw new SelectionException(FailureCode.INVALID_TEMPLATE_CATALOG,
                        "템플릿 최신 버전의 연결 정보가 잘못됐습니다.");
            }
            Long templateId = version.getQuestTemplate().getId();
            if (!selectedIds.contains(templateId) || versions.putIfAbsent(templateId, version) != null) {
                throw new SelectionException(FailureCode.INVALID_TEMPLATE_CATALOG,
                        "템플릿 최신 버전 조회 결과가 중복되거나 범위를 벗어났습니다: " + templateId);
            }
        }
        return versions;
    }

    /**
     * 난이도별로 적용할 최소 활성 수·배정 슬롯 수·시작 오프셋입니다.
     * ACTIVE 카탈로그가 최소 수에 미달하면 모든 배정을 중단합니다.
     * 동일 난이도 내 슬롯은 rotationOrder 오름차순으로 이어서 선택합니다.
     */
    private record DifficultyRule(String difficulty, int minimumActive, int slots, int offset) {
    }

    /**
     * 기간의 배정 후보 전체를 변경할 수 없는 목록으로 제공합니다.
     * 실제 기간 레코드 저장이나 멱등성 판단은 포함하지 않습니다.
     * 슬롯 번호는 1부터 시작하며 난이도 LOW → MEDIUM → HIGH 순서입니다.
     */
    public record SelectionBatch(String periodType, long rotationIndex, List<SelectedQuest> slots) {
    }

    /**
     * 배정 시점의 QuestAssignment Snapshot에 저장할 최종 값입니다.
     * JPA 엔티티를 외부로 전달하지 않아 원본 카탈로그의 변경과 독립됩니다.
     * templateId와 templateVersionId는 후속 FK 저장에도 사용합니다.
     */
    public record SelectedQuest(
            int slotNumber,
            Long templateId,
            Long templateVersionId,
            String templateCode,
            String difficultySnapshot,
            String titleSnapshot,
            String descriptionSnapshot,
            String verificationTypeSnapshot,
            String verificationParametersSnapshot,
            Long creatureExperienceSnapshot,
            Long contributionScoreSnapshot
    ) {
    }

    /**
     * 기간 배정 실패를 다음 단계의 quest_periods.failure_code에 기록할 때 쓸 코드입니다.
     * 운영상 부족/버전 누락과 잘못된 카탈로그를 별도로 구분합니다.
     * 실패 기록의 실제 영속화는 4-F-3에서 구현합니다.
     */
    public enum FailureCode {
        INSUFFICIENT_ACTIVE_TEMPLATES,
        MISSING_TEMPLATE_VERSION,
        INVALID_TEMPLATE_CATALOG
    }

    /**
     * 일부 템플릿만 배정하는 대신 실패 원인과 코드를 전달합니다.
     * 이 예외는 별도 저장 트랜잭션에서 실패 이력으로 변환할 수 있습니다.
     * 현재 단계에서는 예외를 기록하거나 DB를 변경하지 않습니다.
     */
    public static class SelectionException extends RuntimeException {
        private final FailureCode failureCode;

        public SelectionException(FailureCode failureCode, String message) {
            super(message);
            this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
        }

        public FailureCode failureCode() {
            return failureCode;
        }
    }
}
