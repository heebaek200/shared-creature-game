// @ts-check

/**
 * 초대 URL과 대응하는 발급 정보를 비교 가능한 키로 변환합니다.
 * URL 원문은 키에 포함하지 않아 로그나 저장소로 확산되지 않게 합니다.
 *
 * @param {object|null|undefined} data 초대 발급 메타데이터
 * @returns {string|null} 유효한 발급 정보 키
 */
export function invitationIssuanceKey(data) {
  if (
    data?.groupId === null || data?.groupId === undefined ||
    !data?.issuedAt ||
    data?.issuedByUserId === null || data?.issuedByUserId === undefined
  ) {
    return null;
  }

  return JSON.stringify([
    String(data.groupId),
    String(data.issuedAt),
    String(data.issuedByUserId),
  ]);
}

/**
 * 보관 중인 URL의 발급 정보와 새 조회 결과가 같은 링크를 가리키는지 판정합니다.
 * 불완전한 응답은 동일한 링크로 간주하지 않습니다.
 *
 * @param {string|null} retainedKey 보관 중인 URL의 발급 정보 키
 * @param {object|null|undefined} latestData 최신 발급 메타데이터
 * @returns {boolean} 동일 발급 여부
 */
export function isSameInvitationIssuance(retainedKey, latestData) {
  const latestKey = invitationIssuanceKey(latestData);
  return retainedKey !== null && latestKey !== null && retainedKey === latestKey;
}

/**
 * 크리처 종 ID를 서버 기준값과 사용자 초안 비교에 사용할 문자열로 정규화합니다.
 *
 * @param {unknown} value 크리처 종 ID
 * @returns {string} null 또는 undefined이면 빈 문자열
 */
export function normalizeCreatureSpeciesId(value) {
  return value === null || value === undefined ? '' : String(value);
}

/** 주·부 관리자 역할만 크리처 종 선택을 관리할 수 있는지 판정합니다. */
export function canManageCreatureSelection(role) {
  return role === 'PRIMARY' || role === 'DEPUTY';
}

/**
 * 서버 재조회 결과와 저장하지 않은 사용자 선택을 병합합니다.
 * 서버가 다른 종으로 변경됐다면 최신 상태를 우선하고 사용자가 다시 선택하게 합니다.
 *
 * @param {{
 *   baselineId: unknown,
 *   draftId: unknown,
 *   latestId: unknown,
 *   activeSpeciesIds: unknown[],
 *   selectionOpen: boolean
 * }} input 비교할 선택 상태
 * @returns {{selectedId: string, outcome: 'SYNCED'|'DRAFT_PRESERVED'|'ALREADY_APPLIED'|'CONFLICT'}} 병합 결과
 */
export function reconcileCreatureSelectionDraft(input) {
  const baselineId = normalizeCreatureSpeciesId(input.baselineId);
  const draftId = normalizeCreatureSpeciesId(input.draftId);
  const latestId = normalizeCreatureSpeciesId(input.latestId);
  const hasDraft = draftId !== '' && draftId !== baselineId;
  const serverChanged = latestId !== baselineId;

  if (hasDraft && serverChanged) {
    return draftId === latestId
      ? {selectedId: latestId, outcome: 'ALREADY_APPLIED'}
      : {selectedId: latestId, outcome: 'CONFLICT'};
  }

  const draftIsActive = input.activeSpeciesIds.some(
    speciesId => normalizeCreatureSpeciesId(speciesId) === draftId,
  );

  if (hasDraft && input.selectionOpen && draftIsActive) {
    return {selectedId: draftId, outcome: 'DRAFT_PRESERVED'};
  }

  return {selectedId: latestId, outcome: 'SYNCED'};
}
