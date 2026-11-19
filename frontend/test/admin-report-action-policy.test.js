import assert from 'node:assert/strict';
import test from 'node:test';
import {reportActionAvailability} from '../src/features/admin/report-action-policy.js';

test('접수된 신고는 활성 대상에게 기각과 강퇴만 제공한다', () => {
    assert.deepEqual(reportActionAvailability({
        status: 'RECEIVED', reportedUserActive: true,
    }), {dismiss: true, remove: true, resolve: false});
});

test('접수된 신고는 비활성 대상에게 기각과 처리 완료만 제공한다', () => {
    assert.deepEqual(reportActionAvailability({
        status: 'RECEIVED', reportedUserActive: false,
    }), {dismiss: true, remove: false, resolve: true});
});

test('이미 처리된 신고에는 처리 동작을 제공하지 않는다', () => {
    assert.deepEqual(reportActionAvailability({
        status: 'RESOLVED', reportedUserActive: false,
    }), {dismiss: false, remove: false, resolve: false});
});
