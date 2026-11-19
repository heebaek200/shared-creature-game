import assert from 'node:assert/strict';
import test from 'node:test';
import {
  completeMockRefund,
  reconcileMockRefundState,
} from '../src/mocks/mock-payment-state.js';

function fixture(status = 'REQUESTED', transactionStatus = null) {
  const purchase = {
    purchaseId: 101,
    priceKrw: 1000,
    status: 'PAID',
    ownedItemStatus: 'OWNED_UNUSED',
    latestRefundStatus: status,
    transactions: transactionStatus ? [{
      transactionId: 301,
      provider: 'PORTONE',
      providerTransactionId: null,
      transactionType: 'CANCELLATION',
      amountKrw: 1000,
      status: transactionStatus,
      processedAt: null,
    }] : [],
  };
  const refund = {
    refundRequestId: 201,
    purchaseId: 101,
    status,
    completedAt: null,
  };
  return { purchase, refund };
}

test('Mock 환불 완료는 구매·아이템·환불·PG 취소 거래를 함께 확정한다', () => {
  const { purchase, refund } = fixture();
  completeMockRefund(purchase, refund, '2026-11-18T07:00:00Z');

  assert.equal(refund.status, 'COMPLETED');
  assert.equal(purchase.status, 'CANCELLED');
  assert.equal(purchase.ownedItemStatus, 'REVOKED');
  assert.equal(purchase.latestRefundStatus, 'COMPLETED');
  assert.deepEqual(purchase.transactions[0], {
    transactionId: purchase.transactions[0].transactionId,
    provider: 'PORTONE',
    providerTransactionId: 'mock-cancel-201',
    transactionType: 'CANCELLATION',
    amountKrw: 1000,
    status: 'SUCCESS',
    processedAt: '2026-11-18T07:00:00Z',
  });
});

test('Mock 결과 복구는 UNKNOWN을 바로 완료하지 않고 PENDING을 거친다', () => {
  const { purchase, refund } = fixture('CANCEL_UNKNOWN', 'UNKNOWN');

  reconcileMockRefundState(purchase, refund, '2026-11-18T07:00:00Z');
  assert.equal(refund.status, 'CANCEL_PENDING');
  assert.equal(purchase.status, 'PAID');
  assert.equal(purchase.ownedItemStatus, 'OWNED_UNUSED');
  assert.equal(purchase.transactions[0].status, 'REQUESTED');

  reconcileMockRefundState(purchase, refund, '2026-11-18T07:05:00Z');
  assert.equal(refund.status, 'COMPLETED');
  assert.equal(purchase.status, 'CANCELLED');
  assert.equal(purchase.ownedItemStatus, 'REVOKED');
  assert.equal(purchase.transactions[0].status, 'SUCCESS');
});
