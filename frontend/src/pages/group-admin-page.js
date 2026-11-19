
import {createAppShell} from '../components/app-shell.js';
import {ApiError} from '../api/client.js';
import {getMyGroup, listMyGroupMembers} from '../features/group/api.js';
import {
    changeMemberRole,
    removeGroupMember,
    transferPrimaryAdmin,
    getInvitationManagement,
    rotateInvitation
} from '../features/group-admin/api.js';
import {
    getActiveCreatureSpecies,
    getCreatureHome,
    selectCreatureSpecies
} from '../features/creature/api.js';

import creatureLv0 from '../../assets/creature/lv0.png';

// 기존 크리처 홈에서 사용하는 이미지 매핑 방식을 재사용합니다.
// 종의 assetKey와 대응하는 lv1.png가 없으면 공통 알 이미지를 사용합니다.
const CREATURE_EGG_IMAGES = import.meta.glob(
    '../../assets/creature/*/lv1.png',
    {
        eager: true,
        query: '?url',
        import: 'default'
    }
);

const ROLE_LABELS = {
    PRIMARY: '주 관리자',
    DEPUTY: '부 관리자',
    MEMBER: '일반 회원'
};

const INACTIVITY_MS = (72 * 60 + 10) * 60 * 1000;

/**
 * 그룹 관리자 화면을 생성합니다.
 * 구성원 역할 변경·비활동 강퇴·주 관리자 이전을 제공합니다.
 * 권한은 화면에서 안내하되 실제 승인 여부는 서버가 결정합니다.
 *
 * @param {{navigate: Function, currentUser: object}} context Router 컨텍스트
 * @returns {{element: HTMLElement, cleanup: Function}} 화면과 정리 함수
 */
