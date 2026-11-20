import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiError } from '../src/api/client.js';
import { questErrorMessage } from '../src/features/quest/error-policy.js';

test('퀘스트 오류는 서버의 안전한 업무 메시지를 코드 기본 문구보다 우선한다', () => {
  const error = new ApiError(409, {
    code: 'QUEST_PERIOD_ENDED',
    message: '그룹 시즌이 종료됐습니다.',
  });

  assert.equal(questErrorMessage(error), '그룹 시즌이 종료됐습니다.');
});

test('서버 메시지가 없으면 세부 오류 코드별 안내를 사용한다', () => {
  assert.equal(
    questErrorMessage(new ApiError(409, { code: 'SEASON_NOT_ACTIVE' })),
    '현재 진행 중인 시즌이 아닙니다.',
  );
  assert.equal(
    questErrorMessage(new ApiError(409, { code: 'GROUP_SEASON_ENDED' })),
    '그룹 시즌이 종료되었습니다.',
  );
});

test('서버 메시지와 코드 문구가 모두 없으면 공통 오류 문구를 사용한다', () => {
  const error = new ApiError(500, { code: 'UNKNOWN_ERROR' });

  assert.equal(error.message, '요청을 처리하지 못했습니다.');
  assert.equal(questErrorMessage(error), '요청을 처리하지 못했습니다.');
});

test('클라이언트 입력 오류는 자체 안내 문구를 유지한다', () => {
  assert.equal(questErrorMessage({
    code: 'FILE_TOO_LARGE',
    message: '선택한 파일을 다시 확인해 주세요.',
  }), '선택한 파일을 다시 확인해 주세요.');
});
