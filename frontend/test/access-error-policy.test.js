import test from 'node:test';
import assert from 'node:assert/strict';
import { creatureAccessErrorRoute } from '../src/features/creature/access-error-policy.js';

test('Snapshot 인증 오류는 로그인 화면으로 이동한다', () => {
    assert.equal(creatureAccessErrorRoute({ code: 'AUTH_REQUIRED' }), '/login');
    assert.equal(creatureAccessErrorRoute({ code: 'SESSION_EXPIRED' }), '/login');
});

test('Snapshot 그룹 권한 오류는 권한 오류 화면으로 이동한다', () => {
    assert.equal(creatureAccessErrorRoute({ code: 'ROLE_FORBIDDEN' }), '/403');
});

test('일시적인 오류는 기존 Snapshot 복구 흐름을 유지한다', () => {
    assert.equal(creatureAccessErrorRoute({ code: 'CONNECTION_ERROR' }), null);
});
