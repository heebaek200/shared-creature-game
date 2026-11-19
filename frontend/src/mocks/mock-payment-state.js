// @ts-check

/** Mock 구매에서 가장 최근 PG 취소 거래를 찾습니다. */
function latestCancellation(purchase) {
  return [...purchase.transactions].reverse()
      .find((transaction) => transaction.transactionType === 'CANCELLATION') ?? null;
}

/**
 * Mock 환불을 서버의 완료 계약과 동일하게 확정합니다.
 * 구매·아이템·환불·PG 취소 거래를 한 번에 일관된 상태로 변경합니다.
 */
export function completeMockRefund(purchase, refund, now = new Date().toISOString()) {
  let cancellation = latestCancellation(purchase);
  if (!cancellation) {
    cancellation = {
      transactionId: Date.now(),
      provider: 'PORTONE',
      providerTransactionId: `mock-cancel-${refund.refundRequestId}`,
      transactionType: 'CANCELLATION',
      amountKrw: purchase.priceKrw,
      status: 'SUCCESS',
      processedAt: now,
    };
    purchase.transactions.push(cancellation);
  } else {
    cancellation.providerTransactionId ??= `mock-cancel-${refund.refundRequestId}`;
    cancellation.status = 'SUCCESS';
    cancellation.processedAt = now;
  }

  refund.status = 'COMPLETED';
  refund.completedAt = now;
  purchase.status = 'CANCELLED';
  purchase.ownedItemStatus = 'REVOKED';
  purchase.latestRefundStatus = 'COMPLETED';
}

/**
 * Mock 미확정 환불을 UNKNOWN → PENDING → SUCCESS 순서로 재현합니다.
 * 첫 복구에서 무조건 성공시키지 않아 실제 서버의 재조회 흐름을 시연할 수 있습니다.
 */
export function reconcileMockRefundState(purchase, refund, now = new Date().toISOString()) {
  if (refund.status === 'CANCEL_PENDING') {
    completeMockRefund(purchase, refund, now);
    return;
  }
  if (refund.status !== 'CANCEL_UNKNOWN') {
    throw new Error('복구할 수 없는 Mock 환불 상태입니다.');
  }

  let cancellation = latestCancellation(purchase);
  if (!cancellation) {
    cancellation = {
      transactionId: Date.now(),
      provider: 'PORTONE',
      providerTransactionId: `mock-cancel-${refund.refundRequestId}`,
      transactionType: 'CANCELLATION',
      amountKrw: purchase.priceKrw,
      status: 'REQUESTED',
      processedAt: null,
    };
    purchase.transactions.push(cancellation);
  } else {
    cancellation.providerTransactionId ??= `mock-cancel-${refund.refundRequestId}`;
    cancellation.status = 'REQUESTED';
    cancellation.processedAt = null;
  }

  refund.status = 'CANCEL_PENDING';
  purchase.latestRefundStatus = 'CANCEL_PENDING';
}
