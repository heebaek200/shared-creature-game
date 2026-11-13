// @ts-check
import {getAdminDashboard} from "../features/admin/api.js";
import {ApiError} from '../api/client.js';
import {renderDashboardCharts} from "../features/admin/chart-renderer.js";

const numberFormat = new Intl.NumberFormat('ko-KR');
const stageNames = {
    EGG: '알',
    HATCHLING: '유년기',
    GROWING: '성장기',
    MATURE: '성숙기',
    COMPLETE: '완전체'
};

/**
 * 표의 열 제목과 행 데이터로 안전한 DOM 테이블을 생성한다.
 * 서버 문자열을 HTML로 해석하지 않고 textContent로 삽입한다.
 * @param {string[]} headers 표 제목
 * @param {Array<Array<string|number|null|undefined>>} rows 표의 각 행
 * @returns {HTMLTableElement} 완성한 표 요소
 */
function createDataTable(headers, rows) {
    const table = document.createElement('table');
    table.className = 'table table-sm table-striped align-middle mb-0';
    const thead = document.createElement('thead');
    const headerRow = document.createElement('tr');

    for (const label of headers) {
        const th = document.createElement('th');
        th.scope = 'col';
        th.textContent = label;
        headerRow.append(th);
    }
    thead.append(headerRow);

    const tbody = document.createElement('tbody');
    if (rows.length === 0) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = headers.length;
        td.textContent = '조회 결과가 없습니다.';
        tr.append(td);
        tbody.append(tr);
    }

    for (const values of rows) {
        const tr = document.createElement('tr');
        for (const value of values) {
            const td = document.createElement('td');
            td.textContent = value == null ? '-' : String(value);
            tr.append(td);
        }
        tbody.append(tr);
    }
    table.append(thead, tbody);
    return table;
}

/**
 * 관리자 대시보드 Page를 생성하고 API 조회를 시작한다.
 * @returns {{element: HTMLElement, cleanup: () => void}} Page 계약 객체
 */
