// @ts-check
import { createGroupLiveClient } from './group-live-client.js';
import { createSnapshotSync } from './snapshot-sync.js';

/** @typedef {number|string} GroupLiveId */
/** @typedef {'disconnected'|'connecting'|'live'|'polling'|'error'} GroupLiveStatus */
/** @typedef {{code?: string, message?: string|null}} GroupLiveError */
/** @typedef {{userId: GroupLiveId, groupId: GroupLiveId}} GroupLiveIdentity */
/**
 * 공통 헤더와 화면이 구독하는 연결·동기화 상태입니다.
 * @typedef {object} GroupLiveState
 * @property {GroupLiveStatus} status
 * @property {GroupLiveId|null} userId
 * @property {GroupLiveId|null} groupId
 * @property {string|null} errorCode
 * @property {string|null} message
 */
/**
 * 서버 스냅샷의 동기화 필수 필드와 화면 데이터를 보존합니다.
 * @typedef {Record<string, any> & {
 *   groupId: GroupLiveId,
 *   streamInstanceId: string,
 *   sequence: number
 * }} GroupLiveSnapshot
 */
/**
 * @typedef {Record<string, any> & {
 *   groupId: GroupLiveId,
 *   eventType: string,
 *   streamInstanceId: string,
 *   sequence: number,
 *   eventId: string
 * }} GroupLiveEvent
 */
/**
 * 구독 함수는 현재 값을 즉시 전달할 수 있으며 해제 함수를 반환합니다.
 * @typedef {object} GroupLiveManager
 * @property {(identity?: {userId?: GroupLiveId|null, groupId?: GroupLiveId|null}) => Promise<void>} start
 * @property {() => Promise<void>} stop
 * @property {() => Promise<void>} reconnect
 * @property {() => Promise<boolean>} refresh
 * @property {() => GroupLiveState} getState
 * @property {() => GroupLiveSnapshot|null} getSnapshot
 * @property {(listener: (state: GroupLiveState) => void) => (() => void)} subscribeStatus
 * @property {(listener: (state: GroupLiveState) => void) => (() => void)} subscribeDisplayStatus
 * @property {(listener: (snapshot: GroupLiveSnapshot) => void) => (() => void)} subscribeSnapshots
 * @property {(listener: (error: GroupLiveError) => void) => (() => void)} subscribeErrors
 * @property {(listener: (event: GroupLiveEvent) => void) => (() => void)} subscribeEvents
 * @property {(listener: (message: Record<string, any>) => void) => (() => void)} subscribeChatMessages
 */

/** @returns {Promise<GroupLiveSnapshot>} */
async function loadCreatureSnapshot() {
    const { getCreatureHome } = await import('../features/creature/api.js');
    const response = await getCreatureHome();
    return response?.data ?? response;
}

const terminalCodes = new Set([
    'AUTH_REQUIRED', 'SESSION_EXPIRED', 'ROLE_FORBIDDEN', 'CSRF_INVALID', 'INVALID_REQUEST',
]);

/**
 * 그룹 연결·스냅샷 조회·폴링을 페이지와 무관하게 유지합니다.
 * @param {{
 *   createClient?: (options: Parameters<typeof createGroupLiveClient>[0]) => ReturnType<typeof createGroupLiveClient>,
 *   loadSnapshot?: () => Promise<GroupLiveSnapshot>,
 *   pollIntervalMs?: number
 * }} [options] 통신 의존성과 폴링 주기를 테스트에서 대체할 수 있습니다.
 * @returns {GroupLiveManager}
 */
