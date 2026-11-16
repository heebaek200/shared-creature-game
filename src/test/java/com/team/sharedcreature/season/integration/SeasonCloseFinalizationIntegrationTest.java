package com.team.sharedcreature.season.integration;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import com.team.sharedcreature.group.domain.Group;
import com.team.sharedcreature.group.infrastructure.GroupRepository;
import com.team.sharedcreature.season.application.SeasonCloseFinalizationService;
import com.team.sharedcreature.season.application.SeasonGroupSnapshotService;
import com.team.sharedcreature.season.domain.GroupSeason;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.domain.SeasonGroupScore;
import com.team.sharedcreature.season.infrastructure.GroupSeasonRepository;
import com.team.sharedcreature.season.infrastructure.SeasonGroupScoreRepository;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL에서 시즌 Snapshot 완료 검사와 ENDED Commit을 검증합니다.
 * 그룹 Snapshot 배치의 실제 저장 결과를 finalizer가 다시 검사합니다.
 * 전체 테스트는 @Transactional 없이 실행해 독립 Commit을 관찰합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class SeasonCloseFinalizationIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SeasonRepository seasonRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private GroupRepository groupRepository;
    @Autowired private GroupSeasonRepository groupSeasonRepository;
    @Autowired private SeasonGroupScoreRepository scoreRepository;
    @Autowired private SeasonGroupSnapshotService groupSnapshotService;
    @Autowired private SeasonCloseFinalizationService finalizationService;

    private Integer seasonNumber;
    private Long seasonId;
    private Long groupId;
    private Long userId;

    /**
     * 테스트마다 독립된 과거 시즌을 CLOSING 상태로 생성합니다.
     * 생성자를 직접 호출하지 않고 SQL로 JPA 시즌 레코드를 준비합니다.
     * 다른 통합 테스트의 유니크 시즌 번호 및 시작 시각과 충돌하지 않게 합니다.
     */
    @BeforeEach
    void setUp() {
        seasonNumber = ThreadLocalRandom.current().nextInt(500_000_000, 900_000_000);
        Instant start = Instant.parse("2020-01-01T00:00:00Z")
                .plusMillis(seasonNumber);
        Instant end = start.plus(Duration.ofDays(28));

        // 종료 출처를 함께 저장해 재시작 후 복구된 CLOSING 상태를 재현합니다.
        jdbc.update("""
                INSERT INTO seasons (
                    season_number
                  , start_at
                  , end_at
                  , status
                  , closing_from_status
                  , version
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, 'CLOSING', 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, seasonNumber, Timestamp.from(start), Timestamp.from(end));

        seasonId = jdbc.queryForObject("""
                SELECT id FROM seasons WHERE season_number = ?
                """, Long.class, seasonNumber);
    }

    /**
     * 생성한 테스트 시즌과 종속 데이터를 외래키 순서에 따라 정리합니다.
     * 독립 Commit된 데이터이므로 테스트 메서드 종료 후 명시적으로 삭제합니다.
     * 실서비스 행은 건드리지 않고 기록된 시즌·그룹·사용자 ID만 삭제합니다.
     */
    @AfterEach
    void tearDown() {
        if (seasonId == null) {
            return;
        }

        // Snapshot → 점수 → 그룹 시즌 → 그룹 → 사용자 → 시즌 순서입니다.
        jdbc.update("DELETE FROM season_member_rank_snapshots WHERE season_id = ?", seasonId);
        jdbc.update("DELETE FROM season_group_rank_snapshots WHERE season_id = ?", seasonId);
        jdbc.update("""
                DELETE scores FROM season_group_scores scores
                  JOIN group_seasons gs ON gs.id = scores.group_season_id
                 WHERE gs.season_id = ?
                """, seasonId);
        jdbc.update("DELETE FROM group_seasons WHERE season_id = ?", seasonId);
        if (groupId != null) {
            jdbc.update("DELETE FROM game_groups WHERE id = ?", groupId);
        }
        if (userId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
    }

    /**
     * 그룹·개인 원본이 전혀 없는 ACTIVE 종료 시즌을 확정합니다.
     * 전체 검증용 ROW_NUMBER 쿼리가 빈 입력에서도 정상 동작해야 합니다.
     * 중복 실행은 상태와 JPA version을 추가로 변경하지 않아야 합니다.
     */
    @Test
    void emptyActiveSeasonEndsIdempotently() {
        assertThat(finalizationService.finishIfSnapshotsComplete(seasonId)).isTrue();
        long version = version();

        // 이미 ENDED라면 다시 Snapshot 조회·상태 변경을 실행하지 않습니다.
        assertThat(finalizationService.finishIfSnapshotsComplete(seasonId)).isFalse();
        assertThat(status()).isEqualTo("ENDED");
        assertThat(version()).isEqualTo(version);
    }

    /**
     * 그룹 시즌과 0점 원본은 있지만 Snapshot이 누락된 상황입니다.
     * 단순히 시즌이 CLOSING이라는 이유만으로 종료하면 안 됩니다.
     * 누락된 데이터를 저장하기 전까지 CLOSING이 유지됩니다.
     */
    @Test
    void missingGroupSnapshotBlocksEnding() {
        createGroupAndScore();

        assertThatThrownBy(() -> finalizationService.finishIfSnapshotsComplete(seasonId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(status()).isEqualTo("CLOSING");
    }

    /**
     * 그룹 Snapshot 배치 Commit 후 최종 상태 변경을 확인합니다.
     * 0점의 도달 시각은 점수 원본 생성 시각으로 보완돼야 합니다.
     * 개인 기여도가 없는 경우 개인 Snapshot은 0건이어야 합니다.
     */
    @Test
    void groupSnapshotCommitAllowsEnding() {
        createGroupAndScore();

        // 독립 트랜잭션에서 그룹 Snapshot 저장을 먼저 완료합니다.
        SeasonGroupSnapshotService.Progress progress = groupSnapshotService.writeNextBatch(seasonId);
        assertThat(progress.inserted()).isEqualTo(1);
        assertThat(progress.remaining()).isZero();

        assertThat(finalizationService.finishIfSnapshotsComplete(seasonId)).isTrue();
        assertThat(status()).isEqualTo("ENDED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM season_group_rank_snapshots WHERE season_id = ?
                """, Long.class, seasonId)).isEqualTo(1L);
    }

    /**
     * Snapshot 건수는 맞아도 순위가 잘못되면 종료를 거절합니다.
     * 실제 SQL ROW_NUMBER 검증을 통해 부정확한 rank_number를 탐지합니다.
     * 잘못된 Snapshot을 자동으로 덮어쓰지 않고 상태를 보존합니다.
     */
    @Test
    void incorrectSnapshotRankBlocksEnding() {
        createGroupAndScore();
        groupSnapshotService.writeNextBatch(seasonId);

        // 실제 순위 1위를 잘못된 2위로 변경해 정합성 검사를 검증합니다.
        jdbc.update("""
                UPDATE season_group_rank_snapshots
                   SET rank_number = 2
                 WHERE season_id = ?
                """, seasonId);

        assertThatThrownBy(() -> finalizationService.finishIfSnapshotsComplete(seasonId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("CLOSING");
    }

    /**
     * SCHEDULED에서 만료된 시즌은 랭킹 없이 ENDED로 확정합니다.
     * 한 번도 ACTIVE로 전환하지 않았으므로 소급 Snapshot이 금지됩니다.
     * 원본 그룹·개인 데이터가 없어도 종료 처리가 완료되어야 합니다.
     */
    @Test
    void scheduledClosingSkipsRankings() {
        jdbc.update("""
                UPDATE seasons SET closing_from_status = 'SCHEDULED'
                 WHERE id = ?
                """, seasonId);

        assertThat(finalizationService.finishIfSnapshotsComplete(seasonId)).isTrue();
        assertThat(status()).isEqualTo("ENDED");
    }

    /**
     * 그룹과 시즌 점수의 실제 테이블 관계를 생성합니다.
     * GROUP 초기화 서비스와 동일한 공개 팩토리 메서드를 사용합니다.
     * 그룹·크리처의 다른 업무 데이터는 생성하지 않습니다.
     */
    private void createGroupAndScore() {
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        User user = userRepository.save(User.register("USER", now));
        userId = user.getId();

        // 실서비스 그룹명 유니크 제약과 충돌하지 않도록 고유 이름을 사용합니다.
        String suffix = Integer.toString(seasonNumber);
        Group group = groupRepository.save(Group.create(
                "snapshot" + suffix, "snapshot" + suffix, "test", user, now
        ));
        groupId = group.getId();

        Season season = seasonRepository.findById(seasonId).orElseThrow();
        GroupSeason groupSeason = groupSeasonRepository.save(GroupSeason.start(group, season, now));
        scoreRepository.save(SeasonGroupScore.zero(groupSeason, now));
    }

    /**
     * JDBC로 Commit된 시즌 상태를 직접 확인합니다.
     * 다른 영속성 컨텍스트에서 조회하므로 캐시 오염을 피합니다.
     * 종료 트랜잭션의 실제 저장 결과를 검사합니다.
     */
    private String status() {
        return jdbc.queryForObject(
                "SELECT status FROM seasons WHERE id = ?", String.class, seasonId
        );
    }

    /**
     * DB에 Commit된 시즌 버전을 직접 확인합니다.
     * 멱등성 검증에서 중복 업데이트 여부를 판정합니다.
     * 처음 종료된 이후의 버전이 그대로 유지돼야 합니다.
     */
    private long version() {
        Long value = jdbc.queryForObject(
                "SELECT version FROM seasons WHERE id = ?", Long.class, seasonId
        );
        return java.util.Objects.requireNonNull(value, "version");
    }
}
