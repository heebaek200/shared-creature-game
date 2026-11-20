import assert from 'node:assert/strict';
import test from 'node:test';
import { questErrorMessage } from '../src/features/quest/error-policy.js';

test('퀘스트 오류는 서버의 안전한 업무 메시지를 코드 기본 문구보다 우선한다', () => {
  assert.equal(questErrorMessage({
    code: 'QUEST_PERIOD_ENDED',
    message: '그룹 시즌이 종료됐습니다.',
  }), '그룹 시즌이 종료됐습니다.');
});

test('서버 메시지가 없으면 세부 오류 코드별 안내를 사용한다', () => {
  assert.equal(
    questErrorMessage({ code: 'SEASON_NOT_ACTIVE' }),
    '현재 진행 중인 시즌이 아닙니다.',
  );
  assert.equal(
    questErrorMessage({ code: 'GROUP_SEASON_ENDED' }),
    '그룹 시즌이 종료되었습니다.',
  );
});
