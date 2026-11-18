// @ts-check
import {
  approveRefund,
  getAdminRefundRequest,
  listAdminRefundRequests,
  reconcileRefund,
  rejectRefund,
} from '../features/payment/api.js';
import {
  adminRefundActions,
  pgCancellationPresentation,
} from '../features/payment/payment-policy.js';

const PAGE_SIZE = 10;
const STATUSES = ['', 'REQUESTED', 'REJECTED', 'CANCEL_PENDING', 'CANCEL_UNKNOWN', 'COMPLETED', 'FAILED'];
const LABELS = { '': '전체', REQUESTED: '환불 요청', REJECTED: '거절', CANCEL_PENDING: '취소 확인 중', CANCEL_UNKNOWN: '결과 확인 필요', COMPLETED: '환불 완료', FAILED: '환불 실패' };

function formatDate(value) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

/** 전체 관리자의 환불 목록·상세·승인·거절·결과 복구 화면을 생성합니다. */
export function adminRefundsPage({ navigate } = {}) {
  const element = document.createElement('section');
  element.className = 'container-fluid px-3 px-md-4 py-4';
  element.innerHTML = `
    <header class="mb-4">
      <h1 class="h3 fw-bold">환불 관리</h1>
      <p class="text-body-secondary mb-0">미사용 상품의 환불 심사와 PG 취소 결과를 관리합니다.</p>
    </header>
    <section class="card mb-3">
      <div class="card-body">
        <form class="row g-3 align-items-end" data-filter-form>
          <div class="col-12 col-sm-6 col-md-4">
            <label class="form-label" for="refund-status">환불 상태</label>
            <select id="refund-status" class="form-select" data-status></select>
          </div>
          <div class="col-12 col-sm-auto">
            <button class="btn btn-primary" type="submit">조회</button>
            <button class="btn btn-outline-secondary" type="button" data-action="reload">새로고침</button>
          </div>
        </form>
      </div>
    </section>
    <div class="alert alert-info" role="status" aria-live="polite" data-feedback>환불 목록을 조회하는 중입니다.</div>
    <div class="row g-3">
      <section class="col-12 col-xl-7">
        <div class="card h-100">
          <div class="card-body">
            <h2 class="h5">환불 요청 목록</h2>
            <div class="table-responsive">
              <table class="table table-hover align-middle">
                <thead><tr><th scope="col">환불 ID</th><th scope="col">구매 ID</th><th scope="col">상태</th><th scope="col">신청</th><th scope="col">선택</th></tr></thead>
                <tbody data-list></tbody>
              </table>
            </div>
            <nav class="d-flex flex-wrap align-items-center justify-content-between gap-2" aria-label="환불 요청 페이지 이동">
              <button class="btn btn-outline-secondary btn-sm" type="button" data-action="prev">이전</button>
              <span class="small text-body-secondary" data-page-info></span>
              <button class="btn btn-outline-secondary btn-sm" type="button" data-action="next">다음</button>
            </nav>
          </div>
        </div>
      </section>
      <section class="col-12 col-xl-5" data-detail-section style="scroll-margin-top: calc(var(--app-header-height) + 12px)">
        <div class="card h-100">
          <div class="card-body" data-detail></div>
        </div>
      </section>
    </div>
    <dialog class="border-0 rounded-4 shadow p-0" data-dialog aria-labelledby="refund-dialog-title">
      <form class="p-4" style="width:min(90vw,440px)" data-action-form>
        <h2 id="refund-dialog-title" class="h5" data-dialog-title></h2>
        <label class="form-label" for="refund-decision-reason">처리 사유 (필수, 최대 500자)</label>
        <textarea id="refund-decision-reason" class="form-control mb-2" rows="5" maxlength="500" required data-reason></textarea>
        <div class="invalid-feedback">처리 사유를 1~500자로 입력해 주세요.</div>
        <div class="d-flex justify-content-end gap-2 mt-4"><button class="btn btn-outline-secondary" type="button" data-action="cancel">취소</button><button class="btn btn-primary" type="submit" data-confirm>확인</button></div>
      </form>
    </dialog>`;

  const listNode = element.querySelector('[data-list]');
  const detailNode = element.querySelector('[data-detail]');
  const feedback = element.querySelector('[data-feedback]');
  const statusInput = element.querySelector('[data-status]');
  const filterForm = element.querySelector('[data-filter-form]');
  const pageInfo = element.querySelector('[data-page-info]');
  const prevButton = element.querySelector('[data-action="prev"]');
  const nextButton = element.querySelector('[data-action="next"]');
  const dialog = element.querySelector('[data-dialog]');
  const actionForm = element.querySelector('[data-action-form]');
  const reasonInput = element.querySelector('[data-reason]');
  const dialogTitle = element.querySelector('[data-dialog-title]');

  let rows = [];
  let page = 0;
  let totalPages = 0;
  let status = '';
  let detail = null;
  let actionKind = null;
  let loading = false;
  let processing = false;
  let disposed = false;
  let listVersion = 0;
  let detailVersion = 0;

  if (statusInput instanceof HTMLSelectElement) {
    statusInput.replaceChildren(...STATUSES.map((value) => {
      const option = document.createElement('option'); option.value = value; option.textContent = LABELS[value]; return option;
    }));
  }

  function showFeedback(message, type = 'danger') {
    if (!(feedback instanceof HTMLElement)) return;
    feedback.className = `alert alert-${type}`; feedback.textContent = message;
  }

  function handleError(error, fallback) {
    if (error?.status === 401 && navigate) { navigate('/login'); return; }
    if (error?.status === 403 && navigate) { navigate('/403'); return; }
    showFeedback(error?.message ?? fallback);
  }

  function renderPagination() {
    if (pageInfo) pageInfo.textContent = totalPages ? `${page + 1} / ${totalPages} 페이지` : '0 / 0 페이지';
    if (prevButton instanceof HTMLButtonElement) prevButton.disabled = loading || processing || page <= 0;
    if (nextButton instanceof HTMLButtonElement) nextButton.disabled = loading || processing || page >= totalPages - 1;
  }

  function renderList() {
    if (!(listNode instanceof HTMLElement)) return;
    listNode.replaceChildren();
    if (!rows.length) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 5; cell.className = 'text-center text-body-secondary py-4'; cell.textContent = '해당 조건에 맞는 환불 요청이 없습니다.';
      row.append(cell); listNode.append(row); return;
    }
    for (const refund of rows) {
      const row = document.createElement('tr');
      if (detail?.refundRequestId === refund.refundRequestId) row.classList.add('table-active');
      const idCell = document.createElement('td'); idCell.textContent = String(refund.refundRequestId);
      const purchaseCell = document.createElement('td'); purchaseCell.textContent = String(refund.purchaseId);
      const statusCell = document.createElement('td');
      const state = document.createElement('span'); state.className = `badge ${['CANCEL_PENDING', 'CANCEL_UNKNOWN'].includes(refund.status) ? 'text-bg-warning' : refund.status === 'COMPLETED' ? 'text-bg-success' : 'text-bg-secondary'}`; state.textContent = LABELS[refund.status] ?? refund.status;
      statusCell.append(state);
      const dateCell = document.createElement('td'); dateCell.textContent = formatDate(refund.requestedAt);
      const actionCell = document.createElement('td');
      const button = document.createElement('button'); button.type = 'button'; button.className = 'btn btn-outline-primary btn-sm'; button.dataset.action = 'detail'; button.dataset.requestId = String(refund.refundRequestId); button.textContent = '상세'; button.disabled = processing;
      actionCell.append(button); row.append(idCell, purchaseCell, statusCell, dateCell, actionCell); listNode.append(row);
    }
  }

  function renderDetail() {
    if (!(detailNode instanceof HTMLElement)) return;
    detailNode.replaceChildren();
    const title = document.createElement('h2'); title.className = 'h5'; title.tabIndex = -1; title.textContent = detail ? `환불 #${detail.refundRequestId}` : '선택 환불 상세';
    detailNode.append(title);
    if (!detail) { const message = document.createElement('p'); message.className = 'text-body-secondary mb-0'; message.textContent = '목록에서 환불 요청을 선택해 주세요.'; detailNode.append(message); return; }
    const pgCancellation = pgCancellationPresentation(detail);
    const description = document.createElement('dl'); description.className = 'row small';
    const values = [
      ['상품', detail.purchase?.itemName], ['금액', `${Number(detail.purchase?.priceKrw ?? 0).toLocaleString('ko-KR')}원`],
      ['구매 상태', detail.purchase?.status], ['아이템 상태', detail.ownedItem?.status ?? '-'],
      ['환불 상태', LABELS[detail.status] ?? detail.status], ['신청 사유', detail.requestReason],
      ['관리자 사유', detail.decisionReason ?? '-'], ['PG 취소 상태', pgCancellation.label],
    ];
    for (const [term, value] of values) { const dt = document.createElement('dt'); dt.className = 'col-4'; dt.textContent = term; const dd = document.createElement('dd'); dd.className = 'col-8 text-break'; dd.textContent = String(value ?? '-'); description.append(dt, dd); }
    const actions = document.createElement('div'); actions.className = 'd-flex flex-wrap gap-2 mt-3';
    for (const action of adminRefundActions(detail.status)) {
      const button = document.createElement('button'); button.type = 'button'; button.dataset.action = action; button.className = action === 'approve' ? 'btn btn-primary' : action === 'reject' ? 'btn btn-outline-danger' : 'btn btn-warning'; button.textContent = action === 'approve' ? '승인' : action === 'reject' ? '거절' : '결과 복구'; button.disabled = processing; actions.append(button);
    }
    detailNode.append(description);
    if (pgCancellation.hint) {
      const hint = document.createElement('p');
      hint.className = 'alert alert-info small py-2 mb-0';
      hint.textContent = pgCancellation.hint;
      detailNode.append(hint);
    }
    detailNode.append(actions);
  }

  async function loadList(targetPage = page, targetStatus = status) {
    const version = ++listVersion; loading = true; renderPagination();
    showFeedback('환불 목록을 조회하는 중입니다.', 'info');
    if (listNode instanceof HTMLElement) listNode.innerHTML = '<tr><td colspan="5" class="text-center text-body-secondary py-4">환불 목록을 조회하는 중입니다.</td></tr>';
    try {
      const result = await listAdminRefundRequests({ page: targetPage, size: PAGE_SIZE, status: targetStatus || undefined });
      if (disposed || version !== listVersion) return false;
      rows = result.data ?? []; page = result.meta?.page ?? targetPage; totalPages = result.meta?.totalPages ?? 0; status = targetStatus;
      renderList(); renderPagination(); showFeedback(`환불 요청 ${rows.length}건을 표시했습니다.`, 'info'); return true;
    } catch (error) {
      if (!disposed && version === listVersion) handleError(error, '환불 목록을 불러오지 못했습니다.'); return false;
    } finally { loading = false; renderPagination(); }
  }

  async function loadDetail(requestId) {
    const version = ++detailVersion;
    if (detailNode instanceof HTMLElement) detailNode.innerHTML = '<h2 class="h5">선택 환불 상세</h2><p class="text-body-secondary mb-0">환불 상세를 조회하는 중입니다.</p>';
    try {
      const result = await getAdminRefundRequest(requestId);
      if (disposed || version !== detailVersion) return;
      detail = result; renderList(); renderDetail();
    } catch (error) { if (!disposed && version === detailVersion) { detail = null; renderDetail(); handleError(error, '환불 상세를 불러오지 못했습니다.'); } }
  }

  function openDialog(kind) {
    if (!(dialog instanceof HTMLDialogElement) || !(reasonInput instanceof HTMLTextAreaElement) || !detail) return;
    actionKind = kind; reasonInput.value = ''; reasonInput.classList.remove('is-invalid');
    if (dialogTitle) dialogTitle.textContent = kind === 'approve' ? '환불 승인' : '환불 거절';
    dialog.showModal(); reasonInput.focus();
  }

  async function submitAction(event) {
    event.preventDefault();
    if (!detail || !actionKind || processing || !(reasonInput instanceof HTMLTextAreaElement)) return;
    const reason = reasonInput.value.trim();
    if (!reason || reason.length > 500) { reasonInput.classList.add('is-invalid'); return; }
    processing = true; reasonInput.disabled = true; renderList(); renderDetail();
    try {
      const result = actionKind === 'approve' ? await approveRefund(detail.refundRequestId, reason) : await rejectRefund(detail.refundRequestId, reason);
      if (disposed) return;
      detail = result; dialog?.close(); showFeedback(actionKind === 'approve' ? '환불 승인 결과를 확인했습니다.' : '환불 요청을 거절했습니다.', 'success');
      await loadList(page); renderDetail();
    } catch (error) {
      if (!disposed) { handleError(error, '환불 요청을 처리하지 못했습니다.'); if (error?.status === 409) { await loadList(page); await loadDetail(detail.refundRequestId); } }
    } finally { processing = false; reasonInput.disabled = false; renderList(); renderDetail(); renderPagination(); }
  }

  async function runReconcile() {
    if (!detail || processing || !window.confirm('PG 취소 상태를 다시 조회할까요? 취소 요청을 재실행하지 않습니다.')) return;
    processing = true; renderList(); renderDetail();
    try {
      detail = await reconcileRefund(detail.refundRequestId);
      if (disposed) return;
      showFeedback(detail.status === 'COMPLETED' ? '환불 완료 상태를 확인했습니다.' : '최신 PG 취소 상태를 확인했습니다.', detail.status === 'COMPLETED' ? 'success' : 'warning');
      await loadList(page); renderDetail();
    } catch (error) {
      if (!disposed) { const unknown = error?.code === 'PG_CANCEL_RESULT_UNKNOWN'; showFeedback(unknown ? 'PG 취소 결과가 아직 확정되지 않았습니다.' : error?.message ?? '결과를 복구하지 못했습니다.', unknown ? 'warning' : 'danger'); await loadList(page); await loadDetail(detail.refundRequestId); }
    } finally { processing = false; renderList(); renderDetail(); renderPagination(); }
  }

  async function onClick(event) {
    if (!(event.target instanceof Element)) return;
    const button = event.target.closest('[data-action]'); if (!(button instanceof HTMLButtonElement) || button.disabled) return;
    const action = button.dataset.action;
    if (action === 'detail') await loadDetail(Number(button.dataset.requestId));
    if (action === 'approve' || action === 'reject') openDialog(action);
    if (action === 'reconcile') await runReconcile();
    if (action === 'reload') await loadList(page);
    if (action === 'prev' && page > 0) { detail = null; renderDetail(); await loadList(page - 1); }
    if (action === 'next' && page < totalPages - 1) { detail = null; renderDetail(); await loadList(page + 1); }
    if (action === 'cancel' && !processing) dialog?.close();
  }

  function onFilter(event) { event.preventDefault(); if (!loading && !processing && statusInput instanceof HTMLSelectElement) { detail = null; renderDetail(); void loadList(0, statusInput.value); } }

  element.addEventListener('click', onClick); filterForm?.addEventListener('submit', onFilter); actionForm?.addEventListener('submit', submitAction);
  renderDetail(); renderPagination(); void loadList();
  return { element, cleanup() { disposed = true; listVersion += 1; detailVersion += 1; if (dialog?.open) dialog.close(); element.removeEventListener('click', onClick); filterForm?.removeEventListener('submit', onFilter); actionForm?.removeEventListener('submit', submitAction); } };
}
