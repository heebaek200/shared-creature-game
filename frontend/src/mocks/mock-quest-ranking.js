// @ts-check

const USER_ID = 101;
const GROUP_ID = 501;
const CREATURE_TOTAL_EXPERIENCE = 200000;

const memberRanking = [
  ['푸른잎', 2980], ['별빛', 2710], ['새싹지기', 2450], ['햇살', 2320],
  ['구름', 2180], ['라일락', 2050], ['단풍', 1910], ['민들레', 1780],
  ['소나기', 1660], ['달맞이', 1540], ['초롱', 1430], ['나무늘보', 1320],
  ['여울', 1210], ['다람', 1100], ['모래', 1010], ['산들', 930],
  ['오후', 850], ['노을', 770], ['새벽', 700], ['아침', 640],
].map(([nickname, contributionScore], index) => ({
  rank: index + 1,
  userId: index === 2 ? USER_ID : 201 + index,
  nickname,
  contributionScore,
  scoreReachedAt: new Date(Date.UTC(2026, 10, 22, 2, index * 3)).toISOString(),
  participantStatus: index > 17 ? 'LEFT' : 'ACTIVE',
}));

const groupRanking = [
  ['매일 성장하기', 19840], ['새싹 연구소', 18650], ['초록빛 정원', 17920],
  ['함께하는 도전', 16840], ['오늘도 한 걸음', 15770], ['별빛 탐험대', 14820],
  ['꾸준한 사람들', 13940], ['작은 숲', 12760], ['모닝 클럽', 11690],
  ['느긋한 정원사', 10520], ['파란 행성', 9380], ['달리는 씨앗', 8240],
].map(([groupName, score], index) => ({
  rank: index + 1,
  groupId: index === 1 ? GROUP_ID : 601 + index,
  groupName,
  score,
  scoreReachedAt: new Date(Date.UTC(2026, 10, 22, 3, index * 4)).toISOString(),
}));

let completionSequence = 9000;
let photoSequence = 7000;
let currentMemberScore = 2450;
let currentGroupScore = 18650;
let questAssignments = createQuestAssignments();

/** 현재 KST 날짜를 기준으로 만료되지 않은 일일·주간 기간을 만듭니다. */
function createPeriods() {
  const kstOffset = 9 * 60 * 60 * 1000;
  const shiftedNow = new Date(Date.now() + kstOffset);
  const dailyStartShifted = new Date(shiftedNow);
  dailyStartShifted.setUTCHours(0, 0, 0, 0);
  const dailyStart = dailyStartShifted.getTime() - kstOffset;
  const mondayOffset = (dailyStartShifted.getUTCDay() + 6) % 7;
  const weeklyStart = dailyStart - mondayOffset * 24 * 60 * 60 * 1000;

  return {
    dailyStart: new Date(dailyStart).toISOString(),
    dailyEnd: new Date(dailyStart + 24 * 60 * 60 * 1000).toISOString(),
    weeklyStart: new Date(weeklyStart).toISOString(),
    weeklyEnd: new Date(weeklyStart + 7 * 24 * 60 * 60 * 1000).toISOString(),
  };
}

/** 촬영 시점에도 유효하도록 현재 날짜 기준의 퀘스트 배정 목록을 생성합니다. */
function createQuestAssignments() {
  const periods = createPeriods();
  const daily = { periodId: 1101, periodType: 'DAILY', periodStartAt: periods.dailyStart, periodEndAt: periods.dailyEnd };
  const weekly = { periodId: 1201, periodType: 'WEEKLY', periodStartAt: periods.weeklyStart, periodEndAt: periods.weeklyEnd };

  return [
    quest(1001, daily, 1, 'DAILY_TIDY', '주변 5분 정리', '책상이나 생활 공간을 5분 동안 정리해요.', 'EASY', 'SELF_CHECK', 80, 8, 1, 1, true),
    quest(1002, daily, 2, 'DAILY_MORNING_CHECK_IN', '아침 기상 체크인', '오전 10시 전에 접속해 하루를 시작해요.', 'EASY', 'SERVER_EVENT', 100, 10, 0, 1),
    quest(1003, daily, 3, 'DAILY_WALK_PHOTO', '오늘의 산책 풍경', '산책 중 발견한 풍경을 사진으로 남겨요.', 'NORMAL', 'PHOTO_EXIF', 150, 15, 0, 1, false, 4),
    quest(2001, weekly, 1, 'WEEKLY_DAILY_FIVE', '일일 퀘스트 5회 완료', '이번 주 일일 퀘스트를 5회 완료해요.', 'NORMAL', 'AGGREGATE', 250, 25, 4, 5),
    quest(2002, weekly, 2, 'WEEKLY_MORNING_THREE', '아침 체크인 3일 달성', '서로 다른 3일에 아침 체크인을 기록해요.', 'NORMAL', 'AGGREGATE', 300, 30, 2, 3),
    quest(2003, weekly, 3, 'WEEKLY_OUTDOOR_PHOTO', '야외 사진 2일 기록', '서로 다른 2일에 야외 활동 사진을 남겨요.', 'NORMAL', 'PHOTO_EXIF', 350, 35, 1, 2, false, 4),
    quest(2004, weekly, 4, 'WEEKLY_FOCUS_THREE', '집중 활동 3회 달성', '독서나 공부 같은 집중 활동을 3회 완료해요.', 'HARD', 'AGGREGATE', 400, 40, 2, 3),
    quest(2005, weekly, 5, 'WEEKLY_EXERCISE_THREE', '운동 퀘스트 3회 완료', '이번 주 운동 퀘스트를 3회 완료해요.', 'NORMAL', 'AGGREGATE', 300, 30, 3, 3, true),
    quest(2006, weekly, 6, 'WEEKLY_CONTRIBUTION_100', '그룹 기여도 100점 달성', '퀘스트를 완료해 그룹 기여도 100점을 모아요.', 'HARD', 'AGGREGATE', 500, 50, 82, 100),
    quest(2007, weekly, 7, 'WEEKLY_REVIEW', '이번 주 돌아보기', '한 주의 성장을 돌아보고 다음 목표를 정해요.', 'EASY', 'SELF_CHECK', 200, 20, 0, 1),
  ];
}

