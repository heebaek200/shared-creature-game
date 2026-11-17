// @ts-check
import {
    createAvatarVisual,
    updateAvatarVisual,
} from '../avatar/render.js';
import {
    calculateIdleWaitScale,
    calculateRenderedPosition,
} from './avatar-movement-policy.js';

const movementAreas = [
    { minX: 8, maxX: 28, minY: 5, maxY: 20 },
    { minX: 36, maxX: 62, minY: 5, maxY: 20 },
    { minX: 72, maxX: 92, minY: 5, maxY: 20 },
    { minX: 8, maxX: 22, minY: 28, maxY: 46 },
    { minX: 78, maxX: 92, minY: 28, maxY: 46 },
    { minX: 8, maxX: 22, minY: 52, maxY: 68 },
    { minX: 78, maxX: 92, minY: 52, maxY: 68 },
    { minX: 8, maxX: 28, minY: 75, maxY: 82 },
    { minX: 36, maxX: 62, minY: 75, maxY: 82 },
    { minX: 72, maxX: 92, minY: 75, maxY: 82 },
];

const nearbyAreaIndexes = [
    [0, 1, 3],
    [0, 1, 2, 3, 4],
    [1, 2, 4],
    [0, 1, 3, 5],
    [1, 2, 4, 6],
    [3, 5, 7, 8],
    [4, 6, 8, 9],
    [5, 7, 8],
    [5, 6, 7, 8, 9],
    [6, 8, 9],
];

const MAX_CONCURRENT_MOVERS = 3;
const MAX_MOVE_DISTANCE = 32;
const IDLE_WAIT_MIN_MS = 12_000;
const IDLE_WAIT_MAX_MS = 26_000;

/**
 * 최대 20명의 그룹원 아바타를 10개 Zone에 배치하고 브라우저 안에서 이동시킵니다.
 * 이동 좌표는 로컬 표현 상태이며 서버와 통신하지 않습니다.
 *
 * @param {{
 *   container: HTMLElement,
 *   onSelect: (member: Record<string, any>) => void
 * }} options
 */
