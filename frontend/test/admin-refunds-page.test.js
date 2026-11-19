import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('../src/pages/admin-refunds-page.js', import.meta.url), 'utf8');

/** 소스에서 지정한 비동기 함수의 중괄호 범위를 추출합니다. */
function asyncFunctionSource(name) {
  const signature = `async function ${name}()`;
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `${name} 함수를 찾을 수 없습니다.`);
  const bodyStart = source.indexOf('{', start);
  let depth = 0;
  for (let index = bodyStart; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    if (source[index] === '}') depth -= 1;
    if (depth === 0) return source.slice(start, index + 1);
  }
  throw new Error(`${name} 함수 범위를 찾을 수 없습니다.`);
}

test('결과 복구 알림은 목록과 상세를 갱신한 뒤 최종 표시한다', () => {
  const runReconcile = asyncFunctionSource('runReconcile');
  const listRefresh = runReconcile.lastIndexOf('await loadList(page)');
  const detailRefresh = runReconcile.lastIndexOf('await loadDetail(requestId)');
  const finalFeedback = runReconcile.lastIndexOf('showFeedback(feedbackResult.message, feedbackResult.type)');

  assert.ok(listRefresh >= 0);
  assert.ok(detailRefresh > listRefresh);
  assert.ok(finalFeedback > detailRefresh);
  assert.match(runReconcile, /feedbackResult = adminRefundFeedback\(detail\.status\)/);
  assert.match(runReconcile, /PG_CANCEL_RESULT_UNKNOWN/);
  assert.match(runReconcile, /unknown \? 'warning' : 'danger'/);
});
