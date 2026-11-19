import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canManageCreatureSelection,
  invitationIssuanceKey,
  isSameInvitationIssuance,
  reconcileCreatureSelectionDraft,
} from '../src/features/group-admin/state-policy.js';

const invitation = {
  groupId: 501,
  issuedAt: '2026-11-19T00:00:00Z',
  issuedByUserId: 10,
};

test('동일한 초대 발급 정보 재조회에서는 보관 중인 URL을 유지한다', () => {
  const retainedKey = invitationIssuanceKey(invitation);

  assert.equal(isSameInvitationIssuance(retainedKey, {...invitation}), true);
});

test('다른 관리자가 초대 링크를 재발급하면 보관 중인 URL을 무효화한다', () => {
  const retainedKey = invitationIssuanceKey(invitation);

  assert.equal(
    isSameInvitationIssuance(retainedKey, {
      ...invitation,
      issuedAt: '2026-11-19T00:01:00Z',
    }),
    false,
  );
  assert.equal(isSameInvitationIssuance(retainedKey, {groupId: 501}), false);
});

test('서버 상태가 그대로면 저장하지 않은 크리처 선택을 보존한다', () => {
  assert.deepEqual(
    reconcileCreatureSelectionDraft({
      baselineId: 1,
      draftId: 2,
      latestId: 1,
      activeSpeciesIds: [1, 2, 3],
      selectionOpen: true,
    }),
    {selectedId: '2', outcome: 'DRAFT_PRESERVED'},
  );
});

test('다른 관리자의 선택과 사용자 초안이 다르면 최신 상태를 우선한다', () => {
  assert.deepEqual(
    reconcileCreatureSelectionDraft({
      baselineId: 1,
      draftId: 2,
      latestId: 3,
      activeSpeciesIds: [1, 2, 3],
      selectionOpen: true,
    }),
    {selectedId: '3', outcome: 'CONFLICT'},
  );
});

test('다른 관리자가 같은 종을 저장했다면 충돌 없이 동기화한다', () => {
  assert.deepEqual(
    reconcileCreatureSelectionDraft({
      baselineId: 1,
      draftId: 2,
      latestId: 2,
      activeSpeciesIds: [1, 2, 3],
      selectionOpen: true,
    }),
    {selectedId: '2', outcome: 'ALREADY_APPLIED'},
  );
});

test('선택 마감 후에는 저장하지 않은 초안을 보존하지 않는다', () => {
  assert.deepEqual(
    reconcileCreatureSelectionDraft({
      baselineId: 1,
      draftId: 2,
      latestId: 1,
      activeSpeciesIds: [1, 2, 3],
      selectionOpen: false,
    }),
    {selectedId: '1', outcome: 'SYNCED'},
  );
});

test('크리처 선택은 최신 주·부 관리자 권한에서만 허용한다', () => {
  assert.equal(canManageCreatureSelection('PRIMARY'), true);
  assert.equal(canManageCreatureSelection('DEPUTY'), true);
  assert.equal(canManageCreatureSelection('MEMBER'), false);
  assert.equal(canManageCreatureSelection(undefined), false);
});
