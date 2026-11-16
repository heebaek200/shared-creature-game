// @ts-check
import {
    listOperationalWarnings,
    transferPrimaryAdminAsSuperAdmin,
} from '../features/admin/api.js';
import {ApiError} from '../api/client.js';

const PAGE_SIZE = 20;
const REFRESH_INTERVAL_MS = 60_000;

const WARNING_TYPES = {
    SCHEDULER_FAILED: '스케줄러 실패',
    SEASON_STATE_INVALID: '시즌 상태 이상',
    TEMPLATE_SHORTAGE: '퀘스트 템플릿 부족',
    QUEST_PERIOD_FAILED: '퀘스트 기간 실패',
    MEMBER_COUNT_MISMATCH: '그룹 인원 불일치',
    PAYMENT_UNCERTAIN: '결제 결과 불명',
    REFUND_UNCERTAIN: '환불 결과 불명',
    PHOTO_DELETION_FAILED: '사진 삭제 실패',
};

/**
 * UTC 시각을 KST 기준으로 표시합니다.
 * 서버에서 받은 원본 시각은 수정하지 않습니다.
 * @param {string|null|undefined} value UTC ISO 8601 시각
 * @returns {string} 표시용 날짜 문자열
 */
function formatDateTime(value) {
    if (!value) return '-';

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';

    return new Intl.DateTimeFormat('ko-KR', {
        timeZone: 'Asia/Seoul',
        dateStyle: 'short',
        timeStyle: 'short',
    }).format(date);
}

/**
 * API 오류를 관리자용 안내 메시지로 변환합니다.
 * 세션, 권한, 리소스, 동시 변경 오류를 구분합니다.
 * @param {unknown} error API 또는 브라우저 오류
 * @returns {string} 사용자에게 표시할 오류 메시지
 */
function getErrorMessage(error) {
    if (error instanceof ApiError) {
        if (error.status === 401) {
            return '로그인 세션이 만료되었거나 인증되지 않았습니다.';
        }
        if (error.status === 403) {
            return '요청 권한 또는 CSRF 토큰을 확인해 주세요.';
        }
        if (error.status === 404) {
            return '대상 그룹 또는 사용자를 찾을 수 없습니다.';
        }
        if (error.status === 409) {
            return '그룹의 상태나 관리자 정보가 변경되었습니다. 다시 확인해 주세요.';
        }
        return error.message;
    }

    return error instanceof Error
        ? error.message
        : '요청 처리 중 오류가 발생했습니다.';
}

/**
 * 양의 정수 ID 문자열을 숫자로 변환합니다.
 * 잘못된 값과 JavaScript의 안전 정수 범위를 벗어나는 값을 거부합니다.
 * @param {string} value 입력된 ID 문자열
 * @param {string} label 오류 메시지에 사용할 이름
 * @returns {number} 검증된 ID
 */
function parsePositiveId(value, label) {
    const input = value.trim();
    const parsed = Number(input);

    if (!/^[1-9]\d*$/.test(input) || !Number.isSafeInteger(parsed)) {
        throw new Error(`${label}는 1 이상의 정수여야 합니다.`);
    }

    return parsed;
}

/**
 * 운영 경고 조회와 주 관리자 재지정 화면을 생성합니다.
 * API 조회, 페이징, 확인 Modal, 중복 제출 방지를 관리합니다.
 * Router에서 사용하는 Page 계약을 반환합니다.
 * @returns {{element: HTMLElement, cleanup: () => void}} Page 객체
 */
