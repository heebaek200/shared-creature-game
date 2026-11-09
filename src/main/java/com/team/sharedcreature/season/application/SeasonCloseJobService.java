package com.team.sharedcreature.season.application;

import com.team.sharedcreature.admin.application.SchedulerClaimTicket;
import com.team.sharedcreature.admin.application.SchedulerExecutionLifecycleService;
import com.team.sharedcreature.admin.application.SchedulerJobRunner;
import com.team.sharedcreature.season.domain.Season;
import com.team.sharedcreature.season.infrastructure.SeasonRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 종료 대상 시즌의 여러 독립 트랜잭션을 하나의 Scheduler 실행에 연결합니다.
 * CLOSING 진입, 그룹·개인 Snapshot 배치, ENDED 확정을 차례로 수행합니다.
 * 서버 중단 후에는 기존 CLOSING과 Snapshot을 보존하면서 누락분을 재개합니다.
 */
@Slf4j
@Service
public class SeasonCloseJobService {

    private static final String JOB_TYPE = "SEASON_CLOSE";
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);
    private static final int MAX_BATCHES_PER_STAGE = 10_000;

    private final SeasonRepository seasonRepository;
    private final SeasonTransitionService transitionService;
    private final SeasonGroupSnapshotService groupSnapshotService;
    private final SeasonMemberSnapshotService memberSnapshotService;
    private final SeasonCloseFinalizationService finalizationService;
    private final SchedulerJobRunner jobRunner;
    private final SchedulerExecutionLifecycleService lifecycleService;
    private final Clock clock;

    /**
     * 시즌 종료 흐름을 구성하는 각 독립 트랜잭션 Service를 주입합니다.
     * 이 오케스트레이터에는 @Transactional을 적용하지 않습니다.
     * 각 배치의 Commit이 끝난 다음 Heartbeat를 수행하기 위함입니다.
     */
    public SeasonCloseJobService(
            SeasonRepository seasonRepository,
            SeasonTransitionService transitionService,
            SeasonGroupSnapshotService groupSnapshotService,
            SeasonMemberSnapshotService memberSnapshotService,
            SeasonCloseFinalizationService finalizationService,
            SchedulerJobRunner jobRunner,
            SchedulerExecutionLifecycleService lifecycleService,
            Clock clock
    ) {
        this.seasonRepository = seasonRepository;
        this.transitionService = transitionService;
        this.groupSnapshotService = groupSnapshotService;
        this.memberSnapshotService = memberSnapshotService;
        this.finalizationService = finalizationService;
        this.jobRunner = jobRunner;
        this.lifecycleService = lifecycleService;
        this.clock = clock;
    }

    /**
     * 한 시즌의 종료를 논리적 종료 시각을 기준으로 실행합니다.
     * 아직 종료 시각이 되지 않은 시즌은 실행 이력을 만들지 않습니다.
     * 이미 ENDED여도 이전 Scheduler 성공 기록이 누락됐다면 안전하게 복구합니다.
     *
     * @param seasonId 종료할 시즌 ID
     * @param ownerInstanceId 실행 서버 인스턴스 ID
     * @return 성공 또는 다른 실행자의 선점으로 인한 건너뜀
     */
    public SchedulerJobRunner.ExecutionResult runSeasonClose(
            Long seasonId,
            String ownerInstanceId
    ) {
        Objects.requireNonNull(seasonId, "seasonId");
        Season season = seasonRepository.findById(seasonId)
                .orElseThrow(() -> new IllegalStateException(
                        "시즌을 찾을 수 없습니다. seasonId=" + seasonId
                ));
        Instant scheduledFor = Objects.requireNonNull(season.getEndAt(), "season.endAt");

        // 실제 호출 시각이 아니라 시즌의 논리 종료 시각을 실행 이력의 키로 사용합니다.
        if (Instant.now(clock).isBefore(scheduledFor)) {
            return SchedulerJobRunner.ExecutionResult.SKIPPED;
        }

        // 티켓을 전달받는 오버로드를 사용하여 배치 사이 Lease 갱신을 허용합니다.
        return jobRunner.run(
                JOB_TYPE,
                scheduledFor,
                ownerInstanceId,
                LEASE_DURATION,
                ticket -> performClose(seasonId, ticket)
        );
    }

    /**
     * 종료 진입부터 최종 ENDED 확정까지 순서대로 처리합니다.
     * 모든 업무 Service의 독립적인 Commit이 완료돼야 JSON 결과를 반환합니다.
     * 이미 ENDED인 시즌은 Snapshot을 다시 쓰지 않고 성공 기록만 보완합니다.
     *
     * @param seasonId 종료할 시즌 ID
     * @param ticket 유효한 Scheduler 실행권
     * @return Scheduler 결과 저장용 JSON 문자열
     */
    private String performClose(Long seasonId, SchedulerClaimTicket ticket) {
        // 이전 실행이 ENDED까지 Commit한 뒤 성공 이력만 놓쳤을 수 있습니다.
        if (isEnded(seasonId)) {
            return summary(seasonId, 0, 0, true);
        }

        // 시즌 전이와 각 Snapshot 배치는 모두 별도 트랜잭션에서 Commit됩니다.
        heartbeat(ticket);
        transitionService.beginClosingIfExpired(seasonId);
        int groupTotal = drainGroupSnapshots(seasonId, ticket);
        int memberTotal = drainMemberSnapshots(seasonId, ticket);

        // 최종 상태 변경 전에 다시 유효한 Lease인지 확인합니다.
        heartbeat(ticket);
        finalizationService.finishIfSnapshotsComplete(seasonId);

        // finalize가 실패하면 여기로 오지 않으며 Runner가 FAILED 기록을 시도합니다.
        log.info("시즌 종료 완료. seasonId={}, groupSnapshots={}, memberSnapshots={}",
                seasonId, groupTotal, memberTotal);
        return summary(seasonId, groupTotal, memberTotal, false);
    }

    /**
     * 그룹 랭킹 Snapshot을 100건 단위의 독립 트랜잭션으로 모두 저장합니다.
     * 이미 저장한 Snapshot은 배치 Service가 건드리지 않습니다.
     * 각 배치 사이에 Heartbeat를 갱신하고 진행 불능 상태는 예외로 중단합니다.
     */
    private int drainGroupSnapshots(Long seasonId, SchedulerClaimTicket ticket) {
        for (int attempt = 0; attempt < MAX_BATCHES_PER_STAGE; attempt++) {
            heartbeat(ticket);
            SeasonGroupSnapshotService.Progress progress =
                    groupSnapshotService.writeNextBatch(seasonId);

            // 배치가 Commit된 후에만 다음 작업을 수행합니다.
            if (progress.remaining() == 0) {
                return progress.total();
            }
            if (progress.inserted() <= 0) {
                throw new IllegalStateException("그룹 Snapshot 처리에 진전이 없습니다.");
            }
        }
        throw new IllegalStateException("그룹 Snapshot 배치 반복 상한 초과");
    }

    /**
     * 개인 랭킹 Snapshot을 100건씩 Commit하고 누락분을 이어서 처리합니다.
     * 이동·탈퇴자의 참가 기록도 기존 개인 Snapshot Service에서 보존합니다.
     * Lease 갱신이 실패하면 다음 배치로 넘어가지 않고 작업을 중단합니다.
     */
    private int drainMemberSnapshots(Long seasonId, SchedulerClaimTicket ticket) {
        for (int attempt = 0; attempt < MAX_BATCHES_PER_STAGE; attempt++) {
            heartbeat(ticket);
            SeasonMemberSnapshotService.Progress progress =
                    memberSnapshotService.writeNextBatch(seasonId);

            // 종료 검사는 양쪽 Snapshot 저장이 완료된 후에만 실행합니다.
            if (progress.remaining() == 0) {
                return progress.total();
            }
            if (progress.inserted() <= 0) {
                throw new IllegalStateException("개인 Snapshot 처리에 진전이 없습니다.");
            }
        }
        throw new IllegalStateException("개인 Snapshot 배치 반복 상한 초과");
    }

    /**
     * 현재 실행권으로 Lease를 갱신하고 오래된 실행자의 작업을 중단합니다.
     * SchedulerExecutionLifecycleService의 짧은 독립 트랜잭션을 사용합니다.
     * 이 작업은 배치의 DB 잠금을 잡은 채 호출해서는 안 됩니다.
     */
    private void heartbeat(SchedulerClaimTicket ticket) {
        lifecycleService.renewLease(ticket, LEASE_DURATION);
    }

    /**
     * 이전 실행에서 시즌 ENDED만 확정하고 중단했는지 조회합니다.
     * Scheduler 작업 성공 기록이 누락된 경우에 멱등 복구를 수행합니다.
     * 아직 CLOSING인 시즌은 다음 단계의 정상 종료 절차를 그대로 수행합니다.
     */
    private boolean isEnded(Long seasonId) {
        return "ENDED".equals(seasonRepository.findById(seasonId)
                .orElseThrow(() -> new IllegalStateException(
                        "시즌을 찾을 수 없습니다. seasonId=" + seasonId
                )).getStatus());
    }

    /**
     * MySQL JSON 컬럼에 저장할 유효한 결과 문서를 생성합니다.
     * 문자열 입력을 쓰지 않고 숫자와 boolean만 직렬화해 이스케이프 문제를 피합니다.
     * 이 결과는 모든 업무 Commit 이후에만 SchedulerJobRunner로 전달합니다.
     */
    private String summary(
            Long seasonId,
            int groupTotal,
            int memberTotal,
            boolean alreadyEnded
    ) {
        return "{\"seasonId\":%d,\"groupSnapshots\":%d,\"memberSnapshots\":%d,\"alreadyEnded\":%s}"
                .formatted(seasonId, groupTotal, memberTotal, alreadyEnded);
    }
}
