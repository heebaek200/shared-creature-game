
import {createAppShell} from '../components/app-shell.js';
import {ApiError} from '../api/client.js';
import {
    getMyGroup,
    listMyGroupMembers,
    leaveMyGroup,
    createUserReport,
} from '../features/group/api.js';

/** 그룹 멤버십의 서버 역할 코드를 화면 표시명으로 변환합니다. */
const ROLE_LABELS = {
    PRIMARY: '주 관리자',
    DEPUTY: '부 관리자',
    MEMBER: '일반 회원',
};

/**
 * 그룹 현황과 활성 구성원을 조회하는 페이지를 생성합니다.
 * 그룹 상세 및 구성원 목록을 실제 API에서 병렬로 가져옵니다.
 * 관리자 권한이 있는 회원에게만 그룹 관리 경로를 제공합니다.
 *
 * @param {object} context Router가 전달한 페이지 컨텍스트
 * @returns {{element: HTMLElement, cleanup: Function}} 페이지 객체
 */
export function groupPage({navigate, currentUser}) {
    const content = document.createElement('main');
    content.className = 'group-page py-3';

    // 화면 구조에는 고정된 HTML만 작성합니다.
    // API에서 받은 사용자 입력값은 아래에서 textContent로 삽입합니다.
    content.innerHTML = `
        <section class="app-surface-card border border-app rounded-4 p-3 p-md-4 mb-4">
            <div class="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-4">
                <div>
                    <p class="small text-app-muted fw-semibold mb-1">
                        GROUP
                    </p>
                    <h1 class="h3 fw-bold mb-2" data-group-name>
                        그룹 정보 조회 중
                    </h1>
                    <p class="text-app-muted mb-0" data-group-description></p>
                </div>

                <a href="/group-admin"
                   data-link
                   data-manage-link
                   class="btn btn-success d-none">
                    그룹 관리
                </a>
            </div>

            <div class="row g-3">
                <div class="col-12 col-md-4">
                    <div class="small text-app-muted mb-1">구성원</div>
                    <div class="fw-semibold" data-member-count>-</div>
                </div>

                <div class="col-12 col-md-4">
                    <div class="small text-app-muted mb-1">주 관리자</div>
                    <div class="fw-semibold" data-primary-admin>-</div>
                </div>

                <div class="col-12 col-md-4">
                    <div class="small text-app-muted mb-1">내 역할</div>
                    <div class="fw-semibold" data-my-role>-</div>
                </div>
            </div>
                        
            <div class="border-top border-app mt-4 pt-3
                        d-flex flex-wrap justify-content-between
                        align-items-center gap-3">
                <div>
                    <p class="fw-semibold mb-1">그룹 탈퇴</p>
                    <p class="small text-app-muted mb-0">
                        탈퇴 후 24시간 동안 다른 그룹에 가입할 수 없습니다.
                    </p>
                </div>
            
                <button type="button"
                        class="btn btn-outline-danger"
                        data-leave-open
                        disabled>
                    그룹 탈퇴
                </button>
            </div>
            
        </section>

        <section class="app-surface-card border border-app rounded-4 p-3 p-md-4">
            <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
                <h2 class="h5 fw-bold mb-0">구성원</h2>
                <span class="small text-app-muted" data-total-members></span>
            </div>

            <div class="table-responsive">
                <table class="table align-middle mb-0">
                    <thead>
                        <tr>
                            <th scope="col">닉네임</th>
                            <th scope="col">역할</th>
                            <th scope="col">가입일</th>
                            <th scope="col">마지막 활동</th>
                            <th scope="col">동작</th>
                        </tr>
                    </thead>

                    <tbody data-member-list>
                        <tr>
                            <td colspan="4" class="py-4">
                                구성원을 불러오는 중입니다.
                            </td>
                        </tr>
                    </tbody>
                </table>
            </div>
        </section>
        
        <!-- 탈퇴 또는 신고 작업의 성공 메시지 -->
        <div class="alert alert-success mt-3 d-none"
             role="status"
             aria-live="polite"
             data-action-feedback></div>
        
        <!-- 그룹 탈퇴 확인 Modal -->
        <dialog class="app-surface-card border border-app rounded-4
                       shadow-lg p-0 text-app"
                style="width: min(90vw, 34rem);"
                aria-labelledby="leave-title"
                aria-describedby="leave-description"
                data-leave-dialog>
        
            <div class="p-4">
                <h2 id="leave-title" class="h5 fw-bold mb-3">
                    그룹을 탈퇴하시겠습니까?
                </h2>
        
                <p id="leave-description"
                   class="mb-3"
                   data-leave-description></p>
        
                <div class="alert alert-warning small"
                     data-leave-warning>
                    탈퇴하면 24시간 동안 새 그룹에 가입할 수 없습니다.
                </div>
        
                <div class="alert alert-danger d-none"
                     role="alert"
                     data-leave-error></div>
        
                <div class="d-flex flex-wrap justify-content-end gap-2 mt-4">
                    <button type="button"
                            class="btn btn-outline-secondary"
                            data-leave-cancel>
                        취소
                    </button>
        
                    <button type="button"
                            class="btn btn-danger"
                            data-leave-confirm>
                        탈퇴 확인
                    </button>
                </div>
            </div>
        </dialog>
        
        <!-- 회원 신고 Modal -->
        <dialog class="app-surface-card border border-app rounded-4
                       shadow-lg p-0 text-app"
                style="width: min(90vw, 34rem);"
                aria-labelledby="report-title"
                data-report-dialog>
        
            <form class="p-4" data-report-form novalidate>
                <h2 id="report-title" class="h5 fw-bold mb-3">
                    회원 신고
                </h2>
        
                <p class="mb-3">
                    신고 대상:
                    <strong data-report-target></strong>
                </p>
        
                <div class="mb-3">
                    <label class="form-label fw-semibold"
                           for="group-report-category">
                        신고 분류
                    </label>
        
                    <select id="group-report-category"
                            class="form-select"
                            required
                            data-report-category>
                        <option value="">분류 선택</option>
                        <option value="HARASSMENT">괴롭힘</option>
                        <option value="SPAM">스팸</option>
                        <option value="INAPPROPRIATE_CONTENT">
                            부적절한 콘텐츠
                        </option>
                        <option value="OTHER">기타</option>
                    </select>
                </div>
        
                <div class="mb-3">
                    <label class="form-label fw-semibold"
                           for="group-report-content">
                        신고 내용
                    </label>
        
                    <textarea id="group-report-content"
                              class="form-control"
                              rows="5"
                              maxlength="1000"
                              required
                              placeholder="신고 사유를 작성해 주세요."
                              data-report-content></textarea>
        
                    <div class="form-text">
                        공백만 입력할 수 없습니다. 최대 1000자.
                    </div>
                </div>
        
                <div class="alert alert-info small">
                    신고는 운영 관리자에게 접수됩니다.
                    신고만으로 해당 회원이 즉시 강퇴되지는 않습니다.
                </div>
        
                <div class="alert alert-danger d-none"
                     role="alert"
                     data-report-error></div>
        
                <div class="d-flex justify-content-end gap-2 mt-4">
                    <button type="button"
                            class="btn btn-outline-secondary"
                            data-report-cancel>
                        취소
                    </button>
        
                    <button type="submit"
                            class="btn btn-danger"
                            data-report-submit>
                        신고 접수
                    </button>
                </div>
            </form>
        </dialog>

        <div class="alert alert-danger mt-3 d-none"
             role="alert"
             data-group-error></div>

        <button type="button"
                class="btn btn-outline-success mt-3 d-none"
                data-retry>
            다시 조회
        </button>
    `;

    // 공통 Header, 사이드 메뉴, 모바일 메뉴는 기존 구현을 재사용합니다.
    const element = createAppShell({
        content,
        currentPath: location.pathname,
        currentUser,
        navigate,
    });

    const memberList = content.querySelector('[data-member-list]');
    const manageLink = content.querySelector('[data-manage-link]');
    const errorBox = content.querySelector('[data-group-error]');
    const retryButton = content.querySelector('[data-retry]');

    let mounted = true;
    let loadVersion = 0;

    // 탈퇴 관련 DOM
    const leaveOpenButton = content.querySelector('[data-leave-open]');
    const leaveDialog = content.querySelector('[data-leave-dialog]');
    const leaveDescription = content.querySelector('[data-leave-description]');
    const leaveWarning = content.querySelector('[data-leave-warning]');
    const leaveError = content.querySelector('[data-leave-error]');
    const leaveConfirm = content.querySelector('[data-leave-confirm]');
    const leaveCancel = content.querySelector('[data-leave-cancel]');

    // 신고 관련 DOM
    const reportDialog = content.querySelector('[data-report-dialog]');
    const reportForm = content.querySelector('[data-report-form]');
    const reportTarget = content.querySelector('[data-report-target]');
    const reportCategory = content.querySelector('[data-report-category]');
    const reportContent = content.querySelector('[data-report-content]');
    const reportError = content.querySelector('[data-report-error]');
    const reportSubmit = content.querySelector('[data-report-submit]');
    const reportCancel = content.querySelector('[data-report-cancel]');

    const actionFeedback = content.querySelector('[data-action-feedback]');

    // 성공한 조회 결과만 화면 작업에 사용합니다.
    let groupSnapshot = null;
    let membersSnapshot = [];
    let selectedReportMember = null;

    // 중복 제출 및 탈퇴 성공 상태를 별도로 관리합니다.
    let leaving = false;
    let reporting = false;
    let leftSuccessfully = false;
    let leaveBlocked = false;

    /**
     * 날짜를 한국 시간대로 표시합니다.
     * 서버의 UTC ISO 8601 값을 Date 객체로 해석합니다.
     * 누락되거나 잘못된 값이면 안내 문구를 반환합니다.
     *
     * @param {string|null} value 서버 날짜
     * @returns {string} 화면 표시용 날짜
     */
    function formatDateTime(value) {
        if (!value) return '기록 없음';

        const date = new Date(value);

        if (Number.isNaN(date.getTime())) {
            return '날짜 확인 불가';
        }

        return new Intl.DateTimeFormat('ko-KR', {
            timeZone: 'Asia/Seoul',
            dateStyle: 'medium',
            timeStyle: 'short',
        }).format(date);
    }

    /**
     * 특정 요소에 일반 텍스트를 표시합니다.
     * API에서 전달된 문자열을 HTML로 해석하지 않습니다.
     * 닉네임이나 그룹명에 포함된 특수문자의 XSS 위험을 줄입니다.
     *
     * @param {string} selector 조회할 요소 선택자
     * @param {unknown} value 표시할 값
     */
    function setText(selector, value) {
        const target = content.querySelector(selector);

        if (target) {
            target.textContent = String(value ?? '');
        }
    }

    /**
     * 테이블 행에 텍스트 셀을 추가합니다.
     * 외부 입력값을 innerHTML에 직접 결합하지 않습니다.
     * 모든 구성원 행에서 같은 생성 방식을 사용합니다.
     *
     * @param {HTMLTableRowElement} row 추가할 행
     * @param {unknown} value 셀에 표시할 내용
     */
    function appendTextCell(row, value) {
        const cell = document.createElement('td');
        cell.textContent = String(value ?? '-');
        row.append(cell);
    }

    /**
     * 구성원 목록을 화면에 표시합니다.
     * 자신을 제외한 그룹 구성원에게만 신고 버튼을 제공합니다.
     * 사용자 입력값은 HTML로 해석하지 않고 텍스트로 처리합니다.
     *
     * @param {object[]} members 활성 구성원 목록
     * @param {number|null} myMembershipId 현재 사용자 멤버십 ID
     */
    function renderMembers(members, myMembershipId) {
        memberList.replaceChildren();

        if (members.length === 0) {
            const row = document.createElement('tr');
            const cell = document.createElement('td');

            cell.colSpan = 5;
            cell.className = 'text-center text-app-muted py-4';
            cell.textContent = '표시할 구성원이 없습니다.';

            row.append(cell);
            memberList.append(row);
            return;
        }

        // 기존 API의 정렬 결과를 유지합니다.
        members.forEach((member) => {
            const row = document.createElement('tr');
            const nameCell = document.createElement('td');

            const isMe = myMembershipId != null
                && String(member.membershipId) === String(myMembershipId);

            nameCell.textContent =
                isMe ? `${member.nickname} (나)` : member.nickname;

            row.append(nameCell);

            // 기존 날짜 및 역할 표시 함수를 재사용합니다.
            appendTextCell(row, ROLE_LABELS[member.role] ?? member.role);
            appendTextCell(row, formatDateTime(member.joinedAt));
            appendTextCell(row, formatDateTime(member.lastActiveAt));

            // 자신이 아닌 구성원에게만 신고 버튼을 표시합니다.
            const actionCell = document.createElement('td');

            if (!isMe) {
                const reportButton = document.createElement('button');
                reportButton.type = 'button';
                reportButton.className = 'btn btn-outline-danger btn-sm';
                reportButton.textContent = '신고';

                // 실제 신고 대상은 userId입니다. membershipId와 혼동하지 않습니다.
                reportButton.dataset.reportUserId = String(member.userId);

                actionCell.append(reportButton);
            } else {
                actionCell.textContent = '-';
            }

            row.append(actionCell);
            memberList.append(row);
        });
    }

    /**
     * 그룹 상세와 구성원 목록을 화면에 반영합니다.
     * 그룹 상세 API의 멤버십 역할을 관리 권한 판단에 사용합니다.
     * 목록 메타데이터를 이용해 총 인원을 표시합니다.
     *
     * @param {object} groupData 그룹 상세 응답
     * @param {object} memberResult 구성원 목록 및 메타데이터
     */
    function renderGroup(groupData, memberResult) {
        const group = groupData.group;
        const primaryAdmin = groupData.primaryAdmin;
        const myMembership = groupData.myMembership;

        // 최신 조회 결과를 후속 탈퇴·신고 작업에서 사용합니다.
        groupSnapshot = groupData;
        membersSnapshot = memberResult.data;

        // 실제 소속 그룹을 확인한 뒤에만 탈퇴 버튼을 활성화합니다.
        leaveOpenButton.disabled = false;

        setText('[data-group-name]', group.name);
        setText(
            '[data-group-description]',
            group.description || '그룹 소개가 없습니다.'
        );
        setText(
            '[data-member-count]',
            `${group.currentMemberCount} / ${group.capacity}명`
        );
        setText(
            '[data-primary-admin]',
            primaryAdmin?.nickname ?? '확인 불가'
        );

        // 관리자 권한은 현재 그룹 상세 응답을 기준으로 판단합니다.
        const myRole = myMembership?.role;
        const canManage = myRole === 'PRIMARY' || myRole === 'DEPUTY';

        setText('[data-my-role]', ROLE_LABELS[myRole] ?? '확인 불가');
        manageLink.classList.toggle('d-none', !canManage);

        const members = memberResult.data;
        const totalMembers = memberResult.meta?.totalElements ?? members.length;

        setText('[data-total-members]', `전체 ${totalMembers}명`);
        renderMembers(members, myMembership?.membershipId ?? null);
    }

    /**
     * 최초 조회 및 재조회 중 Skeleton을 표시합니다.
     * 오류와 재시도 버튼을 일시적으로 숨깁니다.
     * 기존 목록은 새 조회를 시작하면서 비웁니다.
     */
    function showLoading() {
        // 재조회 중에는 이전 조회 결과로 새로운 작업을 시작하지 않습니다.
        groupSnapshot = null;
        membersSnapshot = [];
        leaveOpenButton.disabled = true;

        content.setAttribute('aria-busy', 'true');
        errorBox.classList.add('d-none');
        retryButton.classList.add('d-none');

        memberList.innerHTML = `
            <tr aria-hidden="true">
                <td><span class="placeholder col-8"></span></td>
                <td><span class="placeholder col-6"></span></td>
                <td><span class="placeholder col-8"></span></td>
                <td><span class="placeholder col-8"></span></td>
                <td><span class="placeholder col-6"></span></td>
            </tr>
        `;
    }

    /**
     * 그룹 조회 실패를 사용자에게 알립니다.
     * 공통 ApiError의 코드와 traceId를 보존합니다.
     * 재시도 버튼을 제공하되 자동으로 반복 요청하지 않습니다.
     *
     * @param {unknown} error 발생한 오류
     */
    function showError(error) {
        const code = error instanceof ApiError ? error.code : null;

        const messages = {
            AUTH_REQUIRED: '로그인이 필요합니다.',
            SESSION_EXPIRED: '로그인 세션이 만료되었습니다.',
            ROLE_FORBIDDEN: '그룹 정보를 볼 권한이 없습니다.',
            RESOURCE_NOT_FOUND: '현재 소속 그룹을 찾을 수 없습니다.',
        };

        const message = messages[code]
            ?? (error instanceof Error ? error.message : '조회에 실패했습니다.');

        const details = error instanceof ApiError
            ? ` [${error.code}${error.traceId ? ` / ${error.traceId}` : ''}]`
            : '';

        errorBox.textContent = `${message}${details}`;
        errorBox.classList.remove('d-none');
        retryButton.classList.remove('d-none');

        // 실패한 데이터가 정상 조회된 것처럼 표시되지 않도록 비웁니다.
        memberList.replaceChildren();

        const row = document.createElement('tr');
        const cell = document.createElement('td');

        cell.colSpan = 5;
        cell.className = 'text-center text-app-muted py-4';
        cell.textContent = '구성원 정보를 표시할 수 없습니다.';

        row.append(cell);
        memberList.append(row);
    }

    /**
     * 그룹 상세와 구성원 목록을 병렬 조회합니다.
     * 서버 응답 구조를 확인한 뒤 화면을 갱신합니다.
     * 페이지가 종료된 뒤 도착한 응답은 무시합니다.
     */
    async function loadGroup() {
        const version = ++loadVersion;
        showLoading();

        try {
            const [groupData, memberResult] = await Promise.all([
                getMyGroup(),
                listMyGroupMembers({page: 0, size: 20}),
            ]);

            if (!mounted || version !== loadVersion) return;

            // 공통 Client는 단건 응답의 data Envelope을 이미 제거합니다.
            if (!groupData?.group || !Array.isArray(memberResult?.data)) {
                throw new Error('그룹 조회 응답 형식이 예상과 다릅니다.');
            }

            renderGroup(groupData, memberResult);
        } catch (error) {
            if (!mounted || version !== loadVersion) return;

            showError(error);
        } finally {
            if (mounted && version === loadVersion) {
                content.setAttribute('aria-busy', 'false');
            }
        }
    }

    /**
     * API 오류를 사용자가 이해할 수 있는 문장으로 변환합니다.
     * 알려진 업무 오류는 구체적인 대응 방법을 함께 안내합니다.
     * 공통 ApiError의 오류 코드와 추적 ID도 유지합니다.
     *
     * @param {unknown} error API 요청 오류
     * @returns {string} 화면에 표시할 오류 메시지
     */
    function getActionErrorMessage(error) {
        const messages = {
            PRIMARY_TRANSFER_REQUIRED:
                '다른 구성원이 있는 그룹의 주 관리자는 먼저 권한을 이전해야 합니다.',
            CONCURRENT_MODIFICATION:
                '다른 요청으로 상태가 변경되었습니다. 화면을 다시 조회해 주세요.',
            ROLE_FORBIDDEN:
                '현재 계정에 해당 작업 권한이 없습니다.',
            RESOURCE_NOT_FOUND:
                '대상 회원 또는 그룹을 찾을 수 없습니다. 목록을 다시 조회해 주세요.',
            INVALID_FIELD_VALUE:
                '입력값을 확인해 주세요.',
            CSRF_INVALID:
                '보안 토큰을 확인하지 못했습니다. 다시 시도해 주세요.',
            SESSION_EXPIRED:
                '로그인 세션이 만료되었습니다. 다시 로그인해 주세요.',
            AUTH_REQUIRED:
                '로그인이 필요합니다.',
            RATE_LIMIT_EXCEEDED:
                '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
        };

        if (!(error instanceof ApiError)) {
            return error instanceof Error
                ? error.message
                : '요청 처리에 실패했습니다.';
        }

        const message = messages[error.code] ?? error.message;
        const trace = error.traceId ? ` / ${error.traceId}` : '';

        return `${message} [${error.code}${trace}]`;
    }

    /**
     * 탈퇴 확인 Modal을 열고 현재 그룹의 탈퇴 조건을 설명합니다.
     * 주 관리자가 다른 회원과 함께 있다면 즉시 탈퇴를 막습니다.
     * 최종 검증은 실제 탈퇴 API에서 다시 수행합니다.
     */
    function openLeaveDialog() {
        if (!groupSnapshot || leaving || leftSuccessfully) return;

        const group = groupSnapshot.group;
        const role = groupSnapshot.myMembership?.role;
        const isPrimary = role === 'PRIMARY';
        const memberCount = Number(group.currentMemberCount);

        // 새로 열 때마다 이전 오류와 결과를 초기화합니다.
        leaveError.classList.add('d-none');
        leaveConfirm.classList.remove('d-none');
        leaveCancel.classList.remove('d-none');

        leaveBlocked = isPrimary && memberCount > 1;

        if (leaveBlocked) {
            leaveDescription.textContent =
                '현재 그룹에 다른 구성원이 있습니다. 주 관리자 권한을 ' +
                '다른 활성 회원에게 이전한 후 탈퇴할 수 있습니다.';

            leaveWarning.textContent =
                '그룹 관리 화면에서 주 관리자 이전을 먼저 진행해 주세요.';
        } else if (isPrimary) {
            leaveDescription.textContent =
                '현재 그룹에 혼자 남아 있습니다. 탈퇴 시 그룹이 비활성화되고 ' +
                '초대 링크와 현재 시즌 참여가 함께 종료됩니다. 기존 기록은 보존됩니다.';

            leaveWarning.textContent =
                '그룹 종료와 함께 탈퇴 후 24시간 가입 제한이 적용됩니다.';
        } else {
            leaveDescription.textContent =
                `${group.name} 그룹에서 탈퇴합니다.`;

            leaveWarning.textContent =
                '탈퇴 후 24시간 동안 새 그룹에 가입할 수 없습니다.';
        }

        leaveConfirm.disabled = leaveBlocked;
        leaveDialog.showModal();
    }

    /**
     * 사용자의 최종 확인 후 실제 그룹 탈퇴를 요청합니다.
     * 탈퇴 성공 시 완료 Modal에 머무르지 않고 그룹 선택 화면으로 이동합니다.
     * 실패한 경우에는 Modal을 유지하고 오류 메시지를 표시합니다.
     */
    async function confirmLeave() {
        if (leaving || leftSuccessfully || leaveBlocked) return;

        leaving = true;
        leaveConfirm.disabled = true;
        leaveCancel.disabled = true;
        leaveError.classList.add('d-none');

        try {
            // 서버가 실제 멤버십과 관리자 권한을 최종 검증합니다.
            await leaveMyGroup();

            if (!mounted) return;

            // 성공 이후 추가 탈퇴 요청을 방지합니다.
            leftSuccessfully = true;
            leaveOpenButton.disabled = true;

            // 기존 Modal을 닫고 즉시 그룹 선택 화면으로 이동합니다.
            leaveDialog.close();

            // Router가 /api/v1/me를 재조회하여 최신 소속 상태를 반영합니다.
            navigate('/groups/select?reason=JOIN_COOLDOWN_ACTIVE');

        } catch (error) {
            if (!mounted) return;

            // API 실패 시에는 이동하지 않고 오류 원인을 안내합니다.
            leaveError.textContent = getActionErrorMessage(error);
            leaveError.classList.remove('d-none');

        } finally {
            leaving = false;

            // 탈퇴 실패 시에만 재시도를 허용합니다.
            if (mounted && !leftSuccessfully) {
                leaveConfirm.disabled = leaveBlocked;
                leaveCancel.disabled = false;
            }
        }
    }

    /**
     * 구성원 테이블에서 신고 버튼을 선택하면 Modal을 엽니다.
     * 최신 목록에서 대상을 다시 찾고 본인 신고를 차단합니다.
     * 대상 회원의 ID만 서버 요청에 사용합니다.
     *
     * @param {MouseEvent} event 구성원 목록 클릭 이벤트
     */
    function openReportDialog(event) {
        if (!(event.target instanceof Element) || reporting) return;

        const button = event.target.closest('[data-report-user-id]');
        if (!button || !groupSnapshot) return;

        const targetId = button.dataset.reportUserId;
        const member = membersSnapshot.find(
            (item) => String(item.userId) === targetId
        );

        if (!member) return;

        // 본인 신고 버튼을 숨기는 것과 별개로 이벤트에서도 검사합니다.
        const myMembershipId = groupSnapshot.myMembership?.membershipId;

        if (String(member.membershipId) === String(myMembershipId)) {
            return;
        }

        selectedReportMember = member;

        reportForm.reset();
        reportError.classList.add('d-none');
        reportTarget.textContent = member.nickname;

        reportDialog.showModal();
        reportCategory.focus();
    }

    /**
     * 신고 양식의 필수값을 확인하고 API에 접수합니다.
     * 입력 처리 중에는 중복 신고 요청과 Modal 닫기를 방지합니다.
     * 접수 성공 후에도 회원의 그룹 소속은 그대로 유지됩니다.
     *
     * @param {SubmitEvent} event 신고 양식 제출 이벤트
     */
    async function submitReport(event) {
        event.preventDefault();

        if (reporting || !selectedReportMember) return;

        const category = reportCategory.value;
        const contentText = reportContent.value;
        const validCategories = [
            'HARASSMENT',
            'SPAM',
            'INAPPROPRIATE_CONTENT',
            'OTHER',
        ];

        // 서버 DTO와 같은 입력 조건을 적용합니다.
        if (!validCategories.includes(category)) {
            reportError.textContent = '신고 분류를 선택해 주세요.';
            reportError.classList.remove('d-none');
            return;
        }

        if (!contentText.trim() || contentText.length > 1000) {
            reportError.textContent =
                '신고 내용은 공백을 제외하고 1~1000자로 입력해 주세요.';
            reportError.classList.remove('d-none');
            return;
        }

        reporting = true;
        reportSubmit.disabled = true;
        reportCancel.disabled = true;
        reportError.classList.add('d-none');

        try {
            const result = await createUserReport({
                reportedUserId: selectedReportMember.userId,
                category,
                content: contentText,
            });

            if (!mounted) return;

            reportDialog.close();
            selectedReportMember = null;

            // 신고 접수와 강퇴를 명확히 구분합니다.
            actionFeedback.textContent =
                `신고가 접수되었습니다. (접수 번호: ${result.reportId}) ` +
                '운영 관리자가 검토하며, 신고만으로 해당 회원이 강퇴되지는 않습니다.';

            actionFeedback.classList.remove('d-none');

            // 서버 기준의 최신 구성원 상태를 다시 조회합니다.
            void loadGroup();

        } catch (error) {
            if (!mounted) return;

            reportError.textContent = getActionErrorMessage(error);
            reportError.classList.remove('d-none');
        } finally {
            reporting = false;

            if (mounted) {
                reportSubmit.disabled = false;
                reportCancel.disabled = false;
            }
        }
    }

    /**
     * 제출 처리 중이거나 탈퇴가 완료되었다면 ESC 닫기를 막습니다.
     * 요청 결과가 불명확한 상태에서 다른 작업을 시작하지 않도록 합니다.
     * 일반적인 취소 상황에서는 브라우저 기본 닫기 동작을 허용합니다.
     *
     * @param {Event} event dialog cancel 이벤트
     */
    function handleLeaveCancel(event) {
        if (leaving || leftSuccessfully) {
            event.preventDefault();
        }
    }

    /**
     * 신고 제출이 진행되는 동안 ESC 닫기를 막습니다.
     * 전송 중 입력값과 신고 대상이 바뀌는 일을 방지합니다.
     * 처리가 끝난 뒤에는 정상적으로 닫을 수 있습니다.
     *
     * @param {Event} event dialog cancel 이벤트
     */
    function handleReportCancel(event) {
        if (reporting) {
            event.preventDefault();
        }
    }

    // 재시도 버튼과 최초 API 호출을 연결합니다.
    const onRetry = () => {
        void loadGroup();
    };

    // 기존 그룹 조회 재시도 이벤트
    retryButton.addEventListener('click', onRetry);

    // 탈퇴 관련 이벤트
    leaveOpenButton.addEventListener('click', openLeaveDialog);
    leaveConfirm.addEventListener('click', confirmLeave);
    leaveDialog.addEventListener('cancel', handleLeaveCancel);

    const onLeaveCancelClick = () => {
        if (!leaving && !leftSuccessfully) {
            leaveDialog.close();
        }
    };

    leaveCancel.addEventListener('click', onLeaveCancelClick);

    // 신고 관련 이벤트
    memberList.addEventListener('click', openReportDialog);
    reportForm.addEventListener('submit', submitReport);
    reportDialog.addEventListener('cancel', handleReportCancel);

    const onReportCancelClick = () => {
        if (!reporting) {
            reportDialog.close();
            selectedReportMember = null;
        }
    };

    reportCancel.addEventListener('click', onReportCancelClick);

    // 최초 데이터 조회
    void loadGroup();

    return {
        element,
        cleanup: () => {
            // 페이지가 종료된 뒤 늦게 도착하는 API 응답을 무시합니다.
            mounted = false;
            loadVersion++;

            // 이벤트 리스너를 정리합니다.
            retryButton.removeEventListener('click', onRetry);

            leaveOpenButton.removeEventListener('click', openLeaveDialog);
            leaveConfirm.removeEventListener('click', confirmLeave);
            leaveCancel.removeEventListener('click', onLeaveCancelClick);
            leaveDialog.removeEventListener('cancel', handleLeaveCancel);

            memberList.removeEventListener('click', openReportDialog);
            reportForm.removeEventListener('submit', submitReport);
            reportCancel.removeEventListener('click', onReportCancelClick);
            reportDialog.removeEventListener('cancel', handleReportCancel);

            // 페이지 이동 시 열려 있는 Modal도 정리합니다.
            if (leaveDialog.open) leaveDialog.close();
            if (reportDialog.open) reportDialog.close();
        },
    };

}
