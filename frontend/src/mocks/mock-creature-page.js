// @ts-check

/**
 * 크리처 홈 화면 Mock 데이터 (DB 스키마 및 비즈니스 규칙 반영)
 */
export const mockCreatureHomeData = {
    groupId: 5031,
    groupName: "새싹 연구소",
    season: {
        seasonId: 1,
        seasonNumber: 1,
        status: "ACTIVE",
        startAt: "2026-11-04T23:59:59Z",
        endAt: "2026-11-13T19:59:59Z",
        seasonName: "시즌 1",
        labName: "새싹 연구소"
    },
    creature: {
        groupCreatureId: 701,
        creatureSpeciesId: 1, // null / 1 / 2
        speciesName: "이끼 낀 바위 골렘", // 공허 속 우주 해파리 /
        assetKey: "moss-golem", // abyss-jellyfish /
        growthStage: "COMPLETE", // EGG / HATCHLING / GROWING / MATURE / COMPLETE
        level: null,
        totalExperience: 200000,
        nextStageAtExperience: null,
        progress: 4.76,
        selectionDeadlineAt: "2026-11-07T00:00:00Z",
        selectionSource: "ADMIN_SELECTED"
    },
    onlineMembers: [
        {
            userId: 1,
            nickname: "본인",
            rank: 2,
            contributionScore: 2100,
            zoneId: 2,
            avatar: {
                equipments: {
                    HEAD: { ownedItemId: 10, shopItemId: 10, name: "모험가 모자", assetKey: "head_adventurer" },
                    FACE: null,
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },
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
        },
        {
            userId: 104,
            nickname: "별빛요정4",
            rank: 4,
            contributionScore: 1600,
            zoneId: 4,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },
        {
            userId: 105,
            nickname: "별빛요정5",
            rank: 5,
            contributionScore: 155,
            zoneId: 5,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },

        {
            userId: 106,
            nickname: "별빛요정6",
            rank: 6,
            contributionScore: 1400,
            zoneId: 6,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },
        {
            userId: 107,
            nickname: "별빛요정7",
            rank: 7,
            contributionScore: 1300,
            zoneId: 7,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },
        {
            userId: 108,
            nickname: "별빛요정8",
            rank: 8,
            contributionScore: 1200,
            zoneId: 8,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },
        {
            userId: 109,
            nickname: "별빛요정9",
            rank: 9,
            contributionScore: 1100,
            zoneId: 9,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },
        {
            userId: 110,
            nickname: "별빛요정10",
            rank: 10,
            contributionScore: 1000,
            zoneId: 10,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },
        {
            userId: 111,
            nickname: "별빛요정11",
            rank: 11,
            contributionScore: 900,
            zoneId: 11,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },
        {
            userId: 112,
            nickname: "별빛요정12",
            rank: 12,
            contributionScore: 800,
            zoneId: 12,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },
        {
            userId: 113,
            nickname: "별빛요정13",
            rank: 13,
            contributionScore: 700,
            zoneId: 13,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },
        {
            userId: 114,
            nickname: "별빛요정14",
            rank: 14,
            contributionScore: 600,
            zoneId: 14,
            avatar: {
                equipments: {
                    HEAD: null,
                    FACE: { ownedItemId: 4, shopItemId: 4, name: "나뭇잎 안경", assetKey: "face_leaf_glasses" },
                    BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
                    ACCESSORY: null
                }
            }
        },
    ],
    todayExperience: 2400,
    recentContributions: [
        { userId: 1, nickname: "본인", amount: 200, occurredAt: "2026-11-04T05:30:00Z" },
        { userId: 102, nickname: "푸른잎", amount: 100, occurredAt: "2026-11-04T04:00:00Z" },
        { userId: 103, nickname: "별빛요정", amount: 300, occurredAt: "2026-11-04T05:00:00Z" },
        { userId: 1, nickname: "본인", amount: 400, occurredAt: "2026-11-04T05:00:00Z" }
    ],
    snapshotAt: "2026-11-04T06:00:00Z"
};
