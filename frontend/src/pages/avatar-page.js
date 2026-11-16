import {createAppShell} from '../components/app-shell.js';
import defaultAvatarUrl from '../../assets/avatar/stop-left-default-1.png';
import {getAvatar, listOwnedItems, unequipAvatarItem} from '../features/avatar/api.js';

const itemImageUrls = import.meta.glob(
    '../../assets/item/stop-left-*.png',
    {
        eager: true,
        query: '?url',
        import: 'default',
    }
);

function getItemImageUrl(item) {
    const part = item.slot.toLowerCase();

    const path =
        `../../assets/item/stop-left-${part}-${item.assetKey}-1.png`;

    return itemImageUrls[path] ?? null;
}

// 같은 이미지는 한 번만 가공합니다.
const itemThumbnailCache = new Map();

function createItemThumbnail(sourceUrl) {
    if (itemThumbnailCache.has(sourceUrl)) {
        return itemThumbnailCache.get(sourceUrl);
    }

    const promise = new Promise((resolve, reject) => {
        const image = new Image();

        image.onload = () => {
            try {
                const sourceCanvas = document.createElement('canvas');
                sourceCanvas.width = image.naturalWidth;
                sourceCanvas.height = image.naturalHeight;

                const context = sourceCanvas.getContext('2d', {
                    willReadFrequently: true,
                });

                if (!context) {
                    throw new Error('이미지를 처리할 수 없습니다.');
                }

                context.drawImage(image, 0, 0);

                const { width, height } = sourceCanvas;
                const pixels = context.getImageData(
                    0, 0, width, height
                ).data;

                let left = width;
                let top = height;
                let right = -1;
                let bottom = -1;

                for (let y = 0; y < height; y += 1) {
                    for (let x = 0; x < width; x += 1) {
                        const alpha = pixels[(y * width + x) * 4 + 3];

                        if (alpha === 0) continue;

                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x);
                        bottom = Math.max(bottom, y);
                    }
                }

                // 그림이 없는 완전 투명 이미지이면 원본을 사용합니다.
                if (right < left || bottom < top) {
                    resolve(sourceUrl);
                    return;
                }

                const croppedWidth = right - left + 1;
                const croppedHeight = bottom - top + 1;

                const thumbnail = document.createElement('canvas');
                thumbnail.width = croppedWidth;
                thumbnail.height = croppedHeight;

                const thumbnailContext = thumbnail.getContext('2d');

                if (!thumbnailContext) {
                    throw new Error('썸네일을 생성할 수 없습니다.');
                }

                thumbnailContext.drawImage(
                    image,
                    left,
                    top,
                    croppedWidth,
                    croppedHeight,
                    0,
                    0,
                    croppedWidth,
                    croppedHeight
                );

                resolve(thumbnail.toDataURL('image/png'));
            } catch (error) {
                reject(error);
            }
        };

        image.onerror = () => {
            reject(new Error('아이템 이미지를 불러오지 못했습니다.'));
        };

        image.src = sourceUrl;
    });

    itemThumbnailCache.set(sourceUrl, promise);

    // 실패한 이미지는 다음 조회에서 다시 시도할 수 있도록 합니다.
    promise.catch(() => {
        if (itemThumbnailCache.get(sourceUrl) === promise) {
            itemThumbnailCache.delete(sourceUrl);
        }
    });

    return promise;
}

function applyItemThumbnails(container) {
    const images = container.querySelectorAll(
        'img[data-item-thumbnail]'
    );

    for (const image of images) {
        const sourceUrl = image.getAttribute('src');

        if (!sourceUrl) continue;

        createItemThumbnail(sourceUrl)
            .then((thumbnailUrl) => {
                // 목록이 갱신되어 제거된 이미지는 변경하지 않습니다.
                if (!container.contains(image)) return;

                image.src = thumbnailUrl;
            })
            .catch(() => {
                // 가공 실패 시 원본 이미지를 그대로 표시합니다.
            });
    }
}

