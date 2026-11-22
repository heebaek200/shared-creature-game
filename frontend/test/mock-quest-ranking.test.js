import test from 'node:test';
import assert from 'node:assert/strict';
import {
  completeMockQuestAssignment,
  createMockCheckIn,
  getMockGroupRanking,
  getMockMemberRanking,
  listMockCurrentQuests,
  resetMockQuestRanking,
  submitMockQuestPhoto,
} from '../src/mocks/mock-quest-ranking.js';

test.beforeEach(() => resetMockQuestRanking());

test('촬영용 퀘스트는 일일 3개와 주간 7개를 다양한 인증 방식으로 제공한다', () => {
  const quests = listMockCurrentQuests();
  assert.equal(quests.filter((quest) => quest.periodType === 'DAILY').length, 3);
  assert.equal(quests.filter((quest) => quest.periodType === 'WEEKLY').length, 7);
  assert.deepEqual(
      new Set(quests.map((quest) => quest.verificationType)),
      new Set(['SELF_CHECK', 'SERVER_EVENT', 'PHOTO_EXIF', 'AGGREGATE']),
  );
  assert.ok(quests.every((quest) => Date.parse(quest.periodEndAt) > Date.now()));
});

test('직접 완료와 체크인은 퀘스트 상태와 개인·그룹 점수를 함께 갱신한다', () => {
  const review = completeMockQuestAssignment(2007);
  const checkIn = createMockCheckIn();
  const quests = listMockCurrentQuests();
  const member = getMockMemberRanking({ page: 0, size: 20 });
  const group = getMockGroupRanking({ page: 0, size: 20 });

  assert.equal(review.contributionAwarded, 20);
  assert.equal(checkIn.isMorning, true);
  assert.equal(quests.find((quest) => quest.assignmentId === 2007)?.completed, true);
  assert.equal(quests.find((quest) => quest.assignmentId === 1002)?.completed, true);
  assert.equal(member.meta.currentSubject.score, 2480);
  assert.equal(group.meta.currentSubject.score, 18680);
});

test('사진 인증은 남은 목표 진행도를 올리고 목표 달성 시 완료 처리한다', () => {
  const submission = submitMockQuestPhoto(2003);
  const quest = listMockCurrentQuests().find((item) => item.assignmentId === 2003);

  assert.ok(submission.completionId);
  assert.equal(quest?.progress.current, 2);
  assert.equal(quest?.completed, true);
});

test('개인·그룹 랭킹은 현재 사용자를 강조할 요약과 페이지 정보를 제공한다', () => {
  const members = getMockMemberRanking({ page: 0, size: 5 });
  const groups = getMockGroupRanking({ page: 0, size: 5 });

  assert.equal(members.data.length, 5);
  assert.equal(members.meta.totalElements, 20);
  assert.deepEqual(members.meta.currentSubject, { subjectId: 101, rank: 3, score: 2450 });
  assert.equal(groups.data[1].groupName, '새싹 연구소');
  assert.deepEqual(groups.meta.currentSubject, { subjectId: 501, rank: 2, score: 18650 });
});
