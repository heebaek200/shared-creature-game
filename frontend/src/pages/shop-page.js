// @ts-check
import { createAppShell } from '../components/app-shell.js';
import { getShopItemImage } from '../features/avatar/render.js';
import { getShopItem, listShopItems } from '../features/shop/api.js';
import { createPurchase, verifyPurchasePayment } from '../features/payment/api.js';
import { parseShopItemId } from '../features/payment/payment-policy.js';
import { clearCheckoutContext, requestCheckout, saveCheckoutContext } from '../features/payment/portone-checkout.js';

const PAGE_SIZE = 12;
const SLOTS = ['', 'HEAD', 'FACE', 'BODY', 'ACCESSORY'];
const SLOT_LABELS = { '': '전체', HEAD: '머리', FACE: '얼굴', BODY: '상의', ACCESSORY: '액세서리' };

/** ACTIVE 상품 조회·직접 선택과 카카오페이 결제를 제공하는 상점 화면입니다. */
export function shopPage({ navigate, currentUser }) {
  const content = document.createElement('main');
  content.className = 'commerce-page';
  content.innerHTML = `
    <header class="page-header">
      <div><p class="eyebrow">SHOP</p><h1 class="h2 mb-1">아바타 상점</h1><p class="text-app-muted mb-0">카카오페이 테스트 결제로 아바타 아이템을 구매해 보세요.</p></div>
      <a class="btn btn-outline-primary" href="/payments" data-link>결제 내역</a>
    </header>
    <div class="alert d-none" role="status" aria-live="polite" data-feedback></div>
    <nav class="nav nav-pills commerce-tabs mb-4" aria-label="상품 슬롯" data-slots></nav>
    <div class="row g-4">
      <section class="col-xl-8 order-2 order-xl-1" aria-live="polite" data-list></section>
      <aside class="col-xl-4 order-1 order-xl-2" data-detail style="scroll-margin-top: calc(var(--app-header-height) + 12px)"></aside>
    </div>
    <div class="d-flex justify-content-between align-items-center mt-4" data-pagination>
      <button class="btn btn-outline-primary" type="button" data-action="prev">이전</button>
      <span class="small text-app-muted" data-page-info></span>
      <button class="btn btn-outline-primary" type="button" data-action="next">다음</button>
    </div>`;

  const element = createAppShell({ content, currentPath: '/shop', currentUser, navigate });
  const purchaseBlocker = document.createElement('div');
  purchaseBlocker.className = 'purchase-blocker d-none';
  purchaseBlocker.setAttribute('role', 'status');
  purchaseBlocker.setAttribute('aria-live', 'polite');
  purchaseBlocker.setAttribute('aria-hidden', 'true');
  purchaseBlocker.innerHTML = `
    <div class="purchase-blocker__panel">
      <span class="spinner-border text-primary" aria-hidden="true"></span>
      <strong>결제창을 준비하고 있습니다.</strong>
      <span class="small text-app-muted">잠시만 기다려 주세요.</span>
    </div>`;
  element.append(purchaseBlocker);
  const listNode = content.querySelector('[data-list]');
  const detailNode = content.querySelector('[data-detail]');
  const feedbackNode = content.querySelector('[data-feedback]');
  const slotsNode = content.querySelector('[data-slots]');
  const pageInfo = content.querySelector('[data-page-info]');
  const prevButton = content.querySelector('[data-action="prev"]');
  const nextButton = content.querySelector('[data-action="next"]');

  let items = [];
  let page = 0;
  let totalPages = 0;
  let slot = '';
  let selected = null;
  let loading = false;
  let purchasing = false;
  let mounted = true;
  let loadVersion = 0;

  /** 결제 처리 중에는 공통 화면 조작을 막고 진행 상태를 표시합니다. */
  function renderPurchasingState() {
    for (const child of element.children) {
      if (child instanceof HTMLElement && child !== purchaseBlocker) child.inert = purchasing;
    }
    element.setAttribute('aria-busy', String(purchasing));
    purchaseBlocker.classList.toggle('d-none', !purchasing);
    purchaseBlocker.setAttribute('aria-hidden', String(!purchasing));
  }

  function showFeedback(message, type = 'danger') {
    if (!(feedbackNode instanceof HTMLElement)) return;
    feedbackNode.className = `alert alert-${type}`;
    feedbackNode.textContent = message;
  }

  function renderSlots() {
    if (!(slotsNode instanceof HTMLElement)) return;
    slotsNode.replaceChildren(...SLOTS.map((value) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `nav-link${slot === value ? ' active' : ''}`;
      button.dataset.slot = value;
      button.textContent = SLOT_LABELS[value];
      button.setAttribute('aria-pressed', String(slot === value));
      return button;
    }));
  }

  function renderList() {
    if (!(listNode instanceof HTMLElement)) return;
    listNode.replaceChildren();
    if (items.length === 0) {
      listNode.innerHTML = '<div class="app-surface-card border border-app rounded-3 p-5 text-center"><h2 class="h5">판매 중인 상품이 없습니다.</h2><p class="text-app-muted mb-0">다른 슬롯을 선택하거나 잠시 후 다시 조회해 주세요.</p></div>';
      return;
    }
    const grid = document.createElement('div');
    grid.className = 'row g-3';
    for (const item of items) {
      const column = document.createElement('div');
      column.className = 'col-sm-6 col-lg-4';
      const card = document.createElement('article');
      card.className = `card app-surface-card border-app h-100 shop-card${selected?.shopItemId === item.shopItemId ? ' is-selected' : ''}`;
      const image = document.createElement('img');
      image.className = 'shop-card__image';
      image.src = getShopItemImage(item.slot, item.assetKey);
      image.alt = `${item.name} 미리보기`;
      const body = document.createElement('div');
      body.className = 'card-body d-flex flex-column';
      const badge = document.createElement('span');
      badge.className = 'badge text-bg-secondary align-self-start mb-2';
      badge.textContent = item.slot;
      const title = document.createElement('h2');
      title.className = 'h5';
      title.textContent = item.name;
      const price = document.createElement('p');
      price.className = 'fw-bold text-app-primary mt-auto mb-3';
      price.textContent = `${Number(item.priceKrw).toLocaleString('ko-KR')}원`;
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'btn btn-outline-primary stretched-link';
      button.dataset.selectItem = String(item.shopItemId);
      button.textContent = '상품 보기';
      body.append(badge, title, price, button);
      card.append(image, body);
      column.append(card);
      grid.append(column);
    }
    listNode.append(grid);
  }

  function renderDetail() {
    if (!(detailNode instanceof HTMLElement)) return;
    detailNode.replaceChildren();
    if (!selected) {
      detailNode.innerHTML = '<div class="app-surface-card border border-app rounded-3 p-4"><h2 class="h5">상품을 선택해 주세요.</h2><p class="text-app-muted mb-0">상품 설명과 결제 금액을 확인할 수 있습니다.</p></div>';
      return;
    }
    const card = document.createElement('section');
    card.className = 'card app-surface-card border-app commerce-sticky-card';
    const image = document.createElement('img');
    image.className = 'shop-detail__image';
    image.src = getShopItemImage(selected.slot, selected.assetKey);
    image.alt = `${selected.name} 상세 미리보기`;
    const body = document.createElement('div');
    body.className = 'card-body p-4';
    const title = document.createElement('h2'); title.className = 'h4'; title.tabIndex = -1; title.textContent = selected.name;
    const description = document.createElement('p'); description.className = 'text-app-muted'; description.textContent = selected.description || '상품 설명이 없습니다.';
    const price = document.createElement('p'); price.className = 'fs-4 fw-bold'; price.textContent = `${Number(selected.priceKrw).toLocaleString('ko-KR')}원`;
    const note = document.createElement('p'); note.className = 'small text-app-muted'; note.textContent = '결제 금액은 서버가 확정하며, 결제창 결과를 서버에서 다시 검증합니다.';
    const button = document.createElement('button');
    button.type = 'button'; button.className = 'btn btn-primary w-100'; button.dataset.action = 'purchase';
    button.disabled = purchasing; button.textContent = purchasing ? '결제 준비 중…' : '카카오페이로 결제';
    body.append(title, description, price, note, button); card.append(image, body); detailNode.append(card);
  }

  function renderPagination() {
    if (pageInfo) pageInfo.textContent = totalPages ? `${page + 1} / ${totalPages} 페이지` : '0 / 0 페이지';
    if (prevButton instanceof HTMLButtonElement) prevButton.disabled = loading || page <= 0;
    if (nextButton instanceof HTMLButtonElement) nextButton.disabled = loading || page >= totalPages - 1;
  }

  function syncItemQuery(itemId) {
    const url = new URL(location.href);
    if (itemId) url.searchParams.set('itemId', String(itemId));
    else url.searchParams.delete('itemId');
    history.replaceState({}, '', `${url.pathname}${url.search}${url.hash}`);
  }

  /** 모바일 레이아웃에서 선택한 상품의 상세 카드로 이동합니다. */
  function moveToDetailCard() {
    if (!(detailNode instanceof HTMLElement) || !window.matchMedia('(max-width: 1032px)').matches) return;
    const heading = detailNode.querySelector('h2');
    if (heading instanceof HTMLElement) heading.focus({ preventScroll: true });
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    detailNode.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' });
  }

  /**
   * 상품 상세를 조회하고 사용자가 모바일 목록에서 선택한 경우 상세 카드로 이동합니다.
   * @param {number} itemId 조회할 상품 ID
   * @param {boolean} [sync=true] URL Query에 선택 상품을 반영할지 여부
   * @param {boolean} [moveToDetail=false] 조회 후 상세 카드로 이동할지 여부
   */
  async function selectItem(itemId, sync = true, moveToDetail = false) {
    try {
      const item = await getShopItem(itemId);
      if (!mounted) return;
      selected = item;
      if (sync) syncItemQuery(item.shopItemId);
      renderList(); renderDetail();
      if (moveToDetail) moveToDetailCard();
    } catch (error) {
      if (!mounted) return;
      selected = null; syncItemQuery(null); renderList(); renderDetail();
      if (error?.status !== 404) showFeedback(error?.message ?? '상품 상세를 불러오지 못했습니다.');
    }
  }

  async function loadItems(targetPage = page) {
    const version = ++loadVersion;
    loading = true; renderPagination();
    if (listNode instanceof HTMLElement) listNode.innerHTML = '<div class="app-surface-card border border-app rounded-3 p-4 placeholder-glow"><span class="placeholder col-8 mb-3"></span><span class="placeholder col-12"></span></div>';
    try {
      const result = await listShopItems({ page: targetPage, size: PAGE_SIZE, slot: slot || undefined });
      if (!mounted || version !== loadVersion) return;
      items = result.data ?? []; page = result.meta?.page ?? targetPage; totalPages = result.meta?.totalPages ?? 0;
      renderList(); renderPagination();
    } catch (error) {
      if (!mounted || version !== loadVersion) return;
      if (error?.status === 401) navigate('/login');
      else showFeedback(error?.message ?? '상품 목록을 불러오지 못했습니다.');
      items = []; renderList();
    } finally {
      loading = false; renderPagination();
    }
  }

  async function purchaseSelected() {
    if (!selected || purchasing) return;
    if (!window.confirm(`${selected.name}을(를) ${Number(selected.priceKrw).toLocaleString('ko-KR')}원에 결제할까요?`)) return;
    purchasing = true; renderDetail(); renderPurchasingState();
    try {
      const order = await createPurchase(selected.shopItemId);
      if (!mounted) return;
      saveCheckoutContext(order);
      const returnUrl = new URL('/payments', location.origin);
      returnUrl.searchParams.set('purchaseId', String(order.purchaseId));
      const response = await requestCheckout(order, returnUrl.toString());
      if (!response || !mounted) return;
      if (response.paymentId !== order.payment.paymentId) throw new Error('결제 응답의 주문 정보가 일치하지 않습니다.');
      try {
        await verifyPurchasePayment(order.purchaseId, response.paymentId);
        clearCheckoutContext();
        navigate('/payments');
      } catch (error) {
        if (['PAYMENT_PENDING', 'PAYMENT_RESULT_UNKNOWN'].includes(error?.code)) {
          showFeedback('결제 결과를 확인 중입니다. 결제 내역에서 다시 확인해 주세요.', 'warning');
          navigate(`/payments?purchaseId=${order.purchaseId}&paymentId=${encodeURIComponent(response.paymentId)}`);
        } else {
          clearCheckoutContext();
          throw error;
        }
      }
    } catch (error) {
      if (!mounted) return;
      showFeedback(error?.message ?? '결제를 시작하지 못했습니다.');
    } finally {
      purchasing = false; renderDetail(); renderPurchasingState();
    }
  }

  async function onClick(event) {
    if (!(event.target instanceof Element)) return;
    const slotButton = event.target.closest('[data-slot]');
    if (slotButton instanceof HTMLButtonElement) {
      slot = slotButton.dataset.slot ?? ''; page = 0; renderSlots(); await loadItems(0); return;
    }
    const selectButton = event.target.closest('[data-select-item]');
    if (selectButton instanceof HTMLButtonElement) {
      const itemId = parseShopItemId(selectButton.dataset.selectItem); if (itemId) await selectItem(itemId, true, true); return;
    }
    const action = event.target.closest('[data-action]');
    if (!(action instanceof HTMLButtonElement)) return;
    if (action.dataset.action === 'purchase') await purchaseSelected();
    if (action.dataset.action === 'prev' && page > 0) await loadItems(page - 1);
    if (action.dataset.action === 'next' && page < totalPages - 1) await loadItems(page + 1);
  }

  content.addEventListener('click', onClick);
  renderSlots(); renderDetail(); renderPagination();
  void loadItems(0);
  const directItemId = parseShopItemId(new URLSearchParams(location.search).get('itemId'));
  if (directItemId) void selectItem(directItemId, false);
  else if (new URLSearchParams(location.search).has('itemId')) syncItemQuery(null);

  return { element, cleanup: () => { mounted = false; loadVersion += 1; content.removeEventListener('click', onClick); } };
}

export { shopPage as adminPage };
