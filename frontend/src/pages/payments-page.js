// @ts-check
import { createAppShell } from '../components/app-shell.js';
import {
  createRefundRequest,
  getPurchase,
  listMyRefundRequests,
  listPurchases,
  verifyPurchasePayment,
} from '../features/payment/api.js';
import {
  canRequestRefund,
  isCurrentRequest,
  isDefinitivePurchaseStatus,
  parsePaymentReturn,
} from '../features/payment/payment-policy.js';
import { clearCheckoutContext, loadCheckoutContext } from '../features/payment/portone-checkout.js';

const PAGE_SIZE = 10;
const PURCHASE_LABELS = { READY: '결제 확인 중', PAID: '결제 완료', FAILED: '결제 실패', CANCELLED: '환불 완료' };
const ITEM_LABELS = { OWNED_UNUSED: '미사용', OWNED_USED: '사용됨', REVOKED: '회수됨' };
const REFUND_LABELS = { REQUESTED: '환불 요청', REJECTED: '환불 거절', CANCEL_PENDING: '취소 확인 중', CANCEL_UNKNOWN: '결과 확인 필요', COMPLETED: '환불 완료', FAILED: '환불 실패' };

function formatDate(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

function badge(text, kind = 'secondary') {
  const node = document.createElement('span');
  node.className = `badge text-bg-${kind}`;
  node.textContent = text;
  return node;
}

/** 구매·환불 내역, 결제 복귀 검증과 사용자 환불 요청을 처리합니다. */
export function paymentsPage({ navigate, currentUser }) {
  const content = document.createElement('main');
  content.className = 'commerce-page';
  content.innerHTML = `
    <header class="page-header">
      <div><p class="eyebrow">PAYMENTS</p><h1 class="h2 mb-1">결제·환불 내역</h1><p class="text-app-muted mb-0">서버에서 확인한 결제와 환불 상태를 조회합니다.</p></div>
      <a class="btn btn-outline-primary" href="/shop" data-link>상점으로</a>
    </header>
    <div class="alert d-none" role="status" aria-live="polite" data-feedback></div>
    <nav class="nav nav-pills commerce-tabs mb-4" role="tablist">
      <button class="nav-link active" type="button" data-tab="purchases" role="tab">구매 내역</button>
      <button class="nav-link" type="button" data-tab="refunds" role="tab">환불 요청</button>
    </nav>
    <div class="row g-4">
      <section class="col-xl-7" aria-live="polite" data-list></section>
      <aside class="col-xl-5" data-detail></aside>
    </div>
    <div class="d-flex justify-content-between align-items-center mt-4">
      <button class="btn btn-outline-primary" type="button" data-action="prev">이전</button>
      <span class="small text-app-muted" data-page-info></span>
      <button class="btn btn-outline-primary" type="button" data-action="next">다음</button>
    </div>
    <dialog class="commerce-dialog" data-refund-dialog>
      <form method="dialog" data-refund-form>
        <h2 class="h5">환불 요청</h2>
        <p class="text-app-muted">서버가 미사용 여부와 진행 중인 환불을 다시 확인합니다.</p>
        <label class="form-label" for="refund-reason">환불 사유</label>
        <textarea id="refund-reason" class="form-control" rows="5" maxlength="500" required data-refund-reason></textarea>
        <div class="invalid-feedback">환불 사유를 1~500자로 입력해 주세요.</div>
        <div class="d-flex justify-content-end gap-2 mt-4">
          <button class="btn btn-outline-secondary" type="button" data-action="cancel-refund">취소</button>
          <button class="btn btn-primary" type="submit" data-action="submit-refund">환불 요청</button>
        </div>
      </form>
    </dialog>`;

  const element = createAppShell({ content, currentPath: '/payments', currentUser, navigate });
  const listNode = content.querySelector('[data-list]');
  const detailNode = content.querySelector('[data-detail]');
  const feedbackNode = content.querySelector('[data-feedback]');
  const pageInfo = content.querySelector('[data-page-info]');
  const prevButton = content.querySelector('[data-action="prev"]');
  const nextButton = content.querySelector('[data-action="next"]');
  const dialog = content.querySelector('[data-refund-dialog]');
  const refundForm = content.querySelector('[data-refund-form]');
  const reasonInput = content.querySelector('[data-refund-reason]');

  let tab = 'purchases';
  let page = 0;
  let totalPages = 0;
  let rows = [];
  let selectedPurchase = null;
  let refundPurchaseId = null;
  let loading = false;
  let processing = false;
  let mounted = true;
  let loadVersion = 0;
  let detailVersion = 0;

  function showFeedback(message, type = 'danger') {
    if (!(feedbackNode instanceof HTMLElement)) return;
    feedbackNode.className = `alert alert-${type}`;
    feedbackNode.textContent = message;
  }

  function renderPagination() {
    if (pageInfo) pageInfo.textContent = totalPages ? `${page + 1} / ${totalPages} 페이지` : '0 / 0 페이지';
    if (prevButton instanceof HTMLButtonElement) prevButton.disabled = loading || processing || page <= 0;
    if (nextButton instanceof HTMLButtonElement) nextButton.disabled = loading || processing || page >= totalPages - 1;
  }

  function renderRows() {
    if (!(listNode instanceof HTMLElement)) return;
    listNode.replaceChildren();
    if (!rows.length) {
      listNode.innerHTML = `<div class="app-surface-card border border-app rounded-3 p-5 text-center"><h2 class="h5">${tab === 'purchases' ? '구매 내역' : '환불 요청'}이 없습니다.</h2><p class="text-app-muted mb-0">${tab === 'purchases' ? '상점에서 상품을 구매하면 여기에 표시됩니다.' : '환불을 요청하면 처리 상태를 확인할 수 있습니다.'}</p></div>`;
      return;
    }
    const wrapper = document.createElement('div');
    wrapper.className = 'd-grid gap-3';
    for (const row of rows) wrapper.append(tab === 'purchases' ? purchaseCard(row) : refundCard(row));
    listNode.append(wrapper);
  }

  function purchaseCard(purchase) {
    const card = document.createElement('article'); card.className = 'card app-surface-card border-app';
    const body = document.createElement('div'); body.className = 'card-body p-4';
    const header = document.createElement('div'); header.className = 'd-flex justify-content-between align-items-start gap-3';
    const heading = document.createElement('div');
    const title = document.createElement('h2'); title.className = 'h5 mb-1'; title.textContent = purchase.itemName;
    const order = document.createElement('p'); order.className = 'small text-app-muted mb-0 text-break'; order.textContent = `주문 ${purchase.orderNumber}`;
    heading.append(title, order); header.append(heading, badge(PURCHASE_LABELS[purchase.status] ?? purchase.status, purchase.status === 'PAID' ? 'success' : purchase.status === 'FAILED' ? 'danger' : 'secondary'));
    const status = document.createElement('div'); status.className = 'd-flex flex-wrap gap-2 my-3';
    if (purchase.ownedItemStatus) status.append(badge(ITEM_LABELS[purchase.ownedItemStatus] ?? purchase.ownedItemStatus));
    if (purchase.latestRefundStatus) status.append(badge(REFUND_LABELS[purchase.latestRefundStatus] ?? purchase.latestRefundStatus, ['CANCEL_PENDING', 'CANCEL_UNKNOWN'].includes(purchase.latestRefundStatus) ? 'warning' : 'secondary'));
    const meta = document.createElement('p'); meta.className = 'mb-3'; meta.textContent = `${Number(purchase.priceKrw).toLocaleString('ko-KR')}원 · ${formatDate(purchase.createdAt)}`;
    const actions = document.createElement('div'); actions.className = 'd-flex flex-wrap gap-2';
    actions.append(actionButton('상세 보기', 'detail', purchase.purchaseId, 'outline-primary'));
    if (canRequestRefund(purchase)) actions.append(actionButton('환불 요청', 'refund', purchase.purchaseId, 'primary'));
    if (purchase.status === 'READY') actions.append(actionButton('결제 상태 확인', 'verify', purchase.purchaseId, 'outline-warning'));
    body.append(header, status, meta, actions); card.append(body); return card;
  }

  function refundCard(refund) {
    const card = document.createElement('article'); card.className = 'card app-surface-card border-app';
    const body = document.createElement('div'); body.className = 'card-body p-4';
    const header = document.createElement('div'); header.className = 'd-flex justify-content-between gap-3';
    const title = document.createElement('h2'); title.className = 'h5'; title.textContent = `환불 요청 #${refund.refundRequestId}`;
    header.append(title, badge(REFUND_LABELS[refund.status] ?? refund.status, ['CANCEL_PENDING', 'CANCEL_UNKNOWN'].includes(refund.status) ? 'warning' : refund.status === 'COMPLETED' ? 'success' : 'secondary'));
    const reason = document.createElement('p'); reason.textContent = refund.requestReason;
    const meta = document.createElement('p'); meta.className = 'small text-app-muted mb-0'; meta.textContent = `주문 #${refund.purchaseId} · ${formatDate(refund.requestedAt)}`;
    body.append(header, reason, meta); card.append(body); return card;
  }

  function actionButton(label, action, id, kind) {
    const button = document.createElement('button'); button.type = 'button'; button.className = `btn btn-${kind}`;
    button.dataset.action = action; button.dataset.purchaseId = String(id); button.textContent = label; button.disabled = processing; return button;
  }

  function renderDetail(purchase = selectedPurchase) {
    if (!(detailNode instanceof HTMLElement)) return;
    detailNode.replaceChildren();
    if (!purchase) {
      detailNode.innerHTML = '<div class="app-surface-card border border-app rounded-3 p-4"><h2 class="h5">구매 상세</h2><p class="text-app-muted mb-0">구매 내역에서 상세 보기를 선택해 주세요.</p></div>';
      return;
    }
    const card = document.createElement('section'); card.className = 'card app-surface-card border-app commerce-sticky-card';
    const body = document.createElement('div'); body.className = 'card-body p-4';
    const title = document.createElement('h2'); title.className = 'h4'; title.textContent = purchase.itemName;
    const summary = document.createElement('p'); summary.className = 'text-app-muted text-break'; summary.textContent = `주문 ${purchase.orderNumber}`;
    const statuses = document.createElement('div'); statuses.className = 'd-flex flex-wrap gap-2 mb-4';
    statuses.append(badge(PURCHASE_LABELS[purchase.status] ?? purchase.status));
    if (purchase.ownedItemStatus) statuses.append(badge(ITEM_LABELS[purchase.ownedItemStatus] ?? purchase.ownedItemStatus));
    const txTitle = document.createElement('h3'); txTitle.className = 'h6'; txTitle.textContent = 'PG 거래 이력';
    const txList = document.createElement('ul'); txList.className = 'list-group list-group-flush mb-4';
    for (const tx of purchase.transactions ?? []) {
      const li = document.createElement('li'); li.className = 'list-group-item px-0 bg-transparent'; li.textContent = `${tx.transactionType} · ${tx.status} · ${formatDate(tx.processedAt)}`; txList.append(li);
    }
    if (!txList.children.length) { const li = document.createElement('li'); li.className = 'list-group-item px-0 bg-transparent text-app-muted'; li.textContent = '거래 이력이 없습니다.'; txList.append(li); }
    const refundTitle = document.createElement('h3'); refundTitle.className = 'h6'; refundTitle.textContent = '환불 이력';
    const refundList = document.createElement('ul'); refundList.className = 'list-group list-group-flush';
    for (const refund of purchase.refundRequests ?? []) {
      const li = document.createElement('li'); li.className = 'list-group-item px-0 bg-transparent'; li.textContent = `${REFUND_LABELS[refund.status] ?? refund.status} · ${refund.requestReason}`; refundList.append(li);
    }
    if (!refundList.children.length) { const li = document.createElement('li'); li.className = 'list-group-item px-0 bg-transparent text-app-muted'; li.textContent = '환불 이력이 없습니다.'; refundList.append(li); }
    body.append(title, summary, statuses, txTitle, txList, refundTitle, refundList); card.append(body); detailNode.append(card);
  }

  async function loadRows(targetPage = page) {
    const version = ++loadVersion; loading = true; renderPagination();
    if (listNode instanceof HTMLElement) listNode.innerHTML = '<div class="app-surface-card border border-app rounded-3 p-4 placeholder-glow"><span class="placeholder col-8 mb-3"></span><span class="placeholder col-12"></span></div>';
    try {
      const result = tab === 'purchases'
        ? await listPurchases({ page: targetPage, size: PAGE_SIZE })
        : await listMyRefundRequests({ page: targetPage, size: PAGE_SIZE });
      if (!mounted || version !== loadVersion) return;
      rows = result.data ?? []; page = result.meta?.page ?? targetPage; totalPages = result.meta?.totalPages ?? 0;
      renderRows(); renderPagination();
    } catch (error) {
      if (!mounted || version !== loadVersion) return;
      if (error?.status === 401) navigate('/login'); else showFeedback(error?.message ?? '내역을 불러오지 못했습니다.');
      rows = []; renderRows();
    } finally { loading = false; renderPagination(); }
  }

  async function loadDetail(purchaseId) {
    const version = ++detailVersion;
    if (detailNode instanceof HTMLElement) detailNode.innerHTML = '<div class="app-surface-card border border-app rounded-3 p-4 placeholder-glow"><span class="placeholder col-8"></span></div>';
    try {
      const purchase = await getPurchase(purchaseId);
      if (!isCurrentRequest(version, detailVersion, mounted)) return;
      selectedPurchase = purchase; renderDetail();
    } catch (error) {
      if (!isCurrentRequest(version, detailVersion, mounted)) return;
      showFeedback(error?.message ?? '구매 상세를 불러오지 못했습니다.'); selectedPurchase = null; renderDetail();
    }
  }

  async function verifyPurchase(purchaseId, paymentId) {
    processing = true; renderRows(); renderPagination();
    try {
      const result = await verifyPurchasePayment(purchaseId, paymentId);
      if (!mounted) return;
      showFeedback(result.status === 'PAID' ? '서버 결제 검증이 완료되었습니다.' : '최신 결제 상태를 확인했습니다.', result.status === 'PAID' ? 'success' : 'warning');
      await loadRows(0); await loadDetail(purchaseId);
      if (isDefinitivePurchaseStatus(result.status)) clearCheckoutContext();
    } catch (error) {
      if (!mounted) return;
      const uncertain = ['PAYMENT_PENDING', 'PAYMENT_RESULT_UNKNOWN'].includes(error?.code);
      showFeedback(uncertain ? '결제 결과가 아직 확정되지 않았습니다. 잠시 후 다시 확인해 주세요.' : error?.message ?? '결제 상태를 확인하지 못했습니다.', uncertain ? 'warning' : 'danger');
      await loadRows(0);
    } finally { processing = false; renderRows(); renderPagination(); }
  }

  async function processPaymentReturn() {
    const returned = parsePaymentReturn(location.href);
    if (!returned) return;
    const context = loadCheckoutContext();
    let purchaseId = returned.purchaseId ?? (context?.paymentId === returned.paymentId ? Number(context.purchaseId) : null);
    if (!purchaseId) {
      try {
        const result = await listPurchases({ page: 0, size: 50 });
        purchaseId = result.data?.find((purchase) => purchase.orderNumber === returned.paymentId)?.purchaseId ?? null;
      } catch {
        purchaseId = null;
      }
    }
    history.replaceState({}, '', '/payments');
    if (!purchaseId) {
      showFeedback('복귀한 결제의 주문 정보를 찾지 못했습니다. 구매 내역을 새로고침해 주세요.', 'warning');
      return;
    }
    if (returned.code) showFeedback(returned.message || '결제창에서 결제가 완료되지 않았습니다.', 'warning');
    await verifyPurchase(purchaseId, returned.paymentId);
  }

  function openRefundDialog(purchaseId) {
    if (!(dialog instanceof HTMLDialogElement) || !(reasonInput instanceof HTMLTextAreaElement)) return;
    refundPurchaseId = purchaseId; reasonInput.value = ''; reasonInput.classList.remove('is-invalid'); dialog.showModal(); reasonInput.focus();
  }

  async function submitRefund(event) {
    event.preventDefault();
    if (processing || !refundPurchaseId || !(reasonInput instanceof HTMLTextAreaElement)) return;
    const reason = reasonInput.value.trim();
    if (!reason || reason.length > 500) { reasonInput.classList.add('is-invalid'); return; }
    processing = true; reasonInput.disabled = true;
    try {
      await createRefundRequest(refundPurchaseId, reason);
      if (!mounted) return;
      dialog?.close(); showFeedback('환불 요청을 접수했습니다.', 'success');
      await loadRows(0); await loadDetail(refundPurchaseId);
    } catch (error) {
      if (!mounted) return;
      showFeedback(error?.message ?? '환불을 요청하지 못했습니다.');
      if (error?.status === 409) { await loadRows(0); await loadDetail(refundPurchaseId); }
    } finally { processing = false; reasonInput.disabled = false; renderRows(); renderPagination(); }
  }

  async function onClick(event) {
    if (!(event.target instanceof Element)) return;
    const tabButton = event.target.closest('[data-tab]');
    if (tabButton instanceof HTMLButtonElement && !loading && !processing) {
      detailVersion += 1; tab = tabButton.dataset.tab ?? 'purchases'; page = 0; selectedPurchase = null; renderDetail();
      content.querySelectorAll('[data-tab]').forEach((button) => button.classList.toggle('active', button === tabButton));
      await loadRows(0); return;
    }
    const button = event.target.closest('[data-action]');
    if (!(button instanceof HTMLButtonElement) || button.disabled) return;
    const purchaseId = Number(button.dataset.purchaseId);
    if (button.dataset.action === 'detail' && purchaseId) await loadDetail(purchaseId);
    if (button.dataset.action === 'refund' && purchaseId) openRefundDialog(purchaseId);
    if (button.dataset.action === 'verify' && purchaseId) {
      const purchase = rows.find((row) => row.purchaseId === purchaseId); if (purchase) await verifyPurchase(purchaseId, purchase.orderNumber);
    }
    if (button.dataset.action === 'prev' && page > 0) { detailVersion += 1; selectedPurchase = null; renderDetail(); await loadRows(page - 1); }
    if (button.dataset.action === 'next' && page < totalPages - 1) { detailVersion += 1; selectedPurchase = null; renderDetail(); await loadRows(page + 1); }
    if (button.dataset.action === 'cancel-refund' && !processing) dialog?.close();
  }

  content.addEventListener('click', onClick);
  refundForm?.addEventListener('submit', submitRefund);
  renderDetail(); renderPagination();
  void (async () => { await processPaymentReturn(); if (mounted) await loadRows(0); })();

  return { element, cleanup: () => { mounted = false; loadVersion += 1; detailVersion += 1; if (dialog?.open) dialog.close(); content.removeEventListener('click', onClick); refundForm?.removeEventListener('submit', submitRefund); } };
}

export { paymentsPage as adminPage };
