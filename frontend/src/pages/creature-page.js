// @ts-check
import { getActiveCreatureSpecies, getCreatureHome } from '../features/creature/api.js';
import { renderCreatureGrowth } from '../features/creature/render.js';
import { getCurrentSeason } from '../features/season/api.js';
import { ApiError } from '../api/client.js';
import { getShopItem } from '../features/shop/api.js';
import { createAvatarField } from '../features/creature/avatar-field.js';
import { creatureAccessErrorRoute } from '../features/creature/access-error-policy.js';
import { createGroupLiveClient } from '../realtime/group-live-client.js';
import { createSnapshotSync } from '../realtime/snapshot-sync.js';

import creatureLv0 from '../../assets/creature/lv0.png';
import { createAppShell } from '../components/app-shell.js';
/**
 * 크리처 홈 페이지 마운트 및 렌더링합니다.
 * @param {{ navigate: (path: string) => void }} context
 */
export function creaturePage(context) {
    const content = document.createElement('div');
    content.className = 'd-flex flex-column flex-grow-1';

    content.innerHTML = `  
    <!-- 크리처홈 메인 영역 타이틀 및 상태 영역 -->
    <div class="d-flex justify-content-between align-items-center mb-3 px-1">
        <div>
          <div class="d-flex align-items-center gap-2">
            <h1 class="h3 mb-0 fw-bold text-app" >크리처 홈</h1>
          </div>
        </div>
        <div class="d-flex align-items-center gap-3">
          <button type="button" class="btn border px-3 py-1.5 fw-bold avatar-movement-toggle is-enabled" id="btn-toggle-movement">
            아바타 이동 : ON
          </button>
        </div>
        </div>
        
        <div id="creature-load-error" class="alert alert-danger d-none d-flex justify-content-between align-items-start gap-3" role="alert">
        <div>
          <div id="creature-load-error-message"></div>
          <small id="creature-load-error-details" class="d-block mt-1"></small>
        </div>
        <div class="d-flex flex-shrink-0 gap-2">
          <button type="button" class="btn btn-sm btn-outline-danger d-none" id="creature-error-login">로그인</button>
          <button type="button" class="btn btn-sm btn-danger" id="creature-error-retry">다시 시도</button>
        </div>
        </div>
        
        <!-- 메인 콘텐츠 대시보드 -->
        <div class="row g-4 flex-grow-1 align-items-stretch creature-dashboard-row">
        
        <!-- 좌측: 공동 크리처 영역 -->
        <div class="col-xl-9 col-lg-9 d-flex flex-column creature-dashboard-column">
          <div class="card shadow-sm flex-grow-1 border app-surface-card">
            <div class="card-body d-flex flex-column justify-content-between p-3 p-xxl-4 h-100">
              <div class="d-flex flex-column flex-grow-1">
                <div class="d-flex justify-content-between align-items-center mb-2">
                  <h2 class="h5 card-title mb-0 fw-bold text-app" id="creature-title-name">시즌 크리처 - ???</h2>
                  <div class="d-flex align-items-center gap-2 flex-wrap justify-content-end">
                    <span class="small text-app-muted" id="live-status" role="status" aria-live="polite">실시간 연결 준비 중</span>
                    <span class="badge rounded-pill py-2 px-3 online-count-badge" id="online-count">접속 0명</span>
                  </div>
                </div>
        
                <!-- 크리처 및 아바타 이동 구역 -->
                <div class="border rounded-3 p-4 text-center position-relative my-2 flex-grow-1 d-flex flex-column justify-content-center align-items-center creature-avatar-container" style="min-height: 480px;" id="avatar-container">
                  
                  <div id="creature-display" class="d-flex flex-column align-items-center justify-content-center" style="visibility: hidden;">
                    <div id="creature-img-wrapper" class="mb-1 d-flex align-items-center justify-content-center">
                      <img src="${creatureLv0}" alt="크리처 이미지" id="creature-image" style="image-rendering: pixelated; image-rendering: crisp-edges;" />
                    </div>
                    <h3 class="h4 mb-0 fw-bold text-app" id="growth-stage">미선택 알</h3>
                    <span class="small text-app-muted" id="species-subtitle">???</span>
                    <span class="small text-app-muted" id="hatch-countdown" aria-live="polite"></span>
                  </div>
                </div>
              </div>
        
              <!-- 경험치 및 퀘스트 하단 구역 -->
              <div class="mt-2">
                <div class="d-flex justify-content-end fw-bold mb-2 text-app" id="exp-text">
                  <span>0 / - XP</span>
                </div>
                <div class="progress mb-3" style="height: 12px;">
                  <!-- 부드러운 바 차오름을 위한 CSS 트랜지션 추가 -->
                  <div class="progress-bar" role="progressbar" id="exp-bar" aria-label="크리처 성장 경험치 진행률" aria-valuetext="경험치 정보 불러오는 중" style="width: 0; transition: width 2.8s ease-in-out;" aria-valuenow="0" aria-valuemin="0" aria-valuemax="100"></div>
                </div>
                <div class="text-center">
                  <button type="button" class="btn btn-primary fw-bold px-4 py-2" id="btn-quest">오늘 퀘스트 확인</button>
                </div>
              </div>
            </div>
          </div>
        </div>
        
        <!-- 우측: 아바타 상세 정보 카드 영역 -->
        <div class="col-xl-3 col-lg-3 d-flex flex-column creature-dashboard-column">
          <div class="card shadow-sm flex-grow-1 border app-surface-card">
            <div class="card-body p-4 d-flex flex-column justify-content-between h-100">
              <div>
                <h2 class="h6 mb-2 group-experience-title text-app-muted">오늘 그룹 경험치</h2>
                <div class="h3 fw-bold mb-4 text-app" id="today-exp">+0 XP</div>
        
                <h3 class="h6 fw-bold mb-3 text-app">최근 기여</h3>
                <ul class="list-group list-group-flush mb-4" id="recent-contributions-list"></ul>
              </div>
        
              <!-- 아바타 선택 시에만 나타나는 유저 정보창 -->
              <div class="card border mt-3 d-none shadow-sm app-surface-bordered" id="avatar-info-card">
                <div class="card-header py-2.5 app-surface-bordered">
                  <div class="d-flex justify-content-between align-items-center">
                    <span class="fw-bold text-nowrap text-app" id="avatar-toggle-title">USER INFO [ON]</span>
                  </div>
                </div>
                <div class="card-body collapse show pt-2" id="avatar-detail-collapse" style="overflow-x: auto; overflow-y: hidden;">
                  <p class="mb-2 fw-bold text-nowrap text-app-primary" id="avatar-info-header">-</p>
                  <table class="table table-sm align-middle mb-2 text-app border-app text-nowrap">
                    <thead>
                      <tr class="text-app bg-app-background">
                        <th class="py-1.5 fst-italic">slot</th>
                        <th class="py-1.5 fst-italic">item</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr>
                        <td class="py-1.5 text-app-muted">HEAD</td>
                        <td id="slot-head" class="py-1.5">-</td>
                      </tr>
                      <tr>
                        <td class="py-1.5 text-app-muted">FACE</td>
                        <td id="slot-face" class="py-1.5">-</td>
                      </tr>
                      <tr>
                        <td class="py-1.5 text-app-muted">BODY</td>
                        <td id="slot-body" class="py-1.5">-</td>
                      </tr>
                      <tr>
                        <td class="py-1.5 text-app-muted">ACCESSORY</td>
                        <td id="slot-accessory" class="py-1.5">-</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <div class="card-footer bg-transparent border-0 pt-0 pb-3 text-center">
                  <button type="button" class="btn btn-sm px-3 fw-bold w-100 py-1.5 text-app-primary bg-app-background border-app" id="btn-goto-shop" disabled>판매 상품 보기</button>
                </div>
              </div>
        
            </div>
          </div>
        </div>

    </div>
    `;

    const element = createAppShell({
        content,
        navigate: context.navigate,
        currentPath: location.pathname,
        currentUser: context.currentUser,
    });

    // 2. DOM 요소 참조 및 이벤트 바인딩
    // 화면 요소를 조회하고 버튼 및 바깥 영역 이벤트 연결
    const btnQuest = element.querySelector('#btn-quest');
    const avatarInfoCard = element.querySelector('#avatar-info-card');
    const avatarToggleTitle = element.querySelector('#avatar-toggle-title');
    const btnGotoShop = element.querySelector('#btn-goto-shop');
    const btnToggleMovement = element.querySelector('#btn-toggle-movement');
    const avatarContainer = element.querySelector('#avatar-container');
    const loadError = element.querySelector('#creature-load-error');
    const loadErrorMessage = element.querySelector('#creature-load-error-message');
    const loadErrorDetails = element.querySelector('#creature-load-error-details');
    const retryButton = element.querySelector('#creature-error-retry');
    const loginButton = element.querySelector('#creature-error-login');
    const liveStatus = element.querySelector('#live-status');

    // API 오류를 사용자 안내 문구로 변환 및 재시도 가능 여부 판단
    function getApiErrorMessage(error) {
        if (!(error instanceof ApiError)) {
            return '서버에 연결하지 못했습니다. 네트워크 상태를 확인한 뒤 다시 시도해 주세요.';
        }

        switch (error.code) {
            case 'AUTH_REQUIRED':
            case 'SESSION_EXPIRED':
                return '로그인 세션이 없거나 만료되었습니다. 다시 로그인해 주세요.';
            case 'ROLE_FORBIDDEN':
                return '이 크리처 정보를 볼 수 있는 그룹 권한이 없습니다.';
            case 'RESOURCE_NOT_FOUND':
                return '현재 그룹 또는 시즌의 크리처 정보를 찾을 수 없습니다.';
            default:
                return error.message || '크리처 정보를 불러오지 못했습니다.';
        }
    }

    function showLoadError(error) {
        if (!loadError) return;
        loadError.classList.remove('d-none');
        if (loadErrorMessage) loadErrorMessage.textContent = getApiErrorMessage(error);
        if (loadErrorDetails) {
            const details = error instanceof ApiError
                ? [`오류 코드: ${error.code}`, error.traceId ? `추적 ID: ${error.traceId}` : null]
                    .filter(Boolean).join(' · ')
                : '';
            loadErrorDetails.textContent = details;
        }
        if (loginButton) {
            const requiresLogin = error instanceof ApiError
                && ['AUTH_REQUIRED', 'SESSION_EXPIRED'].includes(error.code);
            loginButton.classList.toggle('d-none', !requiresLogin);
        }
    }

    function clearLoadError() {
        loadError?.classList.add('d-none');
    }

    function isRetryableError(error) {
        if (!(error instanceof ApiError)) return true;
        return error.status === 429 || error.status >= 500;
    }

    const avatarMovementStorageKey = 'avatarMovementEnabled';
    let isAvatarMoving =
        localStorage.getItem(avatarMovementStorageKey) !== 'false';

    function updateMovementButton() {
        if (!btnToggleMovement) return;

        btnToggleMovement.textContent = isAvatarMoving
            ? '아바타 이동 : ON'
            : '아바타 이동 : OFF';

        btnToggleMovement.classList.toggle('is-enabled', isAvatarMoving);
        btnToggleMovement.classList.toggle('is-disabled', !isAvatarMoving);
    }

    // 페이지가 열릴 때 저장된 상태 표시
    updateMovementButton();

    const onQuestClick = () => {
        context.navigate('/quests');
    };
    btnQuest?.addEventListener('click', onQuestClick);

    const onShopClick = () => {
        if (selectedShopItemId !== null) {
            context.navigate(`/shop?itemId=${selectedShopItemId}`);
        }
    };
    btnGotoShop?.addEventListener('click', onShopClick);

    const onToggleMovement = () => {
        isAvatarMoving = !isAvatarMoving;

        localStorage.setItem(
            avatarMovementStorageKey,
            String(isAvatarMoving),
        );

        updateMovementButton();
        avatarField.setMovementEnabled(isAvatarMoving);
    };
    btnToggleMovement?.addEventListener('click', onToggleMovement);

    const onMovementFocus = () => {
        if (!btnToggleMovement) return;
        btnToggleMovement.style.outline = '3px solid var(--app-primary)';
        btnToggleMovement.style.outlineOffset = '0';
    };
    const onMovementBlur = () => {
        if (!btnToggleMovement) return;
        btnToggleMovement.style.outline = '';
        btnToggleMovement.style.outlineOffset = '';
    };
    btnToggleMovement?.addEventListener('focus', onMovementFocus);
    btnToggleMovement?.addEventListener('blur', onMovementBlur);

    const shopAvailabilityCache = new Map();
    let selectedAvatarUserId = null;
    let selectedShopItemId = null;
    let avatarSelectionVersion = 0;

    async function isActiveShopItem(shopItemId) {
        if (shopAvailabilityCache.has(shopItemId)) {
            return shopAvailabilityCache.get(shopItemId);
        }

        const request = getShopItem(shopItemId)
            .then(() => true)
            .catch((error) => {
                if (error instanceof ApiError && error.code === 'RESOURCE_NOT_FOUND') {
                    return false;
                }
                shopAvailabilityCache.delete(shopItemId);
                return false;
            });
        shopAvailabilityCache.set(shopItemId, request);
        return request;
    }

    async function renderEquipmentCell(cell, equipment, selectionVersion) {
        if (!(cell instanceof HTMLElement)) return;
        cell.textContent = equipment?.name ?? '-';
        if (!equipment?.shopItemId) return;

        const active = await isActiveShopItem(equipment.shopItemId);
        if (!isMounted || selectionVersion !== avatarSelectionVersion || !active) return;

        const link = document.createElement('a');
        link.href = `/shop?itemId=${equipment.shopItemId}`;
        link.dataset.link = '';
        link.textContent = equipment.name;
        link.className = 'text-app-primary';
        cell.replaceChildren(link);

        if (selectedShopItemId === null) {
            selectedShopItemId = equipment.shopItemId;
            if (btnGotoShop instanceof HTMLButtonElement) {
                btnGotoShop.disabled = false;
            }
        }
    }

    function selectAvatar(member, scrollToDetails = false) {
        selectedAvatarUserId = member.userId;
        selectedShopItemId = null;
        avatarSelectionVersion += 1;
        const selectionVersion = avatarSelectionVersion;
        const avatarInfoHeader = element.querySelector('#avatar-info-header');
        const equipment = member.avatar?.equipments || {};

        if (avatarToggleTitle) {
            avatarToggleTitle.textContent = `USER - [${member.nickname}]`;
        }
        if (avatarInfoHeader) {
            const rank = member.rank === null ? '집계 중' : `${member.rank}위`;
            avatarInfoHeader.textContent =
                `그룹 ${rank} · ${member.contributionScore}점`;
        }
        if (btnGotoShop instanceof HTMLButtonElement) {
            btnGotoShop.disabled = true;
        }

        void renderEquipmentCell(element.querySelector('#slot-head'), equipment.HEAD, selectionVersion);
        void renderEquipmentCell(element.querySelector('#slot-face'), equipment.FACE, selectionVersion);
        void renderEquipmentCell(element.querySelector('#slot-body'), equipment.BODY, selectionVersion);
        void renderEquipmentCell(element.querySelector('#slot-accessory'), equipment.ACCESSORY, selectionVersion);
        avatarInfoCard?.classList.remove('d-none');
        if (
            scrollToDetails
            && avatarInfoCard instanceof HTMLElement
            && window.matchMedia('(max-width: 1032px)').matches
        ) {
            avatarInfoCard.scrollIntoView({
                behavior: 'smooth',
                block: 'start',
            });
        }
    }

    if (!(avatarContainer instanceof HTMLElement)) {
        throw new Error('아바타 표시 영역을 찾을 수 없습니다.');
    }
    const avatarField = createAvatarField({
        container: avatarContainer,
        onSelect: (member) => selectAvatar(member, true),
    });
    avatarField.setMovementEnabled(isAvatarMoving);

    // 3. API 조회, 크리처 성장 렌더링 및 마감 감시 로직
    let isMounted = true;
    let currentUserId = null;
    let countdownIntervalId = null;
    let deadlineTimeoutId = null;

    /** @type {Set<number>} */
    const growthAnimationTimeoutIds = new Set();

    function scheduleGrowthAnimationTimeout(callback, delay) {
        const timeoutId = window.setTimeout(() => {
            growthAnimationTimeoutIds.delete(timeoutId);
            callback();
        }, delay);
        growthAnimationTimeoutIds.add(timeoutId);
        return timeoutId;
    }

    // 72시간까지 남은 시각(크리처 선택 마감 기간)
    function updateHatchCountdown(hatchDeadlineAt, growthStage, hatchStatusMessage = null) {
        const now = Date.now();
        const hatchCountdown = element.querySelector('#hatch-countdown');

        if (!hatchCountdown) return;

        if (growthStage !== 'EGG') {
            hatchCountdown.textContent = '';
        } else if (hatchStatusMessage !== null) {
            hatchCountdown.textContent = hatchStatusMessage;
        } else if (!Number.isFinite(hatchDeadlineAt)) {
            hatchCountdown.textContent = '남은 시간 확인 불가';
        } else {
            const diffMs = hatchDeadlineAt - now;

            if (diffMs <= 0) {
                hatchCountdown.textContent = '';
            } else {
                const totalMinutes = Math.ceil(diffMs / (1000 * 60));
                const days = Math.floor(totalMinutes / (60 * 24));
                const hours = Math.floor((totalMinutes % (60 * 24)) / 60);
                const minutes = totalMinutes % 60;
                const parts = [];

                if (days > 0) parts.push(`${days}일`);
                if (hours > 0) parts.push(`${hours}시간`);
                if (minutes > 0 || parts.length === 0) parts.push(`${minutes}분`);

                hatchCountdown.textContent = `부화까지 ${parts.join(' ')}`;
            }
        }
    }

    // 72시간 마감 시각에 크리처 Snapshot을 조회하고 미배정 시 재시도
    function startRemainingTimeUpdates(hatchDeadlineAt, growthStage, groupSeasonStatus) {
        if (countdownIntervalId !== null) clearInterval(countdownIntervalId);
        if (deadlineTimeoutId !== null) clearTimeout(deadlineTimeoutId);

        updateHatchCountdown(hatchDeadlineAt, growthStage);

        // 그룹 시즌이 종료됐으면 부화 타이머나 재조회를 설정하지 않습니다.
        if (groupSeasonStatus === 'ENDED') {
            const hatchCountdown = element.querySelector('#hatch-countdown');

            if (hatchCountdown) {
                hatchCountdown.textContent = growthStage === 'EGG'
                    ? '시즌이 종료되었습니다.'
                    : '';
            }
            return;
        }

        let hatchStatusMessage = null;

        updateHatchCountdown(
            hatchDeadlineAt,
            growthStage,
            hatchStatusMessage
        );

        countdownIntervalId = window.setInterval(() => {
            if (isMounted) {
                updateHatchCountdown(
                    hatchDeadlineAt,
                    growthStage,
                    hatchStatusMessage
                );
            }
        }, 60 * 1000);

        if (!Number.isFinite(hatchDeadlineAt)) return;

        const maxAssignmentRetries = 3;
        const assignmentRetryDelayMs = 2000;

        const refreshAtDeadline = async (retryCount = 0) => {
            if (!isMounted) return;

            try {
                const [creatureRes, seasonRes, speciesRes] = await Promise.all([
                    getCreatureHome(),
                    getCurrentSeason(),
                    getActiveCreatureSpecies()
                ]);

                if (!isMounted) return;

                const creatureData = creatureRes.data || creatureRes;
                const refreshedSeasonData = seasonRes.data || seasonRes;
                const speciesList = speciesRes.data || speciesRes;

                const refreshedCreature = creatureData.creature || {};
                const isStillEgg = refreshedCreature.growthStage === 'EGG';
                const groupSeasonEnded = creatureData.season?.status === 'ENDED';

                if (isStillEgg && groupSeasonEnded) {
                    clearLoadError();

                    if (countdownIntervalId !== null) {
                        clearInterval(countdownIntervalId);
                        countdownIntervalId = null;
                    }

                    const hatchCountdown = element.querySelector('#hatch-countdown');
                    if (hatchCountdown) {
                        hatchCountdown.textContent =
                            '시즌이 종료되었습니다.';
                    }
                    return;
                }

                if (isStillEgg) {
                    clearLoadError();

                    const hatchCountdown = element.querySelector('#hatch-countdown');
                    if (retryCount < maxAssignmentRetries) {
                        hatchStatusMessage = '부화 처리 확인 중...';
                        if (hatchCountdown) {
                            hatchCountdown.textContent = hatchStatusMessage;
                        }

                        deadlineTimeoutId = window.setTimeout(
                            () => refreshAtDeadline(retryCount + 1),
                            assignmentRetryDelayMs
                        );
                    } else {
                        hatchStatusMessage = '부화 처리 지연 중. 페이지를 새로고침해 주세요.';
                        if (hatchCountdown) {
                            hatchCountdown.textContent = hatchStatusMessage;
                        }
                    }

                    return;
                }

                currentSeasonData = refreshedSeasonData;
                currentSpeciesList = speciesList;
                applySnapshot(creatureData);
            } catch (error) {
                showLoadError(error);
                if (isMounted && isRetryableError(error) && retryCount < maxAssignmentRetries) {
                    deadlineTimeoutId = window.setTimeout(
                        () => refreshAtDeadline(retryCount + 1),
                        assignmentRetryDelayMs
                    );
                }
            }
        };

        if (hatchDeadlineAt > Date.now()) {
            deadlineTimeoutId = window.setTimeout(
                () => refreshAtDeadline(),
                hatchDeadlineAt - Date.now()
            );
        } else if (growthStage === 'EGG') {
            void refreshAtDeadline();
        }
    }

    // snapshot 데이터를 그룹/경험치/기여/아바타 화면에 반영
    function updateUI(
        creatureData,
        seasonData,
        speciesList = [],
        options = { renderGrowth: true },
    ) {
        if (!creatureData) return;
        clearLoadError();

        const onlineMembers = creatureData.onlineMembers || [];
        const recentContributions = creatureData.recentContributions || [];
        const isCurrentUser = (userId) =>
            currentUserId !== null
            && userId !== null
            && userId !== undefined
            && String(userId) === String(currentUserId);

        if (options.renderGrowth) {
            growthAnimationTimeoutIds.forEach((timeoutId) => clearTimeout(timeoutId));
            growthAnimationTimeoutIds.clear();
            const growthUnlockAt = renderCreatureGrowth({
                element,
                creatureData,
                seasonData,
                speciesList,
                currentUserId,
                isMounted: () => isMounted,
                scheduleTimeout: scheduleGrowthAnimationTimeout,
            });

            const growthStage = creatureData.creature?.growthStage;
            const groupSeasonStatus = creatureData.season?.status;
            startRemainingTimeUpdates(growthUnlockAt, growthStage, groupSeasonStatus);
        }

        const todayExp = element.querySelector('#today-exp');
        // 기타 UI (온라인 멤버 및 최근 기여 목록 렌더링)
        const onlineCount = element.querySelector('#online-count');
        if (onlineCount) onlineCount.textContent = `접속 ${onlineMembers.length}명`;

        if (todayExp) todayExp.textContent = `+${(creatureData.todayExperience || 0).toLocaleString()} XP`;

        const contributionsList = element.querySelector('#recent-contributions-list');
        if (contributionsList) {
            contributionsList.innerHTML = '';
            recentContributions.forEach(contribution => {
                const li = document.createElement('li');
                li.className = 'list-group-item d-flex justify-content-between align-items-center px-0 bg-transparent py-1 border-app text-app';

                const spanName = document.createElement('span');
                spanName.className = 'contribution-name';
                const isMe = isCurrentUser(contribution.userId);
                spanName.textContent = isMe ? `${contribution.nickname} (나)` : contribution.nickname;

                const spanAmount = document.createElement('span');
                spanAmount.className =
                    'fw-bold contribution-amount text-app-primary';
                spanAmount.textContent = `+${contribution.amount} XP`;

                li.appendChild(spanName);
                li.appendChild(spanAmount);
                contributionsList.appendChild(li);
            });
        }

        avatarField.render(onlineMembers, currentUserId);

        if (selectedAvatarUserId !== null) {
            const selectedMember = onlineMembers.find((member) =>
                String(member.userId) === String(selectedAvatarUserId));
            if (selectedMember) selectAvatar(selectedMember);
            else {
                selectedAvatarUserId = null;
                avatarInfoCard?.classList.add('d-none');
            }
        }
    }

    let currentCreatureData = null;
    let currentSeasonData = null;
    let currentSpeciesList = [];
    let snapshotSync = null;
    let liveClient = null;

    function stopForAccessError(error) {
        const route = creatureAccessErrorRoute(error);
        if (!route) return false;

        snapshotSync?.dispose();
        void liveClient?.disconnect();
        context.navigate(route);
        return true;
    }

    function handleSnapshotError(error) {
        if (!stopForAccessError(error)) showLoadError(error);
    }

    function setLiveStatus(status) {
        if (!liveStatus) return;
        const labels = {
            connecting: '실시간 연결 중',
            live: '실시간 연결됨',
            polling: '30초 동기화 중',
            error: '연결 복구 중',
        };
        liveStatus.textContent = labels[status] ?? '연결 상태 확인 중';
        liveStatus.dataset.liveStatus = status;
    }

    function applySnapshot(snapshot) {
        const previousCreature = currentCreatureData?.creature;
        currentCreatureData = structuredClone(snapshot);
        currentSeasonData = currentCreatureData.season ?? currentSeasonData;
        const nextCreature = currentCreatureData.creature;
        const creatureChanged = !previousCreature || [
            'groupCreatureId',
            'creatureSpeciesId',
            'growthStage',
            'totalExperience',
            'nextStageAtExperience',
            'selectionDeadlineAt',
        ].some((key) => previousCreature[key] !== nextCreature?.[key]);
        updateUI(
            currentCreatureData,
            currentSeasonData,
            currentSpeciesList,
            { renderGrowth: creatureChanged },
        );
    }

    function applyLiveEvent(event) {
        if (
            !currentCreatureData
            || String(event.groupId) !== String(currentCreatureData.groupId)
        ) {
            return;
        }

        const payload = event.payload || {};
        const members = currentCreatureData.onlineMembers ?? [];
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
                    currentCreatureData.creature?.growthStage !== payload.growthStage
                    || currentCreatureData.creature?.creatureSpeciesId
                        !== payload.creatureSpeciesId;
                currentCreatureData.creature = {
                    ...currentCreatureData.creature,
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
                currentCreatureData.todayExperience =
                    (currentCreatureData.todayExperience ?? 0)
                    + (payload.creatureExperienceDelta ?? 0);
                currentCreatureData.recentContributions = [
                    {
                        userId: payload.userId,
                        nickname: payload.nickname,
                        amount: payload.creatureExperienceDelta,
                        occurredAt: event.occurredAt,
                    },
                    ...(currentCreatureData.recentContributions ?? []),
                ].slice(0, 10);
                break;
            }
            default:
                return;
        }

        updateUI(
            currentCreatureData,
            currentSeasonData,
            currentSpeciesList,
            { renderGrowth: event.eventType === 'CREATURE_UPDATED' },
        );
        return { refresh: requiresSnapshot };
    }

    async function initializeCreaturePage() {
        if (retryButton instanceof HTMLButtonElement) retryButton.disabled = true;
        const authenticatedUser = context.currentUser?.data || context.currentUser;
        currentUserId = authenticatedUser?.user?.userId ?? null;
        const groupId = authenticatedUser?.membership?.groupId;

        try {
            const [season, species] = await Promise.all([
                getCurrentSeason(),
                getActiveCreatureSpecies(),
            ]);
            if (!isMounted) return;
            currentSeasonData = season?.data || season;
            currentSpeciesList = species?.data || species;
        } catch (error) {
            if (isMounted) showLoadError(error);
        }

        if (!isMounted || !groupId) return;

        snapshotSync = createSnapshotSync({
            loadSnapshot: async () => {
                const snapshot = await getCreatureHome();
                return snapshot?.data || snapshot;
            },
            applySnapshot,
            applyEvent: applyLiveEvent,
            onStatusChange: setLiveStatus,
            onError: handleSnapshotError,
        });

        liveClient = createGroupLiveClient({
            groupId,
            onEvent: (event) => snapshotSync?.handleEvent(event),
            onSubscribed: () => snapshotSync?.recoverRealtime(),
            onDisconnected: () => snapshotSync?.startPolling(),
            onError: (code, error) => {
                if (stopForAccessError({ code })) return;
                if (['CSRF_INVALID', 'INVALID_REQUEST'].includes(code)) {
                    showLoadError(new ApiError(403, {
                        code,
                        message: '실시간 연결 요청을 승인할 수 없습니다. 페이지를 새로고침해 주세요.',
                    }));
                    setLiveStatus('error');
                    return;
                }
                if (code !== 'CONNECTION_ERROR') console.error(error);
                setLiveStatus('error');
            },
        });
        liveClient.connect();
        if (retryButton instanceof HTMLButtonElement) retryButton.disabled = false;
    }

    const onRetryLoad = () => {
        if (retryButton instanceof HTMLButtonElement) retryButton.disabled = true;
        setLiveStatus('connecting');
        void Promise.allSettled([
            snapshotSync?.refresh(),
            liveClient?.reconnect(),
        ]).finally(() => {
            if (isMounted && retryButton instanceof HTMLButtonElement) {
                retryButton.disabled = false;
            }
        });
    };
    const onLoginFromError = () => context.navigate('/login');
    retryButton?.addEventListener('click', onRetryLoad);
    loginButton?.addEventListener('click', onLoginFromError);
    void initializeCreaturePage();

    return {
        element,
        // 페이지를 벗어날 때 타이머와 이벤트를 정리
        cleanup: () => {
            isMounted = false;
            if (countdownIntervalId !== null) clearInterval(countdownIntervalId);
            if (deadlineTimeoutId !== null) clearTimeout(deadlineTimeoutId);
            growthAnimationTimeoutIds.forEach(timeoutId => clearTimeout(timeoutId));
            growthAnimationTimeoutIds.clear();
            retryButton?.removeEventListener('click', onRetryLoad);
            loginButton?.removeEventListener('click', onLoginFromError);
            btnQuest?.removeEventListener('click', onQuestClick);
            btnGotoShop?.removeEventListener('click', onShopClick);
            btnToggleMovement?.removeEventListener('click', onToggleMovement);
            btnToggleMovement?.removeEventListener('focus', onMovementFocus);
            btnToggleMovement?.removeEventListener('blur', onMovementBlur);
            avatarField.dispose();
            snapshotSync?.dispose();
            void liveClient?.disconnect();
        },
    };
}
