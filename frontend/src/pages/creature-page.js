// @ts-check
import { getCreatureHome } from '../features/creature/api.js';
import { mockCreatureHomeData } from '../mocks/mock-creature-page.js';
import creatureImg from '../../../docs/images/sample/creature/sample_creature_lv4.png';

/**
 * 크리처 홈 페이지를 마운트하고 렌더링합니다.
 * @param {{ navigate: (path: string) => void }} context
 */
export function creaturePage(context) {
    const element = document.createElement('main');
    // .app-shell이 가진 기본 상하 패딩 및 제한을 오버라이드하여 위아래 공백을 완전히 제거합니다.
    element.className = 'd-flex min-vh-100 app-shell w-100 overflow-hidden m-0 p-0';
    element.style.backgroundColor = 'var(--app-background)';
    element.style.color = 'var(--app-text)';
    element.style.padding = '0';
    element.style.margin = '0';
    element.style.maxWidth = 'none';

    // 정적 HTML 틀만 innerHTML로 생성
    element.innerHTML = `
    <!-- 최상단 전체 헤더 -->
    <header class="position-fixed top-0 start-0 w-100 border-bottom px-4 py-3 d-flex justify-content-between align-items-center z-3" style="background-color: var(--app-surface); border-color: var(--app-border) !important; height: 60px;">
      <div class="d-flex align-items-center gap-2">
        <span class="fs-5 fw-bold d-flex align-items-center gap-2" style="color: var(--app-primary);">
          <span class="rounded-circle d-inline-block" style="width: 10px; height: 10px; background-color: var(--app-primary);"></span>
          다 같이 키우는 생명체
        </span>
      </div>
      <div class="d-flex align-items-center gap-2">
        <button type="button" class="btn btn-sm btn-outline-secondary theme-toggle">테마 전환 ◐</button>
        <button type="button" class="btn btn-sm btn-outline-secondary">화면 모형</button>
      </div>
    </header>

    <!-- 헤더 높이(60px)만큼 상단 여백 -->
    <div class="d-flex w-100" style="padding-top: 60px;">
      
      <!-- 좌측 서비스 메뉴바 -->
      <nav class="d-none d-lg-flex flex-column flex-shrink-0 p-3 border-end" style="width: 240px; min-height: calc(100vh - 60px); background-color: var(--app-surface); border-color: var(--app-border) !important;">
        <div class="small px-2 mb-3 mt-2 fw-bold" style="color: var(--app-muted);">서비스 메뉴</div>
        <ul class="nav nav-pills flex-column mb-auto gap-1">
          <li class="nav-item">
            <a href="/creature" class="nav-link active text-white" style="background-color: var(--app-primary);" data-link>크리처</a>
          </li>
          <li class="nav-item">
            <a href="/quests" class="nav-link" style="color: var(--app-text);" data-link>퀘스트</a>
          </li>
          <li class="nav-item">
            <a href="/ranking" class="nav-link" style="color: var(--app-text);" data-link>랭킹</a>
          </li>
          <li class="nav-item">
            <a href="/groups" class="nav-link" style="color: var(--app-text);" data-link>그룹</a>
          </li>
          <li class="nav-item">
            <a href="/shop" class="nav-link" style="color: var(--app-text);" data-link>상점</a>
          </li>
          <li class="nav-item">
            <a href="/avatar" class="nav-link" style="color: var(--app-text);" data-link>아바타</a>
          </li>
          <li class="nav-item">
            <a href="/billing" class="nav-link" style="color: var(--app-text);" data-link>결제 내역</a>
          </li>
          <li class="nav-item">
            <a href="/group-manage" class="nav-link" style="color: var(--app-text);" data-link>그룹 관리</a>
          </li>
        </ul>
      </nav>

      <!-- 메인 컨테이너 영역 -->
      <div class="flex-grow-1 d-flex flex-column min-w-0">
        <!-- 페이지 본문 내용 -->
        <div class="container-fluid p-4 p-xxl-5 overflow-auto">
          
          <!-- 크리처 홈 타이틀 및 상태 바 -->
          <div class="d-flex justify-content-between align-items-center mb-4">
            <div>
              <div class="d-flex align-items-center gap-2">
                <h1 class="h3 mb-0 fw-bold" style="color: var(--app-text);">크리처 홈</h1>
                <span class="badge border font-monospace" style="background-color: var(--app-background); color: var(--app-text); border-color: var(--app-border) !important;" id="group-name-badge">/creature</span>
              </div>
              <p class="small mb-0 mt-1" style="color: var(--app-muted);" id="group-season-info">로딩 중...</p>
            </div>
            <div class="d-flex align-items-center gap-3">
              <!-- 아바타 이동 멈춤/시작 토글 버튼 -->
              <button type="button" class="btn btn-sm border px-3 py-2 fw-bold" style="background-color: var(--app-surface); color: var(--app-primary); border-color: var(--app-border) !important;" id="btn-toggle-movement">
                아바타 이동: ON
              </button>
              <span class="badge rounded-pill px-3 py-2" style="background-color: var(--app-text); color: var(--app-surface);" id="connection-status">
                <span style="color: var(--app-primary);" class="me-1">●</span> 실시간 연결됨
              </span>
            </div>
          </div>

          <!-- 대시보드 메인 콘텐츠 (2열 레이아웃) -->
          <div class="row g-4">
            
            <!-- 좌측: 넓어진 공동 크리처 영역 -->
            <div class="col-xl-8 col-lg-7">
              <div class="card shadow-sm h-100 border" style="background-color: var(--app-surface); border-color: var(--app-border) !important; border-radius: 1.5rem;">
                <div class="card-body d-flex flex-column justify-content-between p-4">
                  <div>
                    <div class="d-flex justify-content-between align-items-center mb-3">
                      <h2 class="h5 card-title mb-0 fw-bold" style="color: var(--app-text);">공동 크리처</h2>
                      <span class="badge rounded-pill" style="background-color: var(--app-muted); color: var(--app-surface);" id="online-count">접속 0명</span>
                    </div>

                    <!-- 크리처 및 아바타 이동 구역 (빈 공간 클릭 시 아바타 선택 해제되도록 지정) -->
                    <div class="border rounded-3 p-4 text-center position-relative my-3" style="min-height: 380px; background-color: var(--app-background); border-color: var(--app-border) !important;" id="avatar-container">
                      
                      <!-- 중앙 크리처 영역 -->
                      <div class="position-absolute top-50 start-50 translate-middle d-flex flex-column align-items-center justify-content-center">
                        <div class="mb-2 overflow-hidden d-flex align-items-center justify-content-center" style="width: 180px; height: 180px;">
                          <img src="${creatureImg}" alt="크리처 샘플" class="w-100 h-100 object-fit-contain" />
                        </div>
                        <h3 class="h6 mb-1 fw-bold" style="color: var(--app-text);" id="growth-stage">성장기</h3>
                        <span class="small" style="color: var(--app-muted);">공동 크리처 영역</span>
                      </div>

                    </div>
                  </div>

                  <!-- 경험치 및 퀘스트 하단 구역 -->
                  <div class="mt-3">
                    <div class="d-flex justify-content-between fw-bold mb-1 small" style="color: var(--app-text);" id="exp-text">
                      <span>0 / 0 XP</span>
                    </div>
                    <div class="progress mb-4" style="height: 12px; background-color: var(--app-border);">
                      <div class="progress-bar" role="progressbar" id="exp-bar" style="width: 0%; background-color: var(--app-primary);" aria-valuenow="0" aria-valuemin="0" aria-valuemax="100"></div>
                    </div>
                    <div class="text-center">
                      <button type="button" class="btn btn-primary fw-bold px-4" id="btn-quest">오늘 퀘스트 확인</button>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 우측: 아바타 상세 정보 카드 (선택 시에만 테두리 박스와 함께 노출) -->
            <div class="col-xl-4 col-lg-5">
              <div class="card shadow-sm h-100 border" style="background-color: var(--app-surface); border-color: var(--app-border) !important; border-radius: 1.5rem;">
                <div class="card-body p-4">
                  <h2 class="h6 mb-2" style="color: var(--app-muted);">오늘 그룹 경험치</h2>
                  <div class="display-6 fw-bold mb-4" style="color: var(--app-text);" id="today-exp">+0 XP</div>

                  <h3 class="h6 fw-bold mb-3" style="color: var(--app-text);">최근 기여</h3>
                  <ul class="list-group list-group-flush mb-4" id="recent-contributions-list"></ul>

                  <!-- 아바타 선택 시에만 나타나는 테두리 박스 카드 (초기엔 d-none) -->
                  <div class="card border mt-3 d-none" style="background-color: var(--app-surface); border-color: var(--app-border) !important;" id="avatar-info-card">
                    <div class="card-header py-3" style="background-color: var(--app-surface); border-color: var(--app-border) !important;">
                      <div class="d-flex justify-content-between align-items-center">
                        <span class="fw-bold small" style="color: var(--app-text);" id="avatar-toggle-title"></span>
                      </div>
                    </div>
                    <!-- 아바타 선택 시 열림 -->
                    <div class="card-body collapse show" id="avatar-detail-collapse">
                      <p class="small mb-2" style="color: var(--app-muted);" id="avatar-info-header">-</p>
                      <table class="table table-sm align-middle mb-3 small" style="color: var(--app-text); border-color: var(--app-border);">
                        <thead>
                          <tr style="background-color: var(--app-background); color: var(--app-text);">
                            <th style="font-style: italic">slot</th>
                            <th style="font-style: italic">item</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr>
                            <td style="color: var(--app-muted);">HEAD</td>
                            <td id="slot-head">-</td>
                          </tr>
                          <tr>
                            <td style="color: var(--app-muted);">FACE</td>
                            <td id="slot-face">-</td>
                          </tr>
                          <tr>
                            <td style="color: var(--app-muted);">BODY</td>
                            <td id="slot-body">-</td>
                          </tr>
                          <tr>
                            <td style="color: var(--app-muted);">ACCESSORY</td>
                            <td id="slot-accessory">-</td>
                          </tr>
                        </tbody>
                      </table>
                      <div class="text-center">
                        <button type="button" class="btn btn-sm px-4 fw-bold" style="background-color: var(--app-background); color: var(--app-primary); border: 1px solid var(--app-border);" id="btn-goto-shop">상점에서 보기</button>
                      </div>
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

    // 아바타 이동 멈춤/시작 토글 버튼 이벤트
    const onToggleMovement = () => {
        isAvatarMoving = !isAvatarMoving;
        if (btnToggleMovement) {
            btnToggleMovement.textContent = isAvatarMoving ? '아바타 이동: ON' : '아바타 이동: OFF';
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

    // [중요] 공동 화면의 빈 공간(아바타 스팟이 아닌 배경 영역)을 클릭하면 선택이 해제되면서 테두리 박스가 완전히 숨겨짐
    avatarContainer?.addEventListener('click', (e) => {
        if (e.target === avatarContainer || e.target.closest('.position-absolute.top-50.start-50')) {
            avatarInfoCard?.classList.add('d-none');
        }
    });

    // 3. API 및 목 데이터 바인딩 로직
    let isMounted = true;

    function updateUI(data) {
        const groupNameBadge = element.querySelector('#group-name-badge');
        const groupSeasonInfo = element.querySelector('#group-season-info');
        if (groupNameBadge) groupNameBadge.textContent = data.groupName || '/creature';
        if (groupSeasonInfo) {
            groupSeasonInfo.textContent = `${data.season.seasonName} · ${data.creature.remainingTime}`;
        }

        const onlineCount = element.querySelector('#online-count');
        if (onlineCount) onlineCount.textContent = `접속 ${data.onlineMembers.length}명`;

        const growthStage = element.querySelector('#growth-stage');
        const expText = element.querySelector('#exp-text span');
        const expBar = element.querySelector('#exp-bar');
        if (growthStage) growthStage.textContent = data.creature.status || data.creature.growthStage;
        if (expText) expText.textContent = `${data.creature.totalExperience.toLocaleString()} / ${data.creature.nextStageAtExperience.toLocaleString()} XP`;
        if (expBar) {
            expBar.style.width = `${data.creature.progress}%`;
            expBar.setAttribute('aria-valuenow', String(data.creature.progress));
        }

        const todayExp = element.querySelector('#today-exp');
        if (todayExp) todayExp.textContent = `+${data.todayExperience.toLocaleString()} XP`;

        const contributionsList = element.querySelector('#recent-contributions-list');
        if (contributionsList) {
            contributionsList.innerHTML = '';
            data.recentContributions.forEach(item => {
                const li = document.createElement('li');
                li.className = 'list-group-item d-flex justify-content-between align-items-center px-0 bg-transparent';
                li.style.borderColor = 'var(--app-border)';
                li.style.color = 'var(--app-text)';

                const spanName = document.createElement('span');
                spanName.textContent = item.nickname;

                const spanAmount = document.createElement('span');
                spanAmount.className = 'fw-bold';
                spanAmount.style.color = 'var(--app-primary)';
                spanAmount.textContent = `+${item.amount} XP`;

                li.appendChild(spanName);
                li.appendChild(spanAmount);
                contributionsList.appendChild(li);
            });
        }

        // 기존 생성된 아바타 마커 제거 후 재생성
        avatarContainer?.querySelectorAll('.avatar-spot').forEach(el => el.remove());

        const positions = [
            { top: '15%', start: '15%' },
            { top: '15%', end: '15%' },
            { bottom: '15%', start: '20%' },
            { bottom: '15%', end: '20%' }
        ];

        data.onlineMembers.forEach((member, idx) => {
            const spot = document.createElement('div');
            spot.className = 'position-absolute border rounded-pill px-2 py-1 small shadow-sm avatar-spot d-flex align-items-center gap-1';
            spot.style.cursor = 'pointer';
            spot.style.backgroundColor = 'var(--app-surface)';
            spot.style.color = 'var(--app-text)';
            spot.style.borderColor = 'var(--app-border)';

            const pos = positions[idx % positions.length];
            if (pos.top) spot.style.top = pos.top;
            if (pos.bottom) spot.style.bottom = pos.bottom;
            if (pos.start) spot.style.left = pos.start;
            if (pos.end) spot.style.right = pos.end;

            const dot = document.createElement('span');
            dot.className = 'rounded-circle bg-success d-inline-block';
            dot.style.width = '6px';
            dot.style.height = '6px';

            const nameSpan = document.createElement('span');
            nameSpan.style.fontSize = '0.8rem';
            nameSpan.textContent = member.nickname;

            spot.appendChild(dot);
            spot.appendChild(nameSpan);

            // 아바타 클릭 시 해당 유저 정보 세팅
            spot.addEventListener('click', (e) => {
                e.stopPropagation(); // 배경 클릭 이벤트로 전파되는 것 방지
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

                const eq = member.avatar.equipments;
                if (slotHead) slotHead.textContent = eq.HEAD ? eq.HEAD.name : '-';
                if (slotFace) slotFace.textContent = eq.FACE ? eq.FACE.name : '-';
                if (slotBody) slotBody.textContent = eq.BODY ? eq.BODY.name : '-';
                if (slotAccessory) slotAccessory.textContent = eq.ACCESSORY ? eq.ACCESSORY.name : '-';

                // 숨김 해제(d-none 제거)하여 테두리 박스 노출
                avatarInfoCard?.classList.remove('d-none');
            });

            avatarContainer?.appendChild(spot);
        });
    }

    getCreatureHome()
        .then((res) => {
            if (!isMounted) return;
            const data = res.data || res || mockCreatureHomeData;
            updateUI(data);
        })
        .catch((err) => {
            console.error('크리처 홈 데이터 로드 실패, 목 데이터를 대신 로드합니다:', err);
            if (isMounted) {
                updateUI(mockCreatureHomeData);
            }
        });

    return {
        element,
        cleanup: () => {
            isMounted = false;
            themeToggle?.removeEventListener('click', onThemeToggle);
            btnQuest?.removeEventListener('click', onQuestClick);
            btnGotoShop?.removeEventListener('click', onShopClick);
            btnToggleMovement?.removeEventListener('click', onToggleMovement);
        },
    };
}