export function adminOperationsPage() {
    const element = document.createElement('section');
    element.className = 'container-fluid px-3 px-md-4 py-4';

    // 고정된 화면 구조만 템플릿으로 작성합니다.
    element.innerHTML = `
    <header class="mb-4">
      <h1 class="h3 fw-bold">운영 관리</h1>
      <p class="text-body-secondary mb-0">
        미해결 운영 경고와 그룹 관리자 복구 작업을 관리합니다.
      </p>
    </header>

    <section class="card mb-4">
      <div class="card-body">
        <h2 class="h5 mb-3">현재 운영 경고</h2>

        <form data-warning-filter-form class="row g-3 align-items-end mb-3">
            <div class="col-12 col-md-5">
                <label for="warning-type" class="form-label">경고 유형</label>
                <select id="warning-type" class="form-select" data-warning-type>
                    <option value="">전체 유형</option>
                    <option value="SCHEDULER_FAILED">스케줄러 실패</option>
                    <option value="SEASON_STATE_INVALID">시즌 상태 이상</option>
                    <option value="TEMPLATE_SHORTAGE">퀘스트 템플릿 부족</option>
                    <option value="QUEST_PERIOD_FAILED">퀘스트 기간 실패</option>
                    <option value="MEMBER_COUNT_MISMATCH">그룹 인원 불일치</option>
                    <option value="PAYMENT_UNCERTAIN">결제 결과 불명</option>
                    <option value="REFUND_UNCERTAIN">환불 결과 불명</option>
                    <option value="PHOTO_DELETION_FAILED">사진 삭제 실패</option>
                </select>
            </div>
        
            <div class="col-12 col-md-auto d-flex gap-2">
                <!-- 조회는 Form의 submit 이벤트를 발생시킵니다. -->
                <button type="submit" class="btn btn-primary">
                    조회
                </button>
        
                <!-- 새로고침은 Form을 제출하지 않습니다. -->
                <button type="button" class="btn btn-outline-secondary"
                        data-warning-refresh>
                    새로고침
                </button>
            </div>
        </form>

        <div class="alert alert-info" role="status" aria-live="polite"
             data-warning-notice>운영 경고 조회 중...</div>

        <div class="table-responsive">
          <table class="table table-striped table-hover align-middle">
            <thead>
              <tr>
                <th scope="col">심각도</th>
                <th scope="col">유형</th>
                <th scope="col">대상 자원</th>
                <th scope="col">내용</th>
                <th scope="col">감지 시각</th>
              </tr>
            </thead>
            <tbody data-warning-rows></tbody>
          </table>
        </div>

        <nav class="d-flex justify-content-between align-items-center gap-2"
             aria-label="운영 경고 페이지 이동">
          <button type="button" class="btn btn-outline-secondary btn-sm"
                  data-prev>이전</button>
          <span class="small text-body-secondary" data-page-info>-</span>
          <button type="button" class="btn btn-outline-secondary btn-sm"
                  data-next>다음</button>
        </nav>

        <p class="small text-body-secondary mt-3 mb-0">
          경고는 원본 업무 상태가 정상화되면 다음 조회 결과에서 제외됩니다.
        </p>
      </div>
    </section>

    <section class="card">
      <div class="card-body">
        <h2 class="h5 mb-3">주 관리자 재지정</h2>
        <p class="text-body-secondary small">
          같은 그룹의 활성 회원(MEMBER 또는 DEPUTY)을 새 주 관리자로 지정합니다.
          기존 주 관리자는 일반 회원으로 변경됩니다.
        </p>

        <form data-transfer-form class="row g-3">
          <div class="col-12 col-md-6">
            <label for="transfer-group-id" class="form-label">그룹 ID</label>
            <input id="transfer-group-id" class="form-control"
                   type="number" min="1" step="1" required
                   data-group-id>
          </div>

          <div class="col-12 col-md-6">
            <label for="transfer-user-id" class="form-label">
              새 주 관리자 사용자 ID
            </label>
            <input id="transfer-user-id" class="form-control"
                   type="number" min="1" step="1" required
                   data-target-user-id>
          </div>

          <div class="col-12">
            <label for="transfer-reason" class="form-label">
              변경 사유 (필수, 최대 500자)
            </label>
            <textarea id="transfer-reason" class="form-control"
                      rows="3" maxlength="500" required
                      data-reason></textarea>
          </div>

          <div class="col-12">
            <button type="submit" class="btn btn-danger"
                    data-transfer-open>
              주 관리자 변경 확인
            </button>
          </div>
        </form>

        <div class="alert alert-secondary mt-3 mb-0" role="status"
             aria-live="polite" data-transfer-notice>
          대상 그룹과 회원을 확인한 뒤 변경을 진행하세요.
        </div>
      </div>
    </section>

    <dialog class="border-0 rounded-4 shadow p-0"
            aria-labelledby="transfer-confirm-title"
            aria-describedby="transfer-confirm-message"
            data-confirm-dialog>
      <form data-confirm-form class="p-4" style="width:min(90vw,440px)">
        <h2 class="h5" id="transfer-confirm-title">주 관리자 변경 확인</h2>
        <p id="transfer-confirm-message" data-confirm-message></p>
        <p class="small text-danger" role="alert" data-confirm-error></p>

        <div class="d-flex justify-content-end gap-2">
          <button type="button" class="btn btn-outline-secondary"
                  data-confirm-cancel>취소</button>
          <button type="submit" class="btn btn-danger"
                  data-confirm-submit>변경 확정</button>
        </div>
      </form>
    </dialog>
  `;

    const find = selector => element.querySelector(selector);

    const warningFilterForm = /** @type {HTMLFormElement} */ (find('[data-warning-filter-form]'));
    const typeInput = /** @type {HTMLSelectElement} */ (find('[data-warning-type]'));
    const refreshButton = /** @type {HTMLButtonElement} */ (find('[data-warning-refresh]'));
    const warningNotice = /** @type {HTMLElement} */ (find('[data-warning-notice]'));
    const rowsNode = /** @type {HTMLElement} */ (find('[data-warning-rows]'));
    const pageInfo = /** @type {HTMLElement} */ (find('[data-page-info]'));
    const prevButton = /** @type {HTMLButtonElement} */ (find('[data-prev]'));
    const nextButton = /** @type {HTMLButtonElement} */ (find('[data-next]'));

    const transferForm = /** @type {HTMLFormElement} */ (find('[data-transfer-form]'));
    const groupInput = /** @type {HTMLInputElement} */ (find('[data-group-id]'));
    const targetInput = /** @type {HTMLInputElement} */ (find('[data-target-user-id]'));
    const reasonInput = /** @type {HTMLTextAreaElement} */ (find('[data-reason]'));
    const transferButton = /** @type {HTMLButtonElement} */ (find('[data-transfer-open]'));
    const transferNotice = /** @type {HTMLElement} */ (find('[data-transfer-notice]'));

    const dialog = /** @type {HTMLDialogElement} */ (find('[data-confirm-dialog]'));
    const confirmForm = /** @type {HTMLFormElement} */ (find('[data-confirm-form]'));
    const confirmMessage = /** @type {HTMLElement} */ (find('[data-confirm-message]'));
    const confirmError = /** @type {HTMLElement} */ (find('[data-confirm-error]'));
    const cancelButton = /** @type {HTMLButtonElement} */ (find('[data-confirm-cancel]'));
    const confirmButton = /** @type {HTMLButtonElement} */ (find('[data-confirm-submit]'));

    let disposed = false;
    let loading = false;
    let processing = false;
    let page = 0;
    let totalPages = 0;
    let requestVersion = 0;

    // 사용자가 마지막으로 조회 버튼을 눌러 확정한 경고 유형입니다.
    // 셀렉트박스의 미적용 변경값은 자동 갱신과 페이징에 반영하지 않습니다.
    let appliedType = typeInput.value || undefined;

    /** @type {{groupId: number, targetUserId: number, reason: string}|null} */
    let pendingTransfer = null;

    /**
     * 목록과 변경 결과의 안내 메시지를 표시합니다.
     * 메시지별 영역을 분리해 자동 조회가 변경 결과를 덮지 않도록 합니다.
     * @param {HTMLElement} target 안내 영역
     * @param {string} message 표시할 문구
     * @param {'info'|'success'|'warning'|'danger'} tone 메시지 종류
     * @returns {void}
     */
    function showNotice(target, message, tone = 'info') {
        target.className = `alert alert-${tone}` +
            (target === transferNotice ? ' mt-3 mb-0' : '');
        target.textContent = message;
    }

    /**
     * 비동기 작업 상태에 맞게 버튼 활성 여부를 조정합니다.
     * POST 중에는 필터 변경과 중복 제출을 방지합니다.
     * @returns {void}
     */
    function updateControls() {
        typeInput.disabled = processing;
        refreshButton.disabled = loading || processing;
        prevButton.disabled = loading || processing || page <= 0;
        nextButton.disabled =
            loading || processing || totalPages === 0 || page >= totalPages - 1;
        transferButton.disabled = processing;
        confirmButton.disabled = processing;
        cancelButton.disabled = processing;
    }

    /**
     * 운영 경고 목록의 새로운 테이블 행을 생성합니다.
     * 서버의 경고 메시지는 textContent로 안전하게 표시합니다.
     * DocumentFragment에서 행을 완성한 후 DOM을 한 번만 교체합니다.
     *
     * @param {any[]} warnings 현재 페이지 운영 경고 목록
     * @returns {void}
     */
    function renderWarnings(warnings) {
        const fragment = document.createDocumentFragment();

        // 경고가 없는 경우에도 목록 영역은 정상적으로 표시합니다.
        if (warnings.length === 0) {
            const row = document.createElement('tr');
            const cell = document.createElement('td');

            cell.colSpan = 5;
            cell.className = 'text-center text-body-secondary py-4';
            cell.textContent = '현재 조건에 해당하는 미해결 운영 경고가 없습니다.';

            row.append(cell);
            fragment.append(row);
        }

        // 서버에서 조회한 경고 항목을 순서대로 생성합니다.
        for (const warning of warnings) {
            const row = document.createElement('tr');

            const severityCell = document.createElement('td');
            const badge = document.createElement('span');

            badge.className = warning.severity === 'CRITICAL'
                ? 'badge text-bg-danger'
                : 'badge text-bg-warning';
            badge.textContent = warning.severity;
            severityCell.append(badge);

            const typeCell = document.createElement('td');
            typeCell.textContent =
                WARNING_TYPES[warning.type] ?? warning.type;

            const resourceCell = document.createElement('td');
            resourceCell.textContent = warning.resourceId == null
                ? warning.resourceType
                : `${warning.resourceType} #${warning.resourceId}`;

            const messageCell = document.createElement('td');
            messageCell.className = 'text-break';
            messageCell.textContent = warning.message;

            const dateCell = document.createElement('td');
            dateCell.textContent = formatDateTime(warning.detectedAt);

            row.append(
                severityCell,
                typeCell,
                resourceCell,
                messageCell,
                dateCell
            );

            fragment.append(row);
        }

        // 모든 행을 생성한 후 화면의 목록을 갱신합니다.
        rowsNode.replaceChildren(fragment);
    }



    /**
     * 현재 적용된 필터로 운영 경고 목록을 조회합니다.
     * 로딩 중 이전 목록을 유지하며 성공했을 때만 행을 교체합니다.
     * 실패 시 기존 목록·페이지 번호를 보존하고 오류를 표시합니다.
     *
     * @param {number} [requestedPage=page] 조회할 페이지 번호
     * @returns {Promise<boolean>} 목록 갱신 성공 여부
     */
    async function loadWarnings(requestedPage = page) {
        const version = ++requestVersion;
        const table = rowsNode.closest('table');

        loading = true;

        // 기존 목록을 유지한 채 갱신 중임을 표시합니다.
        table?.setAttribute('aria-busy', 'true');
        table?.classList.add('opacity-75');
        updateControls();
        showNotice(warningNotice, '운영 경고를 갱신하고 있습니다.');

        try {
            // 확정된 필터와 요청 페이지를 사용합니다.
            const result = await listOperationalWarnings({
                type: appliedType,
                page: requestedPage,
                size: PAGE_SIZE
            });

            if (disposed || version !== requestVersion) return false;

            const nextTotalPages = result.meta.totalPages;

            // 경고 해소로 마지막 페이지가 사라졌으면 재조회합니다.
            if (nextTotalPages > 0 && requestedPage >= nextTotalPages) {
                return await loadWarnings(nextTotalPages - 1);
            }

            // 성공 시점에만 테이블과 페이지 번호를 확정합니다.
            renderWarnings(result.data);
            page = nextTotalPages === 0 ? 0 : requestedPage;
            totalPages = nextTotalPages;

            pageInfo.textContent =
                `${totalPages ? page + 1 : 0} / ${totalPages} 페이지 · ` +
                `총 ${result.meta.totalElements}건`;

            showNotice(
                warningNotice,
                `현재 미해결 경고 ${result.meta.totalElements}건을 확인했습니다.`,
                'success'
            );

            return true;
        } catch (error) {
            if (disposed || version !== requestVersion) return false;

            // 이전 정상 조회 결과를 유지해 화면이 비지 않도록 합니다.
            showNotice(
                warningNotice,
                `조회에 실패했습니다. 이전 목록을 유지합니다. ${getErrorMessage(error)}`,
                'danger'
            );

            return false;
        } finally {
            if (!disposed && version === requestVersion) {
                loading = false;
                table?.removeAttribute('aria-busy');
                table?.classList.remove('opacity-75');
                updateControls();
            }
        }
    }

    /**
     * 변경 폼의 ID와 사유를 검증한 뒤 확인 Modal을 엽니다.
     * 이 시점에는 서버로 상태 변경 요청을 전송하지 않습니다.
     * @param {SubmitEvent} event 폼 제출 이벤트
     * @returns {void}
     */
    function onTransferSubmit(event) {
        event.preventDefault();
        if (processing || !transferForm.reportValidity()) return;

        try {
            const groupId = parsePositiveId(groupInput.value, '그룹 ID');
            const targetUserId = parsePositiveId(
                targetInput.value,
                '대상 사용자 ID'
            );
            const reason = reasonInput.value.trim();

            if (reason.length < 1 || reason.length > 500) {
                throw new Error('변경 사유를 1~500자로 입력해 주세요.');
            }

            // 확인 시점의 입력값을 고정해 이후 입력 변화의 영향을 받지 않습니다.
            pendingTransfer = {groupId, targetUserId, reason};

            confirmMessage.textContent =
                `그룹 #${groupId}의 주 관리자를 사용자 #${targetUserId}로 ` +
                '재지정하시겠습니까? 기존 주 관리자는 일반 회원으로 변경되며 ' +
                '변경 사유와 감사 이력이 기록됩니다.';
            confirmError.textContent = '';
            dialog.showModal();
        } catch (error) {
            showNotice(transferNotice, getErrorMessage(error), 'danger');
        }
    }

    /**
     * 확인 Modal에서 승인한 주 관리자 변경 명령을 실행합니다.
     * 서버 성공 응답만 최종 성공으로 인정하며 충돌은 별도 안내합니다.
     * @param {SubmitEvent} event 확인 폼 제출 이벤트
     * @returns {Promise<void>} 변경 요청 완료
     */
    async function onConfirmSubmit(event) {
        event.preventDefault();
        if (processing || !pendingTransfer) return;

        const {groupId, targetUserId, reason} = pendingTransfer;
        processing = true;
        confirmError.textContent = '';
        updateControls();

        try {
            // apiRequest가 JSON, Cookie, CSRF Token을 공통 처리합니다.
            const result = await transferPrimaryAdminAsSuperAdmin(
                groupId,
                targetUserId,
                reason
            );
            if (disposed) return;

            dialog.close();
            pendingTransfer = null;
            transferForm.reset();

            const primary = result.primaryAdmin;
            const displayName = primary?.nickname
                ? `${primary.nickname} (#${primary.userId})`
                : `사용자 #${targetUserId}`;

            showNotice(
                transferNotice,
                `그룹 #${groupId}의 주 관리자가 ${displayName}(으)로 변경되었습니다.`,
                'success'
            );

            // 현재 경고도 다시 조회하되, 반드시 경고가 해소된다고 가정하지 않습니다.
            await loadWarnings();
        } catch (error) {
            if (disposed) return;

            if (error instanceof ApiError && error.status === 409) {
                dialog.close();
                pendingTransfer = null;

                showNotice(
                    transferNotice,
                    getErrorMessage(error),
                    'warning'
                );
                await loadWarnings();
            } else {
                // 실패했는데도 성공으로 표시하지 않고 Modal을 유지합니다.
                confirmError.textContent = getErrorMessage(error);
            }
        } finally {
            processing = false;
            if (!disposed) updateControls();
        }
    }

    /**
     * 처리 중 Escape로 Modal을 닫지 못하도록 방지합니다.
     * 서버 응답이 불명확한 동안 추가 명령이 발생하지 않게 합니다.
     * @param {Event} event 취소 이벤트
     * @returns {void}
     */
    function onDialogCancel(event) {
        if (processing) event.preventDefault();
    }

    /**
     * 경고 유형 조회 Form의 제출을 처리합니다.
     * 선택한 유형을 확정하고 페이지를 첫 페이지로 초기화합니다.
     * 기본 Form 제출을 차단해 SPA 화면을 유지합니다.
     *
     * @param {SubmitEvent} event Form 제출 이벤트
     * @returns {void}
     */
    function onWarningFilterSubmit(event) {
        event.preventDefault();

        // 관리자 변경 작업 중에는 새로운 조회를 시작하지 않습니다.
        if (processing) return;

        // 필터를 확정하되 페이지 번호는 조회 성공 후 변경합니다.
        appliedType = typeInput.value || undefined;
        void loadWarnings(0);
    }

    const onRefresh = () => {
        if (!processing) void loadWarnings();
    };
    // 페이지 번호를 먼저 변경하지 않고 조회 대상만 전달합니다.
    const onPrev = () => {
        if (loading || processing || page <= 0) return;
        void loadWarnings(page - 1);
    };

    const onNext = () => {
        if (loading || processing || page >= totalPages - 1) return;
        void loadWarnings(page + 1);
    };
    const onCancel = () => {
        if (!processing) dialog.close();
    };
    const onDialogClose = () => {
        if (!processing) pendingTransfer = null;
    };
    const onVisibilityChange = () => {
        if (!processing && document.visibilityState === 'visible') {
            void loadWarnings();
        }
    };

    // 경고 유형 변경 대신 Form 제출 시에만 조회합니다.
    warningFilterForm.addEventListener('submit', onWarningFilterSubmit);
    refreshButton.addEventListener('click', onRefresh);
    prevButton.addEventListener('click', onPrev);
    nextButton.addEventListener('click', onNext);
    transferForm.addEventListener('submit', onTransferSubmit);
    confirmForm.addEventListener('submit', onConfirmSubmit);
    cancelButton.addEventListener('click', onCancel);
    dialog.addEventListener('cancel', onDialogCancel);
    dialog.addEventListener('close', onDialogClose);
    document.addEventListener('visibilitychange', onVisibilityChange);

    const intervalId = setInterval(() => {
        if (!processing && document.visibilityState === 'visible') {
            void loadWarnings();
        }
    }, REFRESH_INTERVAL_MS);

    void loadWarnings();

    return {
        element,
        cleanup() {
            // SPA 페이지 이동 시 진행 중인 조회의 화면 반영을 차단합니다.
            disposed = true;
            requestVersion += 1;
            clearInterval(intervalId);

            if (dialog.open) dialog.close();

            // 화면 종료 시 경고 필터 Form의 이벤트를 정리합니다.
            warningFilterForm.removeEventListener('submit', onWarningFilterSubmit);
            refreshButton.removeEventListener('click', onRefresh);
            prevButton.removeEventListener('click', onPrev);
            nextButton.removeEventListener('click', onNext);
            transferForm.removeEventListener('submit', onTransferSubmit);
            confirmForm.removeEventListener('submit', onConfirmSubmit);
            cancelButton.removeEventListener('click', onCancel);
            dialog.removeEventListener('cancel', onDialogCancel);
            dialog.removeEventListener('close', onDialogClose);
            document.removeEventListener('visibilitychange', onVisibilityChange);
        },
    };
}
