// @ts-check
import {
    listAdminReports,
    getAdminReport,
    dismissReport,
    removeReportedMember,
    resolveInactiveReportedMember,
} from "../features/admin/api.js";
import {ApiError} from "../api/client.js";

const PAGE_SIZE = 10;
const STATUS_LABELS = {
    RECEIVED: '미처리',
    REJECTED: '기각',
    RESOLVED: '강퇴 처리'
};
const CATEGORY_LABELS = {
    HARASSMENT: '괴롭힘',
    SPAM: '스팸',
    INAPPROPRIATE_CONTENT: '부적절한 콘텐츠',
    OTHER: '기타'
};

/**
 * UTC 날짜 문자열을 한국 시간으로 표시하는 유틸 함수.
 * 서버 원본 값은 수정하지 않고 표시에만 변환을 적용한다.
 * @param {string|null|undefined} value UTC ISO 8601 문자열
 * @returns {string} KST 시각 또는 대시 표시
 */
function formatDateTime(value) {
    if (! value) return '-';

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';

    return new Intl.DateTimeFormat('ko-KR', {
        timeZone: 'Asia/Seoul',
        dateStyle: 'short',
        timeStyle: 'short'
    }).format(date);
}

/**
 * 서버 API 오류를 관리자용 안내 문구로 변환한다.
 * HTTP 상태별 상황을 구분하고 내부 상세 정보는 노출하지 않는다.
 * @param {unknown} error 발생한 오류
 * @returns {string} 사용자용 오류 메시지
 */
function getErrorMessage(error) {
    if (error instanceof ApiError) {
        if (error.status === 401) return '로그인 세션을 확인해 주세요.';
        if (error.status === 403) return '전체 관리자 권한이 없거나 요청이 거부되었습니다.';
        if (error.status === 404) return '요청한 신고를 찾을 수 없습니다.';
        if (error.status === 409) return '신고 상태가 변경되었습니다. 최신 정보를 다시 확인해 주세요.';
        return error.message;
    }

    return error instanceof Error ? error.message : '요청을 처리하지 못했습니다.';
}

/**
 * 신고 목록, 상세, 확인 Modal이 있는 Page를 구성한다.
 * API 호출과 요청 경합을 고나리하고, 화면 이동 시 리소스를 정리한다.
 * Router에서 사용하는 element, cleanup 계약을 반환한다.
 * @returns {{element: HTMLElement, cleanup: () => void}} SPA Page
 */
