// @ts-check
import {
    createGroup,
    listPublicGroups,
    joinGroup,
} from '../features/group/api.js';

/**
 * 공개 그룹 검색·가입 및 그룹 생성 Page를 생성합니다.
 */
export function groupSelectPage({navigate}) {
    const element = document.createElement('main');
    element.className = 'app-shell';

    const PAGE_SIZE = 5;

    let currentPage = 0;
    let currentQuery = '';
    let loading = false;
    let pendingLoad = false;
    let joining = false;
    let totalPages = 0;

    element.innerHTML = `
<header class="page-header">
  <div>
    <p class="eyebrow">새로운 공동체</p>
    <h1 class="fw-bold">그룹 선택</h1>
    <p class="text-secondary mb-0">
        함께 활동할 공개 그룹을 찾아 참여해 보세요~
    </p>
  </div>
  
  <button class="btn btn-outline-secondary theme-toggle" type="button">테마 전환</button>
</header>

<section class="card border-0 shadow-sm rounded-4 mb-4">
    <div class="card-body p-3 p-md-4">
        <div class="d-flex flex-wrap align-content-center justify-content-between gap-3 mb-4">
            <h2 class="h5 fw-bold mb-0">공개 그룹 선택</h2>
            <span class="badge text-bg-light border text-secondary">공개 그룹</span>
        </div>
        
        <div class="row align-items-end g-3 mb-4">
            <div class="col-md-8">
                <form class="search-form">
                    <label for="group-search" class="form-label fw-semibold">
                        그룹명 검색
                    </label>
                    
                    <div class="d-flex gap-2">
                        <input id="group-search" class="form-control" type="search" maxlength="30" placeholder="그룹명 입력">
                        <button class="btn btn-outline-success search-button" type="submit">검색</button>
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
    const themeButton = element.querySelector('.theme-toggle')

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
    if (reason) {
        showAlert(listAlert, reasonMessages[reason], 'warning');
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

    const getBlockedMessage = (reason) => {
        const messages = {
            GROUP_FULL: '그룹 정원이 가득 찼습니다.',
            JOIN_COOLDOWN_ACTIVE: '24시간 가입 제한이 적용 중입니다.',
            ALREADY_IN_GROUP: '이미 다른 그룹에 가입되어 있습니다.',
            GROUP_NOT_FOUND: '존재하지 않는 그룹입니다'
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

            const canJoin = group.joinable === true;

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
                        ${escapeHtml(getBlockedMessage(group.joinBlockedReason))}
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
        if (loading) {
            pendingLoad = true;
            return;
        }

        loading = true;
        searchButton.disabled = true;

        if (!reason) {
            hideAlert(listAlert);
        }

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

            if (pendingLoad) {
                pendingLoad = false;
                loadGroup();
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

        if (createSubmit.disabled) return;
        if (!createForm.reportValidity()) return;

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
        }
        finally {
            createSubmit.disabled = creationSucceeded;
        }
    };


    const onJoin = async (event) => {
        const button = event.target.closest('.join-button');

        if (!button || joining) return;

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

    const onTheme = () => {
        const current = document.documentElement.dataset.bsTheme;
        const next = current === 'dark' ? 'light' : 'dark';

        document.documentElement.dataset.bsTheme = next;
        localStorage.setItem('theme', next);
    };

    searchForm.addEventListener('submit', onSearch);
    createToggle.addEventListener('click', onCreateToggle);
    createForm.addEventListener('submit', onCreate);
    groupNameInput.addEventListener('input', clearGroupNameError);
    groupDescriptionInput.addEventListener('input', clearGroupDescriptionError);
    groupList.addEventListener('click', onGroupListClick);
    pagination.addEventListener('click', onPagination);
    themeButton.addEventListener('click', onTheme);

    loadGroup();

    return {
        element,
        cleanup: () => {
            searchForm.removeEventListener('submit', onSearch);
            createToggle.removeEventListener('click', onCreateToggle);
            createForm.removeEventListener('submit', onCreate);
            groupNameInput.removeEventListener('input', clearGroupNameError);
            groupDescriptionInput.removeEventListener('input', clearGroupDescriptionError);
            groupList.removeEventListener('click', onGroupListClick);
            pagination.removeEventListener('click', onPagination);
            themeButton.removeEventListener('click', onTheme);
        },
    };
}


