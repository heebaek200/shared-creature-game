import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const pageSource = readFileSync(new URL('../src/pages/payments-page.js', import.meta.url), 'utf8');
const apiSource = readFileSync(new URL('../src/features/payment/api.js', import.meta.url), 'utf8');
const mockSource = readFileSync(new URL('../src/mocks/mock-api.js', import.meta.url), 'utf8');

/** 소스에서 지정한 함수의 중괄호 범위를 추출합니다. */
function functionSource(name) {
  const signature = `function ${name}`;
  const start = pageSource.indexOf(signature);
  assert.notEqual(start, -1, `${name} 함수를 찾을 수 없습니다.`);
  const bodyStart = pageSource.indexOf('{', start);
  let depth = 0;
  for (let index = bodyStart; index < pageSource.length; index += 1) {
    if (pageSource[index] === '{') depth += 1;
    if (pageSource[index] === '}') depth -= 1;
    if (depth === 0) return pageSource.slice(start, index + 1);
  }
  throw new Error(`${name} 함수 범위를 찾을 수 없습니다.`);
}

test('환불 요청 카드의 REQUESTED 상태에만 철회 동작을 연결한다', () => {
  assert.match(pageSource, /canWithdrawRefund\(refund\)/);
  assert.match(pageSource, /data-withdraw-dialog/);
  assert.match(pageSource, /withdraw\.dataset\.requestId/);
});

test('구매 상세는 철회 요청에만 기존 포맷으로 철회 시각을 표시한다', () => {
  const renderDetail = functionSource('renderDetail');
  assert.match(renderDetail, /refund\.status === 'WITHDRAWN'/);
  assert.match(renderDetail, /formatDate\(refund\.withdrawnAt\)/);
  assert.doesNotMatch(renderDetail, /actionButton\([^\n]*withdraw/);
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

test('409 동시 변경은 오류를 알리고 최신 목록과 상세를 다시 조회한다', () => {
  const submitWithdrawal = functionSource('submitWithdrawal');
  assert.match(submitWithdrawal, /error\?\.status === 409/);
  assert.match(submitWithdrawal, /await loadRows\(0\)/);
  assert.match(submitWithdrawal, /await loadDetail\(purchaseId\)/);
});
