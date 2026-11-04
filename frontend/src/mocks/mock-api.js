// @ts-check
import { ApiError } from '../api/client.js';

/** 실제 API 계약과 같은 정상·오류 시나리오를 제공합니다. */
export const mockApi = {
  async getCurrentUser() {
    return {
      authenticationState: 'AUTHENTICATED',
      onboardingState: 'GROUP_REQUIRED',
      user: { userId: 101, nickname: '새싹지기' },
      membership: null,
      roles: [],
      restrictionUntil: null,
    };
  },
  async createGroup(input) {
    await delay(250);
    if (input.name.trim() === '중복그룹') {
      throw new ApiError(409, {
        code: 'GROUP_NAME_ALREADY_USED',
        message: '이미 사용 중인 그룹 이름입니다.',
        fieldErrors: [],
        traceId: 'mock-group-duplicate',
      });
    }
    return {
      group: {
        group: {
          groupId: 501,
          name: input.name.trim(),
          description: input.description.trim(),
          currentMemberCount: 1,
          capacity: 20,
          remainingCapacity: 19,
          joinable: true,
          joinBlockedReason: null,
        },
        primaryAdmin: { userId: 101, nickname: '새싹지기', role: 'PRIMARY', joinedAt: new Date().toISOString() },
        myMembership: { membershipId: 601, groupId: 501, role: 'PRIMARY', joinedAt: new Date().toISOString(), status: 'ACTIVE' },
      },
      initialInvitationUrl: `${location.origin}/invite/mock-invitation-token`,
    };
  },
  async getCreatureHome() {
    await delay(200);
    return {
      groupId: 501,
      groupName: "새싹 연구소 모임",
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
        nextLevelExp: 97500,
        progress: 59,
        remainingTime: "D-18",
        selectionDeadlineAt: "2026-11-10T00:00:00Z"
      },
      todayExperience: 2400,
      onlineMembers: Array.from({ length: 8 }, (_, i) => ({
        userId: i + 101,
        nickname: i === 0 ? "나" : `사용자 ${String.fromCharCode(65 + i)}`,
        rank: i + 1,
        contributionScore: (i + 1) * 250,
        avatar: {
          equipments: {
            HEAD: { ownedItemId: 1, shopItemId: 1, name: "새싹 모자", assetKey: "head_sprout" },
            FACE: null,
            BODY: { ownedItemId: 2, shopItemId: 2, name: "기본 외형", assetKey: "body_default" },
            ACCESSORY: null
          }
        },
        zoneId: (i % 5) + 1
      })),
      recentContributions: [
        { userId: 102, nickname: "사용자 A", amount: 100, occurredAt: new Date().toISOString() }
      ],
      snapshotAt: new Date().toISOString()
    };
  },

  async getCurrentSeason() {
    await delay(150);
    return {
      seasonId: 1,
      seasonNumber: 1,
      status: "ACTIVE",
      startAt: "2026-11-01T00:00:00Z",
      endAt: "2026-11-30T23:59:59Z",
      seasonName: "시즌 1",
      labName: "새싹 연구소"
    };
  },

};

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
