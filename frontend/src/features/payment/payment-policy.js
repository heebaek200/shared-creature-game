// @ts-check

export const REFUND_IN_PROGRESS = new Set([
  'REQUESTED',
  'CANCEL_PENDING',
  'CANCEL_UNKNOWN',
]);

/** 구매 요약을 기준으로 환불 요청 버튼을 노출할 수 있는지 판정합니다. */
export function canRequestRefund(purchase) {
  return purchase?.status === 'PAID'
      && purchase?.ownedItemStatus === 'OWNED_UNUSED'
      && !REFUND_IN_PROGRESS.has(purchase?.latestRefundStatus);
}

/** 관리자 환불 상태에 맞는 동작 목록을 반환합니다. */
export function adminRefundActions(status) {
  if (status === 'REQUESTED') return ['approve', 'reject'];
  if (status === 'CANCEL_PENDING' || status === 'CANCEL_UNKNOWN') {
    return ['reconcile'];
  }
  return [];
}

/** 상점 직접 진입 Query를 1 이상의 안전한 정수로 변환합니다. */
export function parseShopItemId(value) {
  if (!/^[1-9]\d*$/.test(value ?? '')) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

/** PortOne Redirect Query에서 검증에 필요한 공개 결과만 읽습니다. */
export function parsePaymentReturn(url) {
  const parsed = new URL(url, 'http://localhost');
  const paymentId = parsed.searchParams.get('paymentId');
  if (!paymentId) return null;
  return {
    paymentId,
    purchaseId: parseShopItemId(parsed.searchParams.get('purchaseId')),
    code: parsed.searchParams.get('code'),
    message: parsed.searchParams.get('message'),
  };
}
