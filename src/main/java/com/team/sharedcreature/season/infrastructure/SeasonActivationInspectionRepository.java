package com.team.sharedcreature.season.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;

/**
 * SCHEDULED 시즌의 그룹 초기화 완료 상태를 MySQL에서 조회합니다.
 * 활성 그룹별로 시즌 참여·알·0점 집계가 빠짐없이 준비됐는지 검사합니다.
 * 검사 자체는 데이터를 변경하지 않으며 호출자가 시즌 행의 배타 잠금을 보유해야 합니다.
 */
@Repository
public class SeasonActivationInspectionRepository {

    private final JdbcTemplate jdbc;

    /**
     * 실제 DataSource를 공유하는 JdbcTemplate을 주입받습니다.
     * 활성화 상태 변경과 같은 트랜잭션에서 조회하도록 별도 트랜잭션을 열지 않습니다.
     * 조회 결과의 누락이나 잘못된 초기 상태를 임의로 복원하지 않습니다.
     */
    public SeasonActivationInspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 한 시즌에 대한 전체 활성 그룹 초기화 결과를 집계합니다.
     * 그룹 시즌·알·점수의 존재 여부뿐 아니라 PREPARING·EGG·0점 초기 상태도 확인합니다.
     * DB UNIQUE 제약으로 관계당 최대 하나의 자식 행이 존재한다는 전제를 사용합니다.
     *
     * @param seasonId 현재 SCHEDULED 시즌 ID
     * @return 활성 그룹 수와 누락·비정상 행의 분류별 개수
     */
    public Inspection inspect(Long seasonId) {
        Objects.requireNonNull(seasonId, "seasonId");

        // 활성 그룹이 0개여도 집계 한 행을 반환하며, 누락 상태를 0으로 계산합니다.
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COUNT(g.id) AS active_groups
                     , COUNT(CASE WHEN gs.id IS NULL THEN 1 END) AS missing_group_seasons
                     , COUNT(CASE WHEN gs.id IS NOT NULL AND gc.id IS NULL THEN 1 END) AS missing_eggs
                     , COUNT(CASE WHEN gs.id IS NOT NULL AND score.id IS NULL THEN 1 END) AS missing_scores
                     , COUNT(CASE WHEN gs.id IS NOT NULL AND (
                             gs.status <> 'PREPARING'
                          OR gs.selected_species_id IS NOT NULL
                          OR gs.selected_by_user_id IS NOT NULL
                          OR gs.selection_source IS NOT NULL
                          OR gs.selected_at IS NOT NULL
                          OR gs.selection_deadline_at <> DATE_ADD(gs.created_at, INTERVAL 72 HOUR)
                       ) THEN 1 END) AS invalid_group_seasons
                     , COUNT(CASE WHEN gc.id IS NOT NULL AND (
                             gc.status <> 'ACTIVE'
                          OR gc.growth_stage <> 'EGG'
                          OR gc.total_experience <> 0
                          OR gc.species_id IS NOT NULL
                          OR gc.selection_source IS NOT NULL
                          OR gc.growth_stage_reached_at IS NOT NULL
                          OR gc.hatched_at IS NOT NULL
                       ) THEN 1 END) AS invalid_eggs
                     , COUNT(CASE WHEN score.id IS NOT NULL AND (
                             score.score <> 0
                          OR score.score_reached_at IS NOT NULL
                       ) THEN 1 END) AS invalid_scores
                  FROM game_groups g
                  LEFT JOIN group_seasons gs
                    ON gs.group_id = g.id
                   AND gs.season_id = ?
                  LEFT JOIN group_creatures gc
                    ON gc.group_season_id = gs.id
                  LEFT JOIN season_group_scores score
                    ON score.group_season_id = gs.id
                 WHERE g.status = 'ACTIVE'
                """, (rs, rowNumber) -> new Inspection(
                rs.getLong("active_groups"),
                rs.getLong("missing_group_seasons"),
                rs.getLong("missing_eggs"),
                rs.getLong("missing_scores"),
                rs.getLong("invalid_group_seasons"),
                rs.getLong("invalid_eggs"),
                rs.getLong("invalid_scores")
        ), seasonId), "활성 그룹 초기화 검사 결과가 없습니다.");
    }

    /**
     * SCHEDULED 시즌의 활성 그룹 초기화 검증 결과를 보관합니다.
     * 누락 항목뿐 아니라 잘못 생성된 도메인 초기 상태도 별도로 기록합니다.
     * 하나라도 이상이 있으면 COMPLETE로 판단할 수 없습니다.
     */
    public record Inspection(
            long activeGroups,
            long missingGroupSeasons,
            long missingEggs,
            long missingScores,
            long invalidGroupSeasons,
            long invalidEggs,
            long invalidScores
    ) {
        /**
         * 모든 활성 그룹의 새 시즌 초기화가 정확히 완료됐는지 판단합니다.
         * 활성 그룹이 0개라면 별도 생성 작업이 없어도 완료로 인정합니다.
         * 누락된 행과 비정상 행이 모두 0개일 때에만 true를 반환합니다.
         */
        public boolean complete() {
            return missingGroupSeasons == 0
                    && missingEggs == 0
                    && missingScores == 0
                    && invalidGroupSeasons == 0
                    && invalidEggs == 0
                    && invalidScores == 0;
        }
    }
}