export function createGroupLiveManager({
    createClient = createGroupLiveClient,
    loadSnapshot = loadCreatureSnapshot,
    pollIntervalMs,
} = {}) {
    /** @type {Set<(state: GroupLiveState) => void>} */
    const statusListeners = new Set();
    /** @type {Set<(event: GroupLiveEvent) => void>} */
    const eventListeners = new Set();
    /** @type {Set<(message: Record<string, any>) => void>} */
    const chatMessageListeners = new Set();
    /** @type {Set<(snapshot: GroupLiveSnapshot) => void>} */
    const snapshotListeners = new Set();
    /** @type {Set<(error: GroupLiveError) => void>} */
    const errorListeners = new Set();
    /** @type {ReturnType<typeof createGroupLiveClient>|null} */
    let client = null;
    /** @type {ReturnType<typeof createSnapshotSync>|null} */
    let sync = null;
    /** @type {(() => ReturnType<typeof createSnapshotSync>)|null} */
    let createSync = null;
    /** @type {GroupLiveSnapshot|null} */
    let snapshot = null;
    /** @type {GroupLiveError|null} */
    let lastError = null;
    /** @type {GroupLiveIdentity|null} */
    let identity = null;
    let generation = 0;
    let operations = Promise.resolve();
    /** @type {Readonly<GroupLiveState>} */
    let state = Object.freeze({ status: 'disconnected', userId: null, groupId: null,
        errorCode: null, message: null });

    /** @template T @param {Set<(value: T) => void>} listeners @param {T} value */
    function notify(listeners, value) {
        for (const listener of [...listeners]) {
            try { listener(value); }
            catch (error) { console.error('그룹 실시간 구독 처리 실패:', error); }
        }
    }

    /**
     * @param {GroupLiveStatus} status @param {string|null} [errorCode] @param {string|null} [message]
     * @param errorCode
     * @param message
     */
    function setStatus(status, errorCode = null, message = null) {
        state = Object.freeze({ status, userId: identity?.userId ?? null,
            groupId: identity?.groupId ?? null, errorCode, message });
        notify(statusListeners, state);
    }

    /**
     *  @param {() => Promise<void>} operation @returns {Promise<void>} */
    function enqueue(operation) {
        const pending = operations.then(operation);
        operations = pending.catch(() => {});
        return pending;
    }

    async function disconnectClient() {
        const previous = client;
        if (!previous) return;
        await previous.disconnect();
        if (client === previous) client = null;
    }

    /** 연결·조회·폴링을 종료하고 캐시를 지웁니다. @returns {Promise<void>} */
    function stop() {
        generation += 1;
        sync?.dispose();
        sync = null;
        createSync = null;
        snapshot = null;
        lastError = null;
        identity = null;
        setStatus('disconnected');
        return enqueue(disconnectClient);
    }

    /** @param {GroupLiveEvent} event @returns {{refresh: boolean}|undefined} */
    function applyLiveEvent(event) {
        if (
            !snapshot
            || String(event.groupId) !== String(snapshot.groupId)
        ) {
            return;
        }

        const payload = event.payload || {};
        const members = snapshot.onlineMembers ?? [];
        const memberIndex = members.findIndex((member) =>
            String(member.userId) === String(payload.userId));
        let requiresSnapshot = false;

        switch (event.eventType) {
            case 'PRESENCE_JOINED': {
                const joinedMember = {
                    userId: payload.userId,
                    nickname: payload.nickname,
                    rank: payload.rank,
                    contributionScore: payload.contributionScore,
                    avatar: { equipments: payload.equipments },
                };
                if (memberIndex === -1) members.push(joinedMember);
                else members[memberIndex] = joinedMember;
                break;
            }
            case 'PRESENCE_LEFT':
                if (memberIndex !== -1) members.splice(memberIndex, 1);
                break;
            case 'AVATAR_EQUIPMENT_CHANGED':
                if (memberIndex !== -1) {
                    const equipments = members[memberIndex].avatar?.equipments;
                    if (equipments) equipments[payload.slot] = payload.equippedItem;
                }
                break;
            case 'CREATURE_UPDATED':
                requiresSnapshot =
                    snapshot.creature?.growthStage !== payload.growthStage
                    || snapshot.creature?.creatureSpeciesId
                        !== payload.creatureSpeciesId;
                snapshot.creature = {
                    ...snapshot.creature,
                    groupCreatureId: payload.groupCreatureId,
                    totalExperience: payload.totalExperience,
                    growthStage: payload.growthStage,
                    creatureSpeciesId: payload.creatureSpeciesId,
                };
                break;
            case 'CONTRIBUTION_UPDATED': {
                requiresSnapshot = true;
                if (memberIndex !== -1) {
                    members[memberIndex].contributionScore = payload.contributionScore;
                }
                snapshot.todayExperience =
                    (snapshot.todayExperience ?? 0)
                    + (payload.creatureExperienceDelta ?? 0);
                snapshot.recentContributions = [
                    {
                        userId: payload.userId,
                        nickname: payload.nickname,
                        amount: payload.creatureExperienceDelta,
                        occurredAt: event.occurredAt,
                    },
                    ...(snapshot.recentContributions ?? []),
                ].slice(0, 10);
                break;
            }
            default:
                return;
        }

        notify(snapshotListeners, structuredClone(snapshot));
        return { refresh: requiresSnapshot };
    }

    /**
     * 같은 사용자·그룹이면 기존 연결을 유지합니다.
     * @param {{userId?: GroupLiveId|null, groupId?: GroupLiveId|null}} [identity]
     * @returns {Promise<void>} 연결 시작 처리 완료이며 구독 완료는 상태로 전달합니다.
     */
    function start({ userId, groupId } = {}) {
        if (userId == null || groupId == null) return stop();
        if (identity && String(identity.userId) === String(userId)
            && String(identity.groupId) === String(groupId)) return operations;

        const currentGeneration = ++generation;
        sync?.dispose();
        sync = null;
        createSync = null;
        snapshot = null;
        lastError = null;
        identity = { userId, groupId };
        setStatus('connecting');
        return enqueue(async () => {
            if (currentGeneration !== generation) return;
            await disconnectClient();
            if (currentGeneration !== generation) return;
            const active = () => currentGeneration === generation;
            /** @param {unknown} error */
            const reportError = (error) => {
                if (!active()) return;
                const failure = /** @type {GroupLiveError} */ (error);
                lastError = failure;
                if (terminalCodes.has(failure?.code)) {
                    sync?.dispose();
                    sync = null;
                }
                setStatus('error', failure?.code ?? 'CONNECTION_ERROR', failure?.message ?? null);
                notify(errorListeners, failure);
            };
            createSync = () => createSnapshotSync({
                loadSnapshot,
                pollIntervalMs,
                applySnapshot(value) {
                    if (!active()) return;
                    snapshot = /** @type {GroupLiveSnapshot} */ (structuredClone(value));
                    lastError = null;
                    notify(snapshotListeners, structuredClone(snapshot));
                },
                applyEvent: applyLiveEvent,
                onStatusChange(status) {
                    if (active()) setStatus(status);
                },
                onError: reportError,
            });
            sync = createSync();
            try {
                client = createClient({
                    groupId,
                    onEvent(event) {
                        if (!active() || String(event.groupId) !== String(groupId)) return;
                        sync?.handleEvent(event);
                        notify(eventListeners, /** @type {GroupLiveEvent} */ (event));
                    },
                    onChatMessage(message) {
                        if (!active() || String(message.groupId) !== String(groupId)) return;
                        notify(chatMessageListeners, message);
                    },
                    onSubscribed() {
                        if (active()) void sync?.recoverRealtime();
                    },
                    onDisconnected() {
                        if (active() && !terminalCodes.has(lastError?.code)) sync?.startPolling();
                    },
                    onError(code, error) {
                        if (!active()) return;
                        if (terminalCodes.has(code)) {
                            const failure = /** @type {GroupLiveError|undefined} */ (error);
                            reportError({ code, message: failure?.message ?? null });
                        } else {
                            sync?.startPolling();
                        }
                    },
                });
                client.connect();
            } catch (error) {
                reportError(error);
                if (!terminalCodes.has((/** @type {GroupLiveError} */ (error))?.code)) sync?.startPolling();
                throw error;
            }
        });
    }

    /** @param {(state: GroupLiveState) => void} listener @returns {() => void} */
    function subscribeStatus(listener) {
        statusListeners.add(listener);
        notify(new Set([listener]), state);
        return () => statusListeners.delete(listener);
    }

    return {
        start, stop,
        /** @returns {GroupLiveState} */
        getState: () => state,
        /** @returns {GroupLiveSnapshot|null} */
        getSnapshot: () => snapshot ? structuredClone(snapshot) : null,
        /** @returns {Promise<boolean>} 조회 성공 여부이며 종료된 조회는 false입니다. */
        refresh: () => sync?.refresh() ?? Promise.resolve(false),
        /** 종료된 동기화도 재생성하며 인증·권한 오류는 우회하지 않습니다. @returns {Promise<void>} */
        reconnect() {
            const currentGeneration = generation;
            return enqueue(async () => {
                if (!identity || !client || currentGeneration !== generation) return;
                // 인증·권한 오류는 로그인 또는 권한 확인 흐름을 거쳐야 합니다.
                if (['AUTH_REQUIRED', 'SESSION_EXPIRED', 'ROLE_FORBIDDEN'].includes(lastError?.code)) return;
                if (!sync && createSync) {
                    sync = createSync();
                    lastError = null;
                }
                setStatus('connecting');
                await client.reconnect();
            });
        },
        subscribeStatus,
        subscribeDisplayStatus: subscribeStatus,
        /** @param {(snapshot: GroupLiveSnapshot) => void} listener @returns {() => void} */
        subscribeSnapshots(listener) {
            snapshotListeners.add(listener);
            if (snapshot) notify(new Set([listener]), structuredClone(snapshot));
            return () => snapshotListeners.delete(listener);
        },
        /** @param {(error: GroupLiveError) => void} listener @returns {() => void} */
        subscribeErrors(listener) {
            errorListeners.add(listener);
            if (lastError) notify(new Set([listener]), lastError);
            return () => errorListeners.delete(listener);
        },
        /** @param {(event: GroupLiveEvent) => void} listener @returns {() => void} */
        subscribeEvents(listener) {
            eventListeners.add(listener);
            return () => eventListeners.delete(listener);
        },
        /** @param {(message: Record<string, any>) => void} listener @returns {() => void} */
        subscribeChatMessages(listener) {
            chatMessageListeners.add(listener);
            return () => chatMessageListeners.delete(listener);
        },
    };
}

/** @type {GroupLiveManager} 앱 전체에서 공유하는 관리자입니다. */
export const groupLiveManager = createGroupLiveManager();
