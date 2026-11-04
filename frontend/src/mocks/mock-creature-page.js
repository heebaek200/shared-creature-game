// src/mocks/mock-creature-page.js
export const mockCreatureHomeData = {
    groupId: 501,
    groupName: "새싹 연구소",
    season: {
        seasonId: 1,
        seasonNumber: 1,
        status: "ACTIVE",
        startAt: "2026-11-01T00:00:00Z",
        endAt: "2026-11-30T23:59:59Z",
        seasonName: "시즌 1",
        labName: "새싹 연구소"
    },
    creature: {
        groupCreatureId: 701,
        creatureSpeciesId: 3,
        growthStage: "GROWING",
        level: 3,
        totalExperience: 58000,
        nextStageAtExperience: 97500,
        progress: 60,
        selectionDeadlineAt: "2026-11-10T00:00:00Z",
        remainingTime: "12시간 남음"
    },
    currentUser: {
        userId: 101,
        nickname: "나",
        rank: 2,
        contributionScore: 2100
    },
    onlineMembers: [
        {
            userId: 102,
            nickname: "푸른잎",
            rank: 3,
            contributionScore: 1820,
            zoneId: 1,
            avatar: {
                equipments: {
                    HEAD: { ownedItemId: 1, shopItemId: 1, name: "새싹 모자", assetKey: "head_sprout" },
                    FACE: null,
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: { ownedItemId: 3, shopItemId: 3, name: "별 장식", assetKey: "acc_star" }
                }
            }
        },
        {
            userId: 103,
            nickname: "별빛요정",
            rank: 1,
            contributionScore: 2450,
            zoneId: 3,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        }
    ],
    todayExperience: 2400,
    recentContributions: [
        { userId: 102, nickname: "푸른잎", amount: 100, occurredAt: "2026-11-04T04:00:00Z" },
        { userId: 103, nickname: "별빛요정", amount: 300, occurredAt: "2026-11-04T05:00:00Z" }
    ],
    snapshotAt: "2026-11-04T06:00:00Z"
};