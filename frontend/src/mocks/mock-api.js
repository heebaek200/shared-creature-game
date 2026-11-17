// @ts-check
import { ApiError } from '../api/client.js';
import { mockCreatureHomeData } from './mock-creature-page.js';

const MOCK_STATE_KEY = 'shared-creature-mock-state';

function loadMockState() {
  try {
    const savedState = sessionStorage.getItem(MOCK_STATE_KEY);
    return savedState ? JSON.parse(savedState) : null;
  } catch {
    return null;
  }
}

const savedMockState = loadMockState();

const MOCK_SIDEBAR_ROLE_KEY = 'shared-creature-mock-sidebar-role';
const MOCK_SIDEBAR_ROLES = ['MEMBER', 'PRIMARY', 'DEPUTY'];
const requestedSidebarRole = new URLSearchParams(location.search).get('mockSidebarRole');

if (requestedSidebarRole && MOCK_SIDEBAR_ROLES.includes(requestedSidebarRole)) {
  sessionStorage.setItem(MOCK_SIDEBAR_ROLE_KEY, requestedSidebarRole);
}

const savedSidebarRole = sessionStorage.getItem(MOCK_SIDEBAR_ROLE_KEY);
let mockSidebarRole = MOCK_SIDEBAR_ROLES.includes(savedSidebarRole ?? '')
    ? savedSidebarRole
    : null;

let mockMembership = savedMockState?.mockMembership ?? null;
let mockGlobalRoles = savedMockState?.mockGlobalRoles ?? [];
let nextMockGroupId = 501;
let mockAuthenticationState =
    savedMockState?.mockAuthenticationState ?? 'SIGNUP_PENDING';
let mockSignupNickname =
    savedMockState?.mockSignupNickname ?? '새싹지기';


const MOCK_INVITATION_TOKEN =
    'mockInvitationToken1234567890abcdef';

const mockGroups = [
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

  {
    groupId: 4,
    name: '초록빛 정원',
    description: '함께 초록빛 정원을 가꾸는 그룹입니다.',
    currentMemberCount: 3,
    capacity: 20,
    remainingCapacity: 17,
    joinable: true,
    joinBlockedReason: null
  },
  {
    groupId: 5,
    name: '행복한 새싹들',
    description: '매일 함께 성장하는 그룹입니다.',
    currentMemberCount: 7,
    capacity: 20,
    remainingCapacity: 13,
    joinable: true,
    joinBlockedReason: null
  },
  {
    groupId: 6,
    name: '성장하는 우리',
    description: '서로 응원하며 성장하는 그룹입니다.',
    currentMemberCount: 12,
    capacity: 20,
    remainingCapacity: 8,
    joinable: true,
    joinBlockedReason: null
  },
  {
    groupId: 7,
    name: '생명의 숲',
    description: '함께 생명체를 키우는 그룹입니다.',
    currentMemberCount: 15,
    capacity: 20,
    remainingCapacity: 5,
    joinable: true,
    joinBlockedReason: null
  }
];

const AVATAR_MOCK_KEY = 'shared-creature-avatar-mock';

const AVATAR_SLOTS = ['HEAD', 'FACE', 'BODY', 'ACCESSORY'];

function createAvatarMockState() {
  const products = [
    ['HEAD', '단발(갈색)', 'brown-lob'],
    ['FACE', '웃는 얼굴(노랑)', 'yellow-smile'],
    ['BODY', '티셔츠 세트(파랑)', 'blue-shirt'],
    ['BODY', '멜빵 세트(하양)', 'white-overalls'],
    ['ACCESSORY', '리본 머리띠', 'ribbon-hairband'],
  ];

  return {
    avatar: {
      userId: 1,
      equipments: {
        HEAD: null,
        FACE: null,
        BODY: null,
        ACCESSORY: null,
      },
    },
    items: products.map(([slot, name, assetKey], index) => ({
      ownedItemId: index + 1,
      purchaseId: index + 1,
      shopItemId: index + 1,
      name,
      slot,
      assetKey,
      status: 'OWNED_UNUSED',
      usedAt: null,
      revokedAt: null,
      equipped: false,
    })),
  };
}

