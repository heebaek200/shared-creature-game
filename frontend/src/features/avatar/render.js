// @ts-check

/** @type {Record<string, string>} */
const avatarImages = import.meta.glob('../../../assets/avatar/*.png', {
    eager: true,
    query: '?url',
    import: 'default',
});

/** @type {Record<string, string>} */
const itemImages = import.meta.glob('../../../assets/item/*.png', {
    eager: true,
    query: '?url',
    import: 'default',
});

const layerOrder = ['FACE', 'BODY', 'HEAD', 'ACCESSORY'];
const AVATAR_SIZE = 72;

/** 상점 카드에 표시할 정지·우측 방향 첫 프레임 이미지를 반환합니다. */
export function getShopItemImage(slot, assetKey) {
    if (typeof slot !== 'string' || typeof assetKey !== 'string') return '';
    const path = `../../../assets/item/stop-right-${slot.toLowerCase()}-${assetKey}-1.png`;
    return itemImages[path] ?? '';
}

/**
 * 그룹원 아바타의 기본 이미지와 장착 아이템 레이어를 생성합니다.
 * 누락된 아이템 이미지는 해당 레이어만 숨겨 나머지 외형을 유지합니다.
 *
 * @param {{
 *   equipments?: Record<string, any>,
 *   state?: 'stop'|'walk',
 *   direction?: 'left'|'right',
 *   frame?: 1|2,
 *   itemHue?: number,
 *   alt?: string
 * }} options
 */
export function createAvatarVisual(options = {}) {
    const visual = document.createElement('span');
    visual.className = 'group-avatar-visual';

    const base = document.createElement('img');
    base.className = 'group-avatar-visual__layer group-avatar-visual__base';
    base.width = AVATAR_SIZE;
    base.height = AVATAR_SIZE;
    base.draggable = false;
    visual.appendChild(base);

    for (const slot of layerOrder) {
        const layer = document.createElement('img');
        layer.className = 'group-avatar-visual__layer group-avatar-visual__item';
        layer.dataset.avatarSlot = slot;
        layer.width = AVATAR_SIZE;
        layer.height = AVATAR_SIZE;
        layer.alt = '';
        layer.setAttribute('aria-hidden', 'true');
        layer.draggable = false;
        visual.appendChild(layer);
    }

    updateAvatarVisual(visual, options);
    return visual;
}

/**
 * 기존 아바타 DOM에 상태·방향·프레임과 장착 외형을 반영합니다.
 *
 * @param {HTMLElement} visual
 * @param {{
 *   equipments?: Record<string, any>,
 *   state?: 'stop'|'walk',
 *   direction?: 'left'|'right',
 *   frame?: 1|2,
 *   itemHue?: number,
 *   alt?: string
 * }} options
 */
export function updateAvatarVisual(visual, options = {}) {
    const state = options.state === 'walk' ? 'walk' : 'stop';
    const direction = options.direction === 'left' ? 'left' : 'right';
    const frame = state === 'walk' && options.frame === 2 ? 2 : 1;
    const base = visual.querySelector('.group-avatar-visual__base');
    const basePath = `../../../assets/avatar/${state}-${direction}-default-${frame}.png`;
    const itemHue = Number.isFinite(options.itemHue)
        ? Number(options.itemHue) % 360
        : 0;

    visual.style.setProperty('--avatar-item-hue', `${itemHue}deg`);

    if (base instanceof HTMLImageElement) {
        base.src = avatarImages[basePath] ?? '';
        base.alt = options.alt ?? '그룹원 아바타';
    }

    for (const slot of layerOrder) {
        const layer = visual.querySelector(`[data-avatar-slot="${slot}"]`);
        if (!(layer instanceof HTMLImageElement)) continue;

        const equipment = options.equipments?.[slot];
        const assetKey = typeof equipment?.assetKey === 'string'
            ? equipment.assetKey.trim()
            : '';
        const part = slot.toLowerCase();
        const path = assetKey
            ? `../../../assets/item/${state}-${direction}-${part}-${assetKey}-${frame}.png`
            : '';
        const source = path ? itemImages[path] : null;

        if (source) {
            layer.src = source;
            layer.hidden = false;
        } else {
            layer.removeAttribute('src');
            layer.hidden = true;
        }
    }

    visual.dataset.avatarState = state;
    visual.dataset.avatarDirection = direction;
    visual.dataset.avatarFrame = String(frame);
}

