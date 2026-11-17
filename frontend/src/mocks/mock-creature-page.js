// @ts-check

const equipmentCatalog = {
    HEAD: { shopItemId: 1, name: '갈색 단발', assetKey: 'brown-lob' },
    BODY: { shopItemId: 2, name: '파란 셔츠', assetKey: 'blue-shirt' },
    ACCESSORY: { shopItemId: 3, name: '리본 헤어밴드', assetKey: 'ribbon-hairband' },
    FACE: { shopItemId: 4, name: '노란 미소', assetKey: 'yellow-smile' },
};

const equipmentSlots = ['HEAD', 'FACE', 'BODY', 'ACCESSORY'];

const mockNicknames = [
    '본인', '푸른잎', '별빛', '햇살', '산들',
    '이슬', '구름', '단풍', '새봄', '초롱',
    '라일락', '민들레', '여울', '노을', '솔바람',
    '달빛', '은하', '보름', '수풀', '아침',
];

/**
 * 비트 조합으로 장착 슬롯을 구성해 Mock 아바타마다 다른 복장을 만듭니다.
 *
 * @param {number} mask 장착할 네 슬롯을 나타내는 1~15 비트 마스크
 * @param {number} memberIndex Mock 구성원 인덱스
 * @returns {Record<string, Record<string, any>|null>}
 */
function createEquipments(mask, memberIndex) {
    return Object.fromEntries(equipmentSlots.map((slot, slotIndex) => {
        if ((mask & (1 << slotIndex)) === 0) return [slot, null];

        const item = equipmentCatalog[slot];
        return [slot, {
            ownedItemId: (memberIndex + 1) * 10 + slotIndex,
            ...item,
        }];
    }));
}

/**
 * 실제 Snapshot 형태에 Mock 전용 색상 변형만 덧붙인 온라인 구성원을 생성합니다.
 * 운영 API에는 mockItemHue가 없으므로 실제 화면에서는 원본 아이템 색상이 유지됩니다.
 *
 * @param {number} index 0부터 시작하는 구성원 인덱스
 * @returns {Record<string, any>}
 */
function createMockMember(index) {
    const equipmentMask = (index % 15) + 1;

    return {
        userId: 101 + index,
        nickname: mockNicknames[index],
        rank: index + 1,
        contributionScore: 2450 - index * 85,
        zoneId: (index % 10) + 1,
        avatar: {
            equipments: createEquipments(equipmentMask, index),
            mockItemHue: (index * 47) % 360,
        },
    };
}

/**
 * 크리처 홈 화면 Mock 데이터입니다.
 * 20명의 복장·색상을 구분해 이동 애니메이션을 한 화면에서 점검할 수 있습니다.
 */
export const mockCreatureHomeData = {
    groupId: 5031,
    groupName: '새싹 연구소',
    season: {
        seasonId: 1,
        seasonNumber: 1,
        status: 'ACTIVE',
        startAt: '2026-11-15T23:59:59Z',
        endAt: '2026-12-07T19:59:59Z',
        seasonName: '시즌 1',
        labName: '새싹 연구소',
    },
    creature: {
        groupCreatureId: 701,
        creatureSpeciesId: 1,
        speciesName: '이끼 낀 바위 골렘',
        assetKey: 'moss-golem',
        growthStage: 'COMPLETE',
        level: null,
        totalExperience: 200000,
        nextStageAtExperience: null,
        progress: 4.76,
        selectionDeadlineAt: '2026-11-18T00:00:00Z',
        selectionSource: 'ADMIN_SELECTED',
    },
    onlineMembers: Array.from({ length: 20 }, (_, index) => createMockMember(index)),
    todayExperience: 2400,
    recentContributions: [
        { userId: 101, nickname: '본인', amount: 200, occurredAt: '2026-11-04T05:30:00Z' },
        { userId: 102, nickname: '푸른잎', amount: 100, occurredAt: '2026-11-04T04:00:00Z' },
        { userId: 103, nickname: '별빛', amount: 300, occurredAt: '2026-11-04T05:00:00Z' },
        { userId: 101, nickname: '본인', amount: 400, occurredAt: '2026-11-04T05:00:00Z' },
    ],
    snapshotAt: '2026-11-04T06:00:00Z',
    streamInstanceId: '11111111-1111-4111-8111-111111111111',
    sequence: 20,
};
