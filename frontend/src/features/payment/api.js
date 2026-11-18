// @ts-check
import { apiRequest, apiRequestWithMeta } from '../../api/client.js';
import { mockApi } from '../../mocks/mock-api.js';

const useMock = import.meta.env.VITE_USE_MOCK_API === 'true';

function queryString({ page = 0, size = 20, status } = {}) {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) query.set('status', status);
  return query.toString();
}

/** 멱등 요청에 사용할 새 UUID를 생성합니다. */
export function newIdempotencyKey() {
  return crypto.randomUUID();
}

/** 서버 가격을 기준으로 결제 대기 주문을 생성합니다. */
export function createPurchase(shopItemId, idempotencyKey = newIdempotencyKey()) {
  if (useMock) return mockApi.createPurchase(shopItemId, idempotencyKey);
  return apiRequest('/api/v1/purchases', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: { shopItemId },
  });
}

/** PortOne 최신 거래를 조회해 구매 상태를 서버에서 확정합니다. */
export function verifyPurchasePayment(purchaseId, paymentId) {
  if (useMock) return mockApi.verifyPurchasePayment(purchaseId, paymentId);
  return apiRequest(`/api/v1/purchases/${encodeURIComponent(purchaseId)}/payment-verifications`, {
    method: 'POST',
    body: { paymentId },
  });
}

/** 내 구매 목록과 페이지 정보를 조회합니다. */
export function listPurchases(filters = {}) {
  if (useMock) return mockApi.listPurchases(filters);
  return apiRequestWithMeta(`/api/v1/purchases?${queryString(filters)}`);
}

/** 구매의 거래·환불 상세를 조회합니다. */
export function getPurchase(purchaseId) {
  if (useMock) return mockApi.getPurchase(purchaseId);
  return apiRequest(`/api/v1/purchases/${encodeURIComponent(purchaseId)}`);
}

/** 미사용 구매의 환불을 요청합니다. */
export function createRefundRequest(purchaseId, reason, idempotencyKey = newIdempotencyKey()) {
  if (useMock) return mockApi.createRefundRequest(purchaseId, reason, idempotencyKey);
  return apiRequest(`/api/v1/purchases/${encodeURIComponent(purchaseId)}/refund-requests`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: { reason },
  });
}

/** 내 환불 요청 목록과 페이지 정보를 조회합니다. */
export function listMyRefundRequests(filters = {}) {
  if (useMock) return mockApi.listMyRefundRequests(filters);
  return apiRequestWithMeta(`/api/v1/refund-requests?${queryString(filters)}`);
}

/** 전체 관리자의 환불 요청 목록을 조회합니다. */
export function listAdminRefundRequests(filters = {}) {
  if (useMock) return mockApi.listAdminRefundRequests(filters);
  return apiRequestWithMeta(`/api/v1/admin/refund-requests?${queryString(filters)}`);
}

/** 관리자 환불 상세를 조회합니다. */
export function getAdminRefundRequest(requestId) {
  if (useMock) return mockApi.getAdminRefundRequest(requestId);
  return apiRequest(`/api/v1/admin/refund-requests/${encodeURIComponent(requestId)}`);
}

/** 환불을 승인하고 PG 전체 취소를 요청합니다. */
export function approveRefund(requestId, decisionReason, idempotencyKey = newIdempotencyKey()) {
  if (useMock) return mockApi.approveRefund(requestId, decisionReason, idempotencyKey);
  return apiRequest(`/api/v1/admin/refund-requests/${encodeURIComponent(requestId)}/approvals`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: { decisionReason },
  });
}

/** 환불 요청을 관리자 판단으로 거절합니다. */
export function rejectRefund(requestId, decisionReason) {
  if (useMock) return mockApi.rejectRefund(requestId, decisionReason);
  return apiRequest(`/api/v1/admin/refund-requests/${encodeURIComponent(requestId)}/rejections`, {
    method: 'POST',
    body: { decisionReason },
  });
}

/** 미확정 PG 취소 상태를 재조회합니다. */
export function reconcileRefund(requestId, idempotencyKey = newIdempotencyKey()) {
  if (useMock) return mockApi.reconcileRefund(requestId, idempotencyKey);
  return apiRequest(`/api/v1/admin/refund-requests/${encodeURIComponent(requestId)}/reconciliations`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}
