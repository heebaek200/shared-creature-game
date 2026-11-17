import test from 'node:test';
import assert from 'node:assert/strict';
import { mockCreatureHomeData } from '../src/mocks/mock-creature-page.js';

test('20명의 Mock 아바타가 서로 다른 복장 표현을 가진다', () => {
    const members = mockCreatureHomeData.onlineMembers;
    const appearances = members.map((member) => {
        const equippedSlots = Object.entries(member.avatar.equipments)
            .filter(([, equipment]) => equipment !== null)
            .map(([slot]) => slot)
            .sort()
            .join(',');

        return `${equippedSlots}:${member.avatar.mockItemHue}`;
    });

    assert.equal(members.length, 20);
    assert.equal(new Set(appearances).size, 20);
    assert.ok(members.every((member) => (
        Object.values(member.avatar.equipments).some(Boolean)
    )));
});
