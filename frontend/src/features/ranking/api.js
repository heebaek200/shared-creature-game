// @ts-check
import { apiRequestWithMeta } from '../../api/client.js';
import { mockApi } from '../../mocks/mock-api.js';

const USE_MOCK = import.meta.env.VITE_USE_MOCK_API === 'true';

/** 현재 그룹의 개인 시즌 랭킹을 조회합니다. */
export function getMemberRanking({ seasonId, page = 0, size = 20 }) {
  if (USE_MOCK) return mockApi.getMemberRanking({ seasonId, page, size });
  return apiRequestWithMeta(
      `/api/v1/rankings/members?${rankingQuery({ seasonId, page, size })}`,
  );
}

/** 전체 그룹의 시즌 랭킹을 조회합니다. */
export function getGroupRanking({ seasonId, page = 0, size = 20 }) {
  if (USE_MOCK) return mockApi.getGroupRanking({ seasonId, page, size });
  return apiRequestWithMeta(
      `/api/v1/rankings/groups?${rankingQuery({ seasonId, page, size })}`,
  );
}

/** 랭킹 Query를 동일한 필드·순서로 직렬화합니다. */
function rankingQuery({ seasonId, page, size }) {
  return new URLSearchParams({
    seasonId: String(seasonId),
    page: String(page),
    size: String(size),
  }).toString();
}
