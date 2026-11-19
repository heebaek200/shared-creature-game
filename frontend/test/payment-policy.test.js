import assert from 'node:assert/strict';
import test from 'node:test';
import {
  adminRefundActions,
  adminRefundFeedback,
  canRequestRefund,
  canWithdrawRefund,
  checkoutRecoveryPath,
  isCurrentRequest,
  isDefinitivePurchaseStatus,
  parsePaymentReturn,
  parseShopItemId,
  pgCancellationPresentation,
} from '../src/features/payment/payment-policy.js';

test('결제 완료·미사용·진행 중 환불 없음인 구매만 환불할 수 있다', () => {
  assert.equal(canRequestRefund({ status: 'PAID', ownedItemStatus: 'OWNED_UNUSED', latestRefundStatus: null }), true);
  assert.equal(canRequestRefund({ status: 'PAID', ownedItemStatus: 'OWNED_USED', latestRefundStatus: null }), false);
  assert.equal(canRequestRefund({ status: 'PAID', ownedItemStatus: 'OWNED_UNUSED', latestRefundStatus: 'CANCEL_UNKNOWN' }), false);
  assert.equal(canRequestRefund({ status: 'PAID', ownedItemStatus: 'OWNED_UNUSED', latestRefundStatus: 'WITHDRAWN' }), true);
  assert.equal(canRequestRefund({ status: 'READY', ownedItemStatus: null, latestRefundStatus: null }), false);
});

test('사용자는 관리자 처리 전 REQUESTED 환불만 철회할 수 있다', () => {
  assert.equal(canWithdrawRefund({ status: 'REQUESTED' }), true);
  assert.equal(canWithdrawRefund({ status: 'WITHDRAWN' }), false);
  assert.equal(canWithdrawRefund({ status: 'CANCEL_PENDING' }), false);
});

test('관리자 환불 동작은 서버 상태에 따라 제한한다', () => {
  assert.deepEqual(adminRefundActions('REQUESTED'), ['approve', 'reject']);
  assert.deepEqual(adminRefundActions('CANCEL_PENDING'), ['reconcile']);
  assert.deepEqual(adminRefundActions('CANCEL_UNKNOWN'), ['reconcile']);
  assert.deepEqual(adminRefundActions('COMPLETED'), []);
  assert.deepEqual(adminRefundActions('WITHDRAWN'), []);
});

test('관리자 환불 알림은 HTTP 성공 여부가 아니라 환불 상태를 따른다', () => {
  assert.deepEqual(adminRefundFeedback('COMPLETED'), { message: '환불이 완료되었습니다.', type: 'success' });
  assert.deepEqual(adminRefundFeedback('CANCEL_PENDING'), { message: 'PG 취소를 처리하고 있습니다.', type: 'warning' });
  assert.deepEqual(adminRefundFeedback('CANCEL_UNKNOWN'), { message: 'PG 취소 결과를 확인해야 합니다.', type: 'warning' });
  assert.deepEqual(adminRefundFeedback('FAILED'), { message: '환불 처리에 실패했습니다.', type: 'danger' });
  assert.deepEqual(adminRefundFeedback('REJECTED'), { message: '환불 거절 처리가 완료되었습니다.', type: 'success' });
  assert.deepEqual(adminRefundFeedback('WITHDRAWN'), { message: '사용자가 환불 요청을 철회했습니다.', type: 'info' });
});

test('생성된 주문 문맥은 서버의 확정 상태에서만 제거한다', () => {
  assert.equal(isDefinitivePurchaseStatus('PAID'), true);
  assert.equal(isDefinitivePurchaseStatus('FAILED'), true);
  assert.equal(isDefinitivePurchaseStatus('CANCELLED'), true);
  assert.equal(isDefinitivePurchaseStatus('READY'), false);
  assert.equal(isDefinitivePurchaseStatus(undefined), false);
  assert.equal(
      checkoutRecoveryPath({ purchaseId: 12, payment: { paymentId: 'pay/12' } }),
      '/payments?purchaseId=12&paymentId=pay%2F12',
  );
});

test('비동기 응답은 가장 최근 요청이고 화면이 활성 상태일 때만 반영한다', async () => {
  let currentVersion = 0;
  const applied = [];
  let resolveFirst;
  let resolveSecond;
  const first = new Promise((resolve) => { resolveFirst = resolve; });
  const second = new Promise((resolve) => { resolveSecond = resolve; });

  const apply = async (promise) => {
    const version = ++currentVersion;
    const value = await promise;
    if (isCurrentRequest(version, currentVersion, true)) applied.push(value);
  };
  const firstTask = apply(first);
  const secondTask = apply(second);
  resolveSecond('B');
  await secondTask;
  resolveFirst('A');
  await firstTask;

  assert.deepEqual(applied, ['B']);
  assert.equal(isCurrentRequest(currentVersion, currentVersion, false), false);
});

test('PG 취소 상태는 미실행 사유와 자동 점검 안내를 구분한다', () => {
  assert.deepEqual(
      pgCancellationPresentation({ status: 'REJECTED', latestCancellation: null }),
      { label: '미실행 (환불 거절)', hint: null },
  );
  assert.deepEqual(
      pgCancellationPresentation({ status: 'REQUESTED', latestCancellation: null }),
      { label: '승인 전', hint: null },
  );
  assert.deepEqual(
      pgCancellationPresentation({ status: 'WITHDRAWN', latestCancellation: null }),
      { label: '미실행 (사용자 철회)', hint: null },
  );
  assert.equal(
      pgCancellationPresentation({
        status: 'CANCEL_UNKNOWN',
        latestCancellation: { status: 'UNKNOWN' },
      }).label,
      '결과 확인 필요',
  );
  assert.match(
      pgCancellationPresentation({ status: 'CANCEL_PENDING', latestCancellation: null }).hint,
      /5분 주기로/,
  );
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