export function adminDashboardPage() {
    const element = document.createElement('section');
    element.className = 'container-fluid px-3 px-md-4 py-4';
    element.innerHTML = `
    <header class="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-4">
      <div>
        <h1 class="h3 fw-bold mb-1">전체 관리자 대시보드</h1>
        <p class="text-body-secondary mb-0">서버 집계 현황 및 운영 대기열</p>
      </div>
      <div class="text-body-secondary small" data-aggregated-at>집계 시각: -</div>
    </header>

    <form data-dashboard-filters class="row g-3 align-items-end mb-3">
      <div class="col-12 col-sm-4 col-lg-3">
        <label class="form-label" for="admin-range">조회 기간</label>
        <select id="admin-range" class="form-select" data-range>
          <option value="7_DAYS">최근 7일</option>
          <option value="30_DAYS">최근 30일</option>
          <option value="CURRENT_SEASON">현재 시즌</option>
        </select>
      </div>
      <div class="col-12 col-sm-4 col-lg-3">
        <label class="form-label" for="admin-group">그룹 ID (비우면 전체)</label>
        <input id="admin-group" class="form-control" data-group-id
               type="number" min="1" step="1" placeholder="전체 그룹" />
      </div>
      <div class="col-12 col-sm-4 col-lg-auto d-flex gap-2">
        <button type="submit" class="btn btn-primary">조건 조회</button>
        <button type="button" class="btn btn-outline-secondary" data-refresh>새로고침</button>
      </div>
    </form>

    <div class="alert alert-info mb-3" role="status" aria-live="polite" data-status>
      관리자 집계를 불러오는 중입니다.
    </div>
    <div class="row row-cols-1 row-cols-sm-2 row-cols-xl-3 g-3 mb-4" data-summary></div>
    <div class="row row-cols-1 row-cols-xl-2 g-3 mb-4" data-chart-sections></div>

    <section class="card mb-3">
      <div class="card-body">
        <h2 class="h5">최근 관리 대기 목록</h2>
        <div class="mb-3" data-pending-counts></div>
        <div class="d-flex flex-wrap gap-3 mb-3">
          <a href="/admin/reports" data-link>신고 관리</a>
          <a href="/admin/refunds" data-link>환불 관리</a>
          <a href="/admin/operations" data-link>운영 관리</a>
        </div>
        <h3 class="h6">현재 운영 경고 (최근 10건)</h3>
        <div class="table-responsive" data-pending-warnings></div>
      </div>
    </section>
    <p class="small text-body-secondary" data-chart-status aria-live="polite"></p>
    `;

    // 업무 화면과 공통 Shell을 분리할 수 있도록 차트 섹션만 생성
    const chartSections = [
        ['activity', '활동·퀘스트 추이'],
        ['growth', '성장 단계 분포'],
        ['top-groups', '그룹 TOP 10'],
        ['payments', '결제·환불 추이'],
        ['capacity', '그룹 정원 사용률 분포']
    ];

    const chartGrid = element.querySelector('[data-chart-sections]');
    for (const [key, title] of chartSections) {
        const section = document.createElement('section');
        section.className = 'col';
        section.innerHTML = `
          <div class="card h-100">
            <div class="card-body">
              <h2 class="h5"></h2>
              <div data-admin-chart="${key}" style="width:100%; height:280px;"
                   role="img" aria-label="${title} 차트">차트 로딩 중...</div>
              <h3 class="h6 mt-3">집계 데이터 표</h3>
              <div class="table-responsive" data-admin-table="${key}"></div>
            </div>
          </div>
        `;
        section.querySelector('h2').textContent = title;
        chartGrid.append(section);
    }


    const filterForm = element.querySelector('[data-dashboard-filters]');
    const rangeInput = /** @type {HTMLSelectElement} */ (element.querySelector('[data-range]'));
    const groupInput = /** @type {HTMLInputElement} */ (element.querySelector('[data-group-id]'));
    const refreshButton = /** @type {HTMLButtonElement} */ (element.querySelector('[data-refresh]'));
    const status = element.querySelector('[data-status]');
    const chartStatus = element.querySelector('[data-chart-status]');

    let disposed = false;
    let requestVersion = 0;
    let disposeChart = () => {};

    /**
     * 조회 중 성공, 실패 메시지를 한 영역에서 표시한다.
     * API 오류일 때는 기존 성공 상태와 구분해 안내한다.
     * @param {string} message 표시할 메시짖
     * @param {'info'|'success'|'danger'} tone Bootstrap 알림 스타일
     */
    function showStatus(message, tone = 'info') {
        status.className = `alert alert-${tone} mb-3`;
        status.textContent = message;
    }

    /**
     * 화면에 표시할 그룹 ID를 검증하고 숫자로 변환한다.
     * 빈 값은 전체 그룹 조회를 뜻하므로 undefined를 반환한다.
     * @returns {number|undefined} 그룹ID 또는 전체 그룹
     */
    function readGroupId() {
        const value = groupInput.value.trim();
        if (value === '') return undefined;
        const parsed = Number(value);
        if (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(parsed)) {
            throw new Error('그룹 ID는 1 이상의 정수여야 합니다.');
        }
        return parsed;
    }

    /**
     * 서버 요약 통계와 관리 대기 건수를 카드와 문구로 표시한다.
     * 숫자 형식만 변환하고 비율과 집계 기준은 서버 값을 그대로 사용한다.
     * @param {any} dashboard 서버 대시보드 데이터
     * @returns {void}
     */
    function renderSummary(dashboard) {
        const summary = dashboard.summary;
        const queues = dashboard.queues;
        const cards = [
            ['기간 활동 사용자', `${numberFormat.format(summary.activeUsers)}명`],
            ['활성 그룹', `${numberFormat.format(summary.activeGroups)}개`],
            ['퀘스트 완료율', `${summary.questCompletionRate}%`],
            ['성공 결제액', `${numberFormat.format(summary.paidAmountKrw)}원`],
            ['성공 환불액', `${numberFormat.format(summary.refundedAmountKrw)}원`],
            ['현재 정원 사용률', `${summary.capacityUsageRate}%`],
            ['미해결 운영 경고', `${numberFormat.format(summary.operationalWarningCount)}건`],
        ];

        const summaryRoot = element.querySelector('[data-summary]');
        summaryRoot.replaceChildren();
        for (const [label, value] of cards) {
            const column = document.createElement('div');
            column.className = 'col';

            const card = document.createElement('div');
            card.className = 'card h-100';

            const body = document.createElement('div');
            body.className = 'card-body';

            const labelNode = document.createElement('p');
            labelNode.className = 'small text-body-secondary mb-1';
            labelNode.textContent = label;

            const valueNode = document.createElement('strong');
            valueNode.className = 'fs-4';
            valueNode.textContent = value;

            body.append(labelNode, valueNode);
            card.append(body);
            column.append(card);
            summaryRoot.append(column);
        }

        const pending = element.querySelector('[data-pending-counts]');
        pending.textContent
            = `미처리 신고 ${numberFormat.format(queues.pendingReports)}건 · `
            + `환불 대기 ${numberFormat.format(queues.pendingRefunds)}건`;

        const warnings = queues.operationalWarnings.map(item => [
            item.severity, item.type, item.message
        ]);
        element.querySelector('[data-pending-warnings]').replaceChildren(
            createDataTable(
                ['심각도', '유형', '내용']
                , warnings
            )
        );

        element.querySelector('[data-aggregated-at]').textContent
            = '집계 시각: ' + new Intl.DateTimeFormat('ko-KR', {
            timeZone: 'Asia/Seoul',
            dateStyle: 'medium',
            timeStyle: 'medium',
        }).format(new Date(dashboard.aggregatedAt)) + ' KST';
    }

    /**
     * 차트에 대응하는 서버 원본 데이터 표를 표시한다.
     * Google Charts 로딩 여부와 관계없이 표는 항상 유지한다
     * @param {any} data 서버 집계 데이터
     * @returns {void}
     */
    function renderTables(data) {
        const definitions = [
            [
                'activity',
                ['날짜', '활동 사용자', '퀘스트 완료'],
                data.activitySeries.map(x => [
                    x.date,
                    numberFormat.format(x.activeUsers),
                    numberFormat.format(x.questCompletions)
                ])
            ],

            [
                'growth',
                ['성장 단계', '그룹 수'],
                data.growthStages.map(x => [
                    stageNames[x.stage] ?? x.stage,
                    numberFormat.format(x.count)
                ])
            ],

            [
                'top-groups',
                ['그룹', '시즌 점수'],
                data.topGroups.map(x => [
                    x.groupName,
                    numberFormat.format(x.score)
                ])
            ],

            [
                'payments',
                ['날짜', '결제(원)', '환불(원)'],
                data.paymentSeries.map(x => [
                    x.date,
                    numberFormat.format(x.paidAmountKrw),
                    numberFormat.format(x.refundedAmountKrw)
                ])
            ],

            [
                'capacity',
                ['정원 구분', '인원'],
                [
                    ['사용', numberFormat.format(data.capacityUsage.occupied)],
                    ['잔여', numberFormat.format(data.capacityUsage.remaining)]
                ]
            ]

        ];

        for (const [key, headers, rows] of definitions) {
            const container = element.querySelector(`[data-admin-table="${key}"]`);
            container.replaceChildren(createDataTable(headers, rows));
        }
    }

    /**
     * 현재 필터로 서버 통계를 요청하고 Page를 최신 결과로 갱신한다.
     * 이전 응답과 차트 로딩 완료가 늦게 도착해도 최신 화면을 덮지 않는다.
     * @returns {Promise<void>} 조회 완료 시 종료
     */
    async function loadDashboard() {
        const version = ++requestVersion;
        let groupId;
        try {
            groupId = readGroupId();
        } catch (error) {
            showStatus(error instanceof Error ? error.message : '그룹 ID를 확인해 주세요.', 'danger');
            refreshButton.disabled = false;
            return;
        }

        refreshButton.disabled = true;
        showStatus('대시보드 데이터를 불러오는 중입니다.');

        try {
            const dashboard = await getAdminDashboard({
                range: rangeInput.value,
                groupId
            });
            if (disposed || version !== requestVersion) return;

            // 데이터 조회는 차트 로딩보다 먼저 확정하고 즉시 표시한다.
            disposeChart();
            disposeChart = () => {};
            renderSummary(dashboard);
            renderTables(dashboard);
            chartStatus.textContent = '차트 로딩 중...';
            showStatus('서버 집계 데이터를 표시했습니다.', 'success');

            try {
                disposeChart = await renderDashboardCharts(
                    element,
                    dashboard,
                    () => ! disposed && version === requestVersion
                );

                if (!disposed && version === requestVersion) {
                    chartStatus.textContent = '';
                }
            } catch (error) {
                if (! disposed && version === requestVersion) {
                    element.querySelectorAll('[data-admin-chart]').forEach(node => {
                        node.textContent = '차트를 표시할 수 없습니다.';
                    });
                    chartStatus.textContent
                        = '차트 로딩에 실패했습니다. 아래 집계 데이터 표는 계속 확인할 수 있습니다.';
                }

            }
        } catch (error) {
            if (disposed || version !== requestVersion) return;
            const message =
                error instanceof ApiError && error.code === 'CONCURRENT_MODIFICATION'
                ? '현재 정상적으로 진행 중인 시즌이 없어 시즌 통계를 조회할 수 없습니다.'
                : error instanceof Error ? error.message : '관리자 집계를 조회하지 못했습니다.';
            showStatus(message, 'danger');
            chartStatus.textContent = '조회 실패: 마지막으로 성공한 집계가 표시되어 있을 수 있습니다.';
        } finally {
            if (!disposed && version == requestVersion) {
                refreshButton.disabled = false;
            }
        }
    }

    /**
     * 기본 폼 제출을 막고 조건에 따라 서버 집계를 다시 조회한다.
     * 입력값 검증과 실제 요청은 loadDashboard에 위임한다.
     * @param {SubmitEvent} event 폼 제출 이벤트
     * @returns {void}
     */
    function onSubmit(event) {
        event.preventDefault();
        void loadDashboard();
    }

    const onFilterChange = () => {void loadDashboard();};
    const onRefresh = () => {void loadDashboard();};
    const onVisibilityChange = () => {
        if (document.visibilityState === 'visible') void loadDashboard();
    };

    filterForm.addEventListener('submit', onSubmit);
    rangeInput.addEventListener('change', onFilterChange);
    groupInput.addEventListener('change', onFilterChange);
    refreshButton.addEventListener('click', onRefresh);
    document.addEventListener('visibilitychange', onVisibilityChange);

    // 숨겨진 탭에서는 서버 조회를 생략하고 다시 보일 때 갱신한다.
    const intervalId = setInterval(() => {
        if (document.visibilityState === 'visible') void loadDashboard();
    }, 60_000);

    void loadDashboard();

    return {
        element,
        cleanup() {
            disposed = true;
            requestVersion += 1;
            clearInterval(intervalId);
            disposeChart();
            filterForm.removeEventListener('submit', onSubmit);
            rangeInput.removeEventListener('change', onFilterChange);
            groupInput.removeEventListener('change', onFilterChange);
            refreshButton.removeEventListener('click', onRefresh);
            document.removeEventListener('visibilitychange', onVisibilityChange);
        }
    };
}





