package com.team.sharedcreature.season.application;

import com.team.sharedcreature._core.time.SeasonCalendar;
import com.team.sharedcreature.season.domain.Season;
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
 * 서버 재기동 시 현재 4주 구간의 시즌 생성 여부를 정규화합니다.
 * 최초 시즌 행의 배타 잠금으로 다중 서버의 신규 시즌 생성을 직렬화합니다.
 * 지나간 구간의 시즌은 만들지 않고 현재 구간 하나만 SCHEDULED로 저장합니다.
 */
@Service
public class SeasonCreationService {

    private final SeasonRepository seasonRepository;
    private final SeasonCalendar seasonCalendar;
    private final Clock clock;

    /**
     * 시즌 Repository, 4주 달력, 서버 Clock을 주입받습니다.
     * 시즌 기준 시각은 DB에 이미 존재하는 최초 시즌에서 읽어옵니다.
     * 서버 시각으로 최초 시즌을 임의로 만드는 동작은 수행하지 않습니다.
     */
    public SeasonCreationService(
            SeasonRepository seasonRepository,
            SeasonCalendar seasonCalendar,
            Clock clock
    ) {
        this.seasonRepository = seasonRepository;
        this.seasonCalendar = seasonCalendar;
        this.clock = clock;
    }

    /**
     * 이미 종료된 시즌 다음에 현재 4주 구간의 SCHEDULED 시즌을 생성합니다.
     * 최초 시즌 행을 먼저 배타 잠금하고 그 이후 최신 시즌과 현재 시각을 조회합니다.
     * 중간에 건너뛴 달력 구간은 만들지 않으며 완료되지 않은 과거 시즌은 보존합니다.
     *
     * @return 신규 생성, 기존 시즌, 이전 종료 대기 또는 첫 시작 전 상태
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreationResult createCurrentScheduledIfMissing() {
        // 최초 시즌의 잠금 조회가 트랜잭션 내 첫 DB 조회여야 합니다.
        List<Season> firstRows = seasonRepository.lockFirstSeasonForCreation(PageRequest.of(0, 1));
        if (firstRows.isEmpty()) {
            throw new IllegalStateException("최초 시즌 시드가 없습니다. 자동으로 생성하지 않습니다.");
        }
        Season first = firstRows.getFirst();

        // 이 시점까지 다른 생성자가 첫 시즌 행을 잠그고 있었다면 기다렸습니다.
        Season latest = seasonRepository.findFirstByOrderByStartAtDesc()
                .orElseThrow(() -> new IllegalStateException("잠금된 최초 시즌이 사라졌습니다."));
        Instant now = Instant.now(clock);
        SeasonCalendar.SeasonWindow currentWindow = seasonCalendar
                .currentWindow(first.getStartAt(), now)
                .orElse(null);

        if (currentWindow == null) {
            return new CreationResult(Outcome.NOT_STARTED, first.getId(), null);
        }

        // DB 시즌 번호가 아닌 28일 달력 구간을 기준으로 누락 구간을 판단합니다.
        SeasonCalendar.SeasonWindow latestWindow = seasonCalendar
                .currentWindow(first.getStartAt(), latest.getStartAt())
                .orElseThrow(() -> new IllegalStateException("최신 시즌의 시작이 최초 시즌보다 빠릅니다."));
        if (!Objects.equals(latestWindow.startAt(), latest.getStartAt())
                || !Objects.equals(latestWindow.endAt(), latest.getEndAt())) {
            throw new IllegalStateException("저장된 최신 시즌의 4주 달력 경계가 잘못됐습니다.");
        }

        if (latest.getStartAt().equals(currentWindow.startAt())) {
            return new CreationResult(Outcome.ALREADY_EXISTS, latest.getId(), currentWindow.startAt());
        }
        if (latest.getStartAt().isAfter(currentWindow.startAt())) {
            throw new IllegalStateException("현재 구간보다 미래에 시작하는 시즌이 이미 존재합니다.");
        }

        // 이전 시즌이 ENDED로 Commit되기 전에는 새로운 시즌을 만들지 않습니다.
        if (!"ENDED".equals(latest.getStatus())) {
            return new CreationResult(Outcome.WAITING_FOR_CLOSURE, latest.getId(), currentWindow.startAt());
        }

        // 3개 이상 구간을 건너뛰어도 DB seasonNumber는 마지막 번호 + 1입니다.
        int nextSeasonNumber = Math.addExact(latest.getSeasonNumber(), 1);
        Season created = seasonRepository.saveAndFlush(Season.schedule(
                nextSeasonNumber,
                currentWindow.startAt(),
                currentWindow.endAt(),
                now
        ));

        // REQUIRES_NEW 종료 시 Commit, 다음 단계에서 그룹별 초기화가 시작됩니다.
        return new CreationResult(Outcome.CREATED, created.getId(), currentWindow.startAt());
    }

    /**
     * SCHEDULED 시즌 생성의 결정 결과를 나타냅니다.
     * WAITING_FOR_CLOSURE는 실패가 아니라 이전 시즌 종료가 남은 상태입니다.
     * ALREADY_EXISTS에는 ACTIVE 또는 SCHEDULED 같은 현존 시즌도 포함됩니다.
     */
    public enum Outcome {
        CREATED,
        ALREADY_EXISTS,
        WAITING_FOR_CLOSURE,
        NOT_STARTED
    }

    /**
     * 시즌 생성의 결과와 관련 시즌 ID·달력 시작 시각을 보관합니다.
     * 반환된 결과는 실제 트랜잭션 Commit 이후 호출자에게 전달됩니다.
     * 그룹 초기화의 대상 ID는 CREATED 또는 ALREADY_EXISTS에서만 사용합니다.
     */
    public record CreationResult(Outcome outcome, Long seasonId, Instant windowStartAt) {
    }
}
