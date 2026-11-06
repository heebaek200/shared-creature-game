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
                        <div class="invalid-feedback">그룹명을 2~30자 안으로 입력해주세요</div>
                    </div>
                    
                    <div class="mb-3">
                        <label class="form-label" for="group-description">그룹 소개</label>
                        <textarea class="form-control" id="group-description" name="description" maxlength="500" rows="4" placeholder="그룹 소개 (선택 · 최대 500자)"></textarea>
                    </div>
                    
                    <div class="alert alert-info small py-2">
                              
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

    const listAlert = element.querySelector('.list-alert')
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

                        : ` <button type="button" class="btn btn-outline-secondary btn-sm" disabled title="${escapeHtml(getBlockedMessage(group.joinBlockedReason))}"
                            >
                                ${remaining <= 0    
                                    ? '정원 마감'
                                    : '가입 불가'
                                }
                            </button>`
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
        if (loading) return;

        loading = true;
        searchButton.disabled = true;

        hideAlert(listAlert);

        groupList.innerHTML = `<tr>
                                  <td colspan="4" class="text-center py-4">그룹 목록을 불러오는 중입니다.</td>
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
           if (
               totalPages > 0 &&
               currentPage >= totalPages
           ) {
               currentPage = totalPages - 1;
               loading = false;
               await loadGroup();
               return;
           }
           renderGroups(groups);
           renderPagination();

       } catch (error) {
           groupList.innerHTML = `<tr>
                                     <td colspan="4" class="text-center py-4 text-danger">그룹 목록을 불러오지 못했습니다.</td>
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

        try {
            const result = await createGroup({

                name: element.querySelector('#group-name').value.trim(),
                description: element.querySelector('#group-description').value
            });

            console.log('그룹 생성 응답:', result);
            console.log('초대 링크:', result.initialInvitationUrl);

            createAlert.className = 'alert alert-success create-alert mt-3 mb-0';
            createAlert.replaceChildren();

            const message = document.createElement('p');
            message.className = 'mb-2 fw-semibold';
            message.textContent = '그룹을 만들었습니다';
            createAlert.append(message);

            if (result.initialInvitationUrl) {
                const link = document.createElement('a');
                link.href = result.initialInvitationUrl;
                link.textContent = result.initialInvitationUrl;
                link.target = '_blank'
                link.rel = 'noopener noreferrer';
                link.className = 'd-block text-break';

                createAlert.append(link);
            }

            createForm.reset();

            // 그룹 생성 성공 후 생명체 화면으로 이동
            navigate(result?.nextRoute || '/creature');

        } catch (error) {
            showAlert(
                createAlert,
                error.message || '그룹 생성에 실패했습니다.'
            );
        } finally {
            createSubmit.disabled = false;
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
            showAlert(
                listAlert,
                error.message || '그룹 가입에 실패했습니다.'
            );
        } finally {
            joining = false;
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
    groupList.addEventListener('click', onJoin);
    pagination.addEventListener('click', onPagination);
    themeButton.addEventListener('click', onTheme);

    loadGroup();

    return {
        element,
        cleanup: () => {
            searchForm.removeEventListener('submit', onSearch);
            createToggle.removeEventListener('click', onCreateToggle);
            createForm.removeEventListener('submit', onCreate);
            groupList.removeEventListener('click', onJoin);
            pagination.removeEventListener('click', onPagination);
            themeButton.removeEventListener('click', onTheme);
        },
    };
}


