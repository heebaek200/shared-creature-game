
package com.team.sharedcreature.auth.integration;

import com.team.sharedcreature.auth.domain.User;
import com.team.sharedcreature.auth.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MySQL에서 마지막 인증 활동 시각의 조건부 UPDATE를 검증합니다.
 *
 * 저장 간격 제한과 동시 UPDATE의 원자성을 검사합니다.
 * 각 갱신은 독립된 트랜잭션으로 실행하여 실제 운영 환경의 경합을 재현합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DB_USERNAME", matches = ".+")
class UserActivityUpdateIntegrationTest {

    private static final Instant INITIAL_ACTIVITY =
            Instant.parse("2026-01-01T00:00:00Z");

    private static final Duration WRITE_INTERVAL =
            Duration.ofMinutes(10);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private Long userId;

    /**
     * 각 테스트가 사용할 전용 사용자를 생성합니다.
     *
     * 사용자 생성 트랜잭션을 먼저 완료해 다른 스레드에서도 조회할 수 있게 합니다.
     * 활동 시각은 고정된 값으로 설정해 실제 실행 시각에 의존하지 않습니다.
     */
    @BeforeEach
    void setUp() {
        // 갱신 작업별로 독립된 트랜잭션을 생성합니다.
        transactionTemplate = new TransactionTemplate(transactionManager);

        // 실제 users 테이블에 테스트 사용자를 저장합니다.
        userId = userRepository.saveAndFlush(
                User.register("USER", INITIAL_ACTIVITY)
        ).getId();
    }

    /**
     * 테스트에서 생성한 사용자를 삭제합니다.
     *
     * 사용자 ID를 기준으로 삭제해 기존 테스트 데이터에 영향을 주지 않습니다.
     * 각 테스트가 독립된 사용자를 사용하도록 유지합니다.
     */
    @AfterEach
    void tearDown() {
        if (userId != null) {
            // 테스트가 생성한 사용자만 제거합니다.
            userRepository.deleteById(userId);
        }
    }

    /**
     * 활동 시각의 10분 저장 간격과 버전 증가를 검증합니다.
     *
     * 9분 후 요청은 저장하지 않고 11분 후 요청은 저장해야 합니다.
     * 이후 1분이 더 지난 요청도 저장하지 않아야 합니다.
     * 실제 변경이 발생한 경우에만 version이 증가하는지 확인합니다.
     */
    @Test
    void 활동_시각은_10분_간격으로만_갱신된다() {
        Long initialVersion = userRepository.findById(userId)
                .orElseThrow()
                .getVersion();

        // 최초 기록 후 9분: 아직 저장 대상이 아닙니다.
        int skipped = updateAt(INITIAL_ACTIVITY.plus(Duration.ofMinutes(9)));

        assertThat(skipped).isZero();

        User unchanged = userRepository.findById(userId).orElseThrow();
        assertThat(unchanged.getLastActiveAt()).isEqualTo(INITIAL_ACTIVITY);
        assertThat(unchanged.getVersion()).isEqualTo(initialVersion);

        // 최초 기록 후 11분: 실제 UPDATE가 수행되어야 합니다.
        Instant nextActivity = INITIAL_ACTIVITY.plus(Duration.ofMinutes(11));
        int updated = updateAt(nextActivity);

        assertThat(updated).isEqualTo(1);

        User changed = userRepository.findById(userId).orElseThrow();
        assertThat(changed.getLastActiveAt()).isEqualTo(nextActivity);
        assertThat(changed.getVersion()).isEqualTo(initialVersion + 1);

        // 갱신 직후 1분이 지난 요청은 다시 저장하지 않습니다.
        int skippedAgain = updateAt(
                INITIAL_ACTIVITY.plus(Duration.ofMinutes(12))
        );

        assertThat(skippedAgain).isZero();

        User finalState = userRepository.findById(userId).orElseThrow();
        assertThat(finalState.getLastActiveAt()).isEqualTo(nextActivity);
        assertThat(finalState.getVersion()).isEqualTo(initialVersion + 1);
    }

    /**
     * 동일 사용자의 동시 활동 요청이 중복 UPDATE를 만들지 않는지 검증합니다.
     *
     * 두 작업 스레드를 같은 출발 지점에서 시작합니다.
     * MySQL의 조건부 UPDATE가 경합을 처리해 한 요청만 변경해야 합니다.
     * 최종 활동 시각과 버전 증가량도 함께 검사합니다.
     */
    @Test
    void 동일_사용자의_동시_활동은_한_번만_갱신된다() throws Exception {
        Instant activityTime = INITIAL_ACTIVITY.plus(Duration.ofMinutes(11));
        Long initialVersion = userRepository.findById(userId)
                .orElseThrow()
                .getVersion();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 두 작업이 준비된 후 같은 시각에 갱신을 시도합니다.
        Callable<Integer> task = () -> {
            ready.countDown();

            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 요청 시작 대기 실패");
            }

            return updateAt(activityTime);
        };

        try {
            Future<Integer> first = executor.submit(task);
            Future<Integer> second = executor.submit(task);

            // 두 스레드가 준비된 것을 확인한 뒤 동시에 출발시킵니다.
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            // 조건을 만족한 UPDATE는 정확히 한 건이어야 합니다.
            assertThat(results).containsExactlyInAnyOrder(1, 0);

            User actual = userRepository.findById(userId).orElseThrow();

            assertThat(actual.getLastActiveAt()).isEqualTo(activityTime);
            assertThat(actual.getVersion()).isEqualTo(initialVersion + 1);
        } finally {
            // 테스트 실패 시에도 대기 중인 스레드를 정리합니다.
            start.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 지정한 시각에 발생한 인증 활동을 실제 DB에 반영합니다.
     *
     * UserRepository의 @Modifying Query에는 트랜잭션이 필요합니다.
     * TransactionTemplate을 사용해 각 호출을 독립적인 트랜잭션으로 실행합니다.
     *
     * @param now 인증 활동이 발생한 시각
     * @return 실제 UPDATE로 변경된 행 수
     */
    private int updateAt(Instant now) {
        // DB에서 현재 활동 시각과 저장 기준 시각을 비교합니다.
        return transactionTemplate.execute(status ->
        userRepository.updateLastActiveAtIfDue(
                userId,
                now,
                now.minus(WRITE_INTERVAL)
        )
        );
    }
}
