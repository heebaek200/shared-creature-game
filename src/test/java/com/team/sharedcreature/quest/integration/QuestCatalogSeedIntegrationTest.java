package com.team.sharedcreature.quest.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R-2의 공식 퀘스트 템플릿 및 버전 시드를 실제 MySQL로 검증합니다.
 * 테스트 전용 데이터베이스에서 Spring SQL 초기화 스크립트를 직접 실행합니다.
 * 각 테스트는 롤백되므로 다른 테스트의 초기 시즌·퀘스트 데이터에 영향을 남기지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class QuestCatalogSeedIntegrationTest {

    private static final String CANONICAL_CODE_PATTERN = "^[DW]-[LMHX]-[0-9]{2}$";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 설계서의 일일 13개 및 주간 15개가 모두 등록됐는지 검사합니다.
     * 반드시 포함해야 하는 EXIF와 누적 목표 JSON을 함께 확인합니다.
     * 보상 값도 API 계약(일일 100/10·20·30, 주간 300/30·60·90)을 따릅니다.
     */
    @Test
    void seedsAllCanonicalTemplatesAndSnapshotVersions() {
        runCatalogSeed();

        // 전체 카탈로그와 버전 1의 개수가 정확해야 합니다.
        assertEquals(28L, count("SELECT COUNT(*) FROM quest_templates WHERE code REGEXP ?", CANONICAL_CODE_PATTERN));
        assertEquals(28L, count("SELECT COUNT(*) FROM quest_template_versions"));
        assertCount("DAILY", "LOW", 5);
        assertCount("DAILY", "MEDIUM", 5);
        assertCount("DAILY", "HIGH", 3);
        assertCount("WEEKLY", "LOW", 6);
        assertCount("WEEKLY", "MEDIUM", 5);
        assertCount("WEEKLY", "HIGH", 4);

        // 사진 타입도 중 난이도 ACTIVE 회전 대상에 포함됩니다.
        assertEquals("PHOTO_EXIF", verificationType("D-X-01"));
        assertEquals("PHOTO_EXIF", verificationType("W-X-01"));
        assertEquals("SERVER_EVENT", verificationType("D-M-01"));
        assertEquals("AGGREGATE", verificationType("D-H-03"));
        assertEquals("2", jsonTarget("D-H-03"));
        assertEquals("14", jsonTarget("W-H-02"));
        assertEquals("2", jsonTarget("W-X-01"));

        // 버전 1 보상은 확정된 일일·주간 난이도 표와 같아야 합니다.
        assertReward("D-L-01", 100L, 10L);
        assertReward("D-M-01", 100L, 20L);
        assertReward("D-H-01", 100L, 30L);
        assertReward("W-L-01", 300L, 30L);
        assertReward("W-M-01", 300L, 60L);
        assertReward("W-H-01", 300L, 90L);
    }

    /**
     * 같은 카탈로그 시드를 두 번 실행해도 템플릿·버전이 늘어나지 않아야 합니다.
     * 관리자 변경 상태와 기존 버전 내용을 시드가 덮어쓰지 않는지도 검사합니다.
     * 기존 배정은 버전 ID와 Snapshot을 참조하므로 버전 불변성이 중요합니다.
     */
    @Test
    void repeatSeedDoesNotOverwriteAdminChangesOrExistingVersion() {
        runCatalogSeed();
        jdbc.update("UPDATE quest_templates SET status = 'INACTIVE' WHERE code = 'D-L-01'");
        jdbc.update("""
                UPDATE quest_template_versions AS v
                JOIN quest_templates AS t ON t.id = v.quest_template_id
                   SET v.title = '기존 버전 유지 확인'
                 WHERE t.code = 'D-L-01'
                   AND v.version_number = 1
                """);

        // 재시드해도 새 버전이나 동일 코드 템플릿을 중복 생성하면 안 됩니다.
        runCatalogSeed();
        assertEquals(28L, count("SELECT COUNT(*) FROM quest_templates WHERE code REGEXP ?", CANONICAL_CODE_PATTERN));
        assertEquals(28L, count("SELECT COUNT(*) FROM quest_template_versions"));
        assertEquals("INACTIVE", jdbc.queryForObject(
                "SELECT status FROM quest_templates WHERE code = ?", String.class, "D-L-01"));
        assertEquals("기존 버전 유지 확인", jdbc.queryForObject("""
                SELECT v.title
                  FROM quest_template_versions AS v
                  JOIN quest_templates AS t ON t.id = v.quest_template_id
                 WHERE t.code = ?
                   AND v.version_number = 1
                """, String.class, "D-L-01"));
    }

    /**
     * 이전 data.sql의 예시 코드 3개가 남아 있는 로컬 DB를 모의합니다.
     * 기존 행을 삭제하지 않고 INACTIVE 상태로 이동한 뒤 정식 템플릿을 생성합니다.
     * 기존 PK가 그대로 유지되는지 확인해 과거 FK 참조를 보존합니다.
     */
    @Test
    void legacyDemoRowsAreArchivedWithoutDeletingTheirIds() {
        insertOldDemo("DAILY_WATER", "DAILY", "LOW", 1);
        insertOldDemo("DAILY_STRETCH", "DAILY", "LOW", 2);
        insertOldDemo("WEEKLY_WALK", "WEEKLY", "MEDIUM", 1);
        Long oldId = jdbc.queryForObject(
                "SELECT id FROM quest_templates WHERE code = 'DAILY_WATER'", Long.class);

        // 기존 회전 순서가 정식 D-L-01 등과 충돌하지 않아야 합니다.
        runCatalogSeed();
        assertEquals(oldId, jdbc.queryForObject(
                "SELECT id FROM quest_templates WHERE code = 'DAILY_WATER'", Long.class));
        assertEquals(3L, count("""
                SELECT COUNT(*)
                  FROM quest_templates
                 WHERE code IN ('DAILY_WATER', 'DAILY_STRETCH', 'WEEKLY_WALK')
                   AND status = 'INACTIVE'
                """));
        assertEquals(31L, count("SELECT COUNT(*) FROM quest_templates"));
        assertEquals(28L, count("SELECT COUNT(*) FROM quest_template_versions"));
        assertCount("DAILY", "LOW", 5);
        assertCount("WEEKLY", "MEDIUM", 5);
    }

    /**
     * 애플리케이션에 등록되는 동일 SQL 리소스를 테스트 트랜잭션에 실행합니다.
     * ResourceDatabasePopulator는 현재 트랜잭션에 연결된 JDBC 커넥션을 재사용합니다.
     * 각 테스트 종료 시 @Transactional의 롤백으로 카탈로그 INSERT가 취소됩니다.
     */
    private void runCatalogSeed() {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("db/quest-catalog.sql"));
        populator.execute(dataSource);
    }

    /**
     * 이전 샘플의 회전 인덱스와 ACTIVE 상태를 테스트 DB에 재현합니다.
     * 외래키가 있을 수 있는 기존 템플릿을 삭제하지 않는 마이그레이션을 검증합니다.
     * 시험 데이터는 현재 테스트 트랜잭션에서만 존재합니다.
     */
    private void insertOldDemo(String code, String type, String difficulty, int order) {
        jdbc.update("""
                INSERT INTO quest_templates (
                    code
                  , period_type
                  , difficulty
                  , status
                  , rotation_order
                  , created_at
                  , updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, code, type, difficulty, order);
    }

    /**
     * 기본 건수 SQL을 실행해 Long 값으로 반환합니다.
     * 카탈로그 검증이 DB 집계 결과에 직접 의존하도록 합니다.
     * 별도의 메모리 Mock은 사용하지 않습니다.
     */
    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    /**
     * 난이도별 필수 활성 템플릿 개수를 검증합니다.
     * 과거 샘플 코드가 비활성화된 뒤에도 정식 슬롯 수가 유지돼야 합니다.
     * 사진 유형 X 코드 역시 MEDIUM으로 집계됩니다.
     */
    private void assertCount(String period, String difficulty, long expected) {
        assertEquals(expected, count("""
                SELECT COUNT(*)
                  FROM quest_templates
                 WHERE status = 'ACTIVE'
                   AND period_type = ?
                   AND difficulty = ?
                """, period, difficulty), period + "/" + difficulty);
    }

    /**
     * 공식 템플릿의 최초 버전에 지정한 판정 유형을 읽습니다.
     * SELF_CHECK, AGGREGATE, SERVER_EVENT, PHOTO_EXIF 경계를 검증합니다.
     * 누락된 버전은 조회 예외로 테스트 실패 처리됩니다.
     */
    private String verificationType(String code) {
        return jdbc.queryForObject("""
                SELECT v.verification_type
                  FROM quest_template_versions AS v
                  JOIN quest_templates AS t ON t.id = v.quest_template_id
                 WHERE t.code = ?
                   AND v.version_number = 1
                """, String.class, code);
    }

    /**
     * JSON target 값이 올바르게 저장됐는지 MySQL JSON 함수로 검증합니다.
     * AGGREGATE 진행도 조회는 target의 양의 정수 값을 읽어 동작합니다.
     * 사진 목표값은 이후 PHOTO_EXIF 통합 판정에도 활용할 수 있습니다.
     */
    private String jsonTarget(String code) {
        return jdbc.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(v.verification_parameters, '$.target'))
                  FROM quest_template_versions AS v
                  JOIN quest_templates AS t ON t.id = v.quest_template_id
                 WHERE t.code = ?
                   AND v.version_number = 1
                """, String.class, code);
    }

    /**
     * 보상 경험치와 기여도 값을 원본 버전에서 직접 읽어 검증합니다.
     * 모든 템플릿은 일일 또는 주간과 난이도 조합으로 확정 보상을 갖습니다.
     * 배정 Snapshot이 이 버전의 숫자를 그대로 복제할 수 있어야 합니다.
     */
    private void assertReward(String code, long experience, long contribution) {
        assertEquals(experience, count("""
                SELECT v.creature_experience
                  FROM quest_template_versions AS v
                  JOIN quest_templates AS t ON t.id = v.quest_template_id
                 WHERE t.code = ?
                   AND v.version_number = 1
                """, code));
        assertEquals(contribution, count("""
                SELECT v.contribution_score
                  FROM quest_template_versions AS v
                  JOIN quest_templates AS t ON t.id = v.quest_template_id
                 WHERE t.code = ?
                   AND v.version_number = 1
                """, code));
    }
}
