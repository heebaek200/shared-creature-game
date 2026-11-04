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
};

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
