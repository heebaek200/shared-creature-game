package com.team.sharedcreature.season.application;

import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.domain.GroupCreature;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.infrastructure.GroupCreatureRepository;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 현재 SCHEDULED 시즌의 활성 그룹을 100건 단위로 초기화합니다.
 * GroupSeason·알 GroupCreature·0점 SeasonGroupScore를 동일 트랜잭션에 저장합니다.
 * 기존 생성 시각과 부화 기한을 보존하면서 누락된 하위 행만 재생성합니다.
 */
@Service
public class SeasonGroupInitializationService {

    public static final int BATCH_SIZE = 100;

    private final SeasonRepository seasonRepository;
    private final GroupRepository groupRepository;
    private final GroupSeasonRepository groupSeasonRepository;
    private final GroupCreatureRepository creatureRepository;
    private final SeasonGroupScoreRepository scoreRepository;
    private final Clock clock;

    /**
     * 시즌과 그룹의 조회·배타 잠금에 필요한 Repository를 주입받습니다.
     * 최초 그룹 시즌 생성 시각은 공통 Clock을 이용해 기록합니다.
     * 알과 0점 집계는 기존 JPA 도메인 팩토리를 그대로 사용합니다.
     */
    public SeasonGroupInitializationService(
            SeasonRepository seasonRepository,
            GroupRepository groupRepository,
            GroupSeasonRepository groupSeasonRepository,
            GroupCreatureRepository creatureRepository,
            SeasonGroupScoreRepository scoreRepository,
            Clock clock
    ) {
        this.seasonRepository = seasonRepository;
        this.groupRepository = groupRepository;
        this.groupSeasonRepository = groupSeasonRepository;
        this.creatureRepository = creatureRepository;
        this.scoreRepository = scoreRepository;
        this.clock = clock;
    }

