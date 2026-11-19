// @ts-check
import { createGroupChatMessage, getActiveCreatureSpecies } from '../features/creature/api.js';
import { renderCreatureGrowth } from '../features/creature/render.js';
import { getCurrentSeason } from '../features/season/api.js';
import { ApiError } from '../api/client.js';
import { getShopItem } from '../features/shop/api.js';
import { createAvatarField } from '../features/creature/avatar-field.js';
import { creatureAccessErrorRoute } from '../features/creature/access-error-policy.js';
import { groupLiveManager } from '../realtime/group-live-manager.js';
import { createRecentMessageIdRegistry } from '../realtime/recent-message-ids.js';
import { createUserReport } from '../features/group/api.js';

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
                    <span class="badge rounded-pill py-2 px-3 online-count-badge" id="online-count">접속 0명</span>
                  </div>
                </div>
        
                <!-- 크리처 및 아바타 이동 구역 -->
                <div class="border rounded-3 p-4 text-center position-relative my-2 flex-grow-1 d-flex flex-column justify-content-center align-items-center creature-avatar-container" 
                    style="min-height: 480px;" 
                    id="avatar-container">
                  
                  <div id="creature-display" class="d-flex flex-column align-items-center justify-content-center" style="visibility: hidden;">
                    <div id="creature-img-wrapper" class="mb-1 d-flex align-items-center justify-content-center">
                      <img src="${creatureLv0}" alt="크리처 이미지" id="creature-image" style="image-rendering: pixelated; image-rendering: crisp-edges;" />
                    </div>
                    <h3 class="h4 mb-0 fw-bold text-app" id="growth-stage">미선택 알</h3>
                    <span class="small text-app-muted" id="species-subtitle">???</span>
                    <span class="small text-app-muted" id="hatch-countdown" aria-live="polite"></span>
                  </div>
                </div>

                <form class="d-flex gap-2 mt-2" data-chat-form>
                  <label class="visually-hidden" for="creature-chat-content">말풍선 메시지</label>
                  <input id="creature-chat-content" class="form-control" type="text"
                         autocomplete="off" placeholder="말풍선 메시지 (최대 80자)"
                         data-chat-content disabled>
                  <button type="submit" class="btn btn-primary text-nowrap" data-chat-submit disabled>전송</button>
                </form>
                <p class="small mb-0 mt-1 text-app-muted" role="status" aria-live="polite" data-chat-status>
                  실시간 연결 후 메시지를 보낼 수 있습니다.
                </p>
                
                <!-- 알 상태에서 주·부 관리자에게만 보여주는 종 선택 안내 -->
                <div class="alert alert-info d-none mt-3 mb-2"
                     role="status"
                     data-creature-selection-guide>
                
                    <div class="d-flex flex-wrap justify-content-between
                                align-items-center gap-3">
                
                        <div class="flex-grow-1">
                            <h3 class="h6 fw-bold mb-1"
                                data-creature-guide-title>
                                크리처 종을 선택해 주세요
                            </h3>
                
                            <p class="small mb-0"
                               data-creature-guide-description>
                            </p>
                        </div>
                
                        <button type="button"
                                class="btn btn-success flex-shrink-0"
                                data-creature-guide-button>
                            크리처 종 선택하기
                        </button>
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
                  <div class="d-grid gap-2">
                    <button type="button" class="btn btn-sm px-3 fw-bold py-1.5 text-app-primary bg-app-background border-app" id="btn-goto-shop" disabled>판매 상품 보기</button>
                    <button type="button" class="btn btn-sm btn-outline-danger d-none" data-report-chat>현재 말풍선 신고</button>
                  </div>
                </div>
              </div>
        
            </div>
          </div>
        </div>

    </div>

    <dialog class="border-0 rounded-4 shadow p-0" data-chat-report-dialog>
      <form class="p-4" style="width:min(90vw,440px)" data-chat-report-form>
        <h2 class="h5 fw-bold">말풍선 신고</h2>
        <p class="small text-app-muted" data-chat-report-target></p>
        <label class="form-label" for="chat-report-category">신고 분류</label>
        <select id="chat-report-category" class="form-select mb-3" required data-chat-report-category>
          <option value="">선택해 주세요</option>
          <option value="HARASSMENT">괴롭힘</option>
          <option value="SPAM">스팸</option>
          <option value="INAPPROPRIATE_CONTENT">부적절한 콘텐츠</option>
          <option value="OTHER">기타</option>
        </select>
        <label class="form-label" for="chat-report-content">신고 내용</label>
        <textarea id="chat-report-content" class="form-control" rows="4" maxlength="1000"
                  required data-chat-report-content></textarea>
        <p class="small text-danger mt-2 mb-0" role="alert" data-chat-report-error></p>
        <div class="d-flex justify-content-end gap-2 mt-3">
          <button type="button" class="btn btn-outline-secondary" data-chat-report-cancel>취소</button>
          <button type="submit" class="btn btn-danger" data-chat-report-submit>신고 접수</button>
        </div>
      </form>
    </dialog>
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
    const chatForm = /** @type {HTMLFormElement} */ (element.querySelector('[data-chat-form]'));
    const chatContent = /** @type {HTMLInputElement} */ (element.querySelector('[data-chat-content]'));
    const chatSubmit = /** @type {HTMLButtonElement} */ (element.querySelector('[data-chat-submit]'));
    const chatStatus = /** @type {HTMLElement} */ (element.querySelector('[data-chat-status]'));
    const reportChatButton = /** @type {HTMLButtonElement} */ (element.querySelector('[data-report-chat]'));
    const reportDialog = /** @type {HTMLDialogElement} */ (element.querySelector('[data-chat-report-dialog]'));
    const reportForm = /** @type {HTMLFormElement} */ (element.querySelector('[data-chat-report-form]'));
    const reportTarget = /** @type {HTMLElement} */ (element.querySelector('[data-chat-report-target]'));
    const reportCategory = /** @type {HTMLSelectElement} */ (element.querySelector('[data-chat-report-category]'));
    const reportContent = /** @type {HTMLTextAreaElement} */ (element.querySelector('[data-chat-report-content]'));
    const reportError = /** @type {HTMLElement} */ (element.querySelector('[data-chat-report-error]'));
    const reportCancel = /** @type {HTMLButtonElement} */ (element.querySelector('[data-chat-report-cancel]'));
    const reportSubmit = /** @type {HTMLButtonElement} */ (element.querySelector('[data-chat-report-submit]'));

    // 종 선택 진입 안내의 DOM 요소입니다.
    const creatureGuide = element.querySelector('[data-creature-selection-guide]');
    const creatureGuideTitle = element.querySelector('[data-creature-guide-title]');
    const creatureGuideDescription = element.querySelector('[data-creature-guide-description]');
    const creatureGuideButton = element.querySelector('[data-creature-guide-button]');

    const loadError = element.querySelector('#creature-load-error');
    const loadErrorMessage = element.querySelector('#creature-load-error-message');
    const loadErrorDetails = element.querySelector('#creature-load-error-details');
    const retryButton = element.querySelector('#creature-error-retry');
    const loginButton = element.querySelector('#creature-error-login');

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
    let selectedChatMessage = null;
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

    function selectAvatar(member, scrollToDetails = false, chatMessage = null) {
        selectedAvatarUserId = member.userId;
        selectedChatMessage = chatMessage;
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
        reportChatButton.classList.toggle(
            'd-none',
            !chatMessage || String(member.userId) === String(currentUserId),
        );

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
        onSelect: (member, chatMessage) => selectAvatar(member, true, chatMessage),
        onMessageExpired: (message) => {
            if (selectedChatMessage?.messageId !== message.messageId) return;
            selectedChatMessage = null;
            reportChatButton.classList.add('d-none');
        },
    });
    avatarField.setMovementEnabled(isAvatarMoving);

    // 3. API 조회, 크리처 성장 렌더링 및 마감 감시 로직
    let isMounted = true;
    let currentUserId = null;
    const receivedMessageIds = createRecentMessageIdRegistry();
    const pendingChatMessages = new Map();
    let sendingChat = false;
    let reportingChat = false;
    let reportMessage = null;
    let chatRetryTimer = null;
    let unsubscribeChatMessages = null;
    let unsubscribeLiveStatus = null;

    function syncSelectedChatMessage(message) {
        if (String(selectedAvatarUserId) !== String(message.senderUserId)
                || String(currentUserId) === String(message.senderUserId)) return;
        selectedChatMessage = message;
        reportChatButton.classList.remove('d-none');
    }

    function displayChatMessage(message) {
        if (!message?.messageId || !receivedMessageIds.remember(message.messageId)) return;
        if (!avatarField.showMessage(message)) {
            pendingChatMessages.set(message.messageId, {
                message,
                expiresAt: Date.now() + 5000,
            });
        } else syncSelectedChatMessage(message);
    }

    function flushPendingChatMessages() {
        const now = Date.now();
        for (const [messageId, pending] of pendingChatMessages) {
            if (pending.expiresAt <= now) {
                pendingChatMessages.delete(messageId);
            } else if (avatarField.showMessage(pending.message)) {
                pendingChatMessages.delete(messageId);
                syncSelectedChatMessage(pending.message);
            }
        }
    }

    function updateChatAvailability(state = groupLiveManager.getState()) {
        const available = state.status === 'live' && !sendingChat && chatRetryTimer === null;
        chatContent.disabled = !available;
        chatSubmit.disabled = !available;
        if (!sendingChat && chatRetryTimer === null) {
            chatStatus.textContent = available
                ? '메시지는 5초 동안 아바타 위에 표시됩니다.'
                : '실시간 연결 후 메시지를 보낼 수 있습니다.';
        }
    }

    async function submitChat(event) {
        event.preventDefault();
        if (sendingChat || groupLiveManager.getState().status !== 'live') return;
        const contentText = chatContent.value.trim();
        if (!contentText || [...contentText].length > 80) {
            chatStatus.textContent = '메시지는 공백 제외 1~80자로 입력해 주세요.';
            return;
        }
        sendingChat = true;
        updateChatAvailability();
        chatStatus.textContent = '메시지를 보내는 중입니다.';
        try {
            const message = await createGroupChatMessage(contentText);
            if (!isMounted) return;
            displayChatMessage(message);
            chatContent.value = '';
            chatStatus.textContent = '메시지를 보냈습니다.';
        } catch (error) {
            if (!isMounted) return;
            if (error instanceof ApiError && error.status === 429) {
                const retryAfter = error.retryAfter ?? 1;
                chatStatus.textContent = `${retryAfter}초 후 다시 보낼 수 있습니다.`;
                if (chatRetryTimer !== null) window.clearTimeout(chatRetryTimer);
                chatRetryTimer = window.setTimeout(() => {
                    chatRetryTimer = null;
                    if (isMounted) updateChatAvailability();
                }, retryAfter * 1000);
            } else {
                chatStatus.textContent = getApiErrorMessage(error);
            }
        } finally {
            sendingChat = false;
            if (isMounted && chatRetryTimer === null) updateChatAvailability();
        }
    }

    function openChatReport() {
        if (!selectedChatMessage || reportingChat) return;
        reportMessage = selectedChatMessage;
        reportForm.reset();
        reportError.textContent = '';
        reportTarget.textContent = `${selectedChatMessage.senderNickname}: ${selectedChatMessage.content}`;
        reportDialog.showModal();
        reportCategory.focus();
    }

    async function submitChatReport(event) {
        event.preventDefault();
        if (!reportMessage || reportingChat) return;
        const contentText = reportContent.value.trim();
        if (!reportCategory.value || !contentText) {
            reportError.textContent = '신고 분류와 내용을 입력해 주세요.';
            return;
        }
        reportingChat = true;
        reportSubmit.disabled = true;
        reportCancel.disabled = true;
        reportError.textContent = '';
        try {
            const result = await createUserReport({
                reportedUserId: reportMessage.senderUserId,
                category: reportCategory.value,
                content: contentText,
                chatMessageId: reportMessage.messageId,
            });
            if (!isMounted) return;
            reportDialog.close();
            chatStatus.textContent = `신고가 접수되었습니다. (접수 번호: ${result.reportId})`;
        } catch (error) {
            if (isMounted) {
                reportError.textContent = error instanceof ApiError && error.status === 404
                    ? '신고할 메시지가 만료되었거나 더 이상 확인할 수 없습니다.'
                    : getApiErrorMessage(error);
            }
        } finally {
            reportingChat = false;
            if (isMounted) {
                reportSubmit.disabled = false;
                reportCancel.disabled = false;
            }
        }
    }

    chatForm.addEventListener('submit', submitChat);
    reportChatButton.addEventListener('click', openChatReport);
    reportForm.addEventListener('submit', submitChatReport);
    const onChatReportCancel = () => {
        if (!reportingChat) reportDialog.close();
    };
    const onChatReportDialogCancel = (event) => {
        if (reportingChat) event.preventDefault();
    };
    reportCancel.addEventListener('click', onChatReportCancel);
    reportDialog.addEventListener('cancel', onChatReportDialogCancel);

    // 마지막으로 수신한 서버 Snapshot을 안내 표시용으로 보관합니다.
    let latestCreatureGuideData = null;
    let latestGuideSpeciesList = [];

    /**
     * 현재 사용자가 크리처 종을 선택할 수 있는 관리자 역할인지 확인합니다.
     * Router에서 전달받은 /api/v1/me의 현재 멤버십 역할을 사용합니다.
     * 역할이 변경되었다면 다음 페이지에서 서버가 다시 검증합니다.
     *
     * @returns {boolean} PRIMARY 또는 DEPUTY 여부
     */
    function isCreatureGuideAdmin() {
        const role = context.currentUser?.membership?.role;

        return role === 'PRIMARY' || role === 'DEPUTY';
    }

    /**
     * 현재 크리처 Snapshot에서 종 선택 안내가 가능한지 판정합니다.
     * 알 상태, 유효한 시즌, 선택 마감 전이라는 조건을 모두 확인합니다.
     * 잘못된 시간 정보는 선택 가능 상태로 취급하지 않습니다.
     *
     * @returns {boolean} 안내를 노출할지 여부
     */
    function canShowCreatureSelectionGuide() {
        if (!isMounted || !isCreatureGuideAdmin()) return false;

        const creature = latestCreatureGuideData?.creature;
        const season = latestCreatureGuideData?.season;

        if (!creature || !season) return false;

        const startAt = Date.parse(season.startAt ?? '');
        const endAt = Date.parse(season.endAt ?? '');
        const deadlineAt = Date.parse(
            creature.selectionDeadlineAt ?? ''
        );

        // 시간이 누락되거나 잘못되었을 경우 안내하지 않습니다.
        if (
            !Number.isFinite(startAt) ||
            !Number.isFinite(endAt) ||
            !Number.isFinite(deadlineAt)
        ) {
            return false;
        }

        const now = Date.now();

        return (
            season.status === 'ACTIVE' &&
            creature.growthStage === 'EGG' &&
            now >= startAt &&
            now < endAt &&
            now < deadlineAt
        );
    }

    /**
     * 알 상태와 기존 종 선택 여부에 따라 안내 문구를 구성합니다.
     * 서버 Snapshot의 종 ID를 활성 종 목록과 대조해 이름을 표시합니다.
     * 시간 만료 또는 부화 시 안내 영역을 즉시 숨깁니다.
     */
    function renderCreatureSelectionGuide() {
        if (!creatureGuide) return;

        const visible = canShowCreatureSelectionGuide();
        creatureGuide.classList.toggle('d-none', !visible);

        if (!visible) return;

        const speciesId =
            latestCreatureGuideData?.creature?.creatureSpeciesId;

        const alreadySelected =
            speciesId !== null && speciesId !== undefined;

        if (!alreadySelected) {
            creatureGuideTitle.textContent =
                '아직 크리처 종을 선택하지 않았습니다.';

            creatureGuideDescription.textContent =
                '부화 전까지 원하는 종을 선택할 수 있습니다. ' +
                '선택하지 않으면 무작위로 결정됩니다.';

            creatureGuideButton.textContent =
                '크리처 종 선택하기';

            return;
        }

        // 기존 크리처 홈에서 조회한 활성 종 목록을 그대로 재사용합니다.
        const selectedSpecies = latestGuideSpeciesList.find(
            species => String(species.creatureSpeciesId) ===
                String(speciesId)
        );

        const speciesName =
            selectedSpecies?.name ?? `종 ID ${speciesId}`;

        creatureGuideTitle.textContent =
            `현재 선택된 크리처: ${speciesName}`;

        creatureGuideDescription.textContent =
            '마감 전까지 다른 크리처 종으로 변경할 수 있습니다.';

        creatureGuideButton.textContent =
            '크리처 선택 변경하기';
    }

    /**
     * 종 선택 안내 버튼을 클릭하면 그룹 관리의 크리처 탭으로 이동합니다.
     * 클릭 순간에도 역할·시즌·마감 조건을 다시 확인합니다.
     * 이동 후 Router와 관리자 페이지가 서버 정보를 재조회합니다.
     */
    function onCreatureGuideClick() {
        if (!canShowCreatureSelectionGuide()) {
            renderCreatureSelectionGuide();
            return;
        }

        // data-link를 사용하면 기존 Router에서 Query String이 제거됩니다.
        // 따라서 navigate()에 전체 경로를 직접 전달합니다.
        context.navigate('/group-admin?tab=creature');
    }

    /**
     * 다른 브라우저 탭에서 돌아왔을 때 안내 표시를 갱신합니다.
     * 백그라운드 타이머의 지연과 관계없이 현재 시각을 재평가합니다.
     * 마감이 지났으면 더 이상 종 선택 버튼을 노출하지 않습니다.
     */
    function onCreatureGuideVisibilityChange() {
        if (!document.hidden) {
            renderCreatureSelectionGuide();
        }
    }

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
        // 기존 부화 카운트다운과 종 선택 안내의 마감 상태를 동기화합니다.
        renderCreatureSelectionGuide();

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

            // 마감 시각 도달 즉시 종 선택 안내를 숨깁니다.
            renderCreatureSelectionGuide();

            try {
                const [refreshed, seasonRes, speciesRes] = await Promise.all([
                    groupLiveManager.refresh(),
                    getCurrentSeason(),
                    getActiveCreatureSpecies()
                ]);

                if (!isMounted) return;

                const creatureData = groupLiveManager.getSnapshot();
                if (!refreshed || !creatureData) {
                    throw new Error('크리처 상태를 불러오지 못했습니다.');
                }
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

        // 화면이 다시 그려질 때 최신 크리처 상태로 안내 문구를 갱신합니다.
        latestCreatureGuideData = creatureData;
        latestGuideSpeciesList = speciesList;

        renderCreatureSelectionGuide();

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
        flushPendingChatMessages();

        if (selectedAvatarUserId !== null) {
            const selectedMember = onlineMembers.find((member) =>
                String(member.userId) === String(selectedAvatarUserId));
            if (selectedMember) selectAvatar(selectedMember, false, selectedChatMessage);
            else {
                selectedAvatarUserId = null;
                avatarInfoCard?.classList.add('d-none');
            }
        }
    }

    let currentCreatureData = null;
    let currentSeasonData = null;
    let currentSpeciesList = [];
    let unsubscribeSnapshots = null;
    let unsubscribeSyncErrors = null;

    function handleSnapshotError(error) {
        // 인증·권한 오류의 이동은 공통 라우터가 처리합니다.
        if (!creatureAccessErrorRoute(error)) showLoadError(error);
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

        unsubscribeSnapshots = groupLiveManager.subscribeSnapshots((snapshot) => {
            if (!isMounted || String(snapshot.groupId) !== String(groupId)) return;
            applySnapshot(snapshot);
        });
        unsubscribeSyncErrors = groupLiveManager.subscribeErrors((error) => {
            if (isMounted) handleSnapshotError(error);
        });
        unsubscribeChatMessages = groupLiveManager.subscribeChatMessages((message) => {
            if (isMounted) displayChatMessage(message);
        });
        unsubscribeLiveStatus = groupLiveManager.subscribeStatus(updateChatAvailability);

        // 화면 재진입 시에도 조회를 요청하며 중복 조회는 공통 모듈에서 합칩니다.
        void groupLiveManager.refresh();
        if (retryButton instanceof HTMLButtonElement) retryButton.disabled = false;
    }

    const onRetryLoad = () => {
        if (retryButton instanceof HTMLButtonElement) retryButton.disabled = true;
        void Promise.allSettled([
            groupLiveManager.refresh(),
            groupLiveManager.reconnect(),
        ]).finally(() => {
            if (isMounted && retryButton instanceof HTMLButtonElement) {
                retryButton.disabled = false;
            }
        });
    };
    const onLoginFromError = () => context.navigate('/login');
    retryButton?.addEventListener('click', onRetryLoad);
    loginButton?.addEventListener('click', onLoginFromError);
    // 종 선택 안내 버튼
    creatureGuideButton?.addEventListener(
        'click',
        onCreatureGuideClick
    );

    // 백그라운드에서 돌아왔을 때 마감 시각 재확인
    document.addEventListener(
        'visibilitychange',
        onCreatureGuideVisibilityChange
    );

    void initializeCreaturePage();

    return {
        element,
        // 페이지를 벗어날 때 타이머와 이벤트를 정리
        cleanup: () => {
            isMounted = false;
            if (countdownIntervalId !== null) clearInterval(countdownIntervalId);
            if (deadlineTimeoutId !== null) clearTimeout(deadlineTimeoutId);
            if (chatRetryTimer !== null) clearTimeout(chatRetryTimer);
            growthAnimationTimeoutIds.forEach(timeoutId => clearTimeout(timeoutId));
            growthAnimationTimeoutIds.clear();
            retryButton?.removeEventListener('click', onRetryLoad);
            loginButton?.removeEventListener('click', onLoginFromError);
            btnQuest?.removeEventListener('click', onQuestClick);
            btnGotoShop?.removeEventListener('click', onShopClick);
            btnToggleMovement?.removeEventListener('click', onToggleMovement);
            btnToggleMovement?.removeEventListener('focus', onMovementFocus);
            btnToggleMovement?.removeEventListener('blur', onMovementBlur);
            // 종 선택 안내 이벤트를 정리합니다.
            creatureGuideButton?.removeEventListener(
                'click',
                onCreatureGuideClick
            );

            document.removeEventListener(
                'visibilitychange',
                onCreatureGuideVisibilityChange
            );

            // 이전 페이지의 안내용 Snapshot을 정리합니다.
            latestCreatureGuideData = null;
            latestGuideSpeciesList = [];
            receivedMessageIds.clear();
            pendingChatMessages.clear();

            avatarField.dispose();
            unsubscribeSnapshots?.();
            unsubscribeSyncErrors?.();
            unsubscribeChatMessages?.();
            unsubscribeLiveStatus?.();
            chatForm.removeEventListener('submit', submitChat);
            reportChatButton.removeEventListener('click', openChatReport);
            reportForm.removeEventListener('submit', submitChatReport);
            reportCancel.removeEventListener('click', onChatReportCancel);
            reportDialog.removeEventListener('cancel', onChatReportDialogCancel);
            if (reportDialog.open) reportDialog.close();
        },
    };
}
