# 퀘스트 DB 동시성 수동 테스트

MySQL 8에서 두 개의 SQL 콘솔을 열고, 안내된 시점에 Session A와 Session B의 SQL을 각각 실행합니다. 반드시 테스트 DB에서만 실행하세요. 아래 ID는 실제 연결된 테스트 데이터 ID로 바꿔야 합니다.

## 1. 같은 사용자·배정 동시 완료

사전 확인에서 결과가 없어야 합니다. `user_id`, `quest_assignment_id`, `membership_id`, `group_season_id`는 서로 연결된 유효한 테스트 데이터여야 합니다.

```sql
SELECT id
  FROM quest_completions
 WHERE user_id = 1001
   AND quest_assignment_id = 2001;
```

### Session A

```sql
SET @user_id = 1001;
SET @assignment_id = 2001;
SET @group_season_id = 3001;
SET @membership_id = 4001;

START TRANSACTION;
INSERT INTO quest_completions (
    user_id, quest_assignment_id, group_season_id, membership_id,
    completed_at, created_at
) VALUES (
    @user_id, @assignment_id, @group_season_id, @membership_id,
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);

SELECT SLEEP(10); -- 잠드는 동안 Session B를 실행합니다.
COMMIT;
```

### Session B

Session A가 `SLEEP` 중일 때 실행하고, 위와 같은 ID를 사용합니다.

```sql
SET @user_id = 1001;
SET @assignment_id = 2001;
SET @group_season_id = 3001;
SET @membership_id = 4001;

START TRANSACTION;
INSERT INTO quest_completions (
    user_id, quest_assignment_id, group_season_id, membership_id,
    completed_at, created_at
) VALUES (
    @user_id, @assignment_id, @group_season_id, @membership_id,
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);
-- 예상: A가 Commit할 때까지 대기한 뒤 UNIQUE 위반 오류 1062.
ROLLBACK;
```

완료 행은 하나여야 합니다. 이 테스트 행을 만든 경우에만 정리하세요. 연결된 원장이나 다른 데이터가 생겼다면 단순 삭제하지 마세요.

```sql
SELECT COUNT(*) AS completion_count
  FROM quest_completions
 WHERE user_id = 1001
   AND quest_assignment_id = 2001;

DELETE FROM quest_completions
 WHERE user_id = 1001
   AND quest_assignment_id = 2001;
```

## 2. 같은 사용자의 같은 KST 날짜 체크인 동시 요청

먼저 해당 사용자·날짜에 체크인 기록이 없는지 확인합니다. 기존 기록을 테스트 데이터로 사용하지 마세요.

### 테스트 이벤트 준비 (한 번 실행)

```sql
SET @user_id = 1001;
SET @membership_id = 4001;
SET @group_season_id = 3001;
SET @kst_date = DATE(CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'));

SELECT id
  FROM service_check_ins
 WHERE user_id = @user_id
   AND check_in_date = @kst_date;
-- 결과가 있으면 이 사용자/날짜 조합은 테스트에 사용하지 마세요.

START TRANSACTION;
INSERT INTO user_activity_events (
    user_id, event_type, source_type, source_id, membership_id, group_season_id,
    business_date, occurred_at, deduplication_key, metadata, created_at
) VALUES (
    @user_id, 'check_in', 'service_check_in', @user_id, @membership_id, @group_season_id,
    @kst_date, UTC_TIMESTAMP(6), CONCAT('manual-checkin-a:', UUID()), NULL, UTC_TIMESTAMP(6)
);
SET @event_a = LAST_INSERT_ID();

INSERT INTO user_activity_events (
    user_id, event_type, source_type, source_id, membership_id, group_season_id,
    business_date, occurred_at, deduplication_key, metadata, created_at
) VALUES (
    @user_id, 'check_in', 'service_check_in', @user_id, @membership_id, @group_season_id,
    @kst_date, UTC_TIMESTAMP(6), CONCAT('manual-checkin-b:', UUID()), NULL, UTC_TIMESTAMP(6)
);
SET @event_b = LAST_INSERT_ID();
COMMIT;

SELECT @event_a AS event_a_id, @event_b AS event_b_id, @kst_date AS kst_date;
```

두 이벤트 ID와 날짜를 복사해 다음 Session A/B SQL에 넣습니다.

### Session A

```sql
START TRANSACTION;
INSERT INTO service_check_ins (
    user_id, activity_event_id, check_in_date, checked_in_at, is_morning, created_at
) VALUES (
    1001, 5001, '2026-11-05', UTC_TIMESTAMP(6), FALSE, UTC_TIMESTAMP(6)
);
SELECT SLEEP(10); -- 잠드는 동안 Session B를 실행합니다.
COMMIT;
```

### Session B

Session A가 `SLEEP` 중일 때 실행합니다. 날짜는 같게, activity event ID는 B 값을 넣습니다.