export function avatarPage({navigate, currentUser}) {
    const content = document.createElement('div');
    content.className = 'avatar-page';

    content.innerHTML = `
        <h1 class="h4 fw-bold mb-4">내 아바타</h1>
        
        <p
            id="avatar-feedback"
            class="avatar-muted small mb-3"
            role="status"
            aria-live="polite"
        ></p>

        <div class="row g-3 align-items-stretch">
            <!-- 장착 슬롯 -->
            <section
                class="col-12 col-xl-4"
                aria-labelledby="avatar-slots-title"
            >
                <div class="avatar-panel border rounded-4 p-3 p-md-4 h-100">
                    <h2
                        id="avatar-slots-title"
                        class="h6 fw-bold mb-4"
                    >
                        ITEM SLOT
                    </h2>

                    <ul id="avatar-slot-list" class="list-unstyled d-flex flex-column gap-3 mb-0">
                    </ul>
                </div>
            </section>

            <!-- 아바타 -->
            <section
                class="col-12 col-xl-4"
                aria-labelledby="avatar-preview-title"
            >
                <div class="avatar-panel border rounded-4 p-3 p-md-4 h-100
                            d-flex flex-column">
                    <h2
                        id="avatar-preview-title"
                        class="h6 fw-bold mb-4"
                    >
                        아바타
                    </h2>

                    <div class="avatar-preview rounded-4 flex-grow-1
                                d-flex flex-column align-items-center
                                justify-content-center gap-4 p-4">
                        <div class="avatar-preview__stage">
                            <img
                                src="${defaultAvatarUrl}"
                                class="avatar-preview__image"
                                alt="기본 아바타"
                                width="60"
                                height="60"
                            >
                        </div>

                        <p class="avatar-muted small text-center mb-0">
                            아이템을 장착하면 외형이 변경됩니다.
                        </p>
                    </div>
                </div>
            </section>

            <!-- 보유 아이템 -->
            <section
                class="col-12 col-xl-4"
                aria-labelledby="avatar-items-title"
            >
                <div class="avatar-panel border rounded-4 p-3 p-md-4 h-100">
                    <h2
                        id="avatar-items-title"
                        class="h6 fw-bold mb-4"
                    >
                        보유 아이템
                    </h2>

                    <div class="mb-4">
                        <label
                            for="avatar-status-filter"
                            class="form-label small"
                        >
                            사용 상태
                        </label>
                    
                        <select
                            id="avatar-status-filter"
                            class="form-select avatar-filter"
                        >
                            <option value="">전체</option>
                            <option value="OWNED_UNUSED">미사용</option>
                            <option value="OWNED_USED">사용함</option>
                            <option value="REVOKED">회수됨</option>
                        </select>
                    </div>
                    
                    <div class="d-flex align-items-center justify-content-between gap-2 mb-3">
                        <p
                            id="avatar-slot-filter-label"
                            class="avatar-muted small mb-0"
                        >
                            전체 부위
                        </p>
                    
                        <button
                            id="avatar-show-all"
                            type="button"
                            class="btn avatar-action btn-sm"
                        >
                            전체 보기
                        </button>
                    </div>
                    
                    <p
                        id="avatar-items-feedback"
                        class="avatar-muted small"
                        role="status"
                        aria-live="polite"
                    ></p>
                    
                    <ul
                        id="avatar-owned-list"
                        class="list-unstyled d-flex flex-column gap-3 mb-0"
                    ></ul>
                    
                    <div class="d-flex align-items-center justify-content-between gap-2 mt-3">
                        <button
                            id="avatar-prev-page"
                            type="button"
                            class="btn avatar-action btn-sm"
                            disabled
                        >
                            이전
                        </button>
                    
                        <span
                            id="avatar-page-label"
                            class="avatar-muted small"
                        ></span>
                    
                        <button
                            id="avatar-next-page"
                            type="button"
                            class="btn avatar-action btn-sm"
                            disabled
                        >
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
    const filterLabel = content.querySelector('#avatar-slot-filter-label');
    const statusFilter = content.querySelector('#avatar-status-filter');
    const showAllButton = content.querySelector('#avatar-show-all');
    const previousButton = content.querySelector('#avatar-prev-page');
    const nextButton = content.querySelector('#avatar-next-page');
    const pageLabel = content.querySelector('#avatar-page-label');

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
    let disposed = false;

    // 이전 조회 응답이 새로운 필터 결과를 덮어쓰지 않도록 합니다.
    let itemsRequestId = 0;

    function renderSlots() {
        slotList.innerHTML = slots.map((slot) =>
            renderSlot(
                slot,
                equipments[slot],
                avatarLoaded && !changingEquipment
            )
        ).join('');

        applyItemThumbnails(slotList);
    }

    function updatePagination() {
        previousButton.disabled = itemsLoading || page === 0;
        nextButton.disabled =
            itemsLoading || totalPages === 0 || page + 1 >= totalPages;

        pageLabel.textContent = totalPages === 0
            ? '0 / 0'
            : `${page + 1} / ${totalPages}`;
    }

    async function loadOwnedItems() {
        const requestId = ++itemsRequestId;

        itemsLoading = true;
        ownedList.replaceChildren();
        itemsFeedback.textContent = '보유 아이템을 불러오는 중입니다.';
        filterLabel.textContent = selectedSlot
            ? `${selectedSlot} 부위`
            : '전체 부위';

        updatePagination();

        try {
            const result = await listOwnedItems({
                page,
                size: 20,
                ...(selectedSlot ? {slot: selectedSlot} : {}),
                ...(selectedStatus ? {status: selectedStatus} : {}),
            });

            if (disposed || requestId !== itemsRequestId) return;

            totalPages = result.meta.totalPages;

            ownedList.innerHTML = result.data
                .map(renderOwnedItem)
                .join('');

            applyItemThumbnails(ownedList);

            itemsFeedback.textContent = result.data.length === 0
                ? '조건에 맞는 보유 아이템이 없습니다.'
                : '';
        } catch {
            if (disposed || requestId !== itemsRequestId) return;

            totalPages = 0;
            itemsFeedback.textContent =
                '보유 아이템을 불러오지 못했습니다. 필터나 전체 보기를 눌러 다시 시도해주세요.';
        } finally {
            if (!disposed && requestId === itemsRequestId) {
                itemsLoading = false;
                updatePagination();
            }
        }
    }

    async function loadAvatar() {
        const avatar = await getAvatar();

        if (disposed) return;

        equipments = avatar.equipments;
        avatarLoaded = true;
        renderSlots();
    }

    async function handleSlotClick(event) {
        const button = event.target.closest('button[data-action]');

        if (!button || !slotList.contains(button)) return;

        const {action, slot} = button.dataset;

        if (action === 'select') {
            selectedSlot = slot;
            page = 0;

            // 선택한 부위의 모든 사용 상태를 표시합니다.
            selectedStatus = undefined;
            statusFilter.value = '';

            await loadOwnedItems();
            return;
        }

        if (
            action !== 'unequip' ||
            changingEquipment ||
            !avatarLoaded ||
            !equipments[slot]
        ) {
            return;
        }

        changingEquipment = true;
        feedback.textContent = `${slot} 아이템을 해제하는 중입니다.`;
        renderSlots();

        try {
            const avatar = await unequipAvatarItem(slot);

            if (disposed) return;

            // 성공 응답의 전체 장착 상태를 반영합니다.
            equipments = avatar.equipments;
            feedback.textContent = `${slot} 아이템을 해제했습니다.`;

            await loadOwnedItems();
        } catch {
            if (disposed) return;

            feedback.textContent =
                '해제 요청을 완료하지 못했습니다. 최신 장착 상태를 확인합니다.';

            try {
                await loadAvatar();

                if (disposed) return;

                feedback.textContent =
                    '해제 요청을 완료하지 못해 최신 장착 상태를 반영했습니다.';

                await loadOwnedItems();
            } catch {
                if (disposed) return;

                // 상태를 확인하지 못했다면 추가 해제를 막습니다.
                avatarLoaded = false;
                feedback.textContent =
                    '최신 장착 상태를 확인하지 못했습니다. 새로고침 후 다시 시도해주세요.';
            }
        } finally {
            if (!disposed) {
                changingEquipment = false;
                renderSlots();
            }
        }
    }

    function handleStatusChange() {
        selectedStatus = statusFilter.value || undefined;
        page = 0;
        void loadOwnedItems();
    }

    function handleShowAll() {
        selectedSlot = undefined;
        selectedStatus = undefined;
        statusFilter.value = '';
        page = 0;
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
    statusFilter.addEventListener('change', handleStatusChange);
    showAllButton.addEventListener('click', handleShowAll);
    previousButton.addEventListener('click', handlePreviousPage);
    nextButton.addEventListener('click', handleNextPage);

    renderSlots();

    void loadAvatar().catch(() => {
        if (disposed) return;

        feedback.textContent =
            '장착 상태를 불러오지 못했습니다. 새로고침 후 다시 시도해주세요.';
    });

    void loadOwnedItems();


    const element = createAppShell({
        content,
        currentPath: '/avatar',
        currentUser,
        navigate,
    });

    return {
        element,
        cleanup: () => {
            disposed = true;
            itemsRequestId += 1;

            slotList.removeEventListener('click', handleSlotClick);
            statusFilter.removeEventListener('change', handleStatusChange);
            showAllButton.removeEventListener('click', handleShowAll);
            previousButton.removeEventListener('click', handlePreviousPage);
            nextButton.removeEventListener('click', handleNextPage);
        },
    };
}

function renderSlot(slot, equipment, canUnequip) {
    const unequipDisabled = !equipment || !canUnequip;

    // 장착 정보에는 slot이 없으므로 현재 슬롯을 함께 전달합니다.
    const imageUrl = equipment
        ? getItemImageUrl({
            slot,
            assetKey: equipment.assetKey,
        })
        : null;

    return `
        <li class="avatar-slot border rounded-3 p-3">
            <div class="d-flex align-items-start
                        justify-content-between gap-3 mb-3">
                <div class="avatar-owned-item__info">
                    <h3 class="h6 fw-semibold mb-2">
                        ${slot}
                    </h3>

                    <p class="avatar-muted small mb-0">
                        ${equipment
        ? escapeHtml(equipment.name)
        : '기본 외형'}
                    </p>
                </div>

                <div class="avatar-owned-item__thumbnail rounded-3">
                    ${imageUrl ? `
                        <img
                            src="${escapeHtml(imageUrl)}"
                            alt="${escapeHtml(equipment.name)}"
                            class="avatar-owned-item__image"
                            data-item-thumbnail
                            width="60"
                            height="60"
                        >
                    ` : `
                        <span class="avatar-muted small text-center">
                            ${equipment ? '이미지 오류' : '없음'}
                        </span>
                    `}
                </div>
            </div>

            <div class="d-flex gap-2">
                <button
                    type="button"
                    class="btn avatar-action btn-sm flex-fill"
                    data-action="select"
                    data-slot="${slot}"
                    aria-label="${slot} 아이템 선택"
                >
                    선택
                </button>

                <button
                    type="button"
                    class="btn avatar-action btn-sm flex-fill"
                    data-action="unequip"
                    data-slot="${slot}"
                    aria-label="${slot} 아이템 해제"
                    ${unequipDisabled ? 'disabled' : ''}
                >
                    해제
                </button>
            </div>
        </li>
    `;
}

function renderOwnedItem(item) {
    const statusLabels = {
        OWNED_UNUSED: '미사용',
        OWNED_USED: '사용',
        REVOKED: '회수',
    };

    const imageUrl = getItemImageUrl(item);
    const isRevoked = item.status === 'REVOKED';
    const action = item.equipped ? 'unequip' : 'equip';
    const buttonLabel = item.equipped ? '해제' : '장착';

    const equipmentLabel = item.equipped
        ? `현재 장착 중 - ${item.slot}`
        : `미장착 - ${item.slot}`;

    return `
        <li class="avatar-owned-item border rounded-3 p-3">
            <div class="d-flex align-items-start
                        justify-content-between gap-3">
                <div class="avatar-owned-item__info">
                    <div class="d-flex align-items-center
                                flex-wrap gap-2">
                        <h3 class="h6 mb-0">
                            ${escapeHtml(item.name)}
                        </h3>

                        <span class="avatar-item-status badge rounded-pill">
                            ${escapeHtml(
        statusLabels[item.status] ?? item.status
    )}
                        </span>
                    </div>
                </div>

                <div class="avatar-owned-item__thumbnail rounded-3">
                    ${imageUrl ? `
                        <img
                            src="${escapeHtml(imageUrl)}"
                            alt="${escapeHtml(item.name)}"
                            class="avatar-owned-item__image"
                            data-item-thumbnail
                            width="60"
                            height="60"
                        >
                    ` : `
                        <span class="avatar-muted small text-center">
                            이미지 없음
                        </span>
                    `}
                </div>
            </div>

            <div class="d-flex align-items-center
                        justify-content-between gap-3 mt-3">
                <p class="avatar-muted small mb-0">
                    ${escapeHtml(equipmentLabel)}
                </p>

                <button
                    type="button"
                    class="btn avatar-action btn-sm flex-shrink-0"
                    data-action="${action}"
                    data-slot="${escapeHtml(item.slot)}"
                    data-owned-item-id="${item.ownedItemId}"
                    aria-label="${escapeHtml(item.name)} ${buttonLabel}"
                    ${isRevoked ? 'disabled' : ''}
                >
                    ${buttonLabel}
                </button>
            </div>
        </li>
    `;
}

function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, (character) => ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;',
    })[character]);
}