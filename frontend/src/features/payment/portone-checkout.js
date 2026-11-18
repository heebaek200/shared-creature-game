// @ts-check
import * as PortOne from '@portone/browser-sdk/v2';

const CONTEXT_KEY = 'shared-creature-payment-context';
const useMock = import.meta.env.VITE_USE_MOCK_API === 'true';

/** 서버가 발급한 공개 설정에 모바일 복귀 URL만 추가합니다. */
export function createCheckoutRequest(order, redirectUrl) {
  return {
    ...order.payment,
    redirectUrl,
  };
}

/** PortOne V2 결제창을 서버 주문 정보로 호출합니다. */
export function requestCheckout(order, redirectUrl) {
  if (useMock) {
    return Promise.resolve({
      transactionType: 'PAYMENT',
      txId: `mock-${order.payment.paymentId}`,
      paymentId: order.payment.paymentId,
    });
  }
  return PortOne.requestPayment(createCheckoutRequest(order, redirectUrl));
}

/** Redirect 뒤 서버 검증에 사용할 주문 문맥을 현재 Tab에 보존합니다. */
export function saveCheckoutContext(order) {
  sessionStorage.setItem(CONTEXT_KEY, JSON.stringify({
    purchaseId: order.purchaseId,
    paymentId: order.payment.paymentId,
  }));
}

/** 현재 Tab에 보존된 결제 문맥을 안전하게 읽습니다. */
export function loadCheckoutContext() {
  try {
    const value = JSON.parse(sessionStorage.getItem(CONTEXT_KEY) ?? 'null');
    if (!value?.purchaseId || !value?.paymentId) return null;
    return value;
  } catch {
    return null;
  }
}

/** 검증이 끝난 결제 문맥을 제거합니다. */
export function clearCheckoutContext() {
  sessionStorage.removeItem(CONTEXT_KEY);
}