```sql
START TRANSACTION;
INSERT INTO service_check_ins (
    user_id, activity_event_id, check_in_date, checked_in_at, is_morning, created_at
) VALUES (
    1001, 5002, '2026-11-05', UTC_TIMESTAMP(6), FALSE, UTC_TIMESTAMP(6)
);
-- 예상: A Commit을 기다린 뒤 UNIQUE 위반 오류 1062 (user_id, check_in_date).
ROLLBACK;
```

확인 후 두 테스트 이벤트 ID에 해당하는 행만 정리합니다.

```sql
SELECT COUNT(*) AS check_in_count
  FROM service_check_ins
 WHERE user_id = 1001
   AND check_in_date = '2026-11-05';

DELETE FROM service_check_ins WHERE activity_event_id IN (5001, 5002);
DELETE FROM user_activity_events WHERE id IN (5001, 5002);
```

## 3. 서로 다른 사용자의 같은 그룹 시즌 보상 잠금

Session A와 B는 서로 다른 사용자 ID를 사용하되 같은 `season_id`, `group_season_id`를 사용합니다. 두 멤버십·기여도 행이 해당 그룹 시즌에 속하는지도 확인하세요.

### Session A

```sql
START TRANSACTION;
SELECT id FROM seasons WHERE id = 10 AND status = 'ACTIVE' FOR SHARE;
SELECT id FROM users WHERE id = 1001 FOR UPDATE;
SELECT id FROM group_seasons WHERE id = 3001 FOR UPDATE;
SELECT id FROM season_member_contributions
 WHERE group_season_id = 3001 AND user_id = 1001 FOR UPDATE;
SELECT id FROM season_group_scores WHERE group_season_id = 3001 FOR UPDATE;
SELECT id FROM group_creatures WHERE group_season_id = 3001 FOR UPDATE;
SELECT SLEEP(10); -- 잠드는 동안 Session B를 실행합니다.
COMMIT;
```

### Session B

Session A가 잠든 동안 실행합니다. 예상대로 `group_seasons` 행 잠금에서 대기해야 합니다.

```sql
START TRANSACTION;
SELECT id FROM seasons WHERE id = 10 AND status = 'ACTIVE' FOR SHARE;
SELECT id FROM users WHERE id = 1002 FOR UPDATE;
SELECT id FROM group_seasons WHERE id = 3001 FOR UPDATE;
SELECT id FROM season_member_contributions
 WHERE group_season_id = 3001 AND user_id = 1002 FOR UPDATE;
SELECT id FROM season_group_scores WHERE group_season_id = 3001 FOR UPDATE;
SELECT id FROM group_creatures WHERE group_season_id = 3001 FOR UPDATE;
ROLLBACK;
```

이 케이스는 DB 잠금 대기와 순서만 확인합니다. 보상 계산이나 애플리케이션 서비스의 Commit/Rollback은 검증하지 않으므로, 실제 보상 정합성은 API를 병렬 호출하는 통합 테스트가 필요합니다.

## 4. 멤버십 이동 전후 체크인 집계 범위 확인

이 쿼리는 과거 체크인이 당시의 멤버십·그룹 시즌으로 분리되어 집계되는지 확인합니다.

```sql
SELECT e.membership_id,
       e.group_season_id,
       COUNT(*) AS check_in_count
  FROM service_check_ins c
  JOIN user_activity_events e ON e.id = c.activity_event_id
 WHERE c.user_id = 1001
   AND c.check_in_date >= '2026-11-02'
   AND c.check_in_date <  '2026-11-09'
 GROUP BY e.membership_id, e.group_season_id
 ORDER BY e.membership_id, e.group_season_id;
```

## 5. 시즌 종료와 보상 잠금 경합

Session A가 활성 시즌을 공유 잠금으로 읽고 보상 처리에 필요한 잠금을 잡습니다. Session B의 종료 상태 변경은 A가 끝날 때까지 기다려야 합니다. B는 테스트 후 `ROLLBACK`해 실제 시즌 상태를 보존합니다.

### Session A

```sql
START TRANSACTION;
SELECT id FROM seasons WHERE id = 10 AND status = 'ACTIVE' FOR SHARE;
SELECT id FROM users WHERE id = 1001 FOR UPDATE;
SELECT id FROM group_seasons WHERE id = 3001 FOR UPDATE;
SELECT SLEEP(10); -- 잠드는 동안 Session B를 실행합니다.
COMMIT;
```

### Session B

```sql
START TRANSACTION;
UPDATE seasons
   SET status = 'CLOSING'
 WHERE id = 10
   AND status = 'ACTIVE';
-- 예상: A가 Commit할 때까지 UPDATE가 대기합니다.
ROLLBACK;
```

이 SQL은 DB 제약과 행 잠금 동작을 재현합니다. API 오류 응답, 서비스의 최종 재검증, 이벤트 발행까지 확인하려면 별도로 두 클라이언트에서 API 요청을 병렬 실행해야 합니다.
