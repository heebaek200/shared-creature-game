import {createAppShell} from '../components/app-shell.js';
import defaultAvatarUrl from '../../assets/avatar/stop-left-default-1.png';
import {getAvatar, listOwnedItems, equipAvatarItem, unequipAvatarItem} from '../features/avatar/api.js';
import { Modal } from 'bootstrap';
import {
    applyItemThumbnails,
    renderAvatarEquipment,
    renderOwnedItem,
    renderSlot,
} from '../features/avatar/render.js';

export function avatarPage({navigate, currentUser}) {
    const content = document.createElement('div');
    content.className = 'avatar-page';

    content.innerHTML = `
        <div class="avatar-page-heading d-flex align-items-center gap-3 mb-4">
            <h1 class="h4 fw-bold mb-0">내 아바타</h1>
            <div id="avatar-resync-area" hidden>
                <button id="avatar-resync-button" type="button" class="btn avatar-action btn-sm">
                    새로고침
                </button>
            </div>
        </div>
        
        <div
            class="modal fade"
            id="avatar-result-modal"
            tabindex="-1"
            aria-labelledby="avatar-result-title"
            aria-hidden="true"
        >
            <div class="modal-dialog modal-dialog-centered">
                <div class="modal-content avatar-result-modal">
                    <div class="modal-header">
                        <h2 id="avatar-result-title" class="modal-title fs-5">
                            알림
                        </h2>
                    </div>
        
                    <div class="modal-body">
                        <p id="avatar-result-message" class="mb-0"></p>
                    </div>
        
                    <div class="modal-footer">
                        <button type="button" class="btn avatar-action" data-bs-dismiss="modal">
                            확인
                        </button>
                    </div>
                </div>
            </div>
        </div>        
        
        <div class="modal fade" id="avatar-first-use-modal" tabindex="-1"
            aria-labelledby="avatar-first-use-title" aria-describedby="avatar-first-use-message" aria-hidden="true">
            <div class="modal-dialog modal-dialog-centered">
                <div class="modal-content avatar-result-modal">
                    <div class="modal-header">
                        <h2 id="avatar-first-use-title" class="modal-title fs-5">
                            최초 장착 안내
                        </h2>
                    </div>
        
                    <div class="modal-body">
                        <p id="avatar-first-use-message"></p>
                        <p class="mb-0">
                            이후 장착을 해제해도 환불할 수 없습니다.<br>
                            장착하시겠습니까?
                        </p>
                    </div>
        
                    <div class="modal-footer">
                        <button type="button" class="btn avatar-action" id="avatar-first-use-confirm">
                            확인
                        </button>

                        <button type="button" class="btn avatar-action" data-bs-dismiss="modal">
                            취소
                        </button>
                    </div>
                </div>
            </div>
        </div>
        
        <p id="avatar-feedback" class="avatar-muted small mb-3" role="status" aria-live="polite"></p>
        

        <div class="row g-3 align-items-stretch avatar-layout">
            <!-- 장착 슬롯 -->
            <section class="col-6 col-xl-4" aria-labelledby="avatar-slots-title">
                <div class="avatar-panel border rounded-4 p-3 p-md-4 h-100">
                    <h2 id="avatar-slots-title" class="h6 fw-bold mb-4">
                        ITEM SLOT
                    </h2>

                    <ul id="avatar-slot-list" class="list-unstyled d-flex flex-column gap-3 mb-0">
                    </ul>
                </div>
            </section>

            <!-- 아바타 -->
            <section class="col-6 col-xl-4" aria-labelledby="avatar-preview-title">
                <div class="avatar-panel border rounded-4 p-3 p-md-4 h-100 d-flex flex-column">
                    <h2 id="avatar-preview-title" class="h6 fw-bold mb-4">
                        AVATAR
                    </h2>

                    <div class="avatar-preview rounded-4 flex-grow-1
                                d-flex flex-column align-items-center
                                justify-content-center gap-4 py-4 px-2">
                        <div id="avatar-preview-stage" class="avatar-preview__stage"
                            role="img" aria-label="기본 아바타">
                            <img src="${defaultAvatarUrl}" class="avatar-preview__image" alt="" width="320" height="320">
                            <div id="avatar-equipment-layers" aria-hidden="true"></div>
                        </div>

                        <p class="avatar-muted small text-center mb-0">
                            <span>아이템을 장착하면</span>
                            <span>외형이 변경됩니다.</span>
                        </p>
                        <p id="avatar-image-feedback" class="avatar-muted small text-center mb-0"
                           role="status" aria-live="polite" hidden></p>
                    </div>
                </div>
            </section>

            <!-- 보유 아이템 -->
            <section class="col-12 col-xl-4" aria-labelledby="avatar-items-title">
                <div class="avatar-panel avatar-inventory-panel border rounded-4 p-3 p-md-4 h-100 d-flex flex-column">
                    <h2 id="avatar-items-title" class="h6 fw-bold mb-4">
                        ITEM INVENTORY
                    </h2>

                    <div id="avatar-status-filter" class="d-flex flex-wrap gap-2 mb-4"
                        role="group" aria-label="아이템 사용 상태">
                        <button type="button" class="btn btn-sm avatar-status-button"
                            data-status="" aria-pressed="true">
                            전체
                        </button>
                    
                        <button type="button" class="btn btn-sm avatar-status-button"
                            data-status="OWNED_UNUSED" aria-pressed="false">
                            미사용
                        </button>
                    
                        <button type="button" class="btn btn-sm avatar-status-button"
                            data-status="OWNED_USED" aria-pressed="false">
                            사용
                        </button>
                    
                        <button type="button" class="btn btn-sm avatar-status-button"
                            data-status="REVOKED" aria-pressed="false">
                            회수
                        </button>
                    </div>
                    
                    <div class="d-flex align-items-center gap-2 mb-3">
                        <label for="avatar-slot-filter" class="visually-hidden">
                            아이템 부위
                        </label>
                    
                        <select id="avatar-slot-filter"
                            class="form-select form-select-sm avatar-filter flex-grow-1"
                        >
                            <option value="">ALL</option>
                            <option value="HEAD">HEAD</option>
                            <option value="FACE">FACE</option>
                            <option value="BODY">BODY</option>
                            <option value="ACCESSORY">ACCESSORY</option>
                        </select>
                    
                        <button id="avatar-show-all" type="button" class="btn avatar-action btn-sm flex-shrink-0">
                            초기화
                        </button>
                    </div>
                    
                    <p id="avatar-items-feedback" class="avatar-muted small" role="status" aria-live="polite"></p>
                    
                    <ul id="avatar-owned-list" class="list-unstyled d-flex flex-column gap-3 mb-0"></ul>
                    
                    <div class="d-flex align-items-center justify-content-between gap-2 mt-auto pt-3">
                        <button id="avatar-prev-page" type="button" class="btn avatar-action btn-sm" disabled>
                            이전
                        </button>
                    
                        <span id="avatar-page-label" class="avatar-muted small"></span>
                    
                        <button id="avatar-next-page" type="button" class="btn avatar-action btn-sm" disabled>
                            다음
                        </button>
                    </div>
                </div>
            </section>
        </div>
    `;

    const slotList = content.querySelector('#avatar-slot-list');
    const ownedList = content.querySelector('#avatar-owned-list');
    const feedback = content.querySelector('#avatar-feedback');
    const itemsFeedback = content.querySelector('#avatar-items-feedback');
    const slotFilter = content.querySelector('#avatar-slot-filter');
    const statusFilter = content.querySelector('#avatar-status-filter');
    const showAllButton = content.querySelector('#avatar-show-all');
    const previousButton = content.querySelector('#avatar-prev-page');
    const nextButton = content.querySelector('#avatar-next-page');
    const pageLabel = content.querySelector('#avatar-page-label');
    const avatarPreviewStage = content.querySelector('#avatar-preview-stage');
    const avatarEquipmentLayers = content.querySelector('#avatar-equipment-layers');
    const avatarImageFeedback = content.querySelector('#avatar-image-feedback');
    const resyncArea = content.querySelector('#avatar-resync-area');
    const resyncButton = content.querySelector('#avatar-resync-button');

    const slots = ['HEAD', 'FACE', 'BODY', 'ACCESSORY'];

    let equipments = {
        HEAD: null,
        FACE: null,
        BODY: null,
        ACCESSORY: null,
    };

    let selectedSlot;
    let selectedStatus;
    let page = 0;
    let totalPages = 0;
    let itemsLoading = false;
    let avatarLoaded = false;
    let changingEquipment = false;
    let needsResync = false;
    let avatarRequestId = 0;
    let currentOwnedItems = [];
    let disposed = false;

    // 이전 조회 응답이 새로운 필터 결과를 덮어쓰지 않도록 합니다.
    let itemsRequestId = 0;

    function canChangeEquipment() {
        return avatarLoaded &&
            !changingEquipment &&
            !needsResync;
    }

    function renderSlots() {
        slotList.innerHTML = slots.map((slot) =>
            renderSlot(
                slot,
                equipments[slot],
                canChangeEquipment()
            )
        ).join('');

        applyItemThumbnails(slotList);

        renderAvatarEquipment({
            stage: avatarPreviewStage,
            layers: avatarEquipmentLayers,
            equipments,
            feedback: avatarImageFeedback,
        });

        updateEquipmentControls();
    }

    function updatePagination() {
        previousButton.disabled = itemsLoading || page === 0;
        nextButton.disabled =
            itemsLoading || totalPages === 0 || page + 1 >= totalPages;

        pageLabel.textContent = totalPages === 0
            ? '0 / 0'
            : `${page + 1} / ${totalPages}`;
    }

    async function loadOwnedItems({ throwOnError = false } = {}) {
        const requestId = ++itemsRequestId;

        itemsLoading = true;
        currentOwnedItems = [];
        ownedList.replaceChildren();

        itemsFeedback.textContent = '보유 아이템을 불러오는 중입니다.';
        slotFilter.value = selectedSlot ?? '';

        updateEquipmentControls();

        try {
            const result = await listOwnedItems({
                page,
                size: 4,
                ...(selectedSlot ? { slot: selectedSlot } : {}),
                ...(selectedStatus ? { status: selectedStatus } : {}),
            });

            if (disposed || requestId !== itemsRequestId) {
                return false;
            }

            // 사용 상태 변경으로 마지막 페이지가 사라진 경우 보정합니다.
            if (page > 0 && page >= result.meta.totalPages) {
                page = Math.max(0, result.meta.totalPages - 1);

                return await loadOwnedItems({ throwOnError });
            }

            totalPages = result.meta.totalPages;
            currentOwnedItems = result.data;

            ownedList.innerHTML = currentOwnedItems
                .map(renderOwnedItem)
                .join('');

            applyItemThumbnails(ownedList);
            updateOwnedActionButtons();

            itemsFeedback.textContent = currentOwnedItems.length === 0
                ? '조건에 맞는 보유 아이템이 없습니다.'
                : '';

            return true;
        } catch (error) {
            if (disposed) return false;

            if (requestId !== itemsRequestId) {
                // 페이지 보정 조회에서 전달된 오류를 호출자에게 유지합니다.
                if (throwOnError) throw error;

                if (isAuthenticationError(error)) {
                    handleAuthenticationError();
                }

                return false;
            }

            totalPages = 0;
            itemsFeedback.textContent =
                '보유 아이템을 불러오지 못했습니다. 다시 시도해주세요.';

            if (throwOnError) throw error;

            if (isAuthenticationError(error)) {
                handleAuthenticationError();
            }

            return false;
        } finally {
            if (!disposed && requestId === itemsRequestId) {
                itemsLoading = false;
                updateEquipmentControls();
            }
        }
    }

    async function loadAvatar() {
        const requestId = ++avatarRequestId;
        const avatar = await getAvatar();

        if (disposed || requestId !== avatarRequestId) {
            return false;
        }

        equipments = avatar.equipments;
        avatarLoaded = true;

        renderSlots();

        return true;
    }

    function isAuthenticationError(error) {
        return error?.status === 401 ||
            error?.code === 'AUTH_REQUIRED' ||
            error?.code === 'SESSION_EXPIRED';
    }

    function handleAuthenticationError() {
        needsResync = true;
        avatarLoaded = false;
        updateEquipmentControls();

        navigate('/login');
    }

    function invalidatePreviousReads() {
        avatarRequestId += 1;
        itemsRequestId += 1;

        // 무효화한 목록 조회의 finally에서는 상태를 갱신하지 않습니다.
        itemsLoading = false;
    }

    async function resyncAvatarState() {
        const results = await Promise.allSettled([
            loadAvatar(),
            loadOwnedItems({ throwOnError: true }),
        ]);

        if (disposed) return false;

        const authenticationFailure = results.find(
            (result) =>
                result.status === 'rejected' &&
                isAuthenticationError(result.reason)
        );

        if (authenticationFailure) {
            handleAuthenticationError();
            return false;
        }

        const succeeded = results.every(
            (result) =>
                result.status === 'fulfilled' &&
                result.value === true
        );

        needsResync = !succeeded;
        updateEquipmentControls();

        return succeeded;
    }

    async function changeAvatarEquipment({ action, slot, item }) {
        if (
            disposed ||
            !canChangeEquipment() ||
            !slots.includes(slot)
        ) {
            return;
        }

        if (action === 'equip') {
            if (
                !item ||
                item.slot !== slot ||
                item.status === 'REVOKED'
            ) {
                return;
            }
        } else if (action === 'unequip') {
            if (!equipments[slot]) return;
        } else {
            return;
        }

        const itemName = action === 'equip'
            ? item.name
            : equipments[slot].name;

        const actionLabel = action === 'equip' ? '장착' : '해제';

        // 모달을 기다리기 전부터 추가 변경을 차단합니다.
        changingEquipment = true;
        updateEquipmentControls();

        try {
            let confirmFirstUse = false;

            if (action === 'equip' && item.status === 'OWNED_UNUSED') {
                confirmFirstUse = await confirmFirstUseWithModal(
                    `${slot} - ${itemName}`
                );

                if (!confirmFirstUse || disposed) return;
            }

            invalidatePreviousReads();

            let avatar;

            // 변경 API의 실패와 성공 후 목록 조회 실패를 구분합니다.
            try {
                avatar = action === 'equip'
                    ? await equipAvatarItem(slot, {
                        ownedItemId: item.ownedItemId,
                        confirmFirstUse,
                    })
                    : await unequipAvatarItem(slot);
            } catch (error) {
                if (disposed) return;

                if (isAuthenticationError(error)) {
                    handleAuthenticationError();
                    return;
                }

                const message = error?.message ||
                    `${actionLabel} 요청 결과를 확인하지 못했습니다.`;

                needsResync = true;

                const recovered = await resyncAvatarState();

                if (disposed) return;

                feedback.textContent = '';

                showResult(
                    recovered
                        ? `${message}\n최신 상태를 반영했습니다.`
                        : `${message}\n최신 상태를 확인하지 못했습니다.\n` +
                        '새로고침을 눌러주세요.'
                );

                return;
            }

            if (disposed) return;

            // 변경 API가 성공했으므로 외형은 즉시 반영합니다.
            equipments = avatar.equipments;
            avatarLoaded = true;
            renderSlots();

            let listUpdated = false;

            try {
                listUpdated = await loadOwnedItems({
                    throwOnError: true,
                });
            } catch (error) {
                if (disposed) return;

                if (isAuthenticationError(error)) {
                    handleAuthenticationError();
                    return;
                }
            }

            if (disposed) return;

            needsResync = !listUpdated;
            feedback.textContent = '';

            if (!listUpdated) {
                showResult(
                    `아이템을 ${actionLabel}하는 과정에서 인벤토리 갱신에 실패했습니다.\n` +
                    '새로고침을 눌러주세요.'
                );
            } else if (action === 'equip' && confirmFirstUse) {
                showResult(`${slot} - ${itemName}을(를) 장착했습니다.`);
            }
        } finally {
            if (!disposed) {
                changingEquipment = false;
                updateEquipmentControls();
            }
        }
    }

    async function handleResyncClick() {
        if (disposed || changingEquipment) return;

        changingEquipment = true;
        needsResync = true;

        invalidatePreviousReads();
        updateEquipmentControls();

        try {
            const recovered = await resyncAvatarState();

            if (disposed) return;

            feedback.textContent = '';

            if (!recovered) {
                showResult('최신 상태를 불러오지 못했습니다.\n새로고침을 눌러주세요.');
            }
        } finally {
            if (!disposed) {
                changingEquipment = false;
                updateEquipmentControls();
            }
        }
    }

    async function handleSlotClick(event) {
        const button = event.target.closest('button[data-action]');

        if (
            !button ||
            !slotList.contains(button) ||
            changingEquipment
        ) {
            return;
        }

        const { action, slot } = button.dataset;

        if (action === 'select') {
            selectedSlot = slot;
            selectedStatus = undefined;
            page = 0;

            updateStatusButtons();
            await loadOwnedItems();

            if (disposed) return;

            if (window.matchMedia('(max-width: 1032px)').matches) {
                content.querySelector(
                    '[aria-labelledby="avatar-items-title"]'
                ).scrollIntoView({
                    behavior: window.matchMedia(
                        '(prefers-reduced-motion: reduce)'
                    ).matches ? 'instant' : 'smooth',
                    block: 'start',
                });
            }

            return;
        }

        if (action === 'unequip') {
            await changeAvatarEquipment({
                action: 'unequip',
                slot,
            });
        }
    }

    function updateOwnedActionButtons() {
        ownedList.querySelectorAll('button[data-action]')
            .forEach((button) => {
                const item = currentOwnedItems.find(
                    (candidate) =>
                        candidate.ownedItemId ===
                        Number(button.dataset.ownedItemId)
                );

                button.disabled =
                    !canChangeEquipment() ||
                    !item ||
                    item.status === 'REVOKED';
            });
    }

    function updateEquipmentControls() {
        slotList.querySelectorAll('button[data-action="unequip"]')
            .forEach((button) => {
                button.disabled =
                    !canChangeEquipment() ||
                    !equipments[button.dataset.slot];
            });

        updateOwnedActionButtons();

        // 재조회 중 조건 변경으로 복구용 목록 조회가 무효화되는 것을 막습니다.
        slotList.querySelectorAll('button[data-action="select"]')
            .forEach((button) => {
                button.disabled = changingEquipment;
            });

        statusFilter.querySelectorAll('button')
            .forEach((button) => {
                button.disabled = changingEquipment;
            });

        slotFilter.disabled = changingEquipment;
        showAllButton.disabled = changingEquipment;

        updatePagination();

        if (changingEquipment) {
            previousButton.disabled = true;
            nextButton.disabled = true;
        }

        resyncArea.hidden = !needsResync || changingEquipment;
        resyncButton.disabled = changingEquipment;
    }

    async function handleOwnedItemClick(event) {
        const button = event.target.closest('button[data-action]');

        if (
            !button ||
            !ownedList.contains(button) ||
            !canChangeEquipment()
        ) {
            return;
        }

        const item = currentOwnedItems.find(
            (candidate) =>
                candidate.ownedItemId ===
                Number(button.dataset.ownedItemId)
        );

        if (!item || item.status === 'REVOKED') return;

        await changeAvatarEquipment({
            action: button.dataset.action,
            slot: item.slot,
            item,
        });
    }

    function updateStatusButtons() {
        const currentStatus = selectedStatus ?? '';

        statusFilter.querySelectorAll('button[data-status]')
            .forEach((button) => {
                button.setAttribute(
                    'aria-pressed',
                    String(button.dataset.status === currentStatus)
                );
            });
    }

    function handleStatusChange(event) {
        const button = event.target.closest('button[data-status]');

        if (!button || !statusFilter.contains(button)) return;

        selectedStatus = button.dataset.status || undefined;
        page = 0;

        updateStatusButtons();
        void loadOwnedItems();
    }

    function handleSlotFilterChange() {
        selectedSlot = slotFilter.value || undefined;
        page = 0;

        void loadOwnedItems();
    }

    function handleShowAll() {
        selectedSlot = undefined;
        selectedStatus = undefined;
        page = 0;

        slotFilter.value = '';
        updateStatusButtons();

        void loadOwnedItems();
    }

    function handlePreviousPage() {
        if (itemsLoading || page === 0) return;

        page -= 1;
        void loadOwnedItems();
    }

    function handleNextPage() {
        if (itemsLoading || page + 1 >= totalPages) return;

        page += 1;
        void loadOwnedItems();
    }

    slotList.addEventListener('click', handleSlotClick);
    statusFilter.addEventListener('click', handleStatusChange);
    showAllButton.addEventListener('click', handleShowAll);
    previousButton.addEventListener('click', handlePreviousPage);
    nextButton.addEventListener('click', handleNextPage);
    slotFilter.addEventListener('change', handleSlotFilterChange);
    ownedList.addEventListener('click', handleOwnedItemClick);
    resyncButton.addEventListener('click', handleResyncClick);

    renderSlots();

    // 모달과 이벤트 초기화가 끝난 뒤 초기 조회를 시작합니다.
    queueMicrotask(() => {
        if (!disposed) {
            void handleResyncClick();
        }
    });


    const element = createAppShell({
        content,
        currentPath: '/avatar',
        currentUser,
        navigate,
    });

    const resultModalElement = content.querySelector('#avatar-result-modal');
    const resultMessage = resultModalElement.querySelector(
        '#avatar-result-message'
    );

    // 페이지의 높이 계산에 영향을 주지 않도록 이동합니다.
    element.append(resultModalElement);

    const resultModal = new Modal(resultModalElement, {
        backdrop: 'static',
        keyboard: false,
    });

    // Bootstrap의 전환 중에는 hide()가 무시되므로 완료 이벤트를 기다립니다.
    function manageModalLifecycle(modalElement, modal, disposeModal) {
        let phase = 'hidden';
        let closing = false;
        let released = false;

        const onShow = () => { phase = 'showing'; };
        const onShown = () => {
            phase = 'shown';
            if (closing) modal.hide();
        };
        const onHide = () => { phase = 'hiding'; };
        const onHidden = () => {
            phase = 'hidden';
            if (closing) release();
        };

        function release() {
            if (released) return;
            released = true;
            modalElement.removeEventListener('show.bs.modal', onShow);
            modalElement.removeEventListener('shown.bs.modal', onShown);
            modalElement.removeEventListener('hide.bs.modal', onHide);
            modalElement.removeEventListener('hidden.bs.modal', onHidden);
            disposeModal();
        }

        modalElement.addEventListener('show.bs.modal', onShow);
        modalElement.addEventListener('shown.bs.modal', onShown);
        modalElement.addEventListener('hide.bs.modal', onHide);
        modalElement.addEventListener('hidden.bs.modal', onHidden);

        return () => {
            if (closing) return;
            closing = true;

            if (phase === 'hidden') {
                release();
            } else {
                // 라우터가 이전 화면을 제거해도 전환 대상은 완료까지 유지합니다.
                document.body.append(modalElement);
                if (phase === 'shown') modal.hide();
            }
        };
    }

    const closeResultModal = manageModalLifecycle(
        resultModalElement, resultModal, disposeResultModal
    );

    let resultTrigger = null;

    function handleModalTab(event) {
        if (event.key !== 'Tab' || event.altKey || event.ctrlKey || event.metaKey) {
            return;
        }

        const modal = event.currentTarget;
        const focusable = Array.from(modal.querySelectorAll(
            'button, a[href], input, select, textarea, [tabindex]'
        )).filter((element) =>
            !element.matches(':disabled, [aria-disabled="true"]') &&
            element.tabIndex >= 0 &&
            element.getClientRects().length > 0 &&
            getComputedStyle(element).visibility !== 'hidden'
        );

        const first = focusable[0];
        const last = focusable[focusable.length - 1];

        if (!first) {
            event.preventDefault();
            modal.focus({ preventScroll: true });
            return;
        }

        const active = document.activeElement;
        if (!focusable.includes(active) ||
            (event.shiftKey && active === first) ||
            (!event.shiftKey && active === last)) {
            event.preventDefault();
            (event.shiftKey ? last : first).focus({ preventScroll: true });
        }
    }

    function handleModalHide(event) {
        // aria-hidden 적용 전에 숨겨질 모달 내부의 포커스를 해제합니다.
        const active = document.activeElement;
        if (active instanceof HTMLElement && event.currentTarget.contains(active)) {
            active.blur();
        }
    }

    resultModalElement.addEventListener('keydown', handleModalTab);
    resultModalElement.addEventListener('hide.bs.modal', handleModalHide);

    function showResult(message) {
        resultTrigger = document.activeElement;
        resultMessage.textContent = message;
        resultModal.show();
    }

    function handleResultShown() {
        if (disposed) return;
        resultModalElement.querySelector(
            '[data-bs-dismiss="modal"]'
        ).focus();
    }

    function restorePageFocus(trigger) {
        // 취소 후 잠금 해제와 장착 후 DOM 갱신이 끝난 다음 복귀합니다.
        requestAnimationFrame(() => {
            if (disposed) return;

            const title = content.querySelector('h1');
            title.setAttribute('tabindex', '-1');

            const slot = trigger instanceof HTMLElement
                ? trigger.dataset.slot
                : null;
            const slotButton = slots.includes(slot)
                ? slotList.querySelector(
                    `button[data-action="select"][data-slot="${slot}"]`
                )
                : null;

            const candidates = [trigger, slotButton, title];
            for (const candidate of candidates) {
                if (!(candidate instanceof HTMLElement) ||
                    !content.contains(candidate) ||
                    candidate.matches(':disabled, [aria-disabled="true"]') ||
                    candidate.getClientRects().length === 0) continue;

                candidate.focus({ preventScroll: true });
                if (document.activeElement === candidate) break;
            }
        });
    }

    function handleResultHidden() {
        restorePageFocus(resultTrigger);
    }

    resultModalElement.addEventListener('shown.bs.modal', handleResultShown);
    resultModalElement.addEventListener('hidden.bs.modal', handleResultHidden);

    function disposeResultModal() {
        resultModalElement.removeEventListener('keydown', handleModalTab);
        resultModalElement.removeEventListener('hide.bs.modal', handleModalHide);
        resultModalElement.removeEventListener(
            'shown.bs.modal',
            handleResultShown
        );
        resultModalElement.removeEventListener(
            'hidden.bs.modal',
            handleResultHidden
        );

        resultModal.dispose();
        resultModalElement.remove();
    }

    const firstUseModalElement = content.querySelector(
        '#avatar-first-use-modal'
    );
    const firstUseMessage = firstUseModalElement.querySelector(
        '#avatar-first-use-message'
    );
    const firstUseConfirmButton = firstUseModalElement.querySelector(
        '#avatar-first-use-confirm'
    );

    element.append(firstUseModalElement);
    firstUseModalElement.addEventListener('keydown', handleModalTab);
    firstUseModalElement.addEventListener('hide.bs.modal', handleModalHide);

    const firstUseModal = new Modal(firstUseModalElement, {
        backdrop: 'static',
        keyboard: true,
    });

    const closeFirstUseModal = manageModalLifecycle(
        firstUseModalElement, firstUseModal, disposeFirstUseModal
    );

    let pendingFirstUse = null;
    let firstUseAccepted = false;
    let firstUseTrigger = null;
    let firstUseDisposed = false;

    function confirmFirstUseWithModal(itemName) {
        // 확인창이 열려 있는 동안 추가 요청은 진행하지 않습니다.
        if (pendingFirstUse || disposed) {
            return Promise.resolve(false);
        }

        firstUseTrigger = document.activeElement;
        firstUseAccepted = false;

        firstUseMessage.textContent =
            `${itemName}을(를) 처음 장착하면 사용 처리됩니다.`;

        return new Promise((resolve) => {
            pendingFirstUse = resolve;
            firstUseModal.show();
        });
    }

    function handleFirstUseConfirm() {
        firstUseAccepted = true;
        firstUseModal.hide();
    }

    function handleFirstUseShown() {
        if (disposed) return;

        // 실수로 동의하지 않도록 취소 버튼에 먼저 포커스합니다.
        firstUseModalElement.querySelector(
            '[data-bs-dismiss="modal"]'
        ).focus();
    }

    function handleFirstUseHidden() {
        const resolve = pendingFirstUse;
        const accepted = firstUseAccepted && !disposed;

        pendingFirstUse = null;
        firstUseAccepted = false;

        // 모달이 완전히 닫힌 다음 장착 요청을 진행합니다.
        resolve?.(accepted);
        restorePageFocus(firstUseTrigger);

    }

    function disposeFirstUseModal() {
        if (firstUseDisposed) return;
        firstUseDisposed = true;
        firstUseModalElement.removeEventListener('keydown', handleModalTab);
        firstUseModalElement.removeEventListener('hide.bs.modal', handleModalHide);

        pendingFirstUse?.(false);
        pendingFirstUse = null;

        firstUseConfirmButton.removeEventListener(
            'click',
            handleFirstUseConfirm
        );
        firstUseModalElement.removeEventListener(
            'shown.bs.modal',
            handleFirstUseShown
        );
        firstUseModalElement.removeEventListener(
            'hidden.bs.modal',
            handleFirstUseHidden
        );

        firstUseModal.dispose();
        firstUseModalElement.remove();
    }

    firstUseConfirmButton.addEventListener(
        'click',
        handleFirstUseConfirm
    );
    firstUseModalElement.addEventListener(
        'shown.bs.modal',
        handleFirstUseShown
    );
    firstUseModalElement.addEventListener(
        'hidden.bs.modal',
        handleFirstUseHidden
    );

    return {
        element,
        cleanup: () => {
            disposed = true;
            // 대기 중인 최초 사용 확인은 취소합니다.
            pendingFirstUse?.(false);
            pendingFirstUse = null;

            closeFirstUseModal();
            itemsRequestId += 1;
            avatarRequestId += 1;

            slotList.removeEventListener('click', handleSlotClick);
            statusFilter.removeEventListener('click', handleStatusChange);
            showAllButton.removeEventListener('click', handleShowAll);
            previousButton.removeEventListener('click', handlePreviousPage);
            nextButton.removeEventListener('click', handleNextPage);
            slotFilter.removeEventListener('change', handleSlotFilterChange);
            ownedList.removeEventListener('click', handleOwnedItemClick);
            resyncButton.removeEventListener('click', handleResyncClick);

            closeResultModal();
        },
    };
}
