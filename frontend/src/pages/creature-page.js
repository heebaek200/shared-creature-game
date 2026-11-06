// @ts-check
import { getActiveCreatureSpecies, getCreatureHome } from '../features/creature/api.js';
import { renderCreatureGrowth } from '../features/creature/render.js';
import { getCurrentSeason } from '../features/season/api.js';
import { ApiError } from '../api/client.js';
import creatureLv0 from '../../assets/creature/lv0.png';

/**
 * 크리처 홈 페이지 마운트 및 렌더링합니다.
 * @param {{ navigate: (path: string) => void }} context
 */
export function creaturePage(context) {
    const element = document.createElement('main');
    element.className = 'd-flex flex-column min-vh-100 app-shell w-100 m-0 p-0 overflow-x-hidden';
    element.style.backgroundColor = 'var(--app-background)';
    element.style.color = 'var(--app-text)';
    element.style.padding = '0';
    element.style.margin = '0';
    element.style.maxWidth = 'none';

    element.innerHTML = `
    <!-- 상단 전체 헤더 (높이 고정 60px) -->
    <header class="position-fixed top-0 start-0 w-100 border-bottom px-4 py-3 d-flex justify-content-between align-items-center z-3" style="background-color: var(--app-surface); border-color: var(--app-border) !important; height: 60px;">
      <div class="d-flex align-items-center gap-2">
        <span class="fs-5 fw-bold d-flex align-items-center gap-2" style="color: var(--app-primary);">
          <span class="rounded-circle d-inline-block" style="width: 10px; height: 10px; background-color: var(--app-primary);"></span>
          다 같이 키우는 생명체
        </span>
      </div>
      <div class="d-flex align-items-center gap-2">
        <button type="button" class="btn btn-sm btn-outline-secondary theme-toggle px-3">테마 전환 ◐</button>
        <button type="button" class="btn btn-sm btn-outline-secondary px-3">로그아웃</button>
      </div>
    </header>

    <!-- 헤더 아래 영역부터 화면 끝까지 꽉 채우는 래퍼 -->
    <div class="d-flex flex-grow-1 w-100" style="padding-top: 60px;">
      
      <!-- 좌측 서비스 메뉴바 -->
      <nav class="d-none d-lg-flex flex-column flex-shrink-0 p-4 border-end" style="width: 230px; background-color: var(--app-surface); border-color: var(--app-border) !important;">
        <div class="small px-2 mb-3 mt-2 fw-bold" style="color: var(--app-muted);">서비스 메뉴</div>
        <ul class="nav nav-pills flex-column mb-auto gap-1">
          <li class="nav-item">
            <a href="/creature" class="nav-link active text-white py-2 px-3" style="background-color: var(--app-primary);" data-link>크리처</a>
          </li>
          <li class="nav-item">
            <a href="/quests" class="nav-link py-2 px-3" style="color: var(--app-text);" data-link>퀘스트</a>
          </li>
          <li class="nav-item">
            <a href="/ranking" class="nav-link py-2 px-3" style="color: var(--app-text);" data-link>랭킹</a>
          </li>
          <li class="nav-item">
            <a href="/groups" class="nav-link py-2 px-3" style="color: var(--app-text);" data-link>그룹</a>
          </li>
          <li class="nav-item">
            <a href="/shop" class="nav-link py-2 px-3" style="color: var(--app-text);" data-link>상점</a>
          </li>
          <li class="nav-item">
            <a href="/avatar" class="nav-link py-2 px-3" style="color: var(--app-text);" data-link>아바타</a>
          </li>
          <li class="nav-item">
            <a href="/billing" class="nav-link py-2 px-3" style="color: var(--app-text);" data-link>결제 내역</a>
          </li>
          <li class="nav-item">
            <a href="/group-manage" class="nav-link py-2 px-3" style="color: var(--app-text);" data-link>그룹 관리</a>
          </li>
        </ul>
      </nav>

      <!-- 우측 메인 콘텐츠 영역 -->
      <div class="flex-grow-1 d-flex flex-column min-w-0">
        <div class="container-fluid px-5 pt-4 pb-4 d-flex flex-column flex-grow-1">
          
          <!-- 메인 콘텐츠 타이틀 및 상태 영역 -->
          <div class="d-flex justify-content-between align-items-center mb-4 px-1">
            <div>
              <div class="d-flex align-items-center gap-2">
                <h1 class="h3 mb-0 fw-bold" style="color: var(--app-text);">크리처 홈</h1>
                <span class="badge border font-monospace px-2 py-1" style="background-color: var(--app-background); color: var(--app-text); border-color: var(--app-border) !important;" id="group-name-badge">소속 그룹 없음</span>
              </div>
              <p class="mb-0 mt-1" style="color: var(--app-muted);" id="group-season-info">로딩 중...</p>
            </div>
            <div class="d-flex align-items-center gap-3">
              <button type="button" class="btn border px-3 py-1.5 fw-bold" style="background-color: var(--app-surface); color: var(--app-primary); border-color: var(--app-border) !important;" id="btn-toggle-movement">
                아바타 이동 : ON
              </button>
              <span class="badge rounded-pill px-3 py-2" style="background-color: var(--app-text); color: var(--app-surface);" id="connection-status">
                <span style="color: var(--app-primary);" class="me-1">●</span> 실시간
              </span>
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
          <div class="row g-4 flex-grow-1 align-items-stretch">
            
            <!-- 좌측: 공동 크리처 영역 -->
            <div class="col-xl-9 col-lg-8 d-flex flex-column">
              <div class="card shadow-sm flex-grow-1 border" style="background-color: var(--app-surface); border-color: var(--app-border) !important; border-radius: 1.25rem;">
                <div class="card-body d-flex flex-column justify-content-between p-3 p-xxl-4 h-100">
                  <div class="d-flex flex-column flex-grow-1">
                    <div class="d-flex justify-content-between align-items-center mb-2">
                      <h2 class="h5 card-title mb-0 fw-bold" style="color: var(--app-text);" id="creature-title-name">시즌 크리처 - ???</h2>
                      <span class="badge rounded-pill py-2 px-3" style="background-color: var(--app-muted); color: var(--app-surface);" id="online-count">접속 0명</span>
                    </div>

                    <!-- 크리처 및 아바타 이동 구역 -->
                    <div class="border rounded-3 p-4 text-center position-relative my-2 flex-grow-1 d-flex flex-column justify-content-center align-items-center" style="min-height: 480px; background-color: var(--app-background); border-color: var(--app-border) !important;" id="avatar-container">
                      
                      <div id="creature-display" class="d-flex flex-column align-items-center justify-content-center" style="visibility: hidden;">
                        <div id="creature-img-wrapper" class="mb-1 d-flex align-items-center justify-content-center">
                          <img src="${creatureLv0}" alt="크리처 이미지" id="creature-image" style="image-rendering: pixelated; image-rendering: crisp-edges;" />
                        </div>
                        <h3 class="h4 mb-0 fw-bold" style="color: var(--app-text);" id="growth-stage">미선택 알</h3>
                        <span class="small" style="color: var(--app-muted);" id="species-subtitle">???</span>
                        <span class="small" style="color: var(--app-muted);" id="hatch-countdown" aria-live="polite"></span>
                      </div>

                    </div>
                  </div>

                  <!-- 경험치 및 퀘스트 하단 구역 -->
                  <div class="mt-2">
                    <div class="d-flex justify-content-end fw-bold mb-2" style="color: var(--app-text);" id="exp-text">
                      <span>0 / - XP</span>
                    </div>
                    <div class="progress mb-3" style="height: 12px; background-color: var(--app-border);">
                      <!-- 부드러운 바 차오름을 위한 CSS 트랜지션 추가 -->
                      <div class="progress-bar" role="progressbar" id="exp-bar" aria-label="크리처 성장 경험치 진행률" aria-valuetext="경험치 정보 불러오는 중" style="width: 0%; background-color: var(--app-primary); transition: width 2.8s ease-in-out;" aria-valuenow="0" aria-valuemin="0" aria-valuemax="100"></div>
                    </div>
                    <div class="text-center">
                      <button type="button" class="btn btn-primary fw-bold px-4 py-2" id="btn-quest">오늘 퀘스트 확인</button>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 우측: 아바타 상세 정보 카드 영역 -->
            <div class="col-xl-3 col-lg-4 d-flex flex-column">
              <div class="card shadow-sm flex-grow-1 border" style="background-color: var(--app-surface); border-color: var(--app-border) !important; border-radius: 1.25rem;">
                <div class="card-body p-4 d-flex flex-column justify-content-between h-100">
                  <div>
                    <h2 class="h6 mb-2" style="color: var(--app-muted);">오늘 그룹 경험치</h2>
                    <div class="h3 fw-bold mb-4" style="color: var(--app-text);" id="today-exp">+0 XP</div>

                    <h3 class="h6 fw-bold mb-3" style="color: var(--app-text);">최근 기여</h3>
                    <ul class="list-group list-group-flush mb-4" id="recent-contributions-list"></ul>
                  </div>

                  <!-- 아바타 선택 시에만 나타나는 유저 정보창 -->
                  <div class="card border mt-3 d-none shadow-sm" style="background-color: var(--app-surface); border-color: var(--app-border) !important;" id="avatar-info-card">
                    <div class="card-header py-2.5" style="background-color: var(--app-surface); border-color: var(--app-border) !important;">
                      <div class="d-flex justify-content-between align-items-center">
                        <span class="fw-bold text-nowrap" id="avatar-toggle-title">USER INFO [ON]</span>
                      </div>
                    </div>
                    <div class="card-body collapse show pt-2" id="avatar-detail-collapse" style="overflow-x: auto; overflow-y: hidden;">
                      <p class="mb-2 fw-bold text-nowrap" style="color: var(--app-primary);" id="avatar-info-header">-</p>
                      <table class="table table-sm align-middle mb-2" style="color: var(--app-text); border-color: var(--app-border); white-space: nowrap;">
                        <thead>
                          <tr style="background-color: var(--app-background); color: var(--app-text);">
                            <th style="font-style: italic" class="py-1.5">slot</th>
                            <th style="font-style: italic" class="py-1.5">item</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr>
                            <td style="color: var(--app-muted);" class="py-1.5">HEAD</td>
                            <td id="slot-head" class="py-1.5">-</td>
                          </tr>
                          <tr>
                            <td style="color: var(--app-muted);" class="py-1.5">FACE</td>
                            <td id="slot-face" class="py-1.5">-</td>
                          </tr>
                          <tr>
                            <td style="color: var(--app-muted);" class="py-1.5">BODY</td>
                            <td id="slot-body" class="py-1.5">-</td>
                          </tr>
                          <tr>
                            <td style="color: var(--app-muted);" class="py-1.5">ACCESSORY</td>
                            <td id="slot-accessory" class="py-1.5">-</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                    <div class="card-footer bg-transparent border-0 pt-0 pb-3 text-center">
                      <button type="button" class="btn btn-sm px-3 fw-bold w-100 py-1.5" style="background-color: var(--app-background); color: var(--app-primary); border: 1px solid var(--app-border);" id="btn-goto-shop">상점에서 보기</button>
                    </div>
                  </div>

                </div>
              </div>
            </div>

          </div>

        </div>
      </div>
    </div>
  `;

    // 2. DOM 요소 참조 및 이벤트 바인딩
    const btnQuest = element.querySelector('#btn-quest');
    const avatarInfoCard = element.querySelector('#avatar-info-card');
    const avatarToggleTitle = element.querySelector('#avatar-toggle-title');
    const btnGotoShop = element.querySelector('#btn-goto-shop');
    const themeToggle = element.querySelector('.theme-toggle');
    const btnToggleMovement = element.querySelector('#btn-toggle-movement');
    const avatarContainer = element.querySelector('#avatar-container');
    const loadError = element.querySelector('#creature-load-error');
    const loadErrorMessage = element.querySelector('#creature-load-error-message');
    const loadErrorDetails = element.querySelector('#creature-load-error-details');
    const retryButton = element.querySelector('#creature-error-retry');
    const loginButton = element.querySelector('#creature-error-login');

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

    let isAvatarMoving = true;

    const onThemeToggle = () => {
        const next = document.documentElement.dataset.bsTheme === 'dark' ? 'light' : 'dark';
        document.documentElement.dataset.bsTheme = next;
        localStorage.setItem('theme', next);
    };
    themeToggle?.addEventListener('click', onThemeToggle);

    const onQuestClick = () => {
        context.navigate('/quests');
    };
    btnQuest?.addEventListener('click', onQuestClick);

    const onShopClick = () => {
        context.navigate('/shop');
    };
    btnGotoShop?.addEventListener('click', onShopClick);

    const onToggleMovement = () => {
        isAvatarMoving = !isAvatarMoving;
        if (btnToggleMovement) {
            btnToggleMovement.textContent = isAvatarMoving ? '아바타 이동 : ON' : '아바타 이동 : OFF';
            if (isAvatarMoving) {
                btnToggleMovement.style.color = 'var(--app-primary)';
                btnToggleMovement.style.backgroundColor = 'var(--app-surface)';
            } else {
                btnToggleMovement.style.color = 'var(--app-muted)';
                btnToggleMovement.style.backgroundColor = 'var(--app-background)';
            }
        }
    };
    btnToggleMovement?.addEventListener('click', onToggleMovement);

    avatarContainer?.addEventListener('click', (e) => {
        if (e.target === avatarContainer || e.target.closest('.d-flex.flex-column.align-items-center.justify-content-center')) {
            avatarInfoCard?.classList.add('d-none');
        }
    });

    // 3. API 및 성장 단계 계산 로직 (단계별 순차 진화 연출 포함)
    let isMounted = true;
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

    function updateRemainingTimeLabels(seasonData, hatchDeadlineAt) {
        const now = Date.now();
        const groupSeasonInfo = element.querySelector('#group-season-info');
        const hatchCountdown = element.querySelector('#hatch-countdown');

        if (groupSeasonInfo && seasonData) {
            const endAtTime = seasonData.endAt ? new Date(seasonData.endAt).getTime() : NaN;
            const diffMs = endAtTime - now;
            let remainingText = '종료됨';

            if (Number.isFinite(endAtTime) && diffMs > 0) {
                const diffHours = diffMs / (1000 * 60 * 60);
                if (diffHours >= 24) {
                    remainingText = `${Math.ceil(diffHours / 24)}일 남음`;
                } else if (diffHours >= 1) {
                    remainingText = `${Math.ceil(diffHours)}시간 남음`;
                } else {
                    remainingText = `시즌 종료까지 ${Math.ceil(diffMs / (1000 * 60))}분`;
                }
            }

            groupSeasonInfo.textContent = `시즌 ${seasonData.seasonNumber || 1} · ${remainingText}`;
        }

        if (hatchCountdown) {
            if (!Number.isFinite(hatchDeadlineAt)) {
                hatchCountdown.textContent = '';
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
    }

    function startRemainingTimeUpdates(seasonData, hatchDeadlineAt, hasAssignedSpecies) {
        if (countdownIntervalId !== null) clearInterval(countdownIntervalId);
        if (deadlineTimeoutId !== null) clearTimeout(deadlineTimeoutId);

        updateRemainingTimeLabels(seasonData, hatchDeadlineAt);
        countdownIntervalId = window.setInterval(() => {
            if (isMounted) updateRemainingTimeLabels(seasonData, hatchDeadlineAt);
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
                const currentSeasonData = seasonRes.data || seasonRes;
                const speciesList = speciesRes.data || speciesRes;
                const refreshedCreature = creatureData.creature || {};
                const hasAssignedSpecies = refreshedCreature.creatureSpeciesId !== null
                    && refreshedCreature.creatureSpeciesId !== undefined;

                if (!hasAssignedSpecies) {
                    clearLoadError();
                    const hatchCountdown = element.querySelector('#hatch-countdown');
                    if (retryCount < maxAssignmentRetries) {
                        if (hatchCountdown) hatchCountdown.textContent = '크리처 배정 확인 중...';
                        deadlineTimeoutId = window.setTimeout(
                            () => refreshAtDeadline(retryCount + 1),
                            assignmentRetryDelayMs
                        );
                    } else if (hatchCountdown) {
                        hatchCountdown.textContent = '크리처 배정이 아직 완료되지 않았습니다. 잠시 후 다시 확인해 주세요.';
                    }
                    return;
                }

                updateUI(creatureData, currentSeasonData, speciesList);
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
        } else if (!hasAssignedSpecies) {
            // 마감 이후 첫 접속에서도 배정 결과를 즉시 조회하고, 필요하면 짧게 재시도합니다.
            void refreshAtDeadline();
        }
    }

    function updateUI(creatureData, seasonData, speciesList = []) {
        if (!creatureData) return;
        clearLoadError();

        const onlineMembers = creatureData.onlineMembers || [];
        const recentContributions = creatureData.recentContributions || [];
        // TODO(identity issue): currentUserId는 임시 크리처 홈 응답 필드입니다.
        // 사용자 정보 API 연동이 정리되면 그 응답의 user.userId를 사용하고 이 의존성을 제거합니다.
        const currentUserId = creatureData.currentUserId ?? null;
        const isCurrentUser = (userId) =>
            currentUserId !== null
            && userId !== null
            && userId !== undefined
            && String(userId) === String(currentUserId);

        const groupNameBadge = element.querySelector('#group-name-badge');
        if (groupNameBadge) groupNameBadge.textContent = creatureData.groupName || '소속 그룹 없음';

        const growthUnlockAt = renderCreatureGrowth({
            element,
            creatureData,
            seasonData,
            speciesList,
            currentUserId,
            isMounted: () => isMounted,
            scheduleTimeout: scheduleGrowthAnimationTimeout,
        });
        const hasAssignedSpecies = creatureData.creature?.creatureSpeciesId !== null
            && creatureData.creature?.creatureSpeciesId !== undefined;
        startRemainingTimeUpdates(seasonData, growthUnlockAt, hasAssignedSpecies);

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
                li.className = 'list-group-item d-flex justify-content-between align-items-center px-0 bg-transparent py-1';
                li.style.borderColor = 'var(--app-border)';
                li.style.color = 'var(--app-text)';

                const spanName = document.createElement('span');
                const isMe = isCurrentUser(contribution.userId);
                spanName.textContent = isMe ? `${contribution.nickname} (나)` : contribution.nickname;

                const spanAmount = document.createElement('span');
                spanAmount.className = 'fw-bold';
                spanAmount.style.color = 'var(--app-primary)';
                spanAmount.textContent = `+${contribution.amount} XP`;

                li.appendChild(spanName);
                li.appendChild(spanAmount);
                contributionsList.appendChild(li);
            });
        }

        // 공동 영역에 10개 구역 테두리를 표시하고, 각 구역에 최대 2명씩 배치합니다.
        avatarContainer?.querySelectorAll('.avatar-zone').forEach(el => el.remove());

        const avatarZones = [
            { top: '1%', left: '1%' },
            { top: '1%', left: '34%' },
            { top: '1%', left: '67%' },
            { top: '27%', left: '1%' },
            { top: '51%', left: '1%' },
            { top: '27%', right: '1%' },
            { top: '51%', right: '1%' },
            { top: '76%', left: '1%' },
            { top: '76%', left: '34%' },
            { top: '76%', left: '67%' }
        ];

        avatarZones.forEach((position, zoneIndex) => {
            const zone = document.createElement('div');
            zone.className = 'avatar-zone position-absolute d-flex flex-column justify-content-start p-2';
            zone.dataset.zone = String(zoneIndex + 1);
            zone.style.top = position.top || 'auto';
            zone.style.bottom = position.bottom || 'auto';
            zone.style.left = position.left || 'auto';
            zone.style.right = position.right || 'auto';
            zone.style.width = '32%';
            zone.style.height = '23%';
            zone.style.boxSizing = 'border-box';
            zone.style.zIndex = '1';
            zone.style.border = '2px dashed #198754';
            zone.style.borderRadius = '0.75rem';
            zone.style.backgroundColor = 'rgba(25, 135, 84, 0.08)';
            zone.style.pointerEvents = 'none';
            zone.style.gap = '0.5rem';
            zone.style.overflow = 'hidden';

            const zoneLabel = document.createElement('span');
            zoneLabel.textContent = `구역 ${zoneIndex + 1}`;
            zoneLabel.className = 'small fw-bold align-self-start px-1';
            zoneLabel.style.color = '#146c43';
            zoneLabel.style.backgroundColor = 'var(--app-surface)';
            zone.appendChild(zoneLabel);

            const membersRow = document.createElement('div');
            membersRow.className = 'avatar-zone-members d-flex flex-row align-items-center gap-2';
            membersRow.style.flexWrap = 'nowrap';
            membersRow.style.width = '100%';
            membersRow.style.minWidth = '0';
            membersRow.style.overflow = 'hidden';
            zone.appendChild(membersRow);
            avatarContainer?.appendChild(zone);
        });

        onlineMembers.slice(0, 20).forEach((member, idx) => {
            const isMe = isCurrentUser(member.userId);
            const zone = avatarContainer?.querySelector(`[data-zone="${(idx % 10) + 1}"]`);
            if (!zone) return;

            const spot = document.createElement('button');
            spot.type = 'button';
            spot.className = 'btn border rounded-pill px-3 py-1.5 shadow-sm avatar-spot d-flex align-items-center gap-2';
            spot.setAttribute('aria-label', `${member.nickname}${isMe ? ' (나)' : ''} 아바타 정보 보기`);
            spot.style.position = 'relative';
            spot.style.zIndex = '2';
            spot.style.whiteSpace = 'nowrap';
            spot.style.flex = '0 1 auto';
            spot.style.minWidth = '0';
            spot.style.maxWidth = 'calc(50% - 0.25rem)';
            spot.style.width = 'fit-content';
            spot.style.overflow = 'hidden';
            spot.style.font = 'inherit';
            spot.style.lineHeight = 'inherit';
            spot.style.textAlign = 'left';
            spot.style.appearance = 'none';
            spot.style.cursor = 'pointer';
            spot.style.pointerEvents = 'auto';
            spot.style.backgroundColor = isMe ? 'var(--app-primary)' : 'var(--app-surface)';
            spot.style.color = isMe ? '#ffffff' : 'var(--app-text)';
            spot.style.borderColor = 'var(--app-border)';

            const dot = document.createElement('span');
            dot.className = 'rounded-circle d-inline-block flex-shrink-0';
            dot.style.width = '8px';
            dot.style.height = '8px';
            dot.style.backgroundColor = isMe ? '#ffffff' : '#198754';

            const nameSpan = document.createElement('span');
            nameSpan.textContent = isMe ? `나` : member.nickname;
            nameSpan.style.minWidth = '0';
            nameSpan.style.flex = '0 1 auto';
            nameSpan.style.overflow = 'hidden';
            nameSpan.style.textOverflow = 'ellipsis';

            spot.appendChild(dot);
            spot.appendChild(nameSpan);

            spot.addEventListener('click', (e) => {
                e.stopPropagation();
                const avatarInfoHeader = element.querySelector('#avatar-info-header');
                const slotHead = element.querySelector('#slot-head');
                const slotFace = element.querySelector('#slot-face');
                const slotBody = element.querySelector('#slot-body');
                const slotAccessory = element.querySelector('#slot-accessory');

                if (avatarToggleTitle) {
                    avatarToggleTitle.textContent = `USER - [${member.nickname}]`;
                    avatarToggleTitle.style.color = 'var(--app-text)';
                }
                if (avatarInfoHeader) {
                    avatarInfoHeader.textContent = `그룹 ${member.rank}위 · ${member.contributionScore}점`;
                }

                const eq = member.avatar?.equipments || {};
                if (slotHead) slotHead.textContent = eq.HEAD ? eq.HEAD.name : '-';
                if (slotFace) slotFace.textContent = eq.FACE ? eq.FACE.name : '-';
                if (slotBody) slotBody.textContent = eq.BODY ? eq.BODY.name : '-';
                if (slotAccessory) slotAccessory.textContent = eq.ACCESSORY ? eq.ACCESSORY.name : '-';

                avatarInfoCard?.classList.remove('d-none');
            });

            zone.querySelector('.avatar-zone-members')?.appendChild(spot);
        });
    }

    async function loadCreaturePageData() {
        if (retryButton) retryButton.disabled = true;
        try {
            const [creatureData, seasonData, speciesList] = await Promise.all([
                getCreatureHome(),
                getCurrentSeason(),
                getActiveCreatureSpecies()
            ]);
            if (!isMounted) return;
            updateUI(
                creatureData.data || creatureData,
                seasonData.data || seasonData,
                speciesList.data || speciesList
            );
        } catch (error) {
            if (isMounted) showLoadError(error);
        } finally {
            if (retryButton && isMounted) retryButton.disabled = false;
        }
    }

    const onRetryLoad = () => { void loadCreaturePageData(); };
    const onLoginFromError = () => context.navigate('/login');
    retryButton?.addEventListener('click', onRetryLoad);
    loginButton?.addEventListener('click', onLoginFromError);
    void loadCreaturePageData();

    return {
        element,
        cleanup: () => {
            isMounted = false;
            if (countdownIntervalId !== null) clearInterval(countdownIntervalId);
            if (deadlineTimeoutId !== null) clearTimeout(deadlineTimeoutId);
            growthAnimationTimeoutIds.forEach(timeoutId => clearTimeout(timeoutId));
            growthAnimationTimeoutIds.clear();
            retryButton?.removeEventListener('click', onRetryLoad);
            loginButton?.removeEventListener('click', onLoginFromError);
            themeToggle?.removeEventListener('click', onThemeToggle);
            btnQuest?.removeEventListener('click', onQuestClick);
            btnGotoShop?.removeEventListener('click', onShopClick);
            btnToggleMovement?.removeEventListener('click', onToggleMovement);
        },
    };
}
