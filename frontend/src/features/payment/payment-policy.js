// @ts-check

export const REFUND_IN_PROGRESS = new Set([
  'REQUESTED',
  'CANCEL_PENDING',
  'CANCEL_UNKNOWN',
]);

const DEFINITIVE_PURCHASE_STATUSES = new Set(['PAID', 'FAILED', 'CANCELLED']);

/** 비동기 응답이 현재 화면의 가장 최근 요청 결과인지 판정합니다. */
export function isCurrentRequest(requestVersion, currentVersion, active = true) {
  return active && requestVersion === currentVersion;
}

/** 서버가 결제 결과를 확정한 상태에서만 저장된 주문 문맥을 제거합니다. */
export function isDefinitivePurchaseStatus(status) {
  return DEFINITIVE_PURCHASE_STATUSES.has(status);
}

/** 생성된 주문을 결제 내역에서 재검증할 수 있는 내부 경로를 만듭니다. */
export function checkoutRecoveryPath(order) {
  const purchaseId = encodeURIComponent(String(order.purchaseId));
  const paymentId = encodeURIComponent(String(order.payment.paymentId));
  return `/payments?purchaseId=${purchaseId}&paymentId=${paymentId}`;
}

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

/** 서버가 반환한 환불 상태를 관리자 알림 문구와 수준으로 변환합니다. */
export function adminRefundFeedback(status) {
  switch (status) {
    case 'COMPLETED': return { message: '환불이 완료되었습니다.', type: 'success' };
    case 'CANCEL_PENDING': return { message: 'PG 취소를 처리하고 있습니다.', type: 'warning' };
    case 'CANCEL_UNKNOWN': return { message: 'PG 취소 결과를 확인해야 합니다.', type: 'warning' };
    case 'FAILED': return { message: '환불 처리에 실패했습니다.', type: 'danger' };
    case 'REJECTED': return { message: '환불 거절 처리가 완료되었습니다.', type: 'success' };
    default: return { message: '최신 환불 상태를 확인했습니다.', type: 'warning' };
  }
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