function loadAvatarMockState() {
  try {
    const saved = sessionStorage.getItem(AVATAR_MOCK_KEY);

    return saved
        ? JSON.parse(saved)
        : createAvatarMockState();
  } catch {
    return createAvatarMockState();
  }
}

const avatarMockState = loadAvatarMockState();

function saveAvatarMockState() {
  sessionStorage.setItem(
      AVATAR_MOCK_KEY,
      JSON.stringify(avatarMockState)
  );
}

// 호출자가 반환값을 변경해도 Mock 내부 상태는 변경되지 않습니다.
function copyAvatarMock(value) {
  return structuredClone(value);
}

function throwAvatarMockError(status, code, message) {
  throw new ApiError(status, {
    code,
    message,
    fieldErrors: [],
    traceId: 'mock-avatar',
  });
}

function validateAvatarSlot(slot) {
  if (!AVATAR_SLOTS.includes(slot)) {
    throwAvatarMockError(
        400,
        'INVALID_FIELD_VALUE',
        '올바르지 않은 슬롯입니다.'
    );
  }
}

/** 실제 API 계약과 같은 정상·오류 시나리오를 제공합니다. */
export const mockApi = {
  async getAvatar() {
    await delay(200);

    return copyAvatarMock(avatarMockState.avatar);
  },

  async listOwnedItems({
                         page = 0,
                         size = 20,
                         slot,
                         status,
                       } = {}) {
    await delay(200);

    const validStatuses = [
      'OWNED_UNUSED',
      'OWNED_USED',
      'REVOKED',
    ];

    if (
        !Number.isInteger(page) ||
        page < 0 ||
        !Number.isInteger(size) ||
        size < 1 ||
        size > 50 ||
        (slot !== undefined && !AVATAR_SLOTS.includes(slot)) ||
        (status !== undefined && !validStatuses.includes(status))
    ) {
      throwAvatarMockError(
          400,
          'INVALID_FIELD_VALUE',
          '조회 조건이 올바르지 않습니다.'
      );
    }

    const filtered = avatarMockState.items.filter((item) =>
        (!slot || item.slot === slot) &&
        (!status || item.status === status)
    );

    return {
      data: copyAvatarMock(
          filtered.slice(page * size, (page + 1) * size)
      ),
      meta: {
        page,
        size,
        totalElements: filtered.length,
        totalPages: Math.ceil(filtered.length / size),
      },
    };
  },

  async equipAvatarItem(
      slot,
      { ownedItemId, confirmFirstUse = false }
  ) {
    await delay(200);
    validateAvatarSlot(slot);

    if (
        !Number.isSafeInteger(ownedItemId) ||
        ownedItemId < 1 ||
        typeof confirmFirstUse !== 'boolean'
    ) {
      throwAvatarMockError(
          400,
          'INVALID_FIELD_VALUE',
          '장착 요청 값이 올바르지 않습니다.'
      );
    }

    const item = avatarMockState.items.find(
        (candidate) => candidate.ownedItemId === ownedItemId
    );

    if (!item || item.status === 'REVOKED') {
      throwAvatarMockError(
          404,
          'RESOURCE_NOT_FOUND',
          '장착할 수 있는 보유 아이템을 찾지 못했습니다.'
      );
    }

    if (item.slot !== slot) {
      throwAvatarMockError(
          400,
          'SLOT_MISMATCH',
          '아이템과 장착 슬롯이 일치하지 않습니다.'
      );
    }

    if (
        item.status === 'OWNED_UNUSED' &&
        !confirmFirstUse
    ) {
      throwAvatarMockError(
          409,
          'FIRST_USE_CONFIRMATION_REQUIRED',
          '최초 사용 및 환불 불가 전환에 동의해야 합니다.'
      );
    }

    // 같은 슬롯에 장착된 기존 아이템의 장착 표시를 해제합니다.
    for (const ownedItem of avatarMockState.items) {
      if (ownedItem.slot === slot) {
        ownedItem.equipped = false;
      }
    }

    // 최초 장착일 때만 사용 상태와 최초 사용 시각을 변경합니다.
    if (item.status === 'OWNED_UNUSED') {
      item.status = 'OWNED_USED';
      item.usedAt = new Date().toISOString();
    }

    item.equipped = true;

    avatarMockState.avatar.equipments[slot] = {
      ownedItemId: item.ownedItemId,
      shopItemId: item.shopItemId,
      name: item.name,
      assetKey: item.assetKey,
    };

    saveAvatarMockState();

    return copyAvatarMock(avatarMockState.avatar);
  },

  async unequipAvatarItem(slot) {
    await delay(200);
    validateAvatarSlot(slot);

    for (const item of avatarMockState.items) {
      if (item.slot === slot) {
        item.equipped = false;
      }
    }

    avatarMockState.avatar.equipments[slot] = null;

    // 장착을 해제해도 사용 상태와 최초 사용 시각은 유지합니다.
    saveAvatarMockState();

    return copyAvatarMock(avatarMockState.avatar);
  },

  async getCurrentUser() {
    if (mockSidebarRole) {
      return {
        authenticationState: 'AUTHENTICATED',
        onboardingState: 'COMPLETED',
        user: {
          userId: 101,
          nickname: mockSignupNickname,
        },
        membership: {
          membershipId: 501,
          groupId: 501,
          role: mockSidebarRole,
          status: 'ACTIVE',
          joinedAt: '2026-01-01T00:00:00Z',
        },
        roles: [mockSidebarRole],
        restrictionUntil: null,
        onboardingReason: null,
      };
    }

    const isAuthenticated =
        mockAuthenticationState !== 'ANONYMOUS';

    const isSignupPending =
        mockAuthenticationState === 'SIGNUP_PENDING';

    const isSuperAdmin = mockGlobalRoles.includes('SUPER_ADMIN');

    return {
      authenticationState: mockAuthenticationState,

      onboardingState: !isAuthenticated
          ? 'NOT_STARTED'
          : isSignupPending
              ? 'SIGNUP_REQUIRED'
              : (mockMembership || isSuperAdmin)
                  ? 'COMPLETED'
                  : 'GROUP_REQUIRED',

      user: isAuthenticated && !isSignupPending
          ? {
            userId: 101,
            nickname: mockSignupNickname,
          }
          : null,

      membership: isSuperAdmin ? null : mockMembership,

      roles: isSuperAdmin
          ? ['SUPER_ADMIN']
          : mockMembership
              ? [mockMembership.role]
              : [],

      restrictionUntil: null,
      onboardingReason: null
    };
  },

  async getMyGroup() {
    const membership = mockSidebarRole
        ? {
          membershipId: 501,
          groupId: 501,
          role: mockSidebarRole,
          status: 'ACTIVE',
          joinedAt: '2026-01-01T00:00:00Z',
        }
        : mockMembership;

    if (!membership) {
      throw new ApiError(403, {
        code: 'ROLE_FORBIDDEN',
        message: '그룹에 가입한 사용자만 조회할 수 있습니다.',
        fieldErrors: [],
        traceId: 'mock-my-group-forbidden',
      });
    }

    return {
      group: {
        groupId: membership.groupId,
        name: '새싹 연구소',
        description: '함께 생명체를 키우고 성장시키는 그룹입니다.',
        currentMemberCount: 14,
        capacity: 20,
        remainingCapacity: 6,
        joinable: true,
        joinBlockedReason: null,
      },
      primaryAdmin: {
        userId: 101,
        nickname: '새싹지기',
      },
      myMembership: membership,
    };
  },

  async logout() {
    mockAuthenticationState = 'ANONYMOUS';
    mockSidebarRole = null;
    sessionStorage.removeItem(MOCK_SIDEBAR_ROLE_KEY);
    saveMockState();
    return {};
  },

  async createOAuthAttempt(returnRoute = 'DEFAULT') {
    await delay(200);

    mockAuthenticationState = 'SIGNUP_PENDING';
    saveMockState();

    return {
      authorizationUrl: '/signup',
      returnRoute,
    };
  },

  async completeSignup(nickname) {
    await delay(200);

    if (mockAuthenticationState !== 'SIGNUP_PENDING') {
      throw new ApiError(409, {
        code: 'SIGNUP_NOT_PENDING',
        message: '가입 대기 상태가 아닙니다.',
        fieldErrors: [],
        traceId: 'mock-signup-not-pending',
      });
    }

    mockSignupNickname = nickname.normalize('NFKC').trim();
    mockAuthenticationState = 'AUTHENTICATED';
    saveMockState();

    return {
      authenticationState: 'AUTHENTICATED',
      onboardingState: 'GROUP_REQUIRED',
      user: {
        userId: 101,
        nickname: mockSignupNickname,
      },
      membership: null,
      roles: [],
      restrictionUntil: null,
      onboardingReason: null,
    };
  },

  async createGroup(input) {
    await delay(250);

    const name = input.name.trim();
    const description = (input.description ?? '').trim();

    // 그룹명 중복 검사
    if (
        mockGroups.some(
            (group) => group.name.normalize('NFKC').trim().toLowerCase()
                === name.normalize('NFKC').trim().toLowerCase()
        )
    ) {
      throw new ApiError(409, {
        code: 'GROUP_NAME_ALREADY_USED',
        message: '이미 사용 중인 그룹 이름입니다.',
        fieldErrors: [],
        traceId: 'mock-group-duplicate',
      });
    }

    const groupId = nextMockGroupId++;
    const now = new Date().toISOString();

    // 그룹 생성 정보
    const newGroup = {
      groupId,
      name,
      description,
      currentMemberCount: 1,
      capacity: 20,
      remainingCapacity: 19,
      joinable: true,
      joinBlockedReason: null,
    };

    // 그룹 목록에 추가
    mockGroups.unshift(newGroup);

    // 생성자를 PRIMARY 관리자로 자동 가입 처리
    mockMembership = {
      membershipId: Date.now(),
      groupId,
      role: 'PRIMARY',
      joinedAt: now,
      status: 'ACTIVE',
    };

    saveMockState();

    return {
      group: {
        group: newGroup,
        primaryAdmin: {
          userId: 101,
          nickname: mockSignupNickname,
          role: 'PRIMARY',
          joinedAt: now,
        },
        myMembership: mockMembership,
      },
      initialInvitationUrl:
          `${location.origin}/invite/${MOCK_INVITATION_TOKEN}`,
      nextRoute: '/creature',
    };
  },

  async listPublicGroups({ q = '', page = 0, size = 20 } = {}) {
    await delay(250);

    const filteredGroups = mockGroups.filter((group) =>
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

    // 실제 Mock 그룹 목록에서 가입할 그룹 조회
    const group = mockGroups.find(
        (item) => item.groupId === groupId
    );

    if (!group) {
      throw new ApiError(404, {
        code: 'GROUP_NOT_FOUND',
        message: '그룹을 찾을 수 없습니다.',
        fieldErrors: [],
        traceId: 'mock-group-not-found',
      });
    }

    // 이미 그룹에 가입했는지 확인
    if (mockMembership) {
      throw new ApiError(409, {
        code: 'ALREADY_IN_GROUP',
        message: '이미 다른 그룹에 가입되어 있습니다.',
        fieldErrors: [],
        traceId: 'mock-already-in-group',
      });
    }


    // 정원 확인
    if (group.currentMemberCount >= group.capacity) {
      throw new ApiError(409, {
        code: 'GROUP_FULL',
        message: '그룹 정원이 가득 찼습니다.',
        fieldErrors: [],
        traceId: 'mock-group-full',
      });
    }



    // 가입 처리
    const now = new Date().toISOString();

    mockMembership = {
      membershipId: Date.now(),
      groupId,
      role: 'MEMBER',
      joinedAt: now,
      status: 'ACTIVE',
    };

    saveMockState();

    // 그룹 인원 갱신
    group.currentMemberCount += 1;
    group.remainingCapacity =
        group.capacity - group.currentMemberCount;

    // 정원이 찼으면 가입 불가 처리
    if (group.remainingCapacity <= 0) {
      group.joinable = false;
      group.joinBlockedReason = 'GROUP_FULL';
    }

    return {
      ...mockMembership,
      nextRoute: '/creature',
    };
  },


  async getSignupContext() {
    await delay(200);

    if (mockAuthenticationState !== 'SIGNUP_PENDING') {
      throw new ApiError(401, {
        code: 'SIGNUP_NOT_PENDING',
        message: '가입 대기 상태가 아닙니다.',
        fieldErrors: [],
        traceId: 'mock-signup-not-pending',
      });
    }

    return {
      provider: 'GOOGLE',
      email: 'example@gmail.com',
      suggestedNickname: mockSignupNickname,
      invitation: null,
      expiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
    };
  },

  /**
   * 공개 초대 정보를 조회합니다.
   */
  async getPublicInvitation(token) {
    await delay(250);

    if (token !== MOCK_INVITATION_TOKEN) {
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

    // 이미 그룹에 가입되어 있는지 먼저 확인
    if (mockMembership) {
      throw new ApiError(409, {
        code: 'ALREADY_IN_GROUP',
        message: '이미 그룹에 가입되어 있습니다.',
        fieldErrors: [],
        traceId: 'mock-invitation-already-in-group',
      });
    }

    // 초대 링크 유효성 확인
    if (token !== MOCK_INVITATION_TOKEN) {
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

    saveMockState();

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


    if (token !== MOCK_INVITATION_TOKEN) {
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

  // mock-creature-page 연결
  async getCreatureHome() {
    await delay(200);
    return mockCreatureHomeData;
  },

  async getActiveCreatureSpecies() {
    await delay(150);
    const creature = mockCreatureHomeData.creature;
    if (creature.creatureSpeciesId == null) return [];
    return [{
      creatureSpeciesId: creature.creatureSpeciesId,
      code: creature.assetKey,
      name: creature.speciesName,
      assetKey: creature.assetKey,
      displayOrder: 1,
    }];
  },

  async getCurrentSeason() {
    await delay(150);
    return mockCreatureHomeData.season;
  },

  /** 판매 중인 Mock 상품 상세를 반환합니다. */
  async getShopItem(shopItemId) {
    await delay(100);
    const items = {
      1: { shopItemId: 1, name: '갈색 단발', slot: 'HEAD', assetKey: 'brown-lob', status: 'ACTIVE' },
      2: { shopItemId: 2, name: '파란 셔츠', slot: 'BODY', assetKey: 'blue-shirt', status: 'ACTIVE' },
      3: { shopItemId: 3, name: '리본 헤어밴드', slot: 'ACCESSORY', assetKey: 'ribbon-hairband', status: 'ACTIVE' },
      4: { shopItemId: 4, name: '노란 미소', slot: 'FACE', assetKey: 'yellow-smile', status: 'ACTIVE' },
    };
    const item = items[Number(shopItemId)];

    if (!item) {
      throw new ApiError(404, {
        code: 'RESOURCE_NOT_FOUND',
        message: '판매 중인 상품을 찾을 수 없습니다.',
        fieldErrors: [],
        traceId: 'mock-shop-item-not-found',
      });
    }

    return item;
  },
};

function saveMockState() {
  sessionStorage.setItem(
      MOCK_STATE_KEY,
      JSON.stringify({
        mockMembership,
        mockAuthenticationState,
        mockSignupNickname,
        mockGlobalRoles,
      })
  );
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