// 아바타 이미지 경로, 썸네일 가공, 슬롯·아이템·장착 레이어 렌더링.
const itemImageUrls = import.meta.glob(
    '../../../assets/item/stop-left-*.png',
    {
        eager: true,
        query: '?url',
        import: 'default',
    }
);

function getItemImageUrl(item) {
    const part = item.slot.toLowerCase();

    const path =
        `../../../assets/item/stop-left-${part}-${item.assetKey}-1.png`;

    return itemImageUrls[path] ?? null;
}

// 같은 이미지는 한 번만 가공.
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

export function applyItemThumbnails(container) {
    const images = container.querySelectorAll(
        'img[data-item-thumbnail]'
    );

    for (const image of images) {
        const sourceUrl = image.getAttribute('src');

        if (!sourceUrl) continue;

        const showImageError = () => {
            if (!container.contains(image)) return;

            const message = document.createElement('span');
            message.className = 'avatar-muted small text-center';
            message.textContent = '이미지 오류';
            message.setAttribute('role', 'status');
            image.replaceWith(message);
        };

        image.addEventListener('error', showImageError, { once: true });

        // 이벤트 연결 전에 캐시된 실패가 확정된 경우도 처리합니다.
        if (image.complete && image.naturalWidth === 0) {
            showImageError();
            continue;
        }

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

const avatarRenderVersions = new WeakMap();

export function renderAvatarEquipment({ stage, layers, equipments, feedback }) {
    const layerOrder = ['FACE', 'BODY', 'HEAD', 'ACCESSORY'];
    const version = {};
    avatarRenderVersions.set(layers, version);

    feedback.textContent = '';
    feedback.hidden = true;

    const showLayerError = () => {
        if (avatarRenderVersions.get(layers) !== version) return;
        feedback.textContent = '일부 아이템 이미지를 표시하지 못했습니다.';
        feedback.hidden = false;
    };

    layers.replaceChildren();

    const equippedNames = [];

    layerOrder.forEach((slot, index) => {
        const equipment = equipments[slot];

        if (!equipment) return;

        const imageUrl = getItemImageUrl({
            slot,
            assetKey: equipment.assetKey,
        });

        equippedNames.push(`${slot} ${equipment.name}`);

        if (!imageUrl) {
            showLayerError();
            return;
        }

        const image = document.createElement('img');

        image.alt = '';
        image.className = 'avatar-preview__equipment';
        image.style.zIndex = String(index + 1);
        image.width = 320;
        image.height = 320;
        image.addEventListener('error', () => {
            if (avatarRenderVersions.get(layers) !== version ||
                !layers.contains(image)) return;

            image.remove();
            showLayerError();
        }, { once: true });

        layers.append(image);
        image.src = imageUrl;
    });

    stage.setAttribute(
        'aria-label',
        equippedNames.length
            ? `아바타: ${equippedNames.join(', ')} 장착`
            : '기본 아바타'
    );
}

export function renderSlot(slot, equipment, canUnequip) {
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

export function renderOwnedItem(item) {
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
        ? `장착 중 - ${item.slot}`
        : `미장착 - ${item.slot}`;

    return `
        <li class="avatar-owned-item border rounded-3 p-3">
            <div class="d-flex align-items-start
                        justify-content-between gap-3">
                <div class="avatar-owned-item__info">
                    <div class="avatar-owned-item__heading d-flex align-items-center
                                flex-wrap gap-2">
                        <h3 class="h6 mb-0" title="${escapeHtml(item.name)}">
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
                            이미지 오류
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

