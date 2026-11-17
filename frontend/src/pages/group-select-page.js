// @ts-check
import {
    createGroup,
    listPublicGroups,
    joinGroup,
} from '../features/group/api.js';
import { getCurrentUser } from '../features/auth/api.js';
import { createAppShell } from '../components/app-shell.js';

/**
 * 공개 그룹 검색·가입 및 그룹 생성 Page를 생성합니다.
 */
export function groupSelectPage({navigate, currentUser}) {
    const content = document.createElement('main');
    content.className = 'app-shell';

    const PAGE_SIZE = 5;

    let currentPage = 0;
    let currentQuery = '';
    let loading = false;
    let pendingLoad = false;
    let joining = false;
    let totalPages = 0;

    content.innerHTML = `
    <section class="card border-0 shadow-sm rounded-4 mb-4">
        <div class="card-body p-3 p-md-4">
            <div class="d-flex flex-wrap align-content-center justify-content-between gap-3 mb-4">
                <h2 class="h5 fw-bold mb-0">공개 그룹 선택</h2>
                <span class="badge text-bg-light border text-secondary">공개 그룹</span>
            </div>
            
            <!-- 서버 인증 정보에 기반한 그룹 가입 제한 안내 -->
            <section class="alert alert-warning mb-4 d-none"
                     aria-label="그룹 가입 제한 안내"
                     data-restriction-panel>
                <h3 class="h6 fw-bold mb-2">
                    그룹 가입 제한 중
                </h3>
            
                <p class="small mb-3" data-restriction-message>
                    가입 가능 상태를 확인하고 있습니다.
                </p>
            
                <div class="row g-3 mb-3">
                    <div class="col-sm-6">
                        <div class="small mb-1">제한 해제 시각</div>
                        <strong data-restriction-until>-</strong>
                    </div>
            
                    <div class="col-sm-6">
                        <div class="small mb-1">남은 시간</div>
                        <strong data-restriction-remaining
                                aria-live="off">-</strong>
                    </div>
                </div>
            
                <button type="button"
                        class="btn btn-outline-secondary btn-sm"
                        data-restriction-retry>
                    가입 가능 상태 다시 확인
                </button>
            </section>
            
            <div class="row align-items-end g-3 mb-4">
                <div class="col-md-8">
                    <form class="search-form">
                        <label for="group-search" class="form-label fw-semibold">
                            그룹명 검색
                        </label>
                        
                        <div class="d-flex gap-2">
                            <input id="group-search" class="form-control" type="search" maxlength="30" placeholder="그룹명 입력">
                            <button class="btn btn-outline-success search-button text-nowrap flex-shrink-0" type="submit">검색</button>
                        </div>
                    </form>
                </div>
                
                <div class="col-md-4">
                    <button class="btn btn-outline-success w-100 create-toggle" type="button" aria-expanded="false">+ 새 그룹 생성</button>
                </div>
            </div>
            
            <section class="create-panel d-none mb-4">
                <div class="border rounded-3 p-3 p-md-4">
                    <h3 class="h6 fw-bold mb-3">새 그룹 만들기</h3>
                    <form class="group-form">
                        <div class="mb-3">
                            <label class="form-label" for="group-name">그룹명</label>
                            <input class="form-control" id="group-name" name="name" minlength="2" maxlength="30" required placeholder="그룹명을 입력하세요">
                            <div class="form-text">2~30자 · 전역 중복 불가</div>
                            <div class="invalid-feedback" id="group-name-error"></div>
                        </div>
                        
                        <div class="mb-3">
                            <label class="form-label" for="group-description">그룹 소개</label>
                            <textarea class="form-control" id="group-description" name="description" maxlength="500" rows="4" placeholder="그룹 소개 (선택 · 최대 500자)"></textarea>
                            <div class="invalid-feedback" id="group-description-error"></div>
                        </div>
                                                       
                        <button class="btn btn-success create-submit" type="submit">그룹 생성</button>
                        <div class="alert d-none create-alert mt-3 mb-0" role="status" aria-live="polite"></div>
                        
                    </form>
                </div>
            </section>
            
            <div class="alert d-none list-alert" role="status" aria-live="polite"></div>
            <div class="table-responsive">
                <table class="table align-middle mb-3">
                <thead class="table-light">
                <tr>  
                    <th scope="col">그룹</th>
                    <th scope="col">현재 인원</th>
                    <th scope="col">남은 자리</th>
                    <th scope="col">동작</th>
                </tr>
                </thead>
                
                <tbody class="group-list">
                    <tr>
                        <td colspan="4" class="text-center py-4">그룹 목록을 불러오는 중입니다.</td>
                    </tr>
                </tbody>
                </table>
            </div>
            
            <nav class="pagination-area" aria-label="그룹 목록 페이지">
                <ul class="pagination justify-content-center mb-3"></ul>
            </nav>        
            <div class="alert alert-secondary border-0 small mb-0" role="status"></div>
        </div>
    </section>
    `;

    const element = createAppShell({
        content,
        currentPath: location.pathname,
        currentUser,
        navigate,
    });

    const searchForm = element.querySelector('.search-form')
    const searchInput = element.querySelector('#group-search')
    const searchButton = element.querySelector('.search-button')

    const createToggle = element.querySelector('.create-toggle')
    const createPanel = element.querySelector('.create-panel')
    const createForm = element.querySelector('.group-form')
    const createSubmit = element.querySelector('.create-submit')
    const createAlert = element.querySelector('.create-alert')
    const groupNameInput = element.querySelector('#group-name');
    const groupNameError = element.querySelector('#group-name-error');
    const groupDescriptionInput = element.querySelector('#group-description');
    const groupDescriptionError = element.querySelector('#group-description-error');

    const showGroupNameError = (message) => {
        groupNameInput.classList.add('is-invalid');
        groupNameError.textContent = message;
    };
    const clearGroupNameError = () => {
        groupNameInput.classList.remove('is-invalid');
        groupNameError.textContent = '';
    };

    /**
     * 그룹 소개 입력 필드의 오류를 표시합니다.
     */
    const showGroupDescriptionError = (message) => {
        groupDescriptionInput.classList.add('is-invalid');
        groupDescriptionError.textContent = message;
    };

    /**
     * 그룹 소개 입력 필드의 오류를 초기화합니다.
     */
    const clearGroupDescriptionError = () => {
        groupDescriptionInput.classList.remove('is-invalid');
        groupDescriptionError.textContent = '';
    };

    const listAlert = element.querySelector('.list-alert')

    // 가입 제한 표시 영역
    const restrictionPanel = content.querySelector('[data-restriction-panel]');
    const restrictionMessage = content.querySelector('[data-restriction-message]');
    const restrictionUntilElement = content.querySelector('[data-restriction-until]');
    const restrictionRemaining = content.querySelector('[data-restriction-remaining]');
    const restrictionRetry = content.querySelector('[data-restriction-retry]');

    // Router가 이미 조회한 /api/v1/me 응답을 사용합니다.
    const rawRestrictionUntil = currentUser?.restrictionUntil ?? null;
    const parsedRestrictionUntil = rawRestrictionUntil
        ? Date.parse(rawRestrictionUntil)
        : null;

    let restrictionUntilMs = parsedRestrictionUntil;
    let restrictionStatus = rawRestrictionUntil ? 'ACTIVE' : 'NONE';

    // 정상적인 ISO 8601 시간이 아니라면 임의로 가입을 허용하지 않습니다.
    if (rawRestrictionUntil && !Number.isFinite(restrictionUntilMs)) {
        restrictionStatus = 'ERROR';
        restrictionUntilMs = null;
    }

    // 페이지와 비동기 작업의 상태
    let pageMounted = true;
    let creating = false;
    let creationCompleted = false;
    let restrictionTimerId = null;
    let nextRestrictionCheckAt = 0;

    /**
     * 가입 제한 또는 재확인 중인지 판정합니다.
     * 서버 확인이 실패했다면 가입 버튼을 잠금 상태로 유지합니다.
     * 프론트엔드 시각만으로 가입 가능을 확정하지 않습니다.
     *
     * @returns {boolean} 가입·생성 UI를 잠가야 하는지 여부
     */
    function isRestrictionLocked() {
        return restrictionStatus !== 'NONE';
    }

    // 회원가입 후 초대 그룹 가입 실패 사유 안내
    const params = new URLSearchParams(window.location.search);
    const allowedReasons = [
        'INVITE_INVALID',
        'GROUP_FULL',
        'JOIN_COOLDOWN_ACTIVE',
        'ALREADY_IN_GROUP',
    ];

    const reason = allowedReasons.includes(params.get('reason'))
        ? params.get('reason')
        : null;

    const reasonMessages = {
        INVITE_INVALID: '초대 링크가 유효하지 않습니다. 다른 그룹을 선택해 주세요.',
        GROUP_FULL: '초대받은 그룹의 정원이 마감되었습니다. 다른 그룹에 가입하거나 새 그룹을 만들어 주세요.',
        JOIN_COOLDOWN_ACTIVE: '그룹 탈퇴 또는 강퇴 후 24시간 가입 제한이 적용 중입니다.',
        ALREADY_IN_GROUP: '이미 다른 그룹에 가입되어 있습니다.',
    };

    const groupList = element.querySelector('.group-list')
    const pagination = element.querySelector('.pagination-area .pagination')

    const showAlert = (target, message, type = 'danger') => {
        target.classList.remove(
            'd-none',
            'alert-danger',
            'alert-success',
            'alert-warning',
            'alert-info'
        );

        target.classList.add('alert', `alert-${type}`);
        target.textContent = message;
    };

    // 최초 진입 안내를 계속 유지할지 여부입니다.
    let preserveInitialAlert = Boolean(reason && reason !== 'JOIN_COOLDOWN_ACTIVE');

    // 가입 제한은 별도의 제한 안내 패널에서 표시합니다.
    if (preserveInitialAlert) {
        showAlert(listAlert, reasonMessages[reason], 'warning');
    }

    // 표시 여부와 관계없이 유효한 reason은 URL에서 소비합니다.
    if (reason) {
        const url = new URL(window.location.href);
        url.searchParams.delete('reason');

        window.history.replaceState(
            window.history.state,
            '',
            `${url.pathname}${url.search}${url.hash}`
        );
    }

    const hideAlert = (target) => {
        target.classList.add('d-none');
        target.textContent = '';
    };

    const escapeHtml = (value) => {
        return String(value ?? '').replace(/[&<>"']/g, (char) => {
            const entities = {
                '&': '&amp;',
                '<': '&lt;',
                '>': '&gt;',
                '"': '&quot;',
                "'": '&#039;',
            };
            return entities[char];
        });
    };

    /**
     * 제한 해제 시각을 한국 표준시로 표시합니다.
     * 서버의 ISO 8601 시각을 변경하지 않고 브라우저에서 변환합니다.
     * 잘못된 시각은 별도 안내로 처리합니다.
     *
     * @param {number|null} timestamp Unix millisecond
     * @returns {string} 한국 시간 표시 문자열
     */
    function formatRestrictionUntil(timestamp) {
        if (!Number.isFinite(timestamp)) {
            return '확인할 수 없음';
        }

        return new Intl.DateTimeFormat('ko-KR', {
            timeZone: 'Asia/Seoul',
            year: 'numeric',
            month: 'long',
            day: 'numeric',
            weekday: 'short',
            hour: 'numeric',
            minute: '2-digit',
            hour12: true,
        }).format(new Date(timestamp));
    }

    /**
     * 남은 초를 일·시간·분·초 형식으로 변환합니다.
     * 화면 표시용 계산만 수행합니다.
     * 가입 가능 여부 자체는 서버 상태로 판단합니다.
     *
     * @param {number} totalSeconds 남은 초
     * @returns {string} 남은 시간 표시 문자열
     */
    function formatRestrictionRemaining(totalSeconds) {
        let seconds = Math.max(0, totalSeconds);

        const days = Math.floor(seconds / 86400);
        seconds %= 86400;

        const hours = Math.floor(seconds / 3600);
        seconds %= 3600;

        const minutes = Math.floor(seconds / 60);
        seconds %= 60;

        const parts = [];

        if (days > 0) parts.push(`${days}일`);
        if (hours > 0 || days > 0) parts.push(`${hours}시간`);

        parts.push(`${minutes}분`);
        parts.push(`${seconds}초`);

        return parts.join(' ');
    }

    /**
     * 현재 가입 제한 상태를 화면과 작업 버튼에 반영합니다.
     * 가입 제한이 있으면 그룹 생성과 가입을 잠급니다.
     * 재확인 실패 시 기존 데이터를 임의로 가입 가능 처리하지 않습니다.
     */
    function renderRestrictionStatus() {
        const locked = isRestrictionLocked();

        restrictionPanel.classList.toggle('d-none', !locked);
        createToggle.disabled = locked;
        createSubmit.disabled = locked || creating || creationCompleted;

        if (!locked) return;

        restrictionUntilElement.textContent =
            formatRestrictionUntil(restrictionUntilMs);

        if (restrictionStatus === 'VERIFYING') {
            restrictionMessage.textContent =
                '가입 제한이 끝났는지 서버에 확인하고 있습니다.';
            restrictionRemaining.textContent = '확인 중';
        } else if (restrictionStatus === 'ERROR') {
            restrictionMessage.textContent =
                '가입 가능 상태를 확인하지 못했습니다. 다시 확인해 주세요.';
            restrictionRemaining.textContent = '서버 확인 필요';
        } else {
            restrictionMessage.textContent =
                '그룹 탈퇴 또는 강퇴 후 24시간 동안 ' +
                '새 그룹에 가입하거나 생성할 수 없습니다.';

            const remainingSeconds = Math.max(
                0,
                Math.ceil((restrictionUntilMs - Date.now()) / 1000)
            );

            restrictionRemaining.textContent =
                remainingSeconds > 0
                    ? formatRestrictionRemaining(remainingSeconds)
                    : '제한 종료 확인 중';
        }

        // 제한 중이거나 확인 중이면 현재 표시된 가입 버튼도 잠급니다.
        content.querySelectorAll('.join-button').forEach((button) => {
            if (button instanceof HTMLButtonElement) {
                button.disabled = locked || joining;
            }
        });

        restrictionRetry.disabled = restrictionStatus === 'VERIFYING';
    }

    /**
     * 현재 사용자 정보를 다시 조회해 가입 제한 해제 여부를 확인합니다.
     * 제한이 해제되면 공개 그룹 목록도 재조회합니다.
     * 조회 실패 시 사용자가 직접 재시도하도록 안내합니다.
     */
    async function refreshRestrictionStatus() {
        if (!pageMounted || restrictionStatus === 'VERIFYING') return;

        restrictionStatus = 'VERIFYING';

        // 브라우저 시계가 앞서 있는 경우 무한 재조회하지 않도록 합니다.
        nextRestrictionCheckAt = Date.now() + 60_000;
        renderRestrictionStatus();

        try {
            const user = await getCurrentUser();

            if (!pageMounted) return;

            // 다른 탭에서 로그아웃하거나 그룹에 가입했을 수도 있습니다.
            if (user.authenticationState !== 'AUTHENTICATED') {
                navigate('/login');
                return;
            }

            if (user.membership) {
                navigate('/creature');
                return;
            }

            const rawUntil = user.restrictionUntil;
            const parsedUntil = rawUntil ? Date.parse(rawUntil) : null;

            if (rawUntil && !Number.isFinite(parsedUntil)) {
                throw new Error('가입 제한 종료 시각을 확인할 수 없습니다.');
            }

            // 서버에서 반환한 최신 제한 시각을 반영합니다.
            restrictionUntilMs = parsedUntil;
            restrictionStatus = rawUntil ? 'ACTIVE' : 'NONE';

            renderRestrictionStatus();

            // 제한 해제 후 서버의 최신 가입 가능 여부를 재조회합니다.
            void loadGroup();

        } catch (error) {
            if (!pageMounted) return;

            restrictionStatus = 'ERROR';
            renderRestrictionStatus();

            console.error('가입 제한 상태 확인 실패:', error);
        }
    }

    /**
     * 남은 시간을 주기적으로 갱신합니다.
     * 브라우저 기준 종료 시각에 도달하면 서버 확인을 시작합니다.
     * 이미 확인 중이거나 실패했다면 자동 요청을 반복하지 않습니다.
     */
    function tickRestrictionCountdown() {
        if (!pageMounted) return;

        renderRestrictionStatus();

        if (
            restrictionStatus === 'ACTIVE' &&
            restrictionUntilMs !== null &&
            Date.now() >= restrictionUntilMs &&
            Date.now() >= nextRestrictionCheckAt
        ) {
            void refreshRestrictionStatus();
        }
    }

    const getBlockedMessage = (reason) => {
        const messages = {
            GROUP_FULL: '그룹 정원이 가득 찼습니다.',
            JOIN_COOLDOWN_ACTIVE: '24시간 가입 제한이 적용 중입니다.',
            ALREADY_IN_GROUP: '이미 다른 그룹에 가입되어 있습니다.',
            GROUP_NOT_FOUND: '존재하지 않는 그룹입니다',
            JOIN_STATE_CHECKING: '가입 가능 상태를 확인 중입니다.'
        };

        return messages[reason] ?? '그룹에 가입할 수 없습니다';
    };

    const renderGroups = (groups) => {
        groupList.replaceChildren();

        if (!groups.length) {
            const row = document.createElement('tr');

            row.innerHTML = `<td colspan="4" class="text-secondary py-5">검색 결과가 없습니다</td>`;

            groupList.append(row);
            return;
        }

        groups.forEach((group) => {
            const row = document.createElement('tr');

            const groupId = escapeHtml(group.groupId);
            const groupName = escapeHtml(group.name);
            const currentCount = Number(group.currentMemberCount ?? 0);
            const groupCapacity = Number(group.capacity ?? 0);

            const remaining = Number(
                group.remainingCapacity ??
                Math.max(0, groupCapacity - currentCount)
            );

            // 서버의 그룹별 가입 가능 여부와 현재 사용자 제한을 함께 확인합니다.
            const canJoin =
                group.joinable === true && !isRestrictionLocked();

            // 서버 확인 중에는 임의로 가입 가능 버튼을 표시하지 않습니다.
            const blockedReason = isRestrictionLocked()
                ? restrictionStatus === 'ACTIVE'
                    ? 'JOIN_COOLDOWN_ACTIVE'
                    : 'JOIN_STATE_CHECKING'
                : group.joinBlockedReason;

            row.innerHTML = `
                <td>
                    <div class="fw-semibold">${groupName}</div>
                    ${group.description
                ? ` <div class="small text-secondary mt-1">
                                ${escapeHtml(group.description)}
                            </div>`
                : ''
            }
                </td>
                
                <td>
                    ${currentCount} / ${groupCapacity}
                </td>              
                 <td>
                    ${remaining}
                </td>
                
                <td>
                    ${canJoin
                ? ` <button type="button" class="btn btn-success btn-sm join-button" data-group-id="${groupId}">가입</button>`
                : `
                    <button
                        type="button"
                        class="btn btn-outline-secondary btn-sm"
                        disabled
                    >
                        ${remaining <= 0 ? '정원 마감' : '가입 불가'}
                    </button>
                
                    <div class="small text-danger mt-1">
                        ${escapeHtml(getBlockedMessage(blockedReason))}
                    </div>
                `
            }
                </td>
              `;

            groupList.append(row);
        });
    };

    const renderPagination = () => {
        pagination.replaceChildren();

        if (totalPages <= 1) return;

        const addPageButton = (label, page, disabled, active = false) => {
            const item = document.createElement('li');
            item.className = `page-item${disabled ? 'disabled' : ''} ${
                active ? 'active' : ''
            }`;

            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'page-link';
            button.textContent = label;
            button.disabled = disabled;
            button.dataset.page = String(page);

            item.append(button);
            pagination.append(item);
        };

        addPageButton('이전', currentPage - 1, currentPage <= 0);

        for (let page = 0; page < totalPages; page += 1) {
            addPageButton(
                String(page + 1),
                page,
                false,
                page === currentPage
            );
        }

        addPageButton(
            '다음',
            currentPage + 1,
            currentPage >= totalPages - 1
        );
    };

    const loadGroup = async () => {
        if (!pageMounted) return;

        if (loading) {
            pendingLoad = true;
            return;
        }

        loading = true;
        searchButton.disabled = true;

        // 최초 안내는 첫 조회까지 유지하고, 이후 재조회에서는 지웁니다.
        if (!preserveInitialAlert) {
            hideAlert(listAlert);
        }
        preserveInitialAlert = false;

        groupList.innerHTML = `<tr>
        <td colspan="4" class="text-center py-4">
            그룹 목록을 불러오는 중입니다.
        </td>
    </tr>`;

        try {
            const result = await listPublicGroups({
                q: currentQuery,
                page: currentPage,
                size: PAGE_SIZE
            });

            if (!pageMounted) return;

            const groups = Array.isArray(result?.data)
                ? result.data
                : [];

            const meta = result?.meta ?? {};
            totalPages = Number(meta.totalPages ?? 0);

            if (totalPages > 0 && currentPage >= totalPages) {
                currentPage = totalPages - 1;
                pendingLoad = true;
                return;
            }

            renderGroups(groups);
            renderPagination();

        } catch (error) {
            // 페이지가 종료된 후에는 오류 UI를 변경하지 않습니다.
            if (!pageMounted) return;

            groupList.innerHTML = `<tr>
    <td colspan="4" class="text-center py-4 text-danger">
        그룹 목록을 불러오지 못했습니다.
        <button
            type="button"
            class="btn btn-outline-primary btn-sm ms-2 retry-button"
        >
            재시도
        </button>
    </td>
</tr>`;
            showAlert(
                listAlert,
                error.message || '그룹 목록 조회 중 오류가 발생했습니다.'
            );

            totalPages = 0;
            renderPagination();

        } finally {
            loading = false;
            searchButton.disabled = false;

            if (pageMounted) {
                searchButton.disabled = false;

                // 현재 페이지가 살아 있을 때만 예약된 재조회를 수행합니다.
                if (pendingLoad) {
                    pendingLoad = false;
                    void loadGroup();
                }
            } else {
                // 화면 종료 후에는 추가 API 요청을 만들지 않습니다.
                pendingLoad = false;
            }
        }
    };

    const onSearch = (event) => {
        event.preventDefault();

        currentQuery = searchInput.value.trim();
        currentPage = 0;

        loadGroup();
    };

    const onCreateToggle = () => {
        if (isRestrictionLocked()) return;

        const isHidden = createPanel.classList.toggle('d-none');

        createToggle.setAttribute(
            'aria-expanded',
            String(!isHidden)
        );

        createToggle.textContent = isHidden
            ? '+ 새 그룹 생성'
            : '- 그룹 생성 닫기';
    };

    const onCreate = async (event) => {
        event.preventDefault();

        // 제한 중이거나 제한 상태 확인에 실패했다면 제출하지 않습니다.
        if (isRestrictionLocked()) {
            showAlert(
                createAlert,
                '가입 제한이 해제된 후 그룹을 생성할 수 있습니다.',
                'warning'
            );
            return;
        }

        if (createSubmit.disabled || creating) return;
        if (!createForm.reportValidity()) return;

        creating = true;
        createSubmit.disabled = true;

        hideAlert(createAlert);

        let creationSucceeded = false;

        try {
            const result = await createGroup({
                name: element.querySelector('#group-name').value.trim(),
                description: element.querySelector('#group-description').value
            });

            creationSucceeded = true;

            createAlert.className =
                'alert alert-success create-alert mt-3 mb-0';

            createAlert.replaceChildren();

            // 그룹 생성 성공 메시지
            const message = document.createElement('p');
            message.className = 'mb-3 fw-semibold';
            message.textContent = '그룹을 성공적으로 만들었습니다!';
            createAlert.append(message);

            // 초대 링크 표시
            const invitationUrl = result?.initialInvitationUrl;

            if (typeof invitationUrl === 'string' && invitationUrl.trim()) {
                const linkLabel = document.createElement('label');
                linkLabel.className = 'form-label fw-semibold';
                linkLabel.textContent = '초대 링크';

                const linkArea = document.createElement('div');
                linkArea.className = 'd-flex align-items-center gap-2 mb-2';

                // 일반 div로 링크 표시
                const linkDisplay = document.createElement('div');
                linkDisplay.className =
                    'border rounded p-2 bg-body text-body flex-grow-1';

                linkDisplay.textContent = invitationUrl;
                linkDisplay.style.minWidth = '0';
                linkDisplay.style.overflowWrap = 'anywhere';

                // 링크 복사 버튼
                const copyButton = document.createElement('button');
                copyButton.type = 'button';
                copyButton.className =
                    'btn btn-outline-success flex-shrink-0';
                copyButton.textContent = '링크 복사';

                const copyStatus = document.createElement('p');
                copyStatus.className = 'small mb-3';
                copyStatus.setAttribute('aria-live', 'polite');

                copyButton.addEventListener('click', async () => {
                    try {
                        await navigator.clipboard.writeText(invitationUrl);

                        copyStatus.textContent =
                            '초대 링크가 복사되었습니다.';
                    } catch (error) {
                        const selection = window.getSelection();
                        const range = document.createRange();

                        range.selectNodeContents(linkDisplay);
                        selection.removeAllRanges();
                        selection.addRange(range);

                        copyStatus.textContent =
                            '자동 복사에 실패했습니다. 선택된 링크를 직접 복사해 주세요.';
                    }
                });

                linkArea.append(linkDisplay, copyButton);
                createAlert.append(linkLabel, linkArea, copyStatus);

            } else {
                const warning = document.createElement('p');
                warning.className = 'small mb-3';
                warning.textContent = '초대 링크를 가져오지 못했습니다.';

                createAlert.append(warning);
            }

            // 크리쳐 화면 이동 버튼
            const moveButton = document.createElement('button');
            moveButton.type = 'button';
            moveButton.className = 'btn btn-success mt-2';
            moveButton.textContent = '크리쳐 화면으로 이동';

            moveButton.addEventListener('click', () => {
                navigate(result?.nextRoute || '/creature');
            });

            createAlert.append(moveButton);

            createForm.reset();

        } catch (error) {
            const nameFieldError = error?.fieldErrors?.find(
                (fieldError) => fieldError.field === 'name'
            );

            const descriptionFieldError = error?.fieldErrors?.find(
                (fieldError) => fieldError.field === 'description'
            );

            let hasFieldError = false;

            if (error?.code === 'GROUP_NAME_ALREADY_USED') {
                showGroupNameError(
                    '이미 사용 중인 그룹명입니다. 다른 이름을 입력해 주세요.'
                );
                hasFieldError = true;
            } else if (error?.code === 'INVALID_FIELD_VALUE' && nameFieldError) {
                showGroupNameError(nameFieldError.message);
                hasFieldError = true;
            }

            if (error?.code === 'INVALID_FIELD_VALUE' && descriptionFieldError) {
                showGroupDescriptionError(descriptionFieldError.message);
                hasFieldError = true;
            }

            if (!hasFieldError) {
                showAlert(
                    createAlert,
                    error.message || '그룹 생성에 실패했습니다.'
                );
            }
        } finally {
            // 가입 제한 상태를 다시 렌더링해도 중복 생성은 허용하지 않습니다.
            creating = false;
            creationCompleted = creationCompleted || creationSucceeded;

            createSubmit.disabled = isRestrictionLocked() || creating || creationCompleted;
        }
    };


    const onJoin = async (event) => {
        const button = event.target.closest('.join-button');

        // 그룹 가입 요청은 화면 전체에서 한 번에 하나만 수행합니다.
        if (!button || joining) return;

        // 제한 상태도 제출 시점에 다시 확인합니다.
        if (isRestrictionLocked()) {
            showAlert(
                listAlert,
                '가입 제한이 해제된 후 그룹에 가입할 수 있습니다.',
                'warning'
            );
            return;
        }

        const groupId = Number(button.dataset.groupId);

        if (!Number.isInteger(groupId)) return;

        joining = true;
        button.disabled = true;
        button.textContent = '가입 중...'

        try {
            const result = await joinGroup(groupId);
            navigate(result?.nextRoute || '/creature');

        } catch (error) {
            const joinErrorMessages = {
                JOIN_COOLDOWN_ACTIVE:
                    '그룹 탈퇴 또는 강퇴 후 24시간 가입 제한이 적용 중입니다.',
                ALREADY_IN_GROUP:
                    '이미 다른 그룹에 가입되어 있습니다.',
            };

            showAlert(
                listAlert,
                joinErrorMessages[error?.code] ||
                error?.message ||
                '그룹 가입에 실패했습니다.'
            );
        } finally {
            joining = false;
            button.disabled = false;
            button.textContent = '가입';
        }
    };

    /**
     * 그룹 목록의 클릭 이벤트를 한 곳에서 처리합니다.
     * 가입 버튼과 재시도 버튼을 구분합니다.
     */
    const onGroupListClick = (event) => {
        if (!(event.target instanceof Element)) return;

        // 목록 조회 재시도 버튼을 클릭한 경우
        if (event.target.closest('.retry-button')) {
            void loadGroup();
            return;
        }

        // 그룹 가입 버튼을 클릭한 경우
        if (event.target.closest('.join-button')) {
            void onJoin(event);
        }
    };

    const onPagination = (event) => {
        const button = event.target.closest('[data-page]');

        if (!button || button.disabled || loading) return;

        const page = Number(button.dataset.page);

        if (!Number.isInteger(page)) return;
        if (page < 0 || page >= totalPages) return;

        currentPage = page;
        loadGroup();
    };

    searchForm.addEventListener('submit', onSearch);
    createToggle.addEventListener('click', onCreateToggle);
    createForm.addEventListener('submit', onCreate);
    groupNameInput.addEventListener('input', clearGroupNameError);
    groupDescriptionInput.addEventListener('input', clearGroupDescriptionError);
    groupList.addEventListener('click', onGroupListClick);
    pagination.addEventListener('click', onPagination);

    // 서버에서 전달된 현재 제한 상태를 즉시 화면에 반영합니다.
    renderRestrictionStatus();

    // 1초마다 남은 시간을 갱신하고, 종료 시에는 서버에 확인합니다.
    restrictionTimerId = window.setInterval(
        tickRestrictionCountdown,
        1000
    );

    /**
     * 브라우저 탭으로 돌아왔을 때 제한 종료 시각을 즉시 확인합니다.
     * 백그라운드 탭에서 지연된 타이머를 보정합니다.
     * 서버 조회는 필요한 경우에만 시작합니다.
     */
    function onRestrictionVisibilityChange() {
        if (!document.hidden) {
            tickRestrictionCountdown();
        }
    }

    document.addEventListener(
        'visibilitychange',
        onRestrictionVisibilityChange
    );

    // 사용자가 직접 가입 가능 상태를 다시 확인할 수 있도록 합니다.
    const onRestrictionRetry = () => {
        void refreshRestrictionStatus();
    };

    restrictionRetry.addEventListener('click', onRestrictionRetry);

    // 최초부터 제한 시각이 이미 경과했다면 바로 서버에 확인합니다.
    tickRestrictionCountdown();

    // 기존 공개 그룹 목록 조회를 시작합니다.
    void loadGroup();

    return {
        element,
        cleanup: () => {
            // 이전 화면의 타이머 및 비동기 결과가 화면을 변경하지 못하게 합니다.
            pageMounted = false;

            if (restrictionTimerId !== null) {
                window.clearInterval(restrictionTimerId);
                restrictionTimerId = null;
            }

            document.removeEventListener(
                'visibilitychange',
                onRestrictionVisibilityChange
            );

            restrictionRetry.removeEventListener('click', onRestrictionRetry);

            searchForm.removeEventListener('submit', onSearch);
            createToggle.removeEventListener('click', onCreateToggle);
            createForm.removeEventListener('submit', onCreate);
            groupNameInput.removeEventListener('input', clearGroupNameError);
            groupDescriptionInput.removeEventListener('input', clearGroupDescriptionError);
            groupList.removeEventListener('click', onGroupListClick);
            pagination.removeEventListener('click', onPagination);
        },
    };
}


