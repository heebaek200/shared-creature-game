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

const PG_CANCELLATION_LABELS = {
  SUCCESS: '취소 완료',
  DEFINITIVE_FAILURE: '취소 실패',
  REQUESTED: '처리 중',
  UNKNOWN: '결과 확인 필요',
};

/** 환불 상태와 최근 취소 거래를 관리자 화면용 문구로 변환합니다. */
export function pgCancellationPresentation(refund) {
  const cancellationStatus = refund?.latestCancellation?.status;
  const unresolved = refund?.status === 'CANCEL_PENDING' || refund?.status === 'CANCEL_UNKNOWN';
  let label;

  if (cancellationStatus) label = PG_CANCELLATION_LABELS[cancellationStatus] ?? cancellationStatus;
  else if (refund?.status === 'REQUESTED') label = '승인 전';
  else if (refund?.status === 'REJECTED') label = '미실행 (환불 거절)';
  else if (unresolved) label = '처리 준비 중';
  else label = '취소 이력 없음';

  return {
    label,
    hint: unresolved
      ? '5분 이상 미확정된 PG 취소는 서버가 5분 주기로 다시 확인합니다. 결과 복구 버튼으로 즉시 확인할 수 있으며, 자동 확인 결과는 상세를 다시 조회하면 반영됩니다.'
      : null,
  };
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
