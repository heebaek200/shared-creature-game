import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const pageSource = readFileSync(new URL('../src/pages/payments-page.js', import.meta.url), 'utf8');
const apiSource = readFileSync(new URL('../src/features/payment/api.js', import.meta.url), 'utf8');
const mockSource = readFileSync(new URL('../src/mocks/mock-api.js', import.meta.url), 'utf8');

test('환불 요청 카드의 REQUESTED 상태에만 철회 동작을 연결한다', () => {
  assert.match(pageSource, /canWithdrawRefund\(refund\)/);
  assert.match(pageSource, /data-withdraw-dialog/);
  assert.match(pageSource, /withdraw\.dataset\.requestId/);
});

test('철회 성공 후 환불 목록과 구매 목록과 구매 상세를 다시 조회한다', () => {
  const start = pageSource.indexOf('async function submitWithdrawal');
  const end = pageSource.indexOf('\n  async function onClick', start);
  const source = pageSource.slice(start, end);
  assert.match(source, /await withdrawRefundRequest\(requestId\)/);
  assert.match(source, /await loadRows\(0\)/);
  assert.match(source, /await listMyRefundRequests\(/);
  assert.match(source, /await listPurchases\(/);
  assert.match(source, /await loadDetail\(purchaseId\)/);
});

test('실제 API와 Mock 모두 철회 Operation을 제공한다', () => {
  assert.match(apiSource, /export function withdrawRefundRequest\(requestId\)/);
  assert.match(apiSource, /\/api\/v1\/refund-requests\/\$\{encodeURIComponent\(requestId\)\}\/withdrawals/);
  assert.match(mockSource, /async withdrawRefundRequest\(requestId\)/);
  assert.match(mockSource, /refund\.status = 'WITHDRAWN'/);
});
