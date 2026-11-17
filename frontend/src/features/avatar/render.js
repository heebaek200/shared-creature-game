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