export function adminReportsPage() {
    const element = document.createElement('section');
    element.className = 'container-fluid px-3 px-md-4 py-4';

    // 화면의 고정된 HTML 템플릿 생성
    // 신고 내용, 닉네임 등 서버 데이터는 textContent로 출력.
    element.innerHTML = `
    <header class="mb-4">
      <h1 class="h3 fw-bold">신고 관리</h1>
      <p class="text-body-secondary mb-0">
        신고 내용을 검토하고 처리 결과를 기록합니다.
      </p>
    </header>

    <section class="card mb-3">
      <div class="card-body">
        <form data-filter-form class="row g-3 align-items-end">
          <div class="col-12 col-sm-6 col-md-4">
            <label for="report-status" class="form-label">처리 상태</label>
            <select id="report-status" class="form-select" data-status-filter>
              <option value="">전체</option>
              <option value="RECEIVED">미처리</option>
              <option value="RESOLVED">강퇴 처리</option>
              <option value="REJECTED">기각</option>
            </select>
          </div>
          <div class="col-12 col-sm-auto">
            <button type="submit" class="btn btn-primary">조회</button>
            <button type="button" class="btn btn-outline-secondary" data-reload>
              새로고침
            </button>
          </div>
        </form>
      </div>
    </section>

    <div class="alert alert-info" role="status" aria-live="polite"
         data-notice>신고 목록을 조회하는 중입니다.</div>

    <div class="row g-3">
      <section class="col-12 col-xl-7">
        <div class="card h-100">
          <div class="card-body">
            <h2 class="h5">신고 목록</h2>
            <div class="table-responsive">
              <table class="table table-hover align-middle">
                <thead>
                  <tr>
                    <th scope="col">신고 ID</th>
                    <th scope="col">그룹</th>
                    <th scope="col">유형</th>
                    <th scope="col">상태</th>
                    <th scope="col">접수</th>
                    <th scope="col">선택</th>
                  </tr>
                </thead>
                <tbody data-report-rows></tbody>
              </table>
            </div>
            <nav class="d-flex flex-wrap align-items-center justify-content-between gap-2"
                 aria-label="신고 페이지 이동">
              <button type="button" class="btn btn-outline-secondary btn-sm"
                      data-prev>이전</button>
              <span class="small text-body-secondary" data-page-info>-</span>
              <button type="button" class="btn btn-outline-secondary btn-sm"
                      data-next>다음</button>
            </nav>
          </div>
        </div>
      </section>

      <!-- 상세 카드로 이동할 때 고정 Header 높이만큼 여백을 확보합니다. -->
      <section class="col-12 col-xl-5"
             data-detail-section
             style="scroll-margin-top: calc(var(--app-header-height) + 12px)">
        <div class="card h-100">
          <div class="card-body">
            <h2 class="h5" data-detail-heading tabindex="-1">
              선택 신고 상세
            </h2>
            <p class="text-body-secondary" data-detail-status>
              목록에서 신고를 선택해 주세요.
            </p>
            <dl class="row small" data-detail-fields></dl>
            <div class="d-flex flex-wrap gap-2 d-none" data-action-buttons>
              <button type="button" class="btn btn-outline-secondary"
                      data-dismiss>기각</button>
              <button type="button" class="btn btn-danger"
                      data-remove>강퇴 처리</button>
              <button type="button" class="btn btn-outline-danger"
                      data-resolve>비활성 대상 처리 완료</button>
            </div>
          </div>
        </div>
      </section>
    </div>

    <dialog class="border-0 rounded-4 shadow p-0"
            data-confirm-dialog
            aria-labelledby="report-dialog-title"
            aria-describedby="report-dialog-warning">
      <form data-confirm-form class="p-4" style="width:min(90vw,440px)">
        <h2 id="report-dialog-title" class="h5" data-dialog-title></h2>
        <p id="report-dialog-warning" class="small text-body-secondary"
           data-dialog-warning></p>
        <label class="form-label" for="report-action-reason">
          처리 사유 (필수, 최대 1000자)
        </label>
        <textarea id="report-action-reason" class="form-control mb-2"
                  rows="4" maxlength="1000" required data-reason></textarea>
        <p class="small text-danger" role="alert" data-dialog-error></p>
        <div class="d-flex justify-content-end gap-2">
          <button type="button" class="btn btn-outline-secondary"
                  data-cancel>취소</button>
          <button type="submit" class="btn btn-danger"
                  data-confirm>처리 확정</button>
        </div>
      </form>
    </dialog>
    `;

    // Page 내부에서만 요소를 검색하여 다른 화면과 선택자가 충돌하지 않게 함.
    const filterForm = /** @type {HTMLFormElement} */ (element.querySelector('[data-filter-form]'));
    const statusInput = /** @type {HTMLSelectElement} */ (element.querySelector('[data-status-filter]'));
    const reloadButton = /** @type {HTMLButtonElement} */ (element.querySelector('[data-reload]'));
    const rowsNode = /** @type {HTMLElement} */ (element.querySelector('[data-report-rows]'));
    const noticeNode = /** @type {HTMLElement} */ (element.querySelector('[data-notice]'));
    const pageInfo = /** @type {HTMLElement} */ (element.querySelector('[data-page-info]'));
    const prevButton = /** @type {HTMLButtonElement} */ (element.querySelector('[data-prev]'));
    const nextButton = /** @type {HTMLButtonElement} */ (element.querySelector('[data-next]'));
    // 신고 상세 영역과 접근성 포커스 대상입니다.
    const detailSection = /** @type {HTMLElement} */ (element.querySelector('[data-detail-section]'));
    const detailHeading = /** @type {HTMLElement} */ (element.querySelector('[data-detail-heading]'));
    const detailStatus = /** @type {HTMLElement} */ (element.querySelector('[data-detail-status]'));
    const detailFields = /** @type {HTMLElement} */ (element.querySelector('[data-detail-fields]'));
    const actions = /** @type {HTMLElement} */ (element.querySelector('[data-action-buttons]'));
    const dismissButton = /** @type {HTMLButtonElement} */ (element.querySelector('[data-dismiss]'));
    const removeButton = /** @type {HTMLButtonElement} */ (element.querySelector('[data-remove]'));
    const resolveButton = /** @type {HTMLButtonElement} */ (element.querySelector('[data-resolve]'));
    const dialog = /** @type {HTMLDialogElement} */ (element.querySelector('[data-confirm-dialog]'));
    const confirmForm = /** @type {HTMLFormElement} */ (element.querySelector('[data-confirm-form]'));
    const dialogTitle = /** @type {HTMLElement} */ (element.querySelector('[data-dialog-title]'));
    const dialogWarning = /** @type {HTMLElement} */ (element.querySelector('[data-dialog-warning]'));
    const dialogError = /** @type {HTMLElement} */ (element.querySelector('[data-dialog-error]'));
    const reasonInput = /** @type {HTMLTextAreaElement} */ (element.querySelector('[data-reason]'));
    const confirmButton = /** @type {HTMLButtonElement} */ (element.querySelector('[data-confirm]'));
    const cancelButton = /** @type {HTMLButtonElement} */ (element.querySelector('[data-cancel]'));

    // 페이지·선택·요청 상태는 Page 인스턴스마다 독립적으로 유지합니다.
    let page = 0;
    let totalPages = 0;
    /** @type {any|null} */
    let selectedReport = null;
    /** @type {'dismiss'|'remove'|'resolve'|null} */
    let actionKind = null;
    let processing = false;
    let loading = false; // 신고 목록을 조회하는 동안 중복 페이징을 막습니다.
    let disposed = false;
    let listVersion = 0;
    let detailVersion = 0;

    // 마지막 조회 버튼 클릭 시 확정한 신고 상태 필터입니다.
    // 화면에서 선택만 한 값은 조회에 반영하지 않습니다.
    // 새로고침과 페이징에서도 동일한 조건을 사용합니다.
    let appliedStatus = statusInput.value || undefined;

    /**
     * 페이지 상단에 조회, 처리 결과를 표시한다.
     * 성공, 경고, 실패를 Bootstrap 알림 스타일로 구분한다.
     * @param {string} text 안내 메시지
     * @param {'info'|'success'|'warning'|'danger'} tone 알림 종류
     * @returns {void}
     */
    function showNotice(text, tone = 'info') {
        noticeNode.className = `alert alert-${tone}`;
        noticeNode.textContent = text;
    }

    /**
     * 신고 처리 및 목록 조회 상태에 따라 버튼 사용 가능 여부를 설정합니다.
     * 목록 조회 중에는 페이징과 수동 새로고침의 중복 실행을 막습니다.
     * 신고 기각·강퇴의 중복 제출 방지 규칙은 기존과 동일하게 유지합니다.
     * @returns {void}
     */
    function updateControls() {
        // 목록 조회 중에는 현재 페이지를 확정할 때까지 이동을 제한합니다.
        prevButton.disabled = processing || loading || page <= 0;
        nextButton.disabled =
            processing || loading || totalPages === 0 || page >= totalPages - 1;
        reloadButton.disabled = processing || loading;

        // 상태 변경 또는 목록 조회 중에는 필터 변경을 차단합니다.
        statusInput.disabled = processing || loading;
        dismissButton.disabled =
            processing || selectedReport?.status !== 'RECEIVED';
        removeButton.disabled =
            processing || selectedReport?.status !== 'RECEIVED';
        resolveButton.disabled =
            processing || selectedReport?.status !== 'RECEIVED';
        confirmButton.disabled = processing;
        cancelButton.disabled = processing;
    }

    /**
     * 선택된 신고 상세를 초기화한다.
     * 이전 상세 요청의 결과가 뒤늦게 표시되지 않도록 버전을 갱신한다.
     * @param {string} message 상세 영역 안내 메시지
     * @returns {void}
     */
    function clearDetail(message = '목록에서 신고를 선택해 주세요.') {
        detailVersion += 1;
        selectedReport = null;
        detailStatus.textContent = message;
        detailFields.replaceChildren();
        actions.classList.add('d-none');
        updateControls();
    }

    /**
     * 신고 상세 DTO를 안전하게 화면에 표시한다.
     * 서버 문자열은 HTML로 해석하지 않고 textContent로 출력한다.
     * @param {any} report 신고 상세 DTO
     * @returns {void}
     */
    function renderDetail(report) {
        selectedReport = report;
        detailStatus.textContent = `신고 #${report.reportId} · ${STATUS_LABELS[report.status] ?? report.status}`;
        detailFields.replaceChildren();

        const values = [
            ['그룹 ID', report.groupId],
            ['신고자', `${report.reporterNickname} (#${report.reporterUserId})`],
            ['신고 대상', `${report.reportedUserNickname} (#${report.reportedUserId})`],
            ['분류', CATEGORY_LABELS[report.category] ?? report.category],
            ['신고 내용', report.content],
            ['접수 시각', formatDateTime(report.createdAt)],
            ['처리 시각', formatDateTime(report.handledAt)],
            ['처리자 ID', report.handledByUserId],
            ['처리 사유', report.resolution],
        ];
        if (report.chatEvidence) {
            values.push(
                ['채팅 메시지 ID', report.chatEvidence.messageId],
                ['채팅 작성자', `${report.chatEvidence.senderNickname} (#${report.chatEvidence.senderUserId})`],
                ['채팅 내용', report.chatEvidence.content],
                ['채팅 전송 시각', formatDateTime(report.chatEvidence.sentAt)],
            );
        }

        // <dl> 안에 <dt>/<dd> 쌍을 생성
        for (const [label, value] of values) {
            const dt = document.createElement('dt');
            dt.className = 'col-4';
            dt.textContent = String(label);

            const dd = document.createElement('dd');
            dd.className = 'col-8 text-break';
            dd.textContent = value == null ? '-' : String(value);
            detailFields.append(dt, dd);
        }

        actions.classList.toggle('d-none', report.status !== 'RECEIVED');
        updateControls();
    }

    /**
     * 모바일·태블릿에서 선택한 신고의 상세 카드로 이동합니다.
     * Bootstrap xl 미만에서만 스크롤하고 데스크톱 배치는 유지합니다.
     * 고정 Header의 높이와 사용자의 움직임 감소 설정을 고려합니다.
     *
     * @returns {void}
     */
    function moveToDetailCard() {
        // col-xl-*이 세로 배치로 변경되는 화면에서만 이동합니다.
        if (!window.matchMedia('(max-width: 1199.98px)').matches) {
            return;
        }

        // 접근성 설정에 따라 스크롤 애니메이션을 선택합니다.
        const reducedMotion = window.matchMedia(
            '(prefers-reduced-motion: reduce)'
        ).matches;

        // 포커스만 먼저 이동시키되 브라우저의 자동 스크롤은 방지합니다.
        detailHeading.focus({preventScroll: true});

        // 실제 이동은 고정 Header 여백을 적용한 카드 기준으로 수행합니다.
        detailSection.scrollIntoView({
            behavior: reducedMotion ? 'auto' : 'smooth',
            block: 'start'
        });
    }

    /**
     * 서버가 반환한 신고 목록으로 새 테이블 행을 구성합니다.
     * 화면에 연결되지 않은 DocumentFragment에서 행을 먼저 완성합니다.
     * 모든 행의 생성이 끝난 뒤 기존 목록을 한 번에 교체합니다.
     * @param {any[]} reports 현재 페이지 신고 목록
     * @returns {void}
     */
    function renderRows(reports) {
        const fragment = document.createDocumentFragment();

        // 빈 결과도 하나의 행으로 표현한다.
        if (reports.length === 0) {
            const row = document.createElement('tr');
            const cell = document.createElement('td');

            cell.colSpan = 6;
            cell.className = 'text-center text-body-secondary py-4';
            cell.textContent = '해당 조건에 맞는 신고가 없습니다.';

            row.append(cell);
            fragment.append(row);
        }

        for (const report of reports) {
            const row = document.createElement('tr');
            const values = [
                report.reportId,
                report.groupId,
                CATEGORY_LABELS[report.category] ?? report.category,
                STATUS_LABELS[report.status] ?? report.status,
                formatDateTime(report.createdAt)
            ];

            for (const value of values) {
                const cell = document.createElement('td');
                cell.textContent = String(value);
                row.append(cell);
            }

            // 상세 버튼은 기존 이벤트 위임 방식을 그대로 사용
            const actionCell = document.createElement('td');
            const button = document.createElement('button');

            button.type = 'button';
            button.className = 'btn btn-outline-primary btn-sm';
            button.dataset.reportId = String(report.reportId);
            button.textContent = '상세';

            actionCell.append(button);
            row.append(actionCell);
            fragment.append(row);
        }

        // 마지막 한 번 DOM 변경
        rowsNode.replaceChildren(fragment);
    }

    /**
     * 지정된 페이지와 신고 상태 조건으로 목록을 조회합니다.
     * 요청 조건은 로컬 인자로 보관하고, 성공한 경우에만 적용 필터를 확정합니다.
     * 실패 시 이전 목록·페이지·필터를 유지하고 입력 선택값을 복구합니다.
     *
     * @param {number} [requestedPage=page] 조회할 페이지 번호
     * @param {string|undefined} [requestedStatus=appliedStatus] 조회할 신고 상태
     * @returns {Promise<boolean>} 목록 갱신 성공 여부
     */
    async function loadReports(
        requestedPage = page,
        requestedStatus = appliedStatus
    ) {
        const version = ++listVersion;
        const table = rowsNode.closest('table');
        const changingFilter = requestedStatus !== appliedStatus;

        loading = true;

        // 기존 목록만 유지하고 조회 중 상태만 표시한다.
        table?.setAttribute('aria-busy', 'true');
        table?.classList.add('opacity-75');

        showNotice('신고 목록을 갱신하고 있습니다.');
        updateControls();

        try {
            // 현재 확정 필터가 아닌 이번 요청의 조건을 전달한다.
            const result = await listAdminReports({
                status: requestedStatus,
                page: requestedPage,
                size: PAGE_SIZE
            });

            // 다른 요청이 시작되었거나 화면이 종료되면 결과를 폐기한다.
            if (disposed || version !== listVersion) {
                return false;
            }

            const nextTotalPages = result.meta.totalPages;

            // 마지막 페이지가 사라진 경우 동일한 필터로 다시 조회한다.
            if (nextTotalPages > 0 && requestedPage >= nextTotalPages) {
                return await loadReports(nextTotalPages - 1, requestedStatus)
            }

            // 조회가 성공했을 때만 목록, 페이지, 필터를 함께 확정한다.
            renderRows(result.data);

            page = nextTotalPages === 0 ? 0 : requestedPage;
            totalPages = nextTotalPages;
            appliedStatus = requestedStatus;

            // 변경된 필터를 화면의 선택값과 동기화한다.
            if (changingFilter) {
                statusInput.value = requestedStatus ?? '';
            }

            pageInfo.textContent = `${totalPages ? page + 1 : 0} / ${totalPages} 페이지 · 총 ${result.meta.totalElements}건`;

            showNotice(`신고 목록 ${result.data.length}건을 조회했습니다.`, 'success');

            return true;
        } catch (error) {
            if (disposed || version !== listVersion) {
                return false;
            }

            // 새 필터 조회가 실패하면 기존 확정 필터로 되돌린다.
            if (changingFilter) {
                statusInput.value = appliedStatus ?? '';
            }

            // 이전 목록과 페이지 번호는 변경하지 않는다.
            showNotice(`조회에 실패했습니다. 이전 목록을 유지합니다. ${getErrorMessage(error)}`, 'danger');

            return false;
        } finally {
            if (!disposed && version === listVersion) {
                loading = false;

                table?.removeAttribute('aria-busy');
                table?.classList.remove('opacity-75');

                updateControls();
            }
        }
    }


    /**
     * 신고 ID에 해당하는 최신 상세 데이터를 조회합니다.
     * 연속 선택 시 마지막 요청의 결과만 화면에 반영합니다.
     * 사용자가 상세 버튼을 클릭했다면 모바일에서 상세 카드로 이동합니다.
     *
     * @param {number} reportId 조회할 신고 ID
     * @param {boolean} [moveToDetail=false] 상세 카드로 이동할지 여부
     * @returns {Promise<void>} 상세 조회 완료
     */
    async function loadDetail(reportId, moveToDetail = false) {
        const version = ++detailVersion;

        // 새로운 상세 조회가 시작되면 이전 선택 상태를 초기화합니다.
        selectedReport = null;
        actions.classList.add('d-none');
        detailFields.replaceChildren();
        detailStatus.textContent = `신고 #${reportId} 조회 중...`;
        updateControls();

        try {
            const report = await getAdminReport(reportId);

            // 화면 종료 또는 다른 신고 선택 시 이전 응답을 무시합니다.
            if (disposed || version !== detailVersion) return;

            renderDetail(report);
        } catch (error) {
            if (disposed || version !== detailVersion) return;

            // 조회에 실패한 경우에도 상세 영역에 오류를 표시합니다.
            detailStatus.textContent = getErrorMessage(error);
        } finally {
            // 최신 요청이고 사용자가 직접 상세를 선택했다면 이동합니다.
            // 오류 발생 시에도 상세 카드의 오류 메시지를 확인할 수 있습니다.
            if (moveToDetail && !disposed && version === detailVersion) {
                moveToDetailCard();
            }
        }
    }

    /**
     * 선택된 신고에 대한 기각·강퇴 확인 Modal을 엽니다.
     * 처리 영향과 입력 사유를 확인한 뒤 별도 제출을 받습니다.
     * @param {'dismiss'|'remove'|'resolve'} kind 조치 종류
     * @returns {void}
     */
    function openActionDialog(kind) {
        if (processing || selectedReport?.status !== 'RECEIVED') return;

        actionKind = kind;
        reasonInput.value = '';
        dialogError.textContent = '';
        dialogTitle.textContent = kind === 'dismiss'
            ? '신고 기각 확인'
            : kind === 'resolve'
                ? '비활성 대상 처리 완료 확인'
                : '신고 기반 강퇴 확인';

        dialogWarning.textContent = kind === 'dismiss'
            ? `신고 #${selectedReport.reportId}를 기각합니다. 처리 후 되돌릴 수 없습니다.`
            : kind === 'resolve'
                ? `신고 #${selectedReport.reportId}의 대상이 이미 그룹을 떠난 경우 추가 강퇴 없이 처리 완료합니다.`
                : `신고 #${selectedReport.reportId}의 대상 회원을 ` +
                `그룹 #${selectedReport.groupId}에서 강퇴합니다. ` +
                '24시간 재가입 제한이 적용됩니다. 주 관리자는 직접 강퇴할 수 없습니다.';

        // 네이티브 dialog는 포커스 관리와 배경 조작 제한을 제공합니다.
        dialog.showModal();
        reasonInput.focus();
    }

    /**
     * 처리 사유를 검증한 후 신고 기각 또는 강퇴 요청을 전송합니다.
     * 요청 중 버튼을 잠그고 성공하면 상세·목록을 최신 상태로 갱신합니다.
     * @param {SubmitEvent} event Modal 제출 이벤트
     * @returns {Promise<void>} 처리 완료
     */
    async function submitAction(event) {
        event.preventDefault();
        if (processing || !actionKind || selectedReport?.status !== 'RECEIVED') {
            return;
        }

        const reason = reasonInput.value.trim();
        if (reason.length < 1 || reason.length > 1000) {
            dialogError.textContent = '처리 사유를 1~1000자로 입력해 주세요.';
            return;
        }

        const kind = actionKind;
        const reportId = selectedReport.reportId;
        processing = true;
        updateControls();
        dialogError.textContent = '';

        try {
            // 실제 상태 변경은 기존 API 모듈과 서버에 위임합니다.
            const updated = kind === 'dismiss'
                ? await dismissReport(reportId, reason)
                : kind === 'resolve'
                    ? await resolveInactiveReportedMember(reportId, reason)
                    : await removeReportedMember(reportId, reason);
            if (disposed) return;

            dialog.close();
            actionKind = null;
            renderDetail(updated);

            // 처리 결과에 따라 RECEIVED 필터 목록에서 행이 사라질 수 있습니다.
            const refreshed = await loadReports();
            if (refreshed && !disposed) {
                showNotice(
                    `신고 #${reportId} ${kind === 'dismiss' ? '기각' : kind === 'resolve' ? '완료' : '강퇴'} ` +
                    '처리가 완료됐습니다.',
                    'success'
                );
            }
        } catch (error) {
            if (disposed) return;

            if (kind === 'resolve' && error instanceof ApiError && error.status === 409) {
                dialogError.textContent =
                    '대상이 현재 활성 회원이거나 신고 상태가 변경되었습니다. 활성 회원은 강퇴 처리를 사용해 주세요.';
            } else if (error instanceof ApiError && error.status === 409) {
                // 다른 관리자가 먼저 처리했을 가능성이 있으므로 재조회합니다.
                dialog.close();
                actionKind = null;
                const refreshed = await Promise.all([
                    loadReports(),
                    loadDetail(reportId),
                ]);
                if (refreshed[0] && !disposed) {
                    showNotice(getErrorMessage(error), 'warning');
                }
            } else {
                // 실패한 요청은 성공으로 간주하지 않고 Modal에 오류를 남깁니다.
                dialogError.textContent = getErrorMessage(error);
            }
        } finally {
            processing = false;
            if (!disposed) updateControls();
        }
    }

    /**
     * 목록 영역에서 상세 버튼 클릭을 이벤트 위임으로 처리합니다.
     * 목록 행을 교체하더라도 이벤트 리스너를 다시 등록하지 않습니다.
     * @param {MouseEvent} event 클릭 이벤트
     * @returns {void}
     */
    function onRowClick(event) {
        if (processing || !(event.target instanceof Element)) return;

        const button = event.target.closest('button[data-report-id]');
        if (!(button instanceof HTMLButtonElement)) return;

        const reportId = Number(button.dataset.reportId);
        if (Number.isSafeInteger(reportId) && reportId > 0) {
            // 사용자 클릭으로 발생한 상세 조회에만 자동 스크롤을 허용합니다.
            void loadDetail(reportId, true);
        }
    }

    /**
     * 사용자가 선택한 신고 상태로 첫 페이지를 조회합니다.
     * 조회 조건을 즉시 확정하지 않고 loadReports()에 전달합니다.
     * 성공하면 이전 상세를 초기화하고, 실패하면 기존 상태를 유지합니다.
     *
     * @param {SubmitEvent} event 조회 Form 제출 이벤트
     * @returns {void}
     */
    function onFilterSubmit(event) {
        event.preventDefault();

        // 다른 상태 변경이나 목록 조회 중에는 중복 요청하지 않습니다.
        if (processing || loading) return;

        // 요청에 사용할 필터만 준비하고 확정은 하지 않습니다.
        const requestedStatus = statusInput.value;

        void loadReports(0, requestedStatus).then(success => {
            if (success && !disposed) {
                clearDetail();
            }
        });
    }


    /**
     * 처리 중에는 Escape 키로 Modal을 닫지 못하도록 제한합니다.
     * 요청이 없을 때는 네이티브 dialog의 기본 동작을 허용합니다.
     * @param {Event} event dialog 취소 이벤트
     * @returns {void}
     */
    function onDialogCancel(event) {
        if (processing) event.preventDefault();
    }

    // 독립적인 이벤트 핸들러를 준비합니다.
    const onReload = () => {
        if (!processing) void loadReports();
    };
    // 현재 페이지가 아닌 요청 대상 페이지를 전달합니다.
    // 조회 성공 후에만 loadReports()가 페이지 번호를 확정합니다.
    const onPrev = () => {
        if (processing || loading || page <= 0) return;

        void loadReports(page - 1).then(success => {
            if (success && !disposed) clearDetail();
        });
    };

    const onNext = () => {
        if (processing || loading || page >= totalPages - 1) return;

        void loadReports(page + 1).then(success => {
            if (success && !disposed) clearDetail();
        });
    };
    const onCancel = () => {
        if (!processing) dialog.close();
    };
    const onDismiss = () => openActionDialog('dismiss');
    const onRemove = () => openActionDialog('remove');
    const onResolve = () => openActionDialog('resolve');
    const onDialogClose = () => {
        actionKind = null;
    };

    // 화면 내부 이벤트를 등록하고 최초 목록 조회를 시작합니다.
    filterForm.addEventListener('submit', onFilterSubmit);
    reloadButton.addEventListener('click', onReload);
    prevButton.addEventListener('click', onPrev);
    nextButton.addEventListener('click', onNext);
    rowsNode.addEventListener('click', onRowClick);
    dismissButton.addEventListener('click', onDismiss);
    removeButton.addEventListener('click', onRemove);
    resolveButton.addEventListener('click', onResolve);
    confirmForm.addEventListener('submit', submitAction);
    cancelButton.addEventListener('click', onCancel);
    dialog.addEventListener('cancel', onDialogCancel);
    dialog.addEventListener('close', onDialogClose);

    void loadReports();

    return {
        element,
        cleanup() {
            // 화면 종료 후 비동기 응답이 DOM을 수정하지 못하도록 막습니다.
            disposed = true;
            listVersion += 1;
            detailVersion += 1;
            if (dialog.open) dialog.close();

            // 등록한 이벤트를 모두 정리합니다.
            filterForm.removeEventListener('submit', onFilterSubmit);
            reloadButton.removeEventListener('click', onReload);
            prevButton.removeEventListener('click', onPrev);
            nextButton.removeEventListener('click', onNext);
            rowsNode.removeEventListener('click', onRowClick);
            dismissButton.removeEventListener('click', onDismiss);
            removeButton.removeEventListener('click', onRemove);
            resolveButton.removeEventListener('click', onResolve);
            confirmForm.removeEventListener('submit', submitAction);
            cancelButton.removeEventListener('click', onCancel);
            dialog.removeEventListener('cancel', onDialogCancel);
            dialog.removeEventListener('close', onDialogClose);
        },
    };
}