/** 퀘스트 응답 계약에 맞춘 배정 한 건을 생성합니다. */
function quest(assignmentId, period, slotNumber, templateCode, title, description, difficulty,
    verificationType, creatureExperience, contributionScore, current, target,
    completed = false, photoAttemptsRemaining = null) {
  return {
    assignmentId,
    ...period,
    slotNumber,
    templateCode,
    title,
    description,
    difficulty,
    verificationType,
    creatureExperience,
    contributionScore,
    completed,
    completedAt: completed ? new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString() : null,
    completionId: completed ? ++completionSequence : null,
    progress: { current, target },
    photoAttemptsRemaining,
  };
}

/** 현재 Mock 퀘스트 목록의 독립 복사본을 반환합니다. */
export function listMockCurrentQuests() {
  return structuredClone(questAssignments);
}

/** SELF_CHECK 퀘스트를 완료하고 랭킹 점수를 함께 반영합니다. */
export function completeMockQuestAssignment(assignmentId) {
  const assignment = findAssignment(assignmentId);
  if (assignment.verificationType !== 'SELF_CHECK') {
    throw new Error('SELF_CHECK 퀘스트만 직접 완료할 수 있습니다.');
  }
  return completeAssignment(assignment);
}

/** 아침 체크인을 기록하고 연결된 SERVER_EVENT 퀘스트를 완료합니다. */
export function createMockCheckIn() {
  const assignment = questAssignments.find((item) => item.templateCode === 'DAILY_MORNING_CHECK_IN');
  const completion = assignment ? completeAssignment(assignment) : null;
  return {
    checkInId: 5001,
    checkedInAt: new Date().toISOString(),
    isMorning: true,
    questCompletion: completion,
  };
}

/** 사진 검증 성공을 재현하고 해당 퀘스트의 진행도를 한 단계 올립니다. */
export function submitMockQuestPhoto(assignmentId) {
  const assignment = findAssignment(assignmentId);
  if (assignment.verificationType !== 'PHOTO_EXIF') {
    throw new Error('사진 인증 퀘스트가 아닙니다.');
  }

  assignment.progress.current = Math.min(assignment.progress.target, assignment.progress.current + 1);
  const completion = assignment.progress.current === assignment.progress.target
    ? completeAssignment(assignment)
    : null;

  return {
    photoSubmissionId: ++photoSequence,
    assignmentId: assignment.assignmentId,
    verificationStatus: 'VALID',
    failureReason: null,
    completionId: completion?.completionId ?? null,
  };
}

/** 현재 시즌의 개인 랭킹을 페이지 단위로 반환합니다. */
export function getMockMemberRanking({ page = 0, size = 20 } = {}) {
  const rows = memberRanking.map((row) => row.userId === USER_ID
    ? { ...row, contributionScore: currentMemberScore }
    : row);
  return rankingPage(rows, page, size, { subjectId: USER_ID, rank: 3, score: currentMemberScore });
}

/** 현재 시즌의 그룹 랭킹을 페이지 단위로 반환합니다. */
export function getMockGroupRanking({ page = 0, size = 20 } = {}) {
  const rows = groupRanking.map((row) => row.groupId === GROUP_ID
    ? { ...row, score: currentGroupScore }
    : row);
  return rankingPage(rows, page, size, { subjectId: GROUP_ID, rank: 2, score: currentGroupScore });
}

/** 테스트와 반복 촬영에서 사용할 수 있도록 변경된 Mock 상태를 초기화합니다. */
export function resetMockQuestRanking() {
  completionSequence = 9000;
  photoSequence = 7000;
  currentMemberScore = 2450;
  currentGroupScore = 18650;
  questAssignments = createQuestAssignments();
}

function findAssignment(assignmentId) {
  const assignment = questAssignments.find((item) => item.assignmentId === Number(assignmentId));
  if (!assignment) throw new Error('퀘스트 배정을 찾을 수 없습니다.');
  return assignment;
}

function completeAssignment(assignment) {
  if (!assignment.completed) {
    assignment.completed = true;
    assignment.completedAt = new Date().toISOString();
    assignment.completionId = ++completionSequence;
    assignment.progress.current = assignment.progress.target;
    currentMemberScore += assignment.contributionScore;
    currentGroupScore += assignment.contributionScore;
  }

  return {
    completionId: assignment.completionId,
    assignmentId: assignment.assignmentId,
    userId: USER_ID,
    completedAt: assignment.completedAt,
    creatureExperienceAwarded: assignment.creatureExperience,
    contributionAwarded: assignment.contributionScore,
    creatureTotalExperience: CREATURE_TOTAL_EXPERIENCE,
    creatureGrowthStage: 'COMPLETE',
    groupSeasonScore: currentGroupScore,
  };
}

function rankingPage(rows, page, size, currentSubject) {
  const safePage = Math.max(0, Number(page));
  const safeSize = Math.max(1, Number(size));
  const start = safePage * safeSize;
  return {
    data: structuredClone(rows.slice(start, start + safeSize)),
    meta: {
      page: safePage,
      size: safeSize,
      totalElements: rows.length,
      totalPages: Math.ceil(rows.length / safeSize),
      currentSubject: structuredClone(currentSubject),
    },
  };
}