    /**
     * 활성 그룹 최대 100개의 새 시즌 참여·알·0점 점수를 원자적으로 준비합니다.
     * 시즌 배타 잠금 후 현재 시각을 검사하여 만료됐거나 이미 활성화된 시즌은 건너뜁니다.
     * 기존 GroupSeason이 있다면 그 행의 선택 기한을 유지하고 빠진 자식만 생성합니다.
     *
     * @param seasonId 초기화할 SCHEDULED 시즌 ID
     * @return 처리 상태, 검사한 그룹 수와 신규 생성된 행 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchResult initializeNextBatch(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");

        // 동일한 시즌에 대한 두 서버의 초기화와 종료 전이를 먼저 직렬화합니다.
        Season season = seasonRepository.findForUpdateById(seasonId)
                .orElseThrow(() -> new IllegalStateException("시즌이 없습니다. seasonId=" + seasonId));
        if (!"SCHEDULED".equals(season.getStatus())) {
            return new BatchResult(Outcome.NOT_SCHEDULED, 0, 0, 0, 0);
        }

        // 시즌 종료 경계 이후의 초기화·알 생성은 어떤 경우에도 수행하지 않습니다.
        Instant beforeGroupLock = Instant.now(clock);
        if (beforeGroupLock.isBefore(season.getStartAt())) {
            return new BatchResult(Outcome.NOT_STARTED, 0, 0, 0, 0);
        }
        if (!beforeGroupLock.isBefore(season.getEndAt())) {
            return new BatchResult(Outcome.EXPIRED, 0, 0, 0, 0);
        }

        // 첫 100건만 잠그며, 이미 완료된 그룹은 쿼리에서 제외합니다.
        List<Group> candidates = groupRepository.findActiveGroupsNeedingSeasonInitializationForUpdate(
                seasonId, PageRequest.of(0, BATCH_SIZE));
        Instant now = Instant.now(clock);
        if (!now.isBefore(season.getEndAt())) {
            return new BatchResult(Outcome.EXPIRED, 0, 0, 0, 0);
        }
        if (now.isBefore(season.getStartAt())) {
            return new BatchResult(Outcome.NOT_STARTED, 0, 0, 0, 0);
        }

        int createdGroupSeasons = 0;
        int createdEggs = 0;
        int createdScores = 0;
        for (Group group : candidates) {
            // 배치 도중 시즌이 만료됐다면 이번 트랜잭션 전체를 Rollback합니다.
            Instant groupNow = Instant.now(clock);
            if (groupNow.isBefore(season.getStartAt()) || !groupNow.isBefore(season.getEndAt())) {
                throw new IllegalStateException("그룹 초기화 도중 시즌 유효기간이 종료됐습니다.");
            }
            if (!"ACTIVE".equals(group.getStatus())) {
                throw new IllegalStateException("초기화 대상 그룹 상태가 변경됐습니다. groupId=" + group.getId());
            }

            // 기존 GroupSeason의 deadline은 재기동 후에도 절대 다시 계산하지 않습니다.
            GroupSeason groupSeason = groupSeasonRepository
                    .findSnapshotByGroupIdAndSeasonId(group.getId(), seasonId)
                    .orElse(null);
            if (groupSeason == null) {
                // 실제 최초 초기화 시각을 사용하며 과거 달력의 startAt을 사용하지 않습니다.
                groupSeason = groupSeasonRepository.saveAndFlush(GroupSeason.start(group, season, groupNow));
                creatureRepository.save(GroupCreature.egg(groupSeason, groupNow));
                scoreRepository.save(SeasonGroupScore.zero(groupSeason, groupNow));
                createdGroupSeasons++;
                createdEggs++;
                createdScores++;
                continue;
            }

            // SCHEDULED에서 부화나 보상이 반영된 비정상 기록은 조용히 덮어쓰지 않습니다.
            if (!"PREPARING".equals(groupSeason.getStatus())) {
                throw new IllegalStateException("SCHEDULED 시즌의 그룹 상태가 PREPARING이 아닙니다. groupId=" + group.getId());
            }

            // 부분 이력 복구는 기존 생성 시각을 사용해 알의 생애와 기한을 보존합니다.
            Instant originalTime = Objects.requireNonNull(groupSeason.getCreatedAt(), "groupSeason.createdAt");
            if (creatureRepository.findSnapshotByGroupSeasonId(groupSeason.getId()).isEmpty()) {
                creatureRepository.save(GroupCreature.egg(groupSeason, originalTime));
                createdEggs++;
            }
            if (scoreRepository.findByGroupSeasonId(groupSeason.getId()).isEmpty()) {
                scoreRepository.save(SeasonGroupScore.zero(groupSeason, originalTime));
                createdScores++;
            }
        }

        // 정확히 100개를 처리했다면 남은 데이터가 있을 수 있어 다음 호출에서 재검사합니다.
        Outcome outcome = candidates.size() == BATCH_SIZE ? Outcome.MORE_MAY_REMAIN : Outcome.COMPLETE;
        return new BatchResult(outcome, candidates.size(), createdGroupSeasons, createdEggs, createdScores);
    }

    /**
     * 초기화 배치의 진행 상태를 나타냅니다.
     * MORE_MAY_REMAIN은 반드시 남았다는 뜻이 아니라 추가 조회가 필요하다는 뜻입니다.
     * COMPLETE는 현재 조회 기준 초기화할 활성 그룹이 없음을 뜻합니다.
     */
    public enum Outcome {
        COMPLETE,
        MORE_MAY_REMAIN,
        NOT_STARTED,
        EXPIRED,
        NOT_SCHEDULED
    }

    /**
     * 그룹 초기화 한 번의 트랜잭션에 대한 처리 결과입니다.
     * processedGroups는 최대 100이며 나머지 값은 실제로 생성한 행 수입니다.
     * 이 결과는 호출 트랜잭션이 정상 Commit된 이후 반환됩니다.
     */
    public record BatchResult(
            Outcome outcome,
            int processedGroups,
            int createdGroupSeasons,
            int createdEggs,
            int createdScores
    ) {
    }
}
