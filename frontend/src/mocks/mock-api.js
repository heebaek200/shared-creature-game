// @ts-check
import { ApiError } from '../api/client.js';

let mockMembership = null;

/** 실제 API 계약과 같은 정상·오류 시나리오를 제공합니다. */
export const mockApi = {
  async getCurrentUser() {
    return {
      authenticationState: 'AUTHENTICATED',
      onboardingState: mockMembership ? 'COMPLETE' : 'GROUP_REQUIRED',
      user: { userId: 101, nickname: '새싹지기' },
      membership: mockMembership,
      roles: mockMembership ? ['MEMBER'] : [],
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


  async listPublicGroups({ q = '', page = 0, size = 20 } = {}) {
    await delay(250);

    const groups = [
      {
        groupId: 1,
        name: '새싹 키우기',
        description: '함께 생명체를 키워나가는 그룹입니다.',
        currentMemberCount: 5,
        capacity: 20,
        remainingCapacity: 15,
        joinable: true,
        joinBlockedReason: null,
      },
      {
        groupId: 2,
        name: '매일 성장하기',
        description: '매일 작은 목표를 달성하는 그룹입니다.',
        currentMemberCount: 20,
        capacity: 20,
        remainingCapacity: 0,
        joinable: false,
        joinBlockedReason: 'GROUP_FULL',
      },
      {
        groupId: 3,
        name: '함께하는 도전',
        description: '다양한 퀘스트에 도전하는 그룹입니다.',
        currentMemberCount: 8,
        capacity: 20,
        remainingCapacity: 12,
        joinable: true,
        joinBlockedReason: null,
      },
    ];

    const filteredGroups = groups.filter((group) =>
        `${group.name} ${group.description}`.includes(q.trim())
    );

    const start = page * size;
    const data = filteredGroups.slice(start, start + size);

    return {
      data,
      meta: {
        page,
        size,
        totalElements: filteredGroups.length,
        totalPages: Math.ceil(filteredGroups.length / size),
      },
    };
  },

  /**
   * 공개 그룹에 가입합니다.
   */
  async joinGroup(groupId) {
    await delay(250);

    const groups = [
      { groupId: 1, name: '새싹 키우기', capacity: 20, currentMemberCount: 5 },
      { groupId: 2, name: '매일 성장하기', capacity: 20, currentMemberCount: 20 },
      { groupId: 3, name: '함께하는 도전', capacity: 20, currentMemberCount: 8 },
    ];

    const group = groups.find((item) => item.groupId === groupId);

    if (!group) {
      throw new ApiError(404, {
        code: 'GROUP_NOT_FOUND',
        message: '그룹을 찾을 수 없습니다.',
        fieldErrors: [],
        traceId: 'mock-group-not-found',
      });
    }

    if (group.currentMemberCount >= group.capacity) {
      throw new ApiError(409, {
        code: 'GROUP_FULL',
        message: '그룹 정원이 가득 찼습니다.',
        fieldErrors: [],
        traceId: 'mock-group-full',
      });
    }

    return {
      membershipId: Date.now(),
      groupId,
      role: 'MEMBER',
      joinedAt: new Date().toISOString(),
      status: 'ACTIVE',
      nickname: '새싹지기',
    };
  },


  async getSignupContext() {
    await delay(200);

    return {
      provider: "GOOGLE",
      email: 'example@gmail.com',
      suggestedNickname: '새싹지기',
      invitation: null,
      expiresAt: new Date(Date.now() + 30 * 60 * 100).toISOString(),
    };
  },

  /**
   * 공개 초대 정보를 조회합니다.
   */
  async getPublicInvitation(token) {
    await delay(250);

    if (!['test-token', 'mock-invitation-token'].includes(token)) {
      throw new ApiError(404, {
        code: 'INVITE_INVALID',
        message: '유효하지 않거나 만료된 초대 링크입니다.',
        fieldErrors: [],
        traceId: 'mock-invitation-invalid',
      });
    }

    return {
      groupId: 501,
      groupName: '새싹 연구소',
      description: '함께 생명체를 키우고 성장시키는 그룹입니다.',
      currentMemberCount: 14,
      capacity: 20,
      remainingCapacity: 6,
      joinable: true,
      joinBlockedReason: null,
    };
  },

  /**
   * 초대를 수락합니다.
   */
  async acceptInvitation(token) {
    await delay(250);

    if (!['test-token', 'mock-invitation-token'].includes(token)) {
      throw new ApiError(404, {
        code: 'INVITE_INVALID',
        message: '유효하지 않거나 만료된 초대 링크입니다.',
        fieldErrors: [],
        traceId: 'mock-invitation-invalid',
      });
    }

    mockMembership = {
      membershipId: 601,
      groupId: 501,
      role: 'MEMBER',
      joinedAt: new Date().toISOString(),
      status: 'ACTIVE',
    };

    return {
      groupId: 501,
      nextRoute: '/creature',
    };
  },

  /**
   * 초대를 거절합니다.
   */
  async rejectInvitation(token) {
    await delay(250);

    if (!['test-token', 'mock-invitation-token'].includes(token)) {
      throw new ApiError(404, {
        code: 'INVITE_INVALID',
        message: '유효하지 않거나 만료된 초대 링크입니다.',
        fieldErrors: [],
        traceId: 'mock-invitation-invalid',
      });
    }

    return {
      nextRoute: '/groups/select',
    };
  },


};

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
