import test from 'node:test';
import assert from 'node:assert/strict';
import {
    mockCreatureChatMessages,
    mockCreatureHomeData,
} from '../src/mocks/mock-creature-page.js';

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

test('완전체 크리처 시연용 메시지를 온라인 아바타 20명이 한 번씩 가진다', () => {
    const members = mockCreatureHomeData.onlineMembers;
    const memberIds = new Set(members.map((member) => member.userId));
    const senderIds = mockCreatureChatMessages.map((message) => message.senderUserId);

    assert.equal(mockCreatureHomeData.creature.growthStage, 'COMPLETE');
    assert.equal(mockCreatureHomeData.groupId, 501);
    assert.equal(mockCreatureChatMessages.length, 20);
    assert.equal(new Set(senderIds).size, 20);
    assert.ok(senderIds.every((senderId) => memberIds.has(senderId)));
    assert.ok(mockCreatureChatMessages.every((message) => (
        message.content.trim().length > 0
        && [...message.content].length <= 80
        && message.groupId === mockCreatureHomeData.groupId
    )));
});
