package com.team.sharedcreature.quest.integration;

import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.quest.application.PhotoRetentionNormalizationService;
import com.team.sharedcreature.quest.application.PhotoRetentionWork;
import com.team.sharedcreature.quest.application.PhotoCleanupLeaseHeartbeat;
import com.team.sharedcreature.quest.application.PhotoSubmissionCleanupTransactionService;
import com.team.sharedcreature.quest.application.PhotoSubmissionCleanupWork;
import com.team.sharedcreature.quest.infrastructure.PhotoFileStorage;
import com.team.sharedcreature.quest.infrastructure.PhotoSubmissionRepository;
import com.team.sharedcreature.quest.scheduler.PhotoRetentionScheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 4-H-2 사진 정리를 실제 MySQL/JPA, 로컬 파일 시스템, Scheduler 실행권으로 검증합니다.
 * 각 메서드는 트랜잭션 외부에서 호출되며 실제 REQUIRES_NEW Commit을 거칩니다.
 * 반드시 전용 test DB만 사용하고 자동 Scheduler는 활성화하지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PhotoRetentionMySqlIntegrationTest.TestClockConfiguration.class)
@TestPropertySource(properties = {
        "app.scheduler.enabled=false",
        "app.scheduler.photo-retention-enabled=false"
})
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhotoRetentionMySqlIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T01:07:45Z");
    private static final Instant SEASON_END = NOW.minus(Duration.ofDays(30));
    private static final Instant SEASON_START = SEASON_END.minus(Duration.ofDays(28));
    private static final Path DIRECTORY = createTemporaryDirectory();
    private static final byte[] MINIMAL_FILE = {(byte) 0xff, (byte) 0xd8, 0, (byte) 0xff, (byte) 0xd9};
    private static final String CLAIM = "11111111-1111-1111-1111-111111111111";
    private static final String NEW_CLAIM = "22222222-2222-2222-2222-222222222222";

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        /**
         * 사진 실행권과 시간 판정에 하나의 고정된 UTC 시각을 사용합니다.
         * 테스트의 DB와 물리 파일 삭제는 실제로 실행됩니다.
         * 테스트 메서드별 시각은 cleanup(now) 인자로 조절합니다.
         */
        @Bean
        @Primary
        AdjustableClock photoRetentionIntegrationClock() {
            return new AdjustableClock(NOW);
        }
    }

    /** 실제 대기 없이 Claim과 재시도 제어 시각을 이동하는 테스트 Clock입니다. */
    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current;

        AdjustableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            current.set(instant);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return current.get(); }
    }

    /**
     * 별도 OS 위치에 사진 테스트 디렉터리를 생성합니다.
     * 디렉터리 이름은 다른 로컬 사진 업로드와 충돌하지 않습니다.
     * 실패한 생성은 클래스 초기화 단계에서 즉시 드러냅니다.
     */
    private static Path createTemporaryDirectory() {
        try {
            return Files.createTempDirectory("shared-creature-photo-retention-h2-");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 테스트 전용 디렉터리로 PhotoFileStorage를 연결합니다.
     * 상대 경로를 사용하지 않아 IntelliJ 작업 폴더와 무관합니다.
     * 운영/개발 업로드 파일을 테스트에서 삭제하지 않습니다.
     */
    @DynamicPropertySource
    static void storageProperty(DynamicPropertyRegistry registry) {
        registry.add("app.photo.storage-dir", DIRECTORY::toString);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PhotoFileStorage storage;
    @Autowired private PhotoSubmissionRepository repository;
    @Autowired private PhotoSubmissionCleanupWork work;
    @Autowired private PhotoSubmissionCleanupTransactionService transactions;
    @Autowired private PhotoRetentionNormalizationService normalizer;
    @Autowired private SchedulerExecutionLifecycleService lifecycle;
    @Autowired private SchedulerJobRunner jobRunner;
    @Autowired private ApplicationContext context;
    @Autowired private PhotoCleanupLeaseHeartbeat leaseHeartbeat;
    @Autowired private AdjustableClock clock;

    private Long userId;
    private Long seasonId;
    private Long periodId;
    private Long templateId;
    private Long versionId;
    private Long assignmentId;
    private final List<Long> bulkUserIds = new ArrayList<>();

    /**
     * 단일 사용자·시즌·PHOTO_EXIF 배정을 실제 MySQL에 준비합니다.
     * 종료 시각과 시즌 상태를 직접 변경할 수 있는 fixture입니다.
     * 다른 테스트의 데이터와 혼동하지 않도록 빈 test DB를 요구합니다.
     */
    @BeforeEach
    void setUp() {
        clock.set(NOW);
        bulkUserIds.clear();
        assertEquals(0L, count("SELECT COUNT(*) FROM seasons"),
                "반드시 별도의 빈 shared_creature_game_test DB에서 이 테스트만 실행하세요.");

        // 사용자·시즌·템플릿·기간·배정 외에는 실제 사진 제출을 만들지 않습니다.
        userId = insert("""
                INSERT INTO users (
                    status
                  , global_role
                  , last_login_at
                  , last_active_at
                  , version
                  , created_at
                  , updated_at
                )
                VALUES ('ACTIVE', 'USER', ?, ?, 0, ?, ?)
                """, at(NOW), at(NOW), at(NOW), at(NOW));
        seasonId = insert("""
                INSERT INTO seasons (
                    season_number
                  , start_at
                  , end_at
                  , status
                  , version
                  , created_at
                  , updated_at
                )
                VALUES (990001, ?, ?, 'CLOSING', 0, ?, ?)
                """, at(SEASON_START), at(SEASON_END), at(NOW), at(NOW));
        templateId = insert("""
                INSERT INTO quest_templates (
                    code
                  , period_type
                  , difficulty
                  , status
                  , rotation_order
                  , created_at
                  , updated_at
                )
                VALUES ('H2-PHOTO', 'WEEKLY', 'LOW', 'ACTIVE', 1, ?, ?)
                """, at(NOW), at(NOW));
        versionId = insert("""
                INSERT INTO quest_template_versions (
                    quest_template_id
                  , version_number
                  , title
                  , description
                  , verification_type
                  , verification_parameters
                  , creature_experience
                  , contribution_score
                  , created_at
                )
                VALUES (?, 1, '사진 검증', 'H2 test', 'PHOTO_EXIF', JSON_OBJECT(), 100, 20, ?)
                """, templateId, at(NOW));
        periodId = insert("""
                INSERT INTO quest_periods (
                    season_id
                  , period_type
                  , period_date
                  , start_at
                  , end_at
                  , status
                  , version
                  , created_at
                  , updated_at
                )
                VALUES (?, 'WEEKLY', ?, ?, ?, 'CLOSED', 0, ?, ?)
                """, seasonId, LocalDate.of(2026, 9, 14), at(SEASON_START.plus(Duration.ofDays(1))),
                at(SEASON_START.plus(Duration.ofDays(8))), at(NOW), at(NOW));
        assignmentId = insert("""
                INSERT INTO quest_assignments (
                    quest_period_id
                  , quest_template_id
                  , quest_template_version_id
                  , slot_number
                  , title_snapshot
                  , description_snapshot
                  , difficulty_snapshot
                  , verification_type_snapshot
                  , verification_parameters_snapshot
                  , creature_experience_snapshot
                  , contribution_score_snapshot
                  , created_at
                )
                VALUES (?, ?, ?, 1, '사진 검증', 'H2 test', 'LOW', 'PHOTO_EXIF',
                        JSON_OBJECT(), 100, 20, ?)
                """, periodId, templateId, versionId, at(NOW));
    }

    /**
     * 이 테스트가 생성한 FK 행과 Scheduler 실행권을 역순으로 정리합니다.
     * 테스트별 실제 사진 파일은 보존 테스트 실패와 관계없이 후처리합니다.
     * 다른 테스트의 물리 디렉터리에는 접근하지 않습니다.
     */
    @AfterEach
    void tearDown() throws IOException {
        try {
            if (assignmentId != null) jdbc.update("DELETE FROM photo_submissions WHERE quest_assignment_id = ?", assignmentId);
            jdbc.update("DELETE FROM scheduler_executions WHERE job_type = ?",
                    PhotoRetentionNormalizationService.JOB_TYPE);
            if (assignmentId != null) jdbc.update("DELETE FROM quest_assignments WHERE id = ?", assignmentId);
            if (periodId != null) jdbc.update("DELETE FROM quest_periods WHERE id = ?", periodId);
            if (versionId != null) jdbc.update("DELETE FROM quest_template_versions WHERE id = ?", versionId);
            if (templateId != null) jdbc.update("DELETE FROM quest_templates WHERE id = ?", templateId);
            if (seasonId != null) jdbc.update("DELETE FROM seasons WHERE id = ?", seasonId);
            for (Long bulkUserId : bulkUserIds) jdbc.update("DELETE FROM users WHERE id = ?", bulkUserId);
            if (userId != null) jdbc.update("DELETE FROM users WHERE id = ?", userId);
        } finally {
            // 남은 파일은 이 테스트 전용 디렉터리에 한정하여 삭제합니다.
            try (var paths = Files.list(DIRECTORY)) {
                for (Path photo : paths.toList()) Files.deleteIfExists(photo);
            }
        }
    }

    /**
     * 클래스 종료 시 임시 저장소 디렉터리까지 삭제합니다.
     * 실패한 테스트도 잔여 파일을 사용자 업로드 디렉터리에 남기지 않습니다.
     * 다른 test 클래스의 경로에는 영향을 미치지 않습니다.
     */
    @AfterAll
    static void deleteTemporaryDirectory() throws IOException {
        try (var files = Files.walk(DIRECTORY)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    /**
     * PENDING이 정확히 1시간이면 유지하고 1마이크로초 넘으면 실패로 바꿉니다.
     * MySQL DATETIME(6) 정밀도를 직접 확인합니다.
     * 정상 업로드 중인 사진 파일은 아직 지우지 않습니다.
     */
    @Test
    void pendingBoundaryIsStrictlyMoreThanOneHour() throws Exception {
        Long id = createPhoto("PENDING", NOW.minus(Duration.ofHours(1)), null);
        assertEquals(0, cleanupAt(work, NOW).pendingInvalidated());
        assertEquals("PENDING", textColumn(id, "verification_status"));

        // 기한을 1마이크로초 초과하도록 조정한 뒤 같은 작업을 다시 호출합니다.
        jdbc.update("UPDATE photo_submissions SET created_at = ? WHERE id = ?",
                at(NOW.minus(Duration.ofHours(1)).minusNanos(1_000)), id);
        assertEquals(1, cleanupAt(work, NOW).pendingInvalidated());
        assertEquals("INVALID", textColumn(id, "verification_status"));
        assertEquals("PHOTO_PROCESSING_FAILED", textColumn(id, "failure_reason"));
        assertNotNull(textColumn(id, "storage_key"));
    }

    /**
     * 1시간 초과 PENDING은 현재 실행에서 실패 상태로 확정합니다.
     * 후보 목록은 실행 시작 시 고정되므로 파일은 다음 5분 실행에서 정리합니다.
     * 실패 사유와 제출 이력은 파일 정리 후에도 보존합니다.
     */
    @Test
    void pendingFailureIsDeletedOnNextCleanupCycle() throws Exception {
        Long id = createPhoto("PENDING", NOW.minus(Duration.ofHours(2)), null);
        String key = textColumn(id, "storage_key");
        assertEquals(1, cleanupAt(work, NOW).pendingInvalidated());
        assertTrue(Files.exists(DIRECTORY.resolve(key)));

        assertEquals(1, cleanupAt(work, NOW.plus(Duration.ofMinutes(5))).invalidFilesDeleted());
        assertFalse(Files.exists(DIRECTORY.resolve(key)));
        assertNull(textColumn(id, "storage_key"));
        assertEquals("PHOTO_PROCESSING_FAILED", textColumn(id, "failure_reason"));
    }

    /**
     * INVALID 사진은 다음 정리 실행에서 즉시 삭제합니다.
     * 정리 후에도 제출 이력, 판정 사유, 촬영 시각은 보존합니다.
     * 파일 키와 GPS 좌표만 제거합니다.
     */
    @Test
    void invalidFileCleanupPreservesMinimalAuditHistory() throws Exception {
        Long id = createPhoto("INVALID", NOW, "EXIF_GPS_MISSING");
        String key = textColumn(id, "storage_key");
        assertEquals(1, cleanupAt(work, NOW).invalidFilesDeleted());
        assertFalse(Files.exists(DIRECTORY.resolve(key)));
        assertNull(textColumn(id, "storage_key"));
        assertNull(textColumn(id, "latitude"));
        assertNull(textColumn(id, "longitude"));
        assertNotNull(textColumn(id, "file_deleted_at"));
        assertEquals("EXIF_GPS_MISSING", textColumn(id, "failure_reason"));
        assertEquals(1L, count("SELECT COUNT(*) FROM photo_submissions WHERE id = ?", id));
    }

    /**
     * VALID 사진은 종료 시각이 충분히 오래됐어도 CLOSING 중에는 삭제하지 않습니다.
     * ENDED로 확정된 뒤 같은 사진을 정리할 수 있습니다.
     * 파일·좌표만 비우고 성공 판정 이력은 그대로 남깁니다.
     */
    @Test
    void validPhotosRequireEndedSeasonAndThirtyDays() throws Exception {
        Long id = createPhoto("VALID", NOW.minus(Duration.ofDays(40)), null);
        String key = textColumn(id, "storage_key");
        assertEquals(0, cleanupAt(work, NOW).validFilesDeleted());
        assertTrue(Files.exists(DIRECTORY.resolve(key)));

        jdbc.update("UPDATE seasons SET status = 'ENDED' WHERE id = ?", seasonId);
        assertEquals(1, cleanupAt(work, NOW).validFilesDeleted());
        assertFalse(Files.exists(DIRECTORY.resolve(key)));
        assertEquals("VALID", textColumn(id, "verification_status"));
        assertNull(textColumn(id, "latitude"));
        assertNotNull(textColumn(id, "file_deleted_at"));
    }

    /**
     * 실제 종료된 시즌이라도 30일보다 빠른 시점에는 사진을 보존합니다.
     * 30일 경계에 정확히 도달한 순간부터 정리를 허용합니다.
     * 시각 계산은 MySQL DATETIME(6) 단위로 비교합니다.
     */
    @Test
    void validRetentionThirtyDayBoundaryIsInclusive() throws Exception {
        Long id = createPhoto("VALID", NOW.minus(Duration.ofDays(40)), null);
        jdbc.update("UPDATE seasons SET status = 'ENDED', end_at = ? WHERE id = ?",
                at(NOW.minus(Duration.ofDays(30)).plusNanos(1_000)), seasonId);
        assertEquals(0, cleanupAt(work, NOW).validFilesDeleted());

        jdbc.update("UPDATE seasons SET end_at = ? WHERE id = ?",
                at(NOW.minus(Duration.ofDays(30))), seasonId);
        assertEquals(1, cleanupAt(work, NOW).validFilesDeleted());
        assertNotNull(textColumn(id, "file_deleted_at"));
    }

    /**
     * 물리 파일 삭제가 IOException을 던지면 DB 키와 좌표를 유지합니다.
     * 다음 실행에서 실제 파일 저장소를 사용하면 정상 삭제되어야 합니다.
     * 단건 실패 결과는 실패 건수에 기록됩니다.
     */
    @Test
    void storageFailureLeavesRecordAndNextRunRecovers() throws Exception {
        Long id = createPhoto("INVALID", NOW.minus(Duration.ofDays(2)), "EXIF_GPS_MISSING");
        String key = textColumn(id, "storage_key");
        PhotoFileStorage unavailable = new PhotoFileStorage(DIRECTORY.toString()) {
            @Override
            public void delete(String storageKey) throws IOException {
                throw new IOException("simulated storage outage");
            }
        };
        PhotoSubmissionCleanupWork failedWork = new PhotoSubmissionCleanupWork(
                repository, transactions, unavailable, leaseHeartbeat, clock);
        assertEquals(1, cleanupAt(failedWork, NOW).failedCount());
        assertTrue(Files.exists(DIRECTORY.resolve(key)));
        assertEquals(key, textColumn(id, "storage_key"));
        assertNull(textColumn(id, "file_deleted_at"));

        assertEquals(0, cleanupAt(work, NOW).invalidFilesDeleted());
        assertEquals(1, cleanupAt(work, NOW.plus(Duration.ofMinutes(5))).invalidFilesDeleted());
        assertFalse(Files.exists(DIRECTORY.resolve(key)));
        assertNull(textColumn(id, "storage_key"));
    }

    /**
     * 별도 트랜잭션이 아닌 실제 파일 삭제 구간에서 DB 연결을 점유하지 않습니다.
     * 두 Bean으로 경계를 나눈 4-H-1 구조가 실제 MySQL에서도 작동해야 합니다.
     * 파일은 테스트 전용 임시 디렉터리에서 실제로 삭제합니다.
     */
    @Test
    void realFileDeletionOccursOutsideDatabaseTransaction() throws Exception {
        Long id = createPhoto("INVALID", NOW.minus(Duration.ofDays(2)), "EXIF_GPS_MISSING");
        PhotoFileStorage checkingStorage = new PhotoFileStorage(DIRECTORY.toString()) {
            @Override
            public void delete(String key) throws IOException {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(), "파일 삭제 시 DB 트랜잭션을 유지하면 안 됩니다.");
                super.delete(key);
            }
        };
        PhotoSubmissionCleanupWork checkingWork = new PhotoSubmissionCleanupWork(
                repository, transactions, checkingStorage, leaseHeartbeat, clock);
        assertEquals(1, cleanupAt(checkingWork, NOW).invalidFilesDeleted());
        assertNotNull(textColumn(id, "file_deleted_at"));
    }

    /**
     * 파일 삭제 뒤 DB Commit 전에 작업이 중단된 상황을 재현합니다.
     * 삭제 완료 기록은 아직 없지만 파일만 없는 상태입니다.
     * 다음 실행의 deleteIfExists와 DB 확정으로 불일치를 복구합니다.
     */
    @Test
    void deletedFileBeforeDatabaseConfirmationRecoversOnRetry() throws Exception {
        Long id = createPhoto("INVALID", NOW.minus(Duration.ofDays(2)), "EXIF_GPS_MISSING");
        String key = textColumn(id, "storage_key");
        storage.delete(key); // 파일 삭제 후 프로세스가 종료되어 DB가 미확정인 상태
        assertFalse(Files.exists(DIRECTORY.resolve(key)));
        assertEquals(key, textColumn(id, "storage_key"));
        assertNull(textColumn(id, "file_deleted_at"));

        assertEquals(1, cleanupAt(work, NOW).invalidFilesDeleted());
        assertNull(textColumn(id, "storage_key"));
        assertNotNull(textColumn(id, "file_deleted_at"));
        assertEquals(0, cleanupAt(work, NOW).invalidFilesDeleted());
    }

    /**
     * 파일 키가 이미 NULL이어도 남은 GPS 좌표를 보존 기한에 정리합니다.
     * 파일 삭제를 시도하지 않고 DB의 최소 감사 이력만 유지합니다.
     * 과거 부분 정리로 키가 사라진 데이터도 다음 점검에서 복구됩니다.
     */
    @Test
    void nullStorageKeyStillCleansCoordinates() throws Exception {
        Long id = createPhoto("INVALID", NOW.minus(Duration.ofDays(2)), "EXIF_GPS_MISSING");
        String key = textColumn(id, "storage_key");
        storage.delete(key);
        jdbc.update("UPDATE photo_submissions SET storage_key = NULL WHERE id = ?", id);

        assertEquals(1, cleanupAt(work, NOW).invalidFilesDeleted());
        assertNull(textColumn(id, "latitude"));
        assertNull(textColumn(id, "longitude"));
        assertNotNull(textColumn(id, "file_deleted_at"));
        assertEquals("EXIF_GPS_MISSING", textColumn(id, "failure_reason"));
    }

    /**
     * 두 물리 트랜잭션이 동일한 삭제 결과를 동시에 확정합니다.
     * SELECT FOR UPDATE 이후 한 건만 이력 변경에 성공해야 합니다.
     * file_deleted_at은 한 번 설정되며 삭제된 키가 다시 기록되지 않습니다.
     */
    @Test
    @Timeout(20)
    void concurrentConfirmationChangesDatabaseOnlyOnce() throws Exception {
        Long id = createPhoto("INVALID", NOW.minus(Duration.ofDays(2)), "EXIF_GPS_MISSING");
        String key = textColumn(id, "storage_key");
        assertTrue(transactions.claimEligibleFile(
                id,
                PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                CLAIM,
                NOW,
                NOW,
                NOW.plus(Duration.ofMinutes(10))
        ).isPresent());
        storage.delete(key);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> confirmWhenReleased(start, id, key));
            Future<Boolean> second = pool.submit(() -> confirmWhenReleased(start, id, key));
            start.countDown();
            int success = (first.get(15, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, success);
            assertNull(textColumn(id, "storage_key"));
            assertNotNull(textColumn(id, "file_deleted_at"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /**
     * 실행 구간이 다른 두 작업이 같은 후보를 보더라도 단건 claim으로 파일 삭제를 직렬화합니다.
     * 첫 실행의 파일 I/O 동안 두 번째 실행은 후보를 건너뛰며 물리 삭제는 한 번만 호출됩니다.
     */
    @Test
    @Timeout(20)
    void concurrentCleanupClaimsFileOnlyOnce() throws Exception {
        Long id = createPhoto("INVALID", NOW, "EXIF_GPS_MISSING");
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger deleteCalls = new AtomicInteger();
        PhotoFileStorage blockingStorage = new PhotoFileStorage(DIRECTORY.toString()) {
            @Override
            public void delete(String storageKey) throws IOException {
                deleteCalls.incrementAndGet();
                deleting.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("release timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", exception);
                }
                super.delete(storageKey);
            }
        };
        PhotoSubmissionCleanupWork concurrentWork = new PhotoSubmissionCleanupWork(
                repository, transactions, blockingStorage, leaseHeartbeat, clock);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PhotoRetentionWork.Summary> first = pool.submit(() -> cleanupAt(concurrentWork, NOW));
            assertTrue(deleting.await(10, TimeUnit.SECONDS));
            Future<PhotoRetentionWork.Summary> second = pool.submit(
                    () -> cleanupAt(concurrentWork, NOW.plus(Duration.ofMinutes(5))));
            PhotoRetentionWork.Summary skipped = second.get(10, TimeUnit.SECONDS);
            release.countDown();
            PhotoRetentionWork.Summary completed = first.get(10, TimeUnit.SECONDS);

            assertEquals(1, completed.invalidFilesDeleted() + skipped.invalidFilesDeleted());
            assertEquals(1, deleteCalls.get());
            assertNull(textColumn(id, "storage_key"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /**
     * Claim 만료 직전에는 다른 실행을 차단하고 정확한 만료 시각부터 재선점을 허용합니다.
     * 재선점 뒤 이전 토큰은 파일 삭제 완료를 DB에 확정할 수 없습니다.
     */
    @Test
    void claimBoundaryAllowsOnlyCurrentTokenToConfirm() throws Exception {
        Long id = createPhoto("INVALID", NOW, "EXIF_GPS_MISSING");
        String key = textColumn(id, "storage_key");
        Instant expiresAt = NOW.plus(Duration.ofMinutes(10));
        assertTrue(transactions.claimEligibleFile(
                id, PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                CLAIM, NOW, NOW, expiresAt).isPresent());

        assertTrue(transactions.claimEligibleFile(
                id, PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                NEW_CLAIM, NOW, expiresAt.minusNanos(1_000), expiresAt.plus(Duration.ofMinutes(10)))
                .isEmpty());
        assertTrue(transactions.claimEligibleFile(
                id, PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                NEW_CLAIM, NOW, expiresAt, expiresAt.plus(Duration.ofMinutes(10)))
                .isPresent());

        storage.delete(key);
        assertFalse(transactions.confirmFileDeletion(
                id, PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                key, CLAIM, NOW, expiresAt));
        assertTrue(transactions.confirmFileDeletion(
                id, PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                key, NEW_CLAIM, NOW, expiresAt));
        assertNull(textColumn(id, "storage_key"));
    }

    /**
     * 파일 I/O 도중 Claim이 강제로 만료·재선점되면 이전 실행은 DB 확정을 중단합니다.
     * 새 소유자는 멱등 파일 삭제 후 동일 이력을 안전하게 복구합니다.
     */
    @Test
    @Timeout(20)
    void reclaimedClaimStopsPreviousLongRunningDeletion() throws Exception {
        Long id = createPhoto("INVALID", NOW, "EXIF_GPS_MISSING");
        String key = textColumn(id, "storage_key");
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PhotoFileStorage blockingStorage = new PhotoFileStorage(DIRECTORY.toString()) {
            @Override
            public void delete(String storageKey) throws IOException {
                deleting.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("release timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", exception);
                }
                super.delete(storageKey);
            }
        };
        PhotoSubmissionCleanupWork blockingWork = new PhotoSubmissionCleanupWork(
                repository, transactions, blockingStorage, leaseHeartbeat, clock);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PhotoRetentionWork.Summary> previous = pool.submit(() -> cleanupAt(blockingWork, NOW));
            assertTrue(deleting.await(10, TimeUnit.SECONDS));
            jdbc.update("UPDATE photo_submissions SET cleanup_claim_expires_at = ? WHERE id = ?",
                    at(NOW.minusNanos(1_000)), id);
            Instant reclaimedAt = NOW.plus(Duration.ofMinutes(11));
            clock.set(reclaimedAt);
            assertTrue(transactions.claimEligibleFile(
                    id, PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                    NEW_CLAIM, NOW, reclaimedAt, reclaimedAt.plus(Duration.ofMinutes(10)))
                    .isPresent());

            release.countDown();
            assertThrows(ExecutionException.class, () -> previous.get(10, TimeUnit.SECONDS));
            assertEquals(key, textColumn(id, "storage_key"));

            storage.delete(key);
            assertTrue(transactions.confirmFileDeletion(
                    id, PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                    key, NEW_CLAIM, NOW, reclaimedAt));
            assertNull(textColumn(id, "storage_key"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** Claim 획득 실패 후 별도 트랜잭션이 실제 MySQL 행에 5분 재시도를 기록합니다. */
    @Test
    void claimFailureDeferralPersistsWithoutOverwritingOtherOwner() throws Exception {
        Long deferredId = createPhoto("INVALID", NOW, "EXIF_GPS_MISSING");
        Instant retryAt = NOW.plus(Duration.ofMinutes(5));
        assertEquals(
                PhotoSubmissionCleanupTransactionService.ClaimFailureResolution.DEFERRED,
                transactions.deferAfterClaimFailure(
                        deferredId,
                        PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                        CLAIM,
                        NOW,
                        NOW,
                        retryAt
                )
        );
        assertEquals(at(retryAt), jdbc.queryForObject(
                "SELECT cleanup_next_attempt_at FROM photo_submissions WHERE id = ?",
                Timestamp.class,
                deferredId
        ));

        jdbc.update("DELETE FROM photo_submissions WHERE id = ?", deferredId);
        Long claimedId = createPhoto("INVALID", NOW, "EXIF_GPS_MISSING");
        assertTrue(transactions.claimEligibleFile(
                claimedId,
                PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                NEW_CLAIM,
                NOW,
                NOW,
                NOW.plus(Duration.ofMinutes(10))
        ).isPresent());
        assertEquals(
                PhotoSubmissionCleanupTransactionService.ClaimFailureResolution.SKIPPED,
                transactions.deferAfterClaimFailure(
                        claimedId,
                        PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                        CLAIM,
                        NOW,
                        NOW,
                        retryAt
                )
        );
        assertEquals(NEW_CLAIM, textColumn(claimedId, "cleanup_claim_token"));
        assertNull(textColumn(claimedId, "cleanup_next_attempt_at"));
    }

    /**
     * 앞선 100건이 계속 실패해도 뒤의 200건을 여러 실행에 걸쳐 모두 정리합니다.
     * 실패 대상은 5분 뒤 재시도 목록으로 순환하며 저장소 복구 후 정상 정리됩니다.
     */
    @Test
    @Timeout(60)
    void persistentFailuresDoNotStarveFollowingTwoHundredPhotos() throws Exception {
        Set<String> failingKeys = new HashSet<>();
        Long currentUserId = userId;
        for (int index = 0; index < 300; index++) {
            if (index > 0 && index % 5 == 0) {
                currentUserId = createBulkUser(index);
                bulkUserIds.add(currentUserId);
            }
            Long id = createPhotoForUser(
                    currentUserId, index % 5 + 1, "INVALID", NOW.minus(Duration.ofDays(1)), "EXIF_GPS_MISSING");
            if (index < 100) failingKeys.add(textColumn(id, "storage_key"));
        }

        PhotoFileStorage partiallyUnavailable = new PhotoFileStorage(DIRECTORY.toString()) {
            @Override
            public void delete(String storageKey) throws IOException {
                if (failingKeys.contains(storageKey)) throw new IOException("persistent failure");
                super.delete(storageKey);
            }
        };
        PhotoSubmissionCleanupWork failingWork = new PhotoSubmissionCleanupWork(
                repository, transactions, partiallyUnavailable, leaseHeartbeat, clock);
        for (int run = 0; run < 5; run++) {
            cleanupAt(failingWork, NOW.plus(Duration.ofMinutes(5L * run)));
        }

        assertEquals(200L, count("SELECT COUNT(*) FROM photo_submissions WHERE storage_key IS NULL"));
        assertEquals(100L, count("SELECT COUNT(*) FROM photo_submissions WHERE storage_key IS NOT NULL"));
        assertEquals(100L, count("SELECT COUNT(*) FROM photo_submissions WHERE cleanup_next_attempt_at IS NOT NULL"));

        assertEquals(100, cleanupAt(work, NOW.plus(Duration.ofMinutes(25))).invalidFilesDeleted());
        assertEquals(300L, count("SELECT COUNT(*) FROM photo_submissions WHERE storage_key IS NULL"));
    }

    /**
     * 실제 SchedulerJobRunner의 UNIQUE 실행권과 FAILED 재시도를 검증합니다.
     * 첫 실행에서 업무 실패가 기록되면 같은 논리 구간에서 재선점합니다.
     * 성공한 구간은 세 번째 호출에서 SKIPPED로 건너뜁니다.
     */
    @Test
    void failedSchedulerRunCanRetryThenSkipSucceededBucket() {
        PhotoRetentionWork fails = now -> new PhotoRetentionWork.Summary(0, 0, 0, 1, false);
        PhotoRetentionNormalizationService firstAttempt = new PhotoRetentionNormalizationService(
                jobRunner, lifecycle, fails, Clock.fixed(NOW, ZoneOffset.UTC));
        var failed = firstAttempt.normalize();
        assertNotNull(failed.error());
        assertEquals("FAILED", schedulerStatus());

        var succeeded = normalizer.normalize();
        assertEquals(SchedulerJobRunner.ExecutionResult.SUCCEEDED, succeeded.execution());
        assertEquals("SUCCEEDED", schedulerStatus());
        assertEquals(2L, count("SELECT attempt_count FROM scheduler_executions WHERE job_type = ?",
                PhotoRetentionNormalizationService.JOB_TYPE));
        assertEquals(SchedulerJobRunner.ExecutionResult.SKIPPED, normalizer.normalize().execution());
    }

    /**
     * test 프로필에서 사진 트리거가 자동으로 등록되면 안 됩니다.
     * 다른 테스트의 DB나 사진 파일이 기동 시 삭제되지 않도록 합니다.
     * 실제 업무 서비스 Bean은 등록되어 수동 검증만 가능합니다.
     */
    @Test
    void photoSchedulerIsNotRegisteredDuringTestProfile() {
        assertTrue(context.getBeansOfType(PhotoRetentionScheduler.class).isEmpty());
        assertNotNull(normalizer);
    }

    /**
     * 서로 다른 MySQL 커넥션이 한 번에 처리하도록 시작 신호를 기다립니다.
     * 실제 REQUIRES_NEW 트랜잭션이 행 잠금 경합을 제어합니다.
     * 실행 결과 예외를 Future를 통해 메인 테스트로 전파합니다.
     */
    private boolean confirmWhenReleased(CountDownLatch start, Long id, String key) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", exception);
        }
        return transactions.confirmFileDeletion(id,
                PhotoSubmissionCleanupTransactionService.RetentionKind.INVALID,
                key, CLAIM, NOW, NOW);
    }

    /** 정책 시각과 실제 제어 시각을 함께 이동해 사진 정리를 실행합니다. */
    private PhotoRetentionWork.Summary cleanupAt(PhotoSubmissionCleanupWork target, Instant now) {
        clock.set(now);
        return target.cleanup(now);
    }

    /**
     * 기본 필드를 가진 사진 제출 이력을 실제 MySQL에 생성합니다.
     * 파일은 PR #76의 PhotoFileStorage로 실제 임시 디렉터리에 씁니다.
     * INVALID의 실패 확정은 판정 시각에 해당하는 updated_at으로 보존합니다.
     */
    private Long createPhoto(String status, Instant decidedAt, String failureReason) throws Exception {
        return createPhotoForUser(userId, 1, status, decidedAt, failureReason);
    }

    /** 대량 정리 검증에서 사용자별 5회 제약을 지키며 사진 제출을 생성합니다. */
    private Long createPhotoForUser(
            Long targetUserId,
            int attemptNumber,
            String status,
            Instant decidedAt,
            String failureReason
    ) throws Exception {
        String key = storage.store(MINIMAL_FILE);
        return insert("""
                INSERT INTO photo_submissions (
                    user_id
                  , quest_assignment_id
                  , storage_key
                  , attempt_number
                  , captured_at
                  , uploaded_at
                  , latitude
                  , longitude
                  , verification_status
                  , failure_reason
                  , file_deleted_at
                  , created_at
                  , updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 35.1234567, 129.1234567, ?, ?, NULL, ?, ?)
                """, targetUserId, assignmentId, key, attemptNumber, at(decidedAt), at(decidedAt),
                status, failureReason, at(decidedAt), at(decidedAt));
    }

    /** 대량 사진 fixture용 사용자를 최소 필드로 생성합니다. */
    private Long createBulkUser(int sequence) {
        return insert("""
                INSERT INTO users (
                    status
                  , global_role
                  , last_login_at
                  , last_active_at
                  , version
                  , created_at
                  , updated_at
                )
                VALUES ('ACTIVE', 'USER', ?, ?, 0, ?, ?)
                """, at(NOW.plusSeconds(sequence)), at(NOW.plusSeconds(sequence)),
                at(NOW.plusSeconds(sequence)), at(NOW.plusSeconds(sequence)));
    }

    /**
     * 실제 JdbcTemplate을 사용하여 DB 생성 ID를 읽습니다.
     * 명시적 파라미터만 바인딩해 SQL에서 문자열을 직접 이어붙이지 않습니다.
     * 모든 데이터는 이 클래스의 tearDown에서 FK 역순으로 삭제합니다.
     */
    private Long insert(String sql, Object... values) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey(), "generated id").longValue();
    }

    /**
     * MySQL DATETIME(6) 비교에 사용할 UTC Instant를 Timestamp로 변환합니다.
     * 기존 프로젝트의 JDBC 테스트와 같은 변환 규칙을 사용합니다.
     * 1마이크로초 경계 값을 손실 없이 보존합니다.
     */
    private Timestamp at(Instant value) {
        return Timestamp.from(value);
    }

    /**
     * 단건 문자열/Nullable 상태를 실제 DB에서 다시 읽습니다.
     * JPA 1차 캐시 없이 물리 테이블 값을 확인합니다.
     * 좌표 컬럼은 문자열로 읽어 NULL 여부만 검사합니다.
     */
    private String textColumn(Long photoId, String column) {
        // column은 테스트 내부의 상수만 사용하며 외부 입력이 아닙니다.
        return jdbc.queryForObject("SELECT " + column + " FROM photo_submissions WHERE id = ?",
                (rs, row) -> rs.getString(1), photoId);
    }

    /**
     * 현재 사진 정리 논리 작업의 실행 상태를 DB에서 읽습니다.
     * 실행 결과만 검증하며 실제 스케줄 트리거는 비활성화 상태입니다.
     * 실행 이력은 tearDown에서 정리합니다.
     */
    private String schedulerStatus() {
        return jdbc.queryForObject("SELECT status FROM scheduler_executions WHERE job_type = ?",
                String.class, PhotoRetentionNormalizationService.JOB_TYPE);
    }

    /**
     * MySQL 단일 수치 조회 결과에서 NULL을 허용하지 않습니다.
     * 상태 전이와 실행권 ID의 실데이터를 확인합니다.
     * 테스트 코드는 업데이트 시각을 조작할 때에만 DML을 사용합니다.
     */
    private long count(String sql, Object... values) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, values));
    }
}
