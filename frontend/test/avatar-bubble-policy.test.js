import assert from 'node:assert/strict';
import test from 'node:test';
import {
    calculateBubbleHorizontalShift,
    shouldRunBubbleTimer,
} from '../src/features/creature/avatar-bubble-policy.js';

test('말풍선 타이머는 hover와 focus가 모두 없을 때만 진행한다', () => {
    assert.equal(shouldRunBubbleTimer({hovered: false, focused: false}), true);
    assert.equal(shouldRunBubbleTimer({hovered: true, focused: false}), false);
    assert.equal(shouldRunBubbleTimer({hovered: false, focused: true}), false);
    assert.equal(shouldRunBubbleTimer({hovered: true, focused: true}), false);
});

test('hover와 focus 중 하나만 해제되어도 남은 상호작용이 타이머를 멈춘다', () => {
    assert.equal(shouldRunBubbleTimer({hovered: false, focused: true}), false);
    assert.equal(shouldRunBubbleTimer({hovered: true, focused: false}), false);
});

test('필드 좌우를 벗어난 말풍선만 안쪽으로 이동한다', () => {
    assert.equal(calculateBubbleHorizontalShift({
        fieldLeft: 10, fieldRight: 310, bubbleLeft: -15, bubbleRight: 135, currentShift: 0,
    }), 25);
    assert.equal(calculateBubbleHorizontalShift({
        fieldLeft: 10, fieldRight: 310, bubbleLeft: 220, bubbleRight: 340, currentShift: 5,
    }), -25);
    assert.equal(calculateBubbleHorizontalShift({
        fieldLeft: 10, fieldRight: 310, bubbleLeft: 80, bubbleRight: 200, currentShift: 7,
    }), 7);
});