export function adminPage({navigate, currentUser}) {
    const content = document.createElement('main');
    content.className = 'group-admin-page py-3';

    content.innerHTML = `
        <section class="app-surface-card border border-app rounded-4 p-3 p-md-4 mb-4">
            <div class="d-flex flex-wrap justify-content-between align-items-center gap-3">
                <div>
                    <p class="small text-app-muted fw-semibold mb-1">
                        GROUP ADMIN
                    </p>
                    <h1 class="h3 fw-bold mb-2">그룹 관리</h1>
                    <p class="text-app-muted mb-0" data-summary>
                        그룹 정보를 확인하고 있습니다.
                    </p>
                </div>

                <a href="/group" data-link
                   class="btn btn-outline-secondary">
                    그룹으로 돌아가기
                </a>
            </div>

            <div class="alert alert-info small mt-3 mb-0">
                강퇴는 마지막 활동 이후 72시간 10분이 지나야 요청할 수 있습니다.
                활동 기록이 없는 회원은 강퇴할 수 없으며,
                최종 가능 여부는 서버가 판단합니다.
            </div>
        </section>
        
        <!-- 그룹 관리자 기능 전환 메뉴 -->
        <nav class="nav nav-pills gap-2 mb-3"
             aria-label="그룹 관리 메뉴"
             data-admin-tabs>
            <button type="button"
                    class="nav-link active"
                    data-admin-tab="members"
                    aria-pressed="true">
                구성원 관리
            </button>
        
            <button type="button"
                    class="nav-link d-none"
                    data-admin-tab="invitation"
                    aria-pressed="false">
                초대 링크
            </button>
            
            <button type="button"
                    class="nav-link d-none"
                    data-admin-tab="creature"
                    aria-pressed="false">
                크리처 선택
            </button>
        </nav>

        <section class="app-surface-card border border-app rounded-4 p-3 p-md-4"
                data-members-panel>
            <div class="d-flex justify-content-between align-items-center gap-2 mb-3">
                <h2 class="h5 fw-bold mb-0">구성원 관리</h2>
                <button type="button"
                        class="btn btn-outline-success btn-sm"
                        data-refresh>
                    새로고침
                </button>
            </div>

            <div class="table-responsive">
                <table class="table align-middle mb-0">
                    <thead>
                        <tr>
                            <th scope="col">회원</th>
                            <th scope="col">역할</th>
                            <th scope="col">마지막 활동</th>
                            <th scope="col">가능 작업</th>
                        </tr>
                    </thead>
                    <tbody data-members>
                        <tr>
                            <td colspan="4">
                                구성원을 불러오는 중입니다.
                            </td>
                        </tr>
                    </tbody>
                </table>
            </div>

            <div class="border-top border-app mt-4 pt-3 d-none"
                 data-transfer-panel>
                <h3 class="h6 fw-bold mb-2">주 관리자 이전</h3>
                <p class="small text-app-muted mb-2">
                    이전 후 현재 주 관리자는 일반 회원이 되며
                    그룹 관리 권한을 잃습니다.
                </p>

                <div class="d-flex flex-wrap gap-2 align-items-center">
                    <label class="visually-hidden"
                           for="primary-transfer-target">
                        새 주 관리자
                    </label>
                    <select id="primary-transfer-target"
                            class="form-select w-auto"
                            data-transfer-target>
                        <option value="">이전할 회원 선택</option>
                    </select>
                    <button type="button"
                            class="btn btn-outline-danger"
                            data-transfer-open>
                        권한 이전
                    </button>
                </div>
            </div>
        </section>
        
        <!-- PRIMARY 전용 초대 링크 관리 -->
        <section class="app-surface-card border border-app rounded-4
                        p-3 p-md-4 d-none"
                 data-invitation-panel>
            <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-3">
                <h2 class="h5 fw-bold mb-0">초대 링크 관리</h2>
        
                <button type="button"
                        class="btn btn-outline-secondary btn-sm"
                        data-invitation-refresh>
                    발급 정보 새로고침
                </button>
            </div>
        
            <div class="alert alert-info small">
                현재 초대 링크의 발급 상태만 조회할 수 있습니다.
                보안상 기존 초대 URL은 다시 표시할 수 없습니다.
            </div>
        
            <dl class="row mb-3">
                <dt class="col-sm-4">발급 시각</dt>
                <dd class="col-sm-8" data-invitation-issued-at>-</dd>
        
                <dt class="col-sm-4">발급자 사용자 ID</dt>
                <dd class="col-sm-8" data-invitation-issued-by>-</dd>
        
                <dt class="col-sm-4">사용 횟수</dt>
                <dd class="col-sm-8" data-invitation-uses>-</dd>
        
                <dt class="col-sm-4">만료 시각</dt>
                <dd class="col-sm-8" data-invitation-expires>-</dd>
            </dl>
        
            <div class="alert alert-danger d-none"
                 role="alert"
                 data-invitation-error></div>
        
            <button type="button"
                    class="btn btn-danger"
                    data-invitation-rotate-open
                    disabled>
                새 초대 링크 발급
            </button>
        
            <!-- 새 초대 URL은 재발급 응답에서만 표시 -->
            <div class="alert alert-success mt-4 d-none"
                 role="status"
                 data-invitation-result>
                <h3 class="h6 fw-bold mb-2">
                    새 초대 링크가 발급되었습니다.
                </h3>
        
                <p class="small">
                    기존 링크는 무효화되었습니다.
                    새 링크를 지금 복사해 전달해 주세요.
                    화면을 떠나거나 새로고침하면 다시 조회할 수 없습니다.
                </p>
        
                <label class="form-label fw-semibold"
                       for="new-invitation-url">
                    새 초대 URL
                </label>
        
                <div class="d-flex flex-wrap gap-2">
                    <input type="text"
                           id="new-invitation-url"
                           class="form-control flex-grow-1"
                           style="min-width: 12rem;"
                           readonly
                           data-invitation-url>
        
                    <button type="button"
                            class="btn btn-success"
                            data-invitation-copy>
                        링크 복사
                    </button>
                </div>
        
                <p class="small mt-2 mb-0"
                   role="status"
                   aria-live="polite"
                   data-invitation-copy-status></p>
            </div>
        </section>
        
        <!-- 그룹 관리자 전용 크리처 종 선택 화면 -->
        <section class="app-surface-card border border-app rounded-4
                        p-3 p-md-4 d-none"
                 data-creature-panel>
        
            <div class="d-flex flex-wrap justify-content-between
                        align-items-center gap-2 mb-3">
                <h2 class="h5 fw-bold mb-0">
                    크리처 종 선택
                </h2>
        
                <button type="button"
                        class="btn btn-outline-secondary btn-sm"
                        data-creature-refresh>
                    최신 정보 조회
                </button>
            </div>
        
            <div class="alert alert-info small">
                부화 전에는 주·부 관리자가 크리처 종을 선택하거나 변경할 수 있습니다.
                아무 종도 선택하지 않으면 부화 시 무작위 종이 확정됩니다.
            </div>
        
            <!-- 선택 기한 및 현재 상태 -->
            <div class="row g-3 mb-4">
                <div class="col-md-6">
                    <div class="small text-app-muted mb-1">
                        종 선택 마감 시각
                    </div>
                    <strong data-creature-deadline>-</strong>
                </div>
        
                <div class="col-md-6">
                    <div class="small text-app-muted mb-1">
                        선택 마감까지
                    </div>
                    <strong data-creature-remaining
                            aria-live="off">
                        -
                    </strong>
                </div>
            </div>
        
            <div class="border border-app rounded-3 p-3 mb-4">
                <div class="small text-app-muted mb-1">
                    현재 선택된 종
                </div>
        
                <div class="fw-semibold mb-2" data-creature-current>
                    확인 중
                </div>
        
                <p class="small mb-0" role="status"
                   data-creature-status>
                    크리처 정보를 불러오고 있습니다.
                </p>
            </div>
        
            <h3 class="h6 fw-bold mb-3">
                선택 가능한 크리처
            </h3>
        
            <!-- 활성 종 목록은 JavaScript에서 생성 -->
            <div class="row g-3 mb-4"
                 role="radiogroup"
                 aria-label="크리처 종 선택"
                 data-creature-options>
            </div>
        
            <button type="button"
                    class="btn btn-success"
                    data-creature-save
                    disabled>
                선택 저장
            </button>
        
            <div class="alert alert-success mt-3 d-none"
                 role="status"
                 data-creature-feedback></div>
        
            <div class="alert alert-danger mt-3 d-none"
                 role="alert"
                 data-creature-error></div>
        </section>

        <!-- 링크 재발급은 기존 링크를 무효화하므로 확인이 필요 -->
        <dialog class="app-surface-card border border-app rounded-4
                       shadow-lg p-0 text-app"
                style="width: min(92vw, 36rem);"
                aria-labelledby="invitation-rotate-title"
                data-invitation-dialog>
            <div class="p-4">
                <h2 class="h5 fw-bold mb-3"
                    id="invitation-rotate-title">
                    초대 링크를 재발급하시겠습니까?
                </h2>
        
                <p>
                    새로운 링크가 발급되면 기존 링크는 즉시 무효화됩니다.
                </p>
        
                <div class="alert alert-warning small">
                    이미 다른 사람에게 공유한 기존 초대 링크도
                    더 이상 사용할 수 없습니다.
                    새 링크는 재발급 성공 응답에서만 확인할 수 있습니다.
                </div>
        
                <div class="alert alert-danger d-none"
                     role="alert"
                     data-invitation-dialog-error></div>
        
                <div class="d-flex justify-content-end gap-2 mt-4">
                    <button type="button"
                            class="btn btn-outline-secondary"
                            data-invitation-cancel>
                        취소
                    </button>
        
                    <button type="button"
                            class="btn btn-danger"
                            data-invitation-confirm>
                        기존 링크 무효화 및 재발급
                    </button>
                </div>
            </div>
        </dialog>

        <div class="alert alert-success mt-3 d-none"
             role="status" data-feedback></div>
        <div class="alert alert-danger mt-3 d-none"
             role="alert" data-error></div>

        <dialog class="app-surface-card border border-app rounded-4 shadow-lg p-0 text-app"
                style="width: min(92vw, 36rem);"
                aria-labelledby="group-action-title"
                data-dialog>
            <div class="p-4">
                <h2 class="h5 fw-bold mb-3"
                    id="group-action-title"
                    data-dialog-title></h2>
                <p data-dialog-description></p>

                <div class="mb-3 d-none" data-reason-wrap>
                    <label class="form-label fw-semibold"
                           for="group-remove-reason">
                        강퇴 사유
                    </label>
                    <textarea id="group-remove-reason"
                              class="form-control"
                              rows="4"
                              maxlength="500"
                              data-reason></textarea>
                    <div class="form-text">
                        공백을 제외하고 1~500자 입력해야 합니다.
                    </div>
                </div>

                <div class="alert alert-danger d-none"
                     role="alert" data-dialog-error></div>

                <div class="d-flex justify-content-end gap-2 mt-4">
                    <button type="button"
                            class="btn btn-outline-secondary"
                            data-cancel>
                        취소
                    </button>
                    <button type="button"
                            class="btn btn-danger"
                            data-confirm>
                        확인
                    </button>
                </div>
            </div>
        </dialog>
    `;

    const element = createAppShell({
        content,
        currentPath: location.pathname,
        currentUser,
        navigate
    });

    // 화면의 주요 DOM 요소를 한 번만 조회합니다.
    const summary = content.querySelector('[data-summary]');
    const membersBody = content.querySelector('[data-members]');
    const refreshButton = content.querySelector('[data-refresh]');
    const transferPanel = content.querySelector('[data-transfer-panel]');
    const transferSelect = content.querySelector('[data-transfer-target]');
    const transferOpen = content.querySelector('[data-transfer-open]');
    const feedback = content.querySelector('[data-feedback]');
    const errorBox = content.querySelector('[data-error]');

    // 관리자 작업 공통 Modal
    const dialog = content.querySelector('[data-dialog]');
    const dialogTitle = content.querySelector('[data-dialog-title]');
    const dialogDescription = content.querySelector('[data-dialog-description]');
    const dialogError = content.querySelector('[data-dialog-error]');
    const reasonWrap = content.querySelector('[data-reason-wrap]');
    const reasonInput = content.querySelector('[data-reason]');
    const confirmButton = content.querySelector('[data-confirm]');
    const cancelButton = content.querySelector('[data-cancel]');

    // 페이지 생명주기와 작업 상태
    let mounted = true;
    let loadVersion = 0;
    let busy = false;
    let groupData = null;
    let members = [];
    let pendingAction = null;

    // 관리자 화면 탭
    const membersPanel = content.querySelector('[data-members-panel]');
    const invitationPanel = content.querySelector('[data-invitation-panel]');
    const membersTab = content.querySelector('[data-admin-tab="members"]');
    const invitationTab = content.querySelector('[data-admin-tab="invitation"]');
    const adminTabs = content.querySelector('[data-admin-tabs]');

    // 초대 발급 정보와 결과 영역
    const invitationRefresh = content.querySelector('[data-invitation-refresh]');
    const invitationIssuedAt = content.querySelector('[data-invitation-issued-at]');
    const invitationIssuedBy = content.querySelector('[data-invitation-issued-by]');
    const invitationUses = content.querySelector('[data-invitation-uses]');
    const invitationExpires = content.querySelector('[data-invitation-expires]');
    const invitationError = content.querySelector('[data-invitation-error]');
    const invitationRotateOpen = content.querySelector('[data-invitation-rotate-open]');
    const invitationResult = content.querySelector('[data-invitation-result]');
    const invitationUrlInput = content.querySelector('[data-invitation-url]');
    const invitationCopy = content.querySelector('[data-invitation-copy]');
    const invitationCopyStatus = content.querySelector('[data-invitation-copy-status]');

    // 재발급 전용 확인 Modal
    const invitationDialog = content.querySelector('[data-invitation-dialog]');
    const invitationDialogError = content.querySelector('[data-invitation-dialog-error]');
    const invitationCancel = content.querySelector('[data-invitation-cancel]');
    const invitationConfirm = content.querySelector('[data-invitation-confirm]');

    // 재발급 응답의 URL은 현재 페이지 메모리에만 유지합니다.
    let activeTab = 'members';

    // 크리처 홈에서 전달한 초기 탭 요청을 기억합니다.
    // 그룹 정보가 로딩되기 전에는 권한을 판단할 수 없습니다.
    const requestedInitialTab =
        new URLSearchParams(window.location.search).get('tab');

    let initialTabHandled = false;

    let invitationData = null;
    let invitationLoaded = false;
    let invitationLoading = false;
    let rotatingInvitation = false;
    let invitationLoadVersion = 0;
    let oneTimeInvitationUrl = '';

    // 크리처 선택 탭
    const creatureTab = content.querySelector('[data-admin-tab="creature"]');
    const creaturePanel = content.querySelector('[data-creature-panel]');

    // 크리처 선택 화면 요소
    const creatureRefresh = content.querySelector('[data-creature-refresh]');
    const creatureDeadline = content.querySelector('[data-creature-deadline]');
    const creatureRemaining = content.querySelector('[data-creature-remaining]');
    const creatureCurrent = content.querySelector('[data-creature-current]');
    const creatureStatus = content.querySelector('[data-creature-status]');
    const creatureOptions = content.querySelector('[data-creature-options]');
    const creatureSave = content.querySelector('[data-creature-save]');
    const creatureFeedback = content.querySelector('[data-creature-feedback]');
    const creatureError = content.querySelector('[data-creature-error]');

    // 크리처 조회 결과와 사용자의 선택 상태
    let creatureHomeData = null;
    let creatureSpeciesList = [];
    let creatureSelectedId = '';

    // 중복 요청 및 페이지 종료 처리
    let creatureLoading = false;
    let creatureSaving = false;
    let creatureLoadVersion = 0;
    let creatureTimerId = null;
    let creatureDeadlineChecked = false;

    /**
     * UTC 활동 시각을 한국 시간대로 표시합니다.
     * 표시할 기록이 없다면 별도 문구를 사용합니다.
     * 잘못된 시각도 강퇴 가능으로 해석하지 않습니다.
     *
     * @param {string|null} value API의 활동 시각
     * @returns {string} 사용자 표시 문자열
     */
    function formatDateTime(value) {
        if (!value) return '기록 없음';

        const timestamp = Date.parse(value);
        if (!Number.isFinite(timestamp)) return '날짜 확인 불가';

        return new Intl.DateTimeFormat('ko-KR', {
            timeZone: 'Asia/Seoul',
            dateStyle: 'medium',
            timeStyle: 'short'
        }).format(new Date(timestamp));
    }

    /**
     * 관리자 자신의 ID인지 판별합니다.
     * 응답의 사용자 ID를 문자열 형태로 비교합니다.
     * 자신을 관리 작업의 대상으로 선택하지 않기 위해 사용합니다.
     *
     * @param {object} member 구성원
     * @returns {boolean} 본인 여부
     */
    function isMe(member) {
        return String(member.userId) ===
            String(currentUser?.user?.userId);
    }

    /**
     * 마지막 활동 시간과 관리 권한으로 강퇴 가능 여부를 안내합니다.
     * 서버의 72시간 10분 기준을 UI에도 동일하게 적용합니다.
     * 서버는 요청을 처리할 때 최신 상태로 다시 판정합니다.
     *
     * @param {object} member 대상 구성원
     * @returns {boolean} UI에서 강퇴를 허용할지 여부
     */
    function canRemove(member) {
        const myRole = groupData?.myMembership?.role;

        const permittedRole = myRole === 'PRIMARY'
            ? ['MEMBER', 'DEPUTY'].includes(member.role)
            : myRole === 'DEPUTY' && member.role === 'MEMBER';

        if (!permittedRole || isMe(member) || !member.lastActiveAt) {
            return false;
        }

        const lastActiveMs = Date.parse(member.lastActiveAt);

        return Number.isFinite(lastActiveMs) &&
            Date.now() - lastActiveMs >= INACTIVITY_MS;
    }

    /**
     * 작업 버튼을 지정한 회원과 명령에 연결합니다.
     * 사용자 문자열을 HTML 속성 문자열로 결합하지 않습니다.
     * 실제 작업 수행 전 확인 대화상자를 사용합니다.
     *
     * @param {HTMLElement} parent 버튼을 담을 셀
     * @param {string} label 버튼 표시명
     * @param {string} action 명령 코드
     * @param {object} member 대상 구성원
     * @param {boolean} disabled 비활성화 여부
     */
    function appendActionButton(
        parent, label, action, member, disabled = false
    ) {
        const button = document.createElement('button');
        button.type = 'button';

        button.className = action === 'REMOVE'
            ? 'btn btn-outline-danger btn-sm'
            : 'btn btn-outline-success btn-sm';

        button.textContent = label;
        button.dataset.action = action;
        button.dataset.userId = String(member.userId);
        button.disabled = disabled;

        if (disabled) {
            button.title = '마지막 활동 이후 72시간 10분이 지나야 합니다.';
        }

        parent.append(button);
    }

    /**
     * 현재 그룹에서 PRIMARY 권한이 있는지 확인합니다.
     * 최신 그룹 상세 조회 결과를 기준으로 판단합니다.
     * 실제 API 실행 권한은 서버가 최종 검증합니다.
     *
     * @returns {boolean} PRIMARY 여부
     */
    function isPrimary() {
        return groupData?.myMembership?.role === 'PRIMARY';
    }

    /**
     * 한 번 표시한 초대 URL을 페이지 메모리와 DOM에서 지웁니다.
     * 새로운 발급 요청이나 명시적인 정보 재조회 전에 호출합니다.
     * 브라우저 저장소에는 URL을 기록하지 않습니다.
     */
    function clearOneTimeInvitationUrl() {
        oneTimeInvitationUrl = '';
        invitationUrlInput.value = '';
        invitationCopyStatus.textContent = '';
        invitationResult.classList.add('d-none');
    }

    /**
     * 그룹 관리자 탭을 전환합니다.
     * 초대 링크는 PRIMARY만, 크리처 선택은 PRIMARY·DEPUTY가 사용합니다.
     * 각 기능의 첫 조회는 해당 탭을 열 때만 실행합니다.
     *
     * @param {'members'|'invitation'|'creature'} tab 이동할 탭
     */
    function showAdminTab(tab) {
        if (
            !mounted ||
            busy ||
            rotatingInvitation ||
            creatureSaving ||
            dialog.open ||
            invitationDialog.open ||
            !groupData
        ) {
            return;
        }

        if (!['members', 'invitation', 'creature'].includes(tab)) {
            return;
        }

        if (tab === 'invitation' && !isPrimary()) return;
        if (tab === 'creature' && !canManageCreature()) return;

        activeTab = tab;

        // 현재 선택한 탭과 주소창의 Query Parameter를 동기화합니다.
        // 크리처 탭은 직접 접근 가능한 URL을 유지합니다.
        // replaceState는 Router를 다시 실행하지 않습니다.
        const url = new URL(window.location.href);

        if (tab === 'creature') {
            url.searchParams.set('tab', 'creature');
        } else {
            url.searchParams.delete('tab');
        }

        window.history.replaceState(
            window.history.state,
            '',
            `${url.pathname}${url.search}${url.hash}`
        );

        membersPanel.classList.toggle('d-none', tab !== 'members');
        invitationPanel.classList.toggle('d-none', tab !== 'invitation');
        creaturePanel.classList.toggle('d-none', tab !== 'creature');

        for (const button of [
            membersTab,
            invitationTab,
            creatureTab
        ]) {
            const selected = button.dataset.adminTab === tab;

            button.classList.toggle('active', selected);
            button.setAttribute('aria-pressed', String(selected));
        }

        if (tab === 'invitation' &&
            !invitationLoaded &&
            !invitationLoading) {
            void loadInvitationInfo();
        }

        if (tab === 'creature') {
            if (!creatureHomeData && !creatureLoading) {
                void loadCreatureSelection();
            } else {
                tickCreatureDeadline();
            }
        }
    }

    /**
     * 서버 초대 발급 정보를 화면에 표시합니다.
     * 조회 API에는 초대 URL이 없으므로 메타데이터만 출력합니다.
     * 서버에서 전달된 값은 HTML로 해석하지 않습니다.
     *
     * @param {object} data 초대 링크 발급 정보
     */
    function renderInvitationInfo(data) {
        invitationIssuedAt.textContent = formatDateTime(data.issuedAt);
        invitationIssuedBy.textContent = String(data.issuedByUserId);
        invitationUses.textContent =
            data.unlimitedUses ? '무제한' : '사용 횟수 제한 있음';
        invitationExpires.textContent = data.expiresAt
            ? formatDateTime(data.expiresAt)
            : '무기한';

        invitationRotateOpen.disabled = false;
    }

    /**
     * 활성 초대 링크의 발급 정보를 서버에서 조회합니다.
     * 조회에 실패하면 재발급을 잠그고 재조회를 요청하도록 안내합니다.
     * 수동 재조회 시 화면에 남은 새 URL도 제거합니다.
     *
     * @returns {Promise<void>} 화면 갱신 작업
     */
    async function loadInvitationInfo() {
        if (!mounted || !isPrimary() || invitationLoading ||
            rotatingInvitation || busy) return;

        const version = ++invitationLoadVersion;
        invitationLoading = true;
        invitationLoaded = false;
        invitationData = null;

        // 재조회 시 기존의 일회성 URL을 다시 제공하지 않습니다.
        clearOneTimeInvitationUrl();
        invitationError.classList.add('d-none');
        invitationRotateOpen.disabled = true;
        invitationRefresh.disabled = true;

        invitationIssuedAt.textContent = '조회 중';
        invitationIssuedBy.textContent = '-';
        invitationUses.textContent = '-';
        invitationExpires.textContent = '-';

        try {
            const data = await getInvitationManagement();

            if (!mounted || version !== invitationLoadVersion) return;

            if (!data?.groupId || !data?.issuedAt ||
                !data?.issuedByUserId) {
                throw new Error('초대 링크 관리 응답 형식이 올바르지 않습니다.');
            }

            invitationData = data;
            invitationLoaded = true;
            renderInvitationInfo(data);

        } catch (error) {
            if (!mounted || version !== invitationLoadVersion) return;

            // 권한이 이전되었다면 관리 화면에서 나갑니다.
            if (error instanceof ApiError &&
                error.code === 'ROLE_FORBIDDEN') {
                navigate('/group');
                return;
            }

            invitationError.textContent = formatActionError(error);
            invitationError.classList.remove('d-none');

        } finally {
            if (version === invitationLoadVersion) {
                invitationLoading = false;

                if (mounted) {
                    invitationRefresh.disabled = false;
                }
            }
        }
    }

    /**
     * 재발급을 확인하는 Modal을 표시합니다.
     * 기존 링크 즉시 무효화와 새 링크 일회성 표시를 경고합니다.
     * 확인 전에는 실제 재발급 API를 호출하지 않습니다.
     */
    function openInvitationDialog() {
        if (!mounted || !isPrimary() || !invitationLoaded ||
            busy || invitationLoading || rotatingInvitation) return;

        invitationDialogError.classList.add('d-none');
        invitationConfirm.disabled = false;
        invitationCancel.disabled = false;

        invitationDialog.showModal();
    }

    /**
     * 최종 확인 후 초대 링크를 새로 발급합니다.
     * 중복 요청을 막고 발급 성공 응답의 URL만 메모리에 저장합니다.
     * 요청 결과가 불확실하면 기존 링크가 유효하다고 안내하지 않습니다.
     */
    async function confirmInvitationRotation() {
        if (!mounted || !isPrimary() || !invitationLoaded ||
            rotatingInvitation || busy) return;

        rotatingInvitation = true;
        invitationConfirm.disabled = true;
        invitationCancel.disabled = true;
        invitationRotateOpen.disabled = true;
        invitationRefresh.disabled = true;
        invitationDialogError.classList.add('d-none');

        try {
            const result = await rotateInvitation();

            if (!mounted) return;

            if (!result?.invitationUrl || !result?.issuedAt ||
                !result?.groupId || !result?.issuedByUserId) {
                throw new Error(
                    '재발급 응답에 새 초대 URL이 없습니다. 발급 상태를 다시 확인해 주세요.'
                );
            }

            // 새로운 응답이 이전에 표시한 URL을 완전히 대체합니다.
            clearOneTimeInvitationUrl();

            invitationData = result;
            invitationLoaded = true;
            renderInvitationInfo(result);

            oneTimeInvitationUrl = result.invitationUrl;
            invitationUrlInput.value = oneTimeInvitationUrl;
            invitationResult.classList.remove('d-none');

            invitationDialog.close();

        } catch (error) {
            if (!mounted) return;

            // 응답 손실 시 서버에서 발급이 완료됐을 가능성도 있습니다.
            invitationLoaded = false;
            invitationData = null;
            clearOneTimeInvitationUrl();

            invitationDialogError.textContent =
                '재발급 결과를 확정할 수 없습니다. ' +
                '기존 링크의 유효 여부도 보장할 수 없습니다. ' +
                '확인 창을 닫고 발급 정보를 다시 조회해 주세요. ' +
                formatActionError(error);

            invitationDialogError.classList.remove('d-none');

        } finally {
            rotatingInvitation = false;

            if (mounted) {
                invitationCancel.disabled = false;
                invitationRefresh.disabled = false;

                // 결과가 불확실하면 재조회 전까지 재발급을 잠급니다.
                invitationConfirm.disabled = !invitationLoaded;
                invitationRotateOpen.disabled = !invitationLoaded;
            }
        }
    }

    /**
     * 새 초대 URL을 클립보드에 복사합니다.
     * Clipboard API가 차단되면 입력값을 선택해 수동 복사를 안내합니다.
     * URL은 콘솔이나 브라우저 저장소에 출력하지 않습니다.
     */
    async function copyInvitationUrl() {
        if (!mounted || !oneTimeInvitationUrl) return;

        try {
            await navigator.clipboard.writeText(oneTimeInvitationUrl);

            if (!mounted) return;
            invitationCopyStatus.textContent = '초대 링크를 복사했습니다.';

        } catch (error) {
            if (!mounted) return;

            // HTTP 또는 브라우저 권한 문제로 복사 실패 시 직접 복사하게 합니다.
            invitationUrlInput.focus();
            invitationUrlInput.select();

            invitationCopyStatus.textContent =
                '자동 복사에 실패했습니다. 선택된 URL을 직접 복사해 주세요.';
        }
    }

    /**
     * 재발급 요청 중에는 ESC로 확인 창을 닫지 못하게 합니다.
     * 요청을 시작하지 않았으면 정상적으로 취소할 수 있습니다.
     * 요청 완료 여부를 오해하지 않도록 합니다.
     *
     * @param {Event} event dialog cancel 이벤트
     */
    function onInvitationDialogCancel(event) {
        if (rotatingInvitation) {
            event.preventDefault();
        }
    }

    /**
     * 현재 사용자가 그룹 관리자 역할을 가지고 있는지 확인합니다.
     * PRIMARY와 DEPUTY는 모두 크리처 선택이 가능합니다.
     * 최종 작업 인가는 백엔드가 다시 검증합니다.
     *
     * @returns {boolean} 그룹 관리자 여부
     */
    function canManageCreature() {
        const role = groupData?.myMembership?.role;

        return role === 'PRIMARY' || role === 'DEPUTY';
    }

    /**
     * 서버의 시즌·크리처·마감 시각으로 종 선택 가능 여부를 판정합니다.
     * 현재 시즌이 ACTIVE이고 크리처가 EGG 상태여야 합니다.
     * 브라우저 판정은 UI용이며 저장 시 서버가 최종 검증합니다.
     *
     * @returns {boolean} 현재 선택 UI를 사용할 수 있는지 여부
     */
    function isCreatureSelectionOpen() {
        if (!canManageCreature() || !creatureHomeData) return false;

        const season = creatureHomeData.season;
        const creature = creatureHomeData.creature;

        if (!season || !creature) return false;

        const startAt = Date.parse(season.startAt);
        const endAt = Date.parse(season.endAt);
        const deadlineAt = Date.parse(creature.selectionDeadlineAt);
        const now = Date.now();

        // 잘못된 시각이 있다면 임의로 선택 기능을 허용하지 않습니다.
        if (![startAt, endAt, deadlineAt].every(Number.isFinite)) {
            return false;
        }

        return (
            season.status === 'ACTIVE' &&
            creature.growthStage === 'EGG' &&
            now >= startAt &&
            now < endAt &&
            now < deadlineAt
        );
    }

    /**
     * 남은 시간을 일·시간·분·초로 표시합니다.
     * 음수는 항상 0초로 처리합니다.
     * 서버 기한에 도달했는지 판단하는 로직과 별도로 사용합니다.
     *
     * @param {number} milliseconds 남은 밀리초
     * @returns {string} 화면 표시 문자열
     */
    function formatCreatureRemaining(milliseconds) {
        let seconds = Math.max(0, Math.ceil(milliseconds / 1000));

        const days = Math.floor(seconds / 86400);
        seconds %= 86400;

        const hours = Math.floor(seconds / 3600);
        seconds %= 3600;

        const minutes = Math.floor(seconds / 60);
        seconds %= 60;

        return `${days}일 ${hours}시간 ${minutes}분 ${seconds}초`;
    }

    /**
     * 서버 Snapshot에 포함된 선택 종의 이름을 반환합니다.
     * 활성 목록에서 찾지 못할 때는 종 ID를 표시합니다.
     * 이미 확정된 종이 비활성 목록에서 빠져도 표시를 유지합니다.
     *
     * @returns {string} 현재 선택 상태
     */
    function getCurrentCreatureName() {
        const speciesId =
            creatureHomeData?.creature?.creatureSpeciesId;

        if (speciesId === null || speciesId === undefined) {
            return '아직 선택하지 않았습니다.';
        }

        const species = creatureSpeciesList.find(
            item => String(item.creatureSpeciesId) === String(speciesId)
        );

        return species?.name ?? `크리처 종 ID ${speciesId}`;
    }

    /**
     * 선택 마감 상태와 저장 버튼을 갱신합니다.
     * 이 함수는 매초 호출되지만 종 목록 자체는 다시 만들지 않습니다.
     * 라디오 버튼 선택과 키보드 포커스가 유지되도록 합니다.
     */
    function renderCreatureStatus() {
        const creature = creatureHomeData?.creature;
        const season = creatureHomeData?.season;

        const editable =
            isCreatureSelectionOpen() &&
            !creatureLoading &&
            !creatureSaving &&
            !busy &&
            !rotatingInvitation;

        creatureRefresh.disabled = creatureLoading || creatureSaving;

        if (!creature || !season) {
            creatureDeadline.textContent = '-';
            creatureRemaining.textContent = '-';
            creatureCurrent.textContent = '확인할 수 없음';

            creatureStatus.textContent = creatureLoading
                ? '크리처 정보를 조회 중입니다.'
                : '크리처 정보를 조회해 주세요.';

        } else {
            const deadlineAt = Date.parse(creature.selectionDeadlineAt);

            creatureDeadline.textContent =
                formatDateTime(creature.selectionDeadlineAt);
            creatureCurrent.textContent = getCurrentCreatureName();

            if (Number.isFinite(deadlineAt)) {
                const remaining = deadlineAt - Date.now();

                creatureRemaining.textContent = remaining > 0
                    ? formatCreatureRemaining(remaining)
                    : '선택 마감';
            } else {
                creatureRemaining.textContent = '기한 확인 불가';
            }

            const hasSelection =
                creature.creatureSpeciesId !== null &&
                creature.creatureSpeciesId !== undefined;

            if (creature.growthStage !== 'EGG') {
                creatureStatus.textContent =
                    '부화가 완료되어 종 선택을 변경할 수 없습니다.';

            } else if (
                !Number.isFinite(deadlineAt) ||
                Date.now() >= deadlineAt
            ) {
                creatureStatus.textContent =
                    '선택이 마감되었습니다. ' +
                    '아직 알 상태라면 서버의 부화 처리를 기다리는 중입니다.';

            } else if (!isCreatureSelectionOpen()) {
                creatureStatus.textContent =
                    '현재 시즌에서는 종 선택을 사용할 수 없습니다.';

            } else {
                creatureStatus.textContent = hasSelection
                    ? '선택이 저장되어 있습니다. 마감 전까지 변경할 수 있습니다.'
                    : '아직 선택하지 않았습니다. 마감 시 무작위 종이 결정됩니다.';
            }
        }

        const currentId =
            creature?.creatureSpeciesId == null
                ? ''
                : String(creature.creatureSpeciesId);

        const selectedIsValid = creatureSpeciesList.some(
            item => String(item.creatureSpeciesId) === creatureSelectedId
        );

        // 변경 사항이 있고 서버상 선택 가능할 때만 저장합니다.
        creatureSave.disabled = !(
            editable &&
            selectedIsValid &&
            creatureSelectedId !== currentId
        );

        creatureSave.textContent = currentId
            ? '선택 변경 저장'
            : '크리처 종 선택 저장';

        // 마감 또는 저장 중에는 기존 라디오 버튼을 모두 잠급니다.
        creatureOptions.querySelectorAll('input[type="radio"]')
            .forEach(input => {
                if (input instanceof HTMLInputElement) {
                    input.disabled = !editable;
                }
            });
    }

    /**
     * 활성 크리처 종을 라디오 카드 목록으로 표시합니다.
     * 닉네임이나 종 이름은 textContent로 처리합니다.
     * 이미지가 없는 종은 기존 공통 알 이미지를 사용합니다.
     */
    function renderCreatureOptions() {
        creatureOptions.replaceChildren();

        if (creatureSpeciesList.length === 0) {
            const message = document.createElement('p');
            message.className = 'text-app-muted mb-0';
            message.textContent = '선택 가능한 크리처 종이 없습니다.';
            creatureOptions.append(message);
            renderCreatureStatus();
            return;
        }

        for (const species of creatureSpeciesList) {
            const col = document.createElement('div');
            col.className = 'col-12 col-md-6 col-xl-4';

            const label = document.createElement('label');
            label.className =
                'border border-app rounded-3 p-3 d-flex ' +
                'align-items-center gap-3 h-100';

            const radio = document.createElement('input');
            radio.type = 'radio';
            radio.name = 'group-admin-creature-species';
            radio.value = String(species.creatureSpeciesId);
            radio.checked = radio.value === creatureSelectedId;
            radio.className = 'form-check-input flex-shrink-0 m-0';

            // Vite가 빌드 시 수집한 로컬 이미지에서만 선택합니다.
            const assetPath =
                `../../assets/creature/${species.assetKey}/lv1.png`;

            const image = document.createElement('img');
            image.src = CREATURE_EGG_IMAGES[assetPath] ?? creatureLv0;
            image.alt = '';
            image.loading = 'lazy';
            image.style.width = '76px';
            image.style.height = '76px';
            image.style.objectFit = 'contain';

            const textArea = document.createElement('div');
            const name = document.createElement('strong');
            name.textContent = species.name;

            textArea.append(name);

            const currentId =
                creatureHomeData?.creature?.creatureSpeciesId;

            if (
                currentId !== null &&
                currentId !== undefined &&
                String(currentId) === radio.value
            ) {
                const currentLabel = document.createElement('div');
                currentLabel.className = 'small text-app-muted mt-1';
                currentLabel.textContent = '현재 선택된 종';
                textArea.append(currentLabel);
            }

            label.append(radio, image, textArea);
            col.append(label);
            creatureOptions.append(col);
        }

        renderCreatureStatus();
    }

    /**
     * 현재 그룹의 크리처 Snapshot과 활성 종 목록을 조회합니다.
     * 두 API를 병렬 호출하고 성공한 최신 결과만 화면에 적용합니다.
     * 이전 페이지나 이전 요청의 응답은 무시합니다.
     *
     * @param {{afterSave?: boolean}} options 저장 후 재조회 여부
     * @returns {Promise<boolean>} 최신 정보 조회 성공 여부
     */
    async function loadCreatureSelection({afterSave = false} = {}) {
        if (
            !mounted ||
            !canManageCreature() ||
            creatureLoading ||
            busy ||
            rotatingInvitation ||
            (creatureSaving && !afterSave)
        ) {
            return false;
        }

        const version = ++creatureLoadVersion;
        creatureLoading = true;

        // 이전 정보로 다시 저장하지 못하도록 상태를 비웁니다.
        creatureHomeData = null;
        creatureSpeciesList = [];
        creatureSelectedId = '';

        creatureOptions.replaceChildren();
        creatureError.classList.add('d-none');

        if (!afterSave) {
            creatureFeedback.classList.add('d-none');
        }

        renderCreatureStatus();

        try {
            // 기존 크리처 홈 API와 종 목록 API를 재사용합니다.
            const [home, species] = await Promise.all([
                getCreatureHome(),
                getActiveCreatureSpecies()
            ]);

            if (!mounted || version !== creatureLoadVersion) {
                return false;
            }

            if (
                !home?.creature ||
                !home?.season ||
                !Array.isArray(species)
            ) {
                throw new Error(
                    '크리처 선택 화면의 응답 형식이 올바르지 않습니다.'
                );
            }

            creatureHomeData = home;
            creatureSpeciesList = species;

            const savedId = home.creature.creatureSpeciesId;

            // 현재 서버에 저장된 선택을 라디오 버튼의 초기값으로 사용합니다.
            creatureSelectedId =
                savedId === null || savedId === undefined
                    ? ''
                    : String(savedId);

            // 최초 조회 시 이미 마감된 경우 중복 자동 조회하지 않습니다.
            const deadlineAt = Date.parse(
                home.creature.selectionDeadlineAt
            );

            creatureDeadlineChecked =
                Number.isFinite(deadlineAt) &&
                Date.now() >= deadlineAt;

            renderCreatureOptions();
            return true;

        } catch (error) {
            if (!mounted || version !== creatureLoadVersion) {
                return false;
            }

            if (
                error instanceof ApiError &&
                error.code === 'ROLE_FORBIDDEN'
            ) {
                navigate('/group');
                return false;
            }

            creatureError.textContent = formatActionError(error);
            creatureError.classList.remove('d-none');

            return false;

        } finally {
            if (mounted && version === creatureLoadVersion) {
                creatureLoading = false;
                renderCreatureStatus();
            }
        }
    }

    /**
     * 마감 시각을 기준으로 현재 선택 가능 상태를 갱신합니다.
     * 기한에 도달하면 서버 Snapshot을 한 번 다시 조회합니다.
     * 브라우저 시계만으로 부화 완료를 확정하지 않습니다.
     */
    function tickCreatureDeadline() {
        if (!mounted || activeTab !== 'creature') return;

        renderCreatureStatus();

        const deadlineAt = Date.parse(
            creatureHomeData?.creature?.selectionDeadlineAt
        );

        if (
            Number.isFinite(deadlineAt) &&
            Date.now() >= deadlineAt &&
            !creatureDeadlineChecked &&
            !creatureLoading &&
            !creatureSaving
        ) {
            // 시간이 지난 후에는 서버의 실제 상태를 확인합니다.
            creatureDeadlineChecked = true;
            void loadCreatureSelection();
        }
    }

    /**
     * 사용자가 크리처 종 라디오 버튼을 변경했을 때 실행합니다.
     * 아직 서버에는 저장하지 않고 선택된 ID만 갱신합니다.
     * 저장 가능한 경우에만 버튼을 활성화합니다.
     *
     * @param {Event} event 변경 이벤트
     */
    function onCreatureOptionChange(event) {
        const input = event.target;

        if (!(input instanceof HTMLInputElement)) return;

        if (
            input.name !== 'group-admin-creature-species' ||
            !input.checked ||
            !isCreatureSelectionOpen() ||
            creatureLoading ||
            creatureSaving
        ) {
            return;
        }

        creatureSelectedId = input.value;
        renderCreatureStatus();
    }

    /**
     * 사용자가 선택한 크리처 종을 서버에 저장합니다.
     * 중복 제출을 막고 서버에서 시즌과 선택 가능 기간을 다시 검증합니다.
     * 저장 성공 후 최신 Snapshot을 재조회해 화면을 동기화합니다.
     */
    async function onCreatureSave() {
        if (
            !mounted ||
            creatureSaving ||
            creatureLoading ||
            busy ||
            rotatingInvitation ||
            !isCreatureSelectionOpen()
        ) {
            return;
        }

        const speciesId = Number(creatureSelectedId);
        const currentId =
            creatureHomeData?.creature?.creatureSpeciesId;

        if (
            !Number.isSafeInteger(speciesId) ||
            speciesId <= 0 ||
            !creatureSpeciesList.some(
                item => Number(item.creatureSpeciesId) === speciesId
            ) ||
            String(currentId ?? '') === creatureSelectedId
        ) {
            return;
        }

        creatureSaving = true;
        creatureFeedback.classList.add('d-none');
        creatureError.classList.add('d-none');
        renderCreatureStatus();

        try {
            // 서버에서 부화 기한·권한·활성 종을 최종 확인합니다.
            await selectCreatureSpecies(speciesId);

            if (!mounted) return;

            // 요청 성공 직후 서버의 실제 선택 상태를 다시 조회합니다.
            const refreshed = await loadCreatureSelection({
                afterSave: true
            });

            if (!mounted) return;

            creatureFeedback.className = refreshed
                ? 'alert alert-success mt-3'
                : 'alert alert-warning mt-3';

            if (refreshed) {
                const latestId =
                    creatureHomeData?.creature?.creatureSpeciesId;

                creatureFeedback.textContent =
                    String(latestId) === String(speciesId)
                        ? '크리처 종 선택이 저장되었습니다.'
                        : '저장 요청은 성공했지만 다른 관리자의 변경이 ' +
                        '최신 상태에 반영되었을 수 있습니다.';

            } else {
                creatureFeedback.textContent =
                    '선택 요청은 성공했지만 최신 상태를 확인하지 못했습니다. ' +
                    '최신 정보 조회 버튼으로 다시 확인해 주세요.';
            }

            creatureFeedback.classList.remove('d-none');

        } catch (error) {
            if (!mounted) return;

            if (
                error instanceof ApiError &&
                error.code === 'ROLE_FORBIDDEN'
            ) {
                navigate('/group');
                return;
            }

            const hasStateChanged =
                error instanceof ApiError &&
                (
                    error.code === 'CREATURE_SELECTION_CLOSED' ||
                    error.code === 'CONCURRENT_MODIFICATION'
                );

            // 마감 또는 동시성 오류 시 최신 서버 상태를 다시 확인합니다.
            if (hasStateChanged) {
                await loadCreatureSelection({afterSave: true});
            }

            if (!mounted) return;

            creatureError.textContent =
                error instanceof ApiError &&
                error.code === 'CREATURE_SELECTION_CLOSED'
                    ? '크리처 종 선택 시간이 종료되었습니다. ' +
                    '최신 상태를 확인해 주세요.'
                    : formatActionError(error);

            creatureError.classList.remove('d-none');

        } finally {
            creatureSaving = false;

            if (mounted) {
                renderCreatureStatus();
            }
        }
    }

    /**
     * 조회된 구성원과 현재 권한을 기준으로 관리 버튼을 구성합니다.
     * 활동 기록이 없는 대상에게는 강퇴 버튼을 활성화하지 않습니다.
     * PRIMARY만 역할 변경과 관리자 이전 대상을 선택할 수 있습니다.
     */
    function renderMembers() {
        membersBody.replaceChildren();

        const myRole = groupData.myMembership.role;
        transferPanel.classList.toggle('d-none', myRole !== 'PRIMARY');

        // 종 선택은 주·부 관리자 모두 사용할 수 있습니다.
        const canSelectCreature =
            myRole === 'PRIMARY' || myRole === 'DEPUTY';

        creatureTab.classList.toggle('d-none', !canSelectCreature);

        // PRIMARY만 초대 링크 관리 탭을 사용할 수 있습니다.
        invitationTab.classList.toggle('d-none', myRole !== 'PRIMARY');

        // 화면이 갱신되는 사이 자신의 권한이 변경되었을 수 있습니다.
        if (myRole !== 'PRIMARY' && activeTab === 'invitation') {
            activeTab = 'members';

            membersPanel.classList.remove('d-none');
            invitationPanel.classList.add('d-none');

            membersTab.classList.add('active');
            invitationTab.classList.remove('active');

            membersTab.setAttribute('aria-pressed', 'true');
            invitationTab.setAttribute('aria-pressed', 'false');

            clearOneTimeInvitationUrl();
            invitationData = null;
            invitationLoaded = false;
        }

        transferSelect.replaceChildren();

        const option = document.createElement('option');
        option.value = '';
        option.textContent = '이전할 회원 선택';
        transferSelect.append(option);

        for (const member of members) {
            const row = document.createElement('tr');
            const name = document.createElement('td');
            const role = document.createElement('td');
            const lastActive = document.createElement('td');
            const actions = document.createElement('td');

            actions.className = 'd-flex flex-wrap gap-2';

            name.textContent =
                `${member.nickname}${isMe(member) ? ' (나)' : ''}`;
            role.textContent = ROLE_LABELS[member.role] ?? member.role;
            lastActive.textContent = formatDateTime(member.lastActiveAt);

            if (!isMe(member)) {
                // PRIMARY만 부 관리자 역할을 부여하거나 회수합니다.
                if (myRole === 'PRIMARY' && member.role === 'MEMBER') {
                    appendActionButton(
                        actions, '부 관리자 지정', 'PROMOTE', member
                    );
                } else if (
                    myRole === 'PRIMARY' && member.role === 'DEPUTY'
                ) {
                    appendActionButton(
                        actions, '권한 회수', 'DEMOTE', member
                    );
                }

                // 역할별 강퇴 대상 범위를 구분합니다.
                const allowedTarget = myRole === 'PRIMARY'
                    ? ['MEMBER', 'DEPUTY'].includes(member.role)
                    : myRole === 'DEPUTY' && member.role === 'MEMBER';

                if (allowedTarget) {
                    appendActionButton(
                        actions, '강퇴', 'REMOVE',
                        member, !canRemove(member)
                    );

                    if (!canRemove(member)) {
                        const reason = document.createElement('small');
                        reason.className = 'text-app-muted align-self-center';

                        reason.textContent = !member.lastActiveAt
                            ? '활동 기록 없음: 강퇴 불가'
                            : '비활동 기준 미충족';

                        actions.append(reason);
                    }
                }

                // 주 관리자 이전 대상은 다른 활성 회원만 선택합니다.
                if (
                    myRole === 'PRIMARY' &&
                    ['MEMBER', 'DEPUTY'].includes(member.role)
                ) {
                    const choice = document.createElement('option');
                    choice.value = String(member.userId);
                    choice.textContent =
                        `${member.nickname} (${ROLE_LABELS[member.role]})`;

                    transferSelect.append(choice);
                }
            }

            if (!actions.hasChildNodes()) {
                actions.textContent = '-';
            }

            row.append(name, role, lastActive, actions);
            membersBody.append(row);
        }

        if (members.length === 0) {
            const row = document.createElement('tr');
            const cell = document.createElement('td');

            cell.colSpan = 4;
            cell.textContent = '표시할 구성원이 없습니다.';
            row.append(cell);
            membersBody.append(row);
        }

        transferOpen.disabled = true;
    }

    /**
     * 그룹 정보와 구성원 정보를 서버에서 병렬로 다시 조회합니다.
     * 이전 응답은 무시하고 최신 조회 결과만 화면에 반영합니다.
     * 실패하거나 역할이 해제되면 오래된 작업 버튼을 사용하지 않습니다.
     *
     * @returns {Promise<boolean>} 정상 갱신 여부
     */
    async function loadGroup() {
        if (!mounted) return false;

        const version = ++loadVersion;

        // 이전 데이터와 작업 버튼을 먼저 제거합니다.
        groupData = null;
        members = [];
        summary.textContent = '그룹 정보를 확인하고 있습니다.';
        transferPanel.classList.add('d-none');
        refreshButton.disabled = true;
        errorBox.classList.add('d-none');
        membersBody.replaceChildren();

        try {
            const [group, result] = await Promise.all([
                getMyGroup(),
                listMyGroupMembers({page: 0, size: 50})
            ]);

            if (!mounted || version !== loadVersion) return false;

            if (!group?.group || !Array.isArray(result?.data)) {
                throw new Error('그룹 구성원 응답 형식이 올바르지 않습니다.');
            }

            // 그룹 관리 권한은 최신 상세 API를 기준으로 확인합니다.
            const role = group.myMembership?.role;

            if (role !== 'PRIMARY' && role !== 'DEPUTY') {
                navigate('/group');
                return false;
            }

            groupData = group;
            members = result.data;

            summary.textContent =
                `${group.group.name} · ${ROLE_LABELS[role]} · ` +
                `구성원 ${group.group.currentMemberCount}명 · ` +
                `주 관리자 ${group.primaryAdmin?.nickname ?? '-'}`;

            renderMembers();

            // 그룹 조회에 성공한 뒤 초기 탭을 한 번만 선택합니다.
            // 서버에서 확인한 관리자 역할이 반영된 상태이므로 안전합니다.
            if (!initialTabHandled) {
                initialTabHandled = true;

                if (requestedInitialTab === 'creature') {
                    showAdminTab('creature');
                }
            }

            return true;

        } catch (error) {
            if (!mounted || version !== loadVersion) return false;

            errorBox.textContent = formatActionError(error);
            errorBox.classList.remove('d-none');
            membersBody.replaceChildren();

            return false;

        } finally {
            if (mounted && version === loadVersion) {
                refreshButton.disabled = false;
            }
        }
    }

    /**
     * API 오류의 업무 코드를 사용자에게 이해할 수 있도록 변환합니다.
     * 서버의 권한·동시성·강퇴 제한 판단 결과를 우선합니다.
     * 추적 ID가 있으면 고객 지원을 위해 함께 표시합니다.
     *
     * @param {unknown} error 요청 오류
     * @returns {string} 표시 문구
     */
    function formatActionError(error) {
        if (!(error instanceof ApiError)) {
            return error instanceof Error
                ? error.message
                : '요청에 실패했습니다.';
        }

        const messages = {
            ROLE_FORBIDDEN:
                '현재 작업 권한이 없습니다. 그룹 정보를 다시 확인해 주세요.',
            RESOURCE_NOT_FOUND:
                '대상 회원 또는 그룹이 변경되었습니다. 다시 조회해 주세요.',
            INACTIVITY_REQUIREMENT_NOT_MET:
                '아직 강퇴 가능한 미접속 시간이 지나지 않았습니다.',
            CONCURRENT_MODIFICATION:
                '다른 요청으로 상태가 변경되었습니다. 다시 조회해 주세요.',
            INVALID_FIELD_VALUE:
                '입력값을 확인해 주세요.',
            CSRF_INVALID:
                '보안 토큰을 확인하지 못했습니다.',
            SESSION_EXPIRED:
                '세션이 만료되었습니다. 다시 로그인해 주세요.'
        };

        return `${messages[error.code] ?? error.message} [${error.code}` +
            `${error.traceId ? ` / ${error.traceId}` : ''}]`;
    }

    /**
     * 회원 관리 작업에 필요한 경고와 확인 대화상자를 표시합니다.
     * 대상 회원은 최신 조회 목록에서 다시 찾습니다.
     * 실제 요청은 별도의 확인 버튼을 클릭했을 때만 전송합니다.
     *
     * @param {string} type 명령 종류
     * @param {object} member 대상 회원
     */
    function openAction(type, member) {
        if (!mounted || busy || dialog.open || !groupData || !member) {
            return;
        }

        if (isMe(member)) return;

        const role = groupData.myMembership?.role;

        // 권한 없는 명령은 Modal도 열지 않습니다.
        if (
            type === 'PROMOTE' &&
            (role !== 'PRIMARY' || member.role !== 'MEMBER')
        ) return;

        if (
            type === 'DEMOTE' &&
            (role !== 'PRIMARY' || member.role !== 'DEPUTY')
        ) return;

        if (type === 'TRANSFER' && role !== 'PRIMARY') return;
        if (type === 'REMOVE' && !canRemove(member)) return;

        pendingAction = {type, member};
        reasonInput.value = '';
        dialogError.classList.add('d-none');
        reasonWrap.classList.toggle('d-none', type !== 'REMOVE');

        if (type === 'TRANSFER') {
            dialogTitle.textContent =
                '주 관리자 권한을 이전하시겠습니까?';

            dialogDescription.textContent =
                `${member.nickname}님이 주 관리자가 됩니다. ` +
                '이전 후 본인은 일반 회원이 되며 그룹 관리 권한을 잃습니다.';

        } else if (type === 'REMOVE') {
            dialogTitle.textContent =
                `${member.nickname}님을 강퇴하시겠습니까?`;

            dialogDescription.textContent =
                '비활동 조건을 서버에서 재검증합니다. ' +
                '강퇴된 회원은 24시간 동안 새 그룹에 가입하거나 생성할 수 없습니다.';

        } else {
            const promoting = type === 'PROMOTE';

            dialogTitle.textContent = promoting
                ? '부 관리자 권한 부여'
                : '부 관리자 권한 회수';

            dialogDescription.textContent =
                `${member.nickname}님의 역할을 ` +
                `${promoting ? '부 관리자' : '일반 회원'}로 변경합니다.`;
        }

        confirmButton.disabled = false;
        cancelButton.disabled = false;
        dialog.showModal();

        if (type === 'REMOVE') reasonInput.focus();
    }

    /**
     * 대화상자에서 최종 승인한 관리자 명령을 전송합니다.
     * 성공하면 최신 그룹과 구성원을 다시 조회합니다.
     * 주 관리자 이전 성공 후에는 그룹 화면으로 이동합니다.
     */
    async function confirmAction() {
        if (!pendingAction || busy || !mounted) return;

        const {type, member} = pendingAction;
        const reason = reasonInput.value.trim();

        // 강퇴 사유는 서버 전송 전에 입력값을 검증합니다.
        if (
            type === 'REMOVE' &&
            (reason.length < 1 || reason.length > 500)
        ) {
            dialogError.textContent =
                '강퇴 사유는 공백을 제외하고 1~500자여야 합니다.';
            dialogError.classList.remove('d-none');
            return;
        }

        busy = true;
        confirmButton.disabled = true;
        cancelButton.disabled = true;
        reasonInput.disabled = true;
        dialogError.classList.add('d-none');

        try {
            if (type === 'PROMOTE' || type === 'DEMOTE') {
                await changeMemberRole(
                    member.userId,
                    type === 'PROMOTE' ? 'DEPUTY' : 'MEMBER'
                );

            } else if (type === 'REMOVE') {
                await removeGroupMember(member.userId, reason);

            } else if (type === 'TRANSFER') {
                await transferPrimaryAdmin(member.userId);
            }

            if (!mounted) return;

            dialog.close();
            pendingAction = null;

            if (type === 'TRANSFER') {
                // Router가 최신 멤버십을 다시 조회하도록 이동합니다.
                navigate('/group');
                return;
            }

            const message = type === 'REMOVE'
                ? `${member.nickname}님을 강퇴했습니다. ` +
                '해당 회원에게 24시간 가입 제한이 적용됩니다.'
                : `${member.nickname}님의 역할 변경이 완료되었습니다.`;

            // 성공 결과만 표시하지 않고 서버에서 다시 조회합니다.
            await loadGroup();

            if (!mounted) return;

            feedback.textContent = message;
            feedback.classList.remove('d-none');

        } catch (error) {
            if (!mounted) return;

            // 실패 시 Modal을 유지하여 오류와 재시도를 제공합니다.
            dialogError.textContent = formatActionError(error);
            dialogError.classList.remove('d-none');

        } finally {
            busy = false;

            if (mounted) {
                reasonInput.disabled = false;

                if (dialog.open) {
                    confirmButton.disabled = false;
                    cancelButton.disabled = false;
                }
            }
        }
    }

    /**
     * 구성원 테이블 클릭을 관리자 명령 대화상자로 연결합니다.
     * 버튼에 저장한 사용자 ID를 현재 구성원 목록과 대조합니다.
     * 화면에서 생성하지 않은 명령은 실행하지 않습니다.
     *
     * @param {MouseEvent} event 목록 클릭
     */
    function onMemberClick(event) {
        if (!(event.target instanceof Element)) return;

        const button = event.target.closest(
            'button[data-action][data-user-id]'
        );

        if (!button || button.disabled) return;

        const member = members.find(
            item => String(item.userId) === button.dataset.userId
        );

        if (member) {
            openAction(button.dataset.action, member);
        }
    }

    /**
     * 확인 대화상자가 처리 중에 닫히지 않도록 막습니다.
     * 처리 중이 아니라면 ESC 취소를 허용합니다.
     * 서버 요청을 취소했는데 완료된 것으로 오인하는 상황을 방지합니다.
     *
     * @param {Event} event cancel 이벤트
     */
    function onDialogCancel(event) {
        if (busy) event.preventDefault();
    }

    // 새로고침과 주 관리자 이전 대상 선택을 처리합니다.
    const onRefresh = () => {
        if (!busy && !dialog.open) void loadGroup();
    };

    const onTransferChange = () => {
        transferOpen.disabled = !transferSelect.value;
    };

    const onTransferOpen = () => {
        const member = members.find(
            item => String(item.userId) === transferSelect.value
        );

        if (member) {
            openAction('TRANSFER', member);
        }
    };

    // 서버 작업 중에는 확인 창을 취소할 수 없습니다.
    const onCancelClick = () => {
        if (!busy) dialog.close();
    };

    const onDialogClose = () => {
        if (!busy) pendingAction = null;
    };

    /**
     * 그룹 관리자 탭의 클릭 이벤트를 처리합니다.
     * 구성원, 초대 링크, 크리처 선택 탭을 구분합니다.
     * 접근 권한과 요청 중 상태는 showAdminTab에서 확인합니다.
     *
     * @param {MouseEvent} event 클릭 이벤트
     */
    function onAdminTabClick(event) {
        if (!(event.target instanceof Element)) return;

        const tab = event.target.closest('[data-admin-tab]');
        if (!tab) return;

        if (tab.dataset.adminTab === 'members') {
            showAdminTab('members');

        } else if (tab.dataset.adminTab === 'invitation') {
            showAdminTab('invitation');

        } else if (tab.dataset.adminTab === 'creature') {
            showAdminTab('creature');
        }
    }

    /**
     * 재발급 확인 Modal의 취소 버튼을 처리합니다.
     * API 요청 중에는 닫지 않습니다.
     * 요청하지 않은 경우에만 대화상자를 종료합니다.
     */
    function onInvitationCancelClick() {
        if (!rotatingInvitation) {
            invitationDialog.close();
        }
    }

    // 초대 관리 및 탭 관련 이벤트
    adminTabs.addEventListener('click', onAdminTabClick);
    invitationRefresh.addEventListener('click', loadInvitationInfo);
    invitationRotateOpen.addEventListener('click', openInvitationDialog);
    invitationConfirm.addEventListener('click', confirmInvitationRotation);
    invitationCancel.addEventListener('click', onInvitationCancelClick);
    invitationDialog.addEventListener('cancel', onInvitationDialogCancel);
    invitationCopy.addEventListener('click', copyInvitationUrl);

    // 이벤트를 연결합니다.
    refreshButton.addEventListener('click', onRefresh);
    membersBody.addEventListener('click', onMemberClick);
    transferSelect.addEventListener('change', onTransferChange);
    transferOpen.addEventListener('click', onTransferOpen);
    confirmButton.addEventListener('click', confirmAction);
    cancelButton.addEventListener('click', onCancelClick);
    dialog.addEventListener('cancel', onDialogCancel);
    dialog.addEventListener('close', onDialogClose);

    /**
     * 최신 크리처 정보를 다시 조회하는 버튼의 이벤트입니다.
     * 중복 요청 차단은 loadCreatureSelection에서 처리합니다.
     * 부화 처리 대기 중에도 사용자가 직접 상태를 재확인할 수 있습니다.
     */
    function onCreatureRefresh() {
        void loadCreatureSelection();
    }

    /**
     * 비활성 브라우저 탭에서 돌아왔을 때 시간을 다시 계산합니다.
     * 백그라운드에서 타이머 호출이 지연되는 경우를 보완합니다.
     * 마감 시각에 도달했다면 서버 상태를 확인합니다.
     */
    function onCreatureVisibilityChange() {
        if (!document.hidden) {
            tickCreatureDeadline();
        }
    }

    // 크리처 종 선택·조회·저장 이벤트
    creatureRefresh.addEventListener('click', onCreatureRefresh);
    creatureOptions.addEventListener('change', onCreatureOptionChange);
    creatureSave.addEventListener('click', onCreatureSave);

    // 초 단위 카운트다운은 현재 페이지가 살아 있는 동안만 실행합니다.
    creatureTimerId = window.setInterval(
        tickCreatureDeadline,
        1000
    );

    document.addEventListener(
        'visibilitychange',
        onCreatureVisibilityChange
    );

    // 최초 서버 데이터 조회
    void loadGroup();

    return {
        element,
        cleanup: () => {
            mounted = false;

            // 이전 페이지의 비동기 크리처 조회 결과를 무효화합니다.
            creatureLoadVersion++;

            if (creatureTimerId !== null) {
                window.clearInterval(creatureTimerId);
                creatureTimerId = null;
            }

            document.removeEventListener(
                'visibilitychange',
                onCreatureVisibilityChange
            );

            creatureRefresh.removeEventListener('click', onCreatureRefresh);
            creatureOptions.removeEventListener('change', onCreatureOptionChange);
            creatureSave.removeEventListener('click', onCreatureSave);

            // 이동한 페이지에 이전 크리처 상태를 남기지 않습니다.
            creatureHomeData = null;
            creatureSpeciesList = [];
            creatureSelectedId = '';

            // 페이지가 종료된 후 도착하는 초대 조회 응답을 무시합니다.
            invitationLoadVersion++;

            // 재발급 URL은 페이지 메모리에서 제거합니다.
            clearOneTimeInvitationUrl();
            invitationData = null;
            invitationLoaded = false;

            // 추가한 이벤트 리스너를 해제합니다.
            adminTabs.removeEventListener('click', onAdminTabClick);
            invitationRefresh.removeEventListener('click', loadInvitationInfo);
            invitationRotateOpen.removeEventListener('click', openInvitationDialog);
            invitationConfirm.removeEventListener('click', confirmInvitationRotation);
            invitationCancel.removeEventListener('click', onInvitationCancelClick);
            invitationDialog.removeEventListener('cancel', onInvitationDialogCancel);
            invitationCopy.removeEventListener('click', copyInvitationUrl);

            if (invitationDialog.open) {
                invitationDialog.close();
            }

            loadVersion++;

            // 라우팅 종료 후 늦은 API 응답이 화면을 갱신하지 못하게 합니다.
            refreshButton.removeEventListener('click', onRefresh);
            membersBody.removeEventListener('click', onMemberClick);
            transferSelect.removeEventListener('change', onTransferChange);
            transferOpen.removeEventListener('click', onTransferOpen);
            confirmButton.removeEventListener('click', confirmAction);
            cancelButton.removeEventListener('click', onCancelClick);
            dialog.removeEventListener('cancel', onDialogCancel);
            dialog.removeEventListener('close', onDialogClose);

            if (dialog.open) dialog.close();
        }
    };
}
