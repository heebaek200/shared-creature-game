import assert from 'node:assert/strict';
import test from 'node:test';
import {
  adminRefundActions,
  canRequestRefund,
  parsePaymentReturn,
  parseShopItemId,
} from '../src/features/payment/payment-policy.js';

test('결제 완료·미사용·진행 중 환불 없음인 구매만 환불할 수 있다', () => {
  assert.equal(canRequestRefund({ status: 'PAID', ownedItemStatus: 'OWNED_UNUSED', latestRefundStatus: null }), true);
  assert.equal(canRequestRefund({ status: 'PAID', ownedItemStatus: 'OWNED_USED', latestRefundStatus: null }), false);
  assert.equal(canRequestRefund({ status: 'PAID', ownedItemStatus: 'OWNED_UNUSED', latestRefundStatus: 'CANCEL_UNKNOWN' }), false);
  assert.equal(canRequestRefund({ status: 'READY', ownedItemStatus: null, latestRefundStatus: null }), false);
});

test('관리자 환불 동작은 서버 상태에 따라 제한한다', () => {
  assert.deepEqual(adminRefundActions('REQUESTED'), ['approve', 'reject']);
  assert.deepEqual(adminRefundActions('CANCEL_PENDING'), ['reconcile']);
  assert.deepEqual(adminRefundActions('CANCEL_UNKNOWN'), ['reconcile']);
  assert.deepEqual(adminRefundActions('COMPLETED'), []);
});

test('상점 직접 진입 ID는 1 이상의 안전한 정수만 허용한다', () => {
  assert.equal(parseShopItemId('31'), 31);
  assert.equal(parseShopItemId('0'), null);
  assert.equal(parseShopItemId('1.5'), null);
  assert.equal(parseShopItemId('abc'), null);
});

test('모바일 결제 복귀 Query에서 검증 문맥을 읽는다', () => {
  assert.deepEqual(
      parsePaymentReturn('https://example.com/payments?purchaseId=12&paymentId=pay-12&code=FAIL&message=cancelled'),
      { purchaseId: 12, paymentId: 'pay-12', code: 'FAIL', message: 'cancelled' },
  );
  assert.equal(parsePaymentReturn('https://example.com/payments'), null);
});