export function createAvatarField(options) {
    const actors = new Map();
    const fieldLayer = document.createElement('div');
    fieldLayer.className = 'avatar-zone avatar-zone--field position-absolute';
    options.container.appendChild(fieldLayer);
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    let movementEnabled = true;
    let disposed = false;

    function randomBetween(minimum, maximum) {
        return minimum + Math.random() * (maximum - minimum);
    }

    function randomPosition(areaIndex) {
        const area = movementAreas[areaIndex];
        return {
            x: randomBetween(area.minX, area.maxX),
            y: randomBetween(area.minY, area.maxY),
        };
    }

    function nearbyTarget(actor) {
        const candidates = nearbyAreaIndexes[actor.areaIndex];

        for (let attempt = 0; attempt < 12; attempt += 1) {
            const areaIndex = candidates[
                Math.floor(Math.random() * candidates.length)
            ];
            const position = randomPosition(areaIndex);
            const distance = Math.hypot(
                position.x - actor.x,
                position.y - actor.y,
            );
            if (distance <= MAX_MOVE_DISTANCE) {
                return { ...position, areaIndex, distance };
            }
        }

        const position = randomPosition(actor.areaIndex);
        return {
            ...position,
            areaIndex: actor.areaIndex,
            distance: Math.hypot(position.x - actor.x, position.y - actor.y),
        };
    }

    function movingActorCount() {
        return Array.from(actors.values()).filter((actor) => actor.moving).length;
    }

    function idleWait() {
        const scale = calculateIdleWaitScale(actors.size);
        return randomBetween(
            IDLE_WAIT_MIN_MS * scale,
            IDLE_WAIT_MAX_MS * scale,
        );
    }

    function initialWait() {
        const scale = calculateIdleWaitScale(actors.size);
        return randomBetween(1500 * scale, 18_000 * scale);
    }

    function updateActorVisual(actor, state, frame = 1) {
        actor.button.classList.toggle('is-moving', state === 'walk');
        updateAvatarVisual(actor.visual, {
            equipments: actor.member.avatar?.equipments,
            itemHue: actor.member.avatar?.mockItemHue,
            state,
            direction: actor.direction,
            frame,
            alt: `${actor.member.nickname} 아바타`,
        });
    }

    function clearActorTimer(actor) {
        if (actor.timer !== null) {
            window.clearTimeout(actor.timer);
            actor.timer = null;
        }
        if (actor.frameTimer !== null) {
            window.clearTimeout(actor.frameTimer);
            actor.frameTimer = null;
        }
    }

    function scheduleNextFrame(actor) {
        if (disposed || !actor.moving || reducedMotion.matches) return;
        actor.frameTimer = window.setTimeout(() => {
            actor.frame = actor.frame === 1 ? 2 : 1;
            updateActorVisual(actor, 'walk', actor.frame);
            scheduleNextFrame(actor);
        }, randomBetween(190, 360));
    }

    function stopActor(actor) {
        clearActorTimer(actor);
        if (actor.moving) {
            const computedStyle = globalThis.getComputedStyle(actor.button);
            const renderedPosition = calculateRenderedPosition({
                left: computedStyle.left,
                top: computedStyle.top,
                fieldWidth: fieldLayer.clientWidth,
                fieldHeight: fieldLayer.clientHeight,
                fallbackX: actor.x,
                fallbackY: actor.y,
            });
            actor.x = renderedPosition.x;
            actor.y = renderedPosition.y;
            actor.button.style.left = `${actor.x}%`;
            actor.button.style.top = `${actor.y}%`;
        }
        actor.moving = false;
        actor.button.style.transitionDuration = '0ms';
        updateActorVisual(actor, 'stop');
    }

    function scheduleMove(
        actor,
        wait = idleWait(),
    ) {
        clearActorTimer(actor);
        if (
            disposed
            || !movementEnabled
            || reducedMotion.matches
            || actor.button.matches(':focus')
        ) {
            stopActor(actor);
            return;
        }

        actor.timer = window.setTimeout(() => {
            if (disposed || !movementEnabled || reducedMotion.matches) {
                stopActor(actor);
                return;
            }

            if (movingActorCount() >= MAX_CONCURRENT_MOVERS) {
                scheduleMove(actor, randomBetween(1800, 4200));
                return;
            }

            const target = nearbyTarget(actor);
            const targetX = target.x;
            const targetY = target.y;
            actor.direction = targetX < actor.x ? 'left' : 'right';
            actor.moving = true;
            actor.frame = Math.random() < 0.5 ? 1 : 2;
            const duration = Math.min(
                4400,
                Math.max(2000, target.distance * randomBetween(70, 105)),
            );
            actor.button.style.transitionDuration = `${duration}ms`;
            actor.button.style.left = `${targetX}%`;
            actor.button.style.top = `${targetY}%`;
            updateActorVisual(actor, 'walk', actor.frame);
            scheduleNextFrame(actor);

            actor.timer = window.setTimeout(() => {
                if (actor.frameTimer !== null) {
                    window.clearTimeout(actor.frameTimer);
                    actor.frameTimer = null;
                }
                actor.x = targetX;
                actor.y = targetY;
                actor.areaIndex = target.areaIndex;
                actor.moving = false;
                updateActorVisual(actor, 'stop');
                scheduleMove(actor);
            }, duration);
        }, wait);
    }

    function stopAllActors() {
        for (const actor of actors.values()) stopActor(actor);
    }

    function startAllActors() {
        for (const actor of actors.values()) {
            scheduleMove(actor, initialWait());
        }
    }

    function handleReducedMotionChange() {
        if (reducedMotion.matches) stopAllActors();
        else if (movementEnabled) startAllActors();
    }

    function isCurrentUser(member, currentUserId) {
        return currentUserId !== null
            && String(member.userId) === String(currentUserId);
    }

    function updateActorMember(actor, member, currentUserId) {
        const currentUser = isCurrentUser(member, currentUserId);
        actor.member = member;
        actor.button.classList.toggle('is-current-user', currentUser);
        actor.button.setAttribute(
            'aria-label',
            `${member.nickname}${currentUser ? ' (나)' : ''} 아바타 정보 보기`,
        );
        actor.name.textContent = currentUser ? '나' : member.nickname;
        updateActorVisual(
            actor,
            actor.moving ? 'walk' : 'stop',
            actor.frame,
        );
    }

    function createActor(member, index, currentUserId) {
        const currentUser = isCurrentUser(member, currentUserId);
        const button = document.createElement('button');
        button.type = 'button';
        button.className = `avatar-spot${currentUser ? ' is-current-user' : ''}`;

        const initialDirection = Math.random() < 0.5 ? 'right' : 'left';
        const visual = createAvatarVisual({
            equipments: member.avatar?.equipments,
            itemHue: member.avatar?.mockItemHue,
            direction: initialDirection,
            alt: `${member.nickname} 아바타`,
        });
        const name = document.createElement('span');
        name.className = 'avatar-spot__name';
        button.append(visual, name);

        const areaIndex = index % movementAreas.length;
        const initialPosition = randomPosition(areaIndex);
        const actor = {
            button,
            visual,
            name,
            member,
            x: initialPosition.x,
            y: initialPosition.y,
            areaIndex,
            direction: initialDirection,
            moving: false,
            frame: Math.random() < 0.5 ? 1 : 2,
            timer: null,
            frameTimer: null,
        };
        updateActorMember(actor, member, currentUserId);
        button.style.left = `${actor.x}%`;
        button.style.top = `${actor.y}%`;
        button.addEventListener('click', () => options.onSelect(actor.member));
        button.addEventListener('focus', () => stopActor(actor));
        button.addEventListener('blur', () => {
            if (movementEnabled && !reducedMotion.matches) {
                scheduleMove(actor, 400);
            }
        });
        fieldLayer.appendChild(button);
        actors.set(String(member.userId), actor);

        return actor;
    }

    reducedMotion.addEventListener('change', handleReducedMotionChange);

    return {
        /**
         * Snapshot의 온라인 구성원을 userId 기준으로 증분 반영합니다.
         * 기존 Actor의 DOM·좌표·이동 Timer는 유지하고 입장과 퇴장만 추가·제거합니다.
         */
        render(members, currentUserId) {
            const previousMemberCount = actors.size;
            const createdActors = [];
            const nextMembers = members.slice(0, 20);
            const nextUserIds = new Set(nextMembers.map(
                (member) => String(member.userId),
            ));

            for (const [userId, actor] of actors) {
                if (nextUserIds.has(userId)) continue;
                clearActorTimer(actor);
                actor.button.remove();
                actors.delete(userId);
            }

            nextMembers.forEach((member, index) => {
                const userId = String(member.userId);
                const actor = actors.get(userId);
                if (actor) updateActorMember(actor, member, currentUserId);
                else createdActors.push(createActor(member, index, currentUserId));
            });

            if (!movementEnabled || reducedMotion.matches) return;

            if (previousMemberCount !== actors.size) {
                for (const actor of actors.values()) {
                    if (!actor.moving) scheduleMove(actor, initialWait());
                }
                return;
            }
            for (const actor of createdActors) scheduleMove(actor, initialWait());
        },

        /** 사용자 이동 토글을 즉시 전체 아바타에 반영합니다. */
        setMovementEnabled(enabled) {
            movementEnabled = enabled;
            if (enabled && !reducedMotion.matches) startAllActors();
            else stopAllActors();
        },

        /** 아바타 이동·프레임 Timer와 미디어 쿼리 Listener를 정리합니다. */
        dispose() {
            if (disposed) return;
            disposed = true;
            stopAllActors();
            actors.clear();
            fieldLayer.remove();
            reducedMotion.removeEventListener('change', handleReducedMotionChange);
        },
    };
}
