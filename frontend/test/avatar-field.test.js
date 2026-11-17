import test from 'node:test';
import assert from 'node:assert/strict';
import { calculateIdleWaitScale } from '../src/features/creature/avatar-movement-policy.js';

test('접속자가 적을수록 아바타 이동 휴식 시간 배율이 작아진다', () => {
    const solo = calculateIdleWaitScale(1);
    const middle = calculateIdleWaitScale(10);
    const full = calculateIdleWaitScale(20);

    assert.equal(solo, 0.2);
    assert.ok(solo < middle);
    assert.ok(middle < full);
    assert.equal(full, 1);
    assert.equal(calculateIdleWaitScale(100), 1);
});
