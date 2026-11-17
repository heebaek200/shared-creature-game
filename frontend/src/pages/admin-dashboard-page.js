// @ts-check
import {getAdminDashboard, searchAdminGroups} from "../features/admin/api.js";
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
  
    <form data-dashboard-filters
      class="row g-3 align-items-start mb-3">

        <!-- 조회 기간 -->
        <div class="col-12 col-md-6 col-xl-2">
            <label class="form-label" for="admin-range">
                조회 기간
            </label>
            <select id="admin-range" class="form-select" data-range>
                <option value="7_DAYS">최근 7일</option>
                <option value="30_DAYS">최근 30일</option>
                <option value="CURRENT_SEASON">현재 시즌</option>
            </select>
        </div>
    
        <!-- 그룹 이름 검색 -->
        <div class="col-12 col-md-6 col-xl-4">
            <label class="form-label" for="admin-group-search">
                그룹 이름 검색
            </label>
            <div class="input-group">
                <input id="admin-group-search"
                       type="text"
                       maxlength="30"
                       class="form-control"
                       placeholder="그룹 이름 입력"
                       data-group-search>
                <button type="button"
                        class="btn btn-outline-secondary"
                        data-group-search-button>
                    검색
                </button>
            </div>
        </div>
    
        <!-- 그룹 검색 결과 선택 -->
        <div class="col-12 col-md-6 col-xl-3">
            <label class="form-label" for="admin-group">
                조회 대상 그룹
            </label>
            <select id="admin-group"
                    class="form-select"
                    data-group-select>
                <option value="">전체 그룹</option>
            </select>
            <div class="form-text"
                 role="status"
                 aria-live="polite"
                 data-group-search-message>
                그룹 이름으로 검색 후 선택하세요.
            </div>
        </div>
    
        <!-- 실제 대시보드 조회 -->
        <div class="col-12 col-md-6 col-xl-3 pt-md-4">
            <div class="d-flex flex-wrap gap-2">
                <button type="submit" class="btn btn-primary">
                    조건 조회
                </button>
                <button type="button"
                        class="btn btn-outline-secondary"
                        data-refresh>
                    새로고침
                </button>
            </div>
        </div>

    </form>

    <div class="alert alert-info mb-3" role="status" aria-live="polite" data-status>
      관리자 집계를 불러오는 중입니다.
    </div>
    <div class="row row-cols-1 row-cols-sm-2 row-cols-xl-3 g-3 mb-3" data-summary></div>
    
    <!-- 기간 활동 통계와 현재 상태 통계의 차이를 명시합니다. -->
    <p class="small text-body-secondary mb-4">
        활동 사용자·그룹은 선택한 기간의 활동 기록 기준이며,
        이후 탈퇴·강퇴한 사용자의 과거 활동도 포함됩니다.
        정원 사용률은 현재 활성 그룹의 회원 수를 기준으로 합니다.
    </p>
    
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
        ['capacity', '현재 그룹 정원 사용 현황']
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
    const groupSearchInput = /** @type {HTMLInputElement} */ (
        element.querySelector('[data-group-search]')
    );
    const groupSearchButton = /** @type {HTMLButtonElement} */ (
        element.querySelector('[data-group-search-button]')
    );
    const groupSelect = /** @type {HTMLSelectElement} */ (
        element.querySelector('[data-group-select]')
    );
    const groupSearchMessage = /** @type {HTMLElement} */ (
        element.querySelector('[data-group-search-message]')
    );
    const refreshButton = /** @type {HTMLButtonElement} */ (element.querySelector('[data-refresh]'));
    const status = element.querySelector('[data-status]');
    const chartStatus = element.querySelector('[data-chart-status]');

    let disposed = false;
    let requestVersion = 0;
    let disposeChart = () => {};
    // 검색 결과가 늦게 도착하거나 화면을 벗어났을 때 무시합니다.
    let groupSearchVersion = 0;
    let groupSearchBusy = false;

    // 자동 갱신에는 마지막으로 확정한 조회 조건을 사용합니다.
    // 화면에 입력 중인 값은 submit 전까지 API 요청에 반영하지 않습니다.
    let appliedFilters = {
        range: rangeInput.value,
        groupId: undefined
    };

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
     * 관리자가 선택한 그룹의 ID를 반환합니다.
     * 전체 그룹 선택 시 undefined를 반환해 기존 API 계약을 유지합니다.
     * 선택 옵션에 저장된 값도 안전한 양의 정수인지 확인합니다.
     *
     * @returns {number|undefined} 선택한 그룹 ID 또는 전체 그룹
     */
    function readGroupId() {
        const value = groupSelect.value;

        // 빈 값은 전체 그룹 조회를 의미합니다.
        if (value === '') return undefined;

        const groupId = Number(value);

        if (!Number.isSafeInteger(groupId) || groupId <= 0) {
            throw new Error('조회할 그룹을 다시 선택해 주세요.');
        }

        return groupId;
    }

    /**
     * 입력한 그룹 이름으로 관리자 그룹 검색 API를 호출합니다.
     * 검색 결과를 안전한 option 요소로 생성해 셀렉트박스에 표시합니다.
     * 이전 검색 응답과 화면 종료 후 응답은 무시합니다.
     *
     * @returns {Promise<void>} 그룹 검색 완료
     */
    async function onGroupSearch() {
        const query = groupSearchInput.value.trim();

        // 백엔드의 검색어 최대 길이 계약을 지킵니다.
        if (query.length > 30) {
            groupSearchMessage.textContent = '그룹 이름은 30자 이하로 입력해 주세요.';
            return;
        }

        const version = ++groupSearchVersion;
        groupSearchBusy = true;
        groupSearchButton.disabled = true;
        groupSearchMessage.textContent = '그룹을 검색하고 있습니다.';

        try {
            const result = await searchAdminGroups({
                q: query,
                page: 0,
                size: 50
            });

            if (disposed || version !== groupSearchVersion) return;

            const fragment = document.createDocumentFragment();

            // 검색 결과와 관계없이 전체 그룹 선택을 제공합니다.
            fragment.append(new Option('전체 그룹', ''));

            for (const group of result.data) {
                const label =
                    `${group.name} (#${group.groupId}) · ` +
                    `${group.currentMemberCount}/${group.capacity}명`;

                fragment.append(
                    new Option(label, String(group.groupId))
                );
            }

            // 기존 검색 결과를 새 결과로 교체합니다.
            groupSelect.replaceChildren(fragment);

            const total = result.meta.totalElements;
            groupSearchMessage.textContent = total > 50
                ? `총 ${total}개 그룹 중 50개를 표시합니다. 검색어를 좁혀 주세요.`
                : `검색된 그룹 ${total}개. 조회할 그룹을 선택하세요.`;
        } catch (error) {
            if (disposed || version !== groupSearchVersion) return;

            groupSearchMessage.textContent =
                error instanceof Error
                    ? `그룹 검색 실패: ${error.message}`
                    : '그룹 검색에 실패했습니다.';
        } finally {
            if (!disposed && version === groupSearchVersion) {
                groupSearchBusy = false;
                groupSearchButton.disabled = false;
            }
        }
    }

    /**
     * 그룹 검색 입력창에서 Enter 키를 처리합니다.
     * 상위 대시보드 Form의 조건 조회가 실행되지 않도록 차단합니다.
     * Enter 키는 그룹 검색 버튼을 누른 것과 같은 동작을 수행합니다.
     *
     * @param {KeyboardEvent} event 키보드 이벤트
     * @returns {void}
     */
    function onGroupSearchKeydown(event) {
        if (event.key !== 'Enter') return;

        event.preventDefault();

        if (!groupSearchBusy) {
            void onGroupSearch();
        }
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
            // 선택 기간에 활동 기록이 존재한 사용자를 집계합니다.
            ['조회 기간 활동 사용자', `${numberFormat.format(summary.activeUsers)}명`],
            ['조회 기간 활동 그룹', `${numberFormat.format(summary.activeGroups)}개`],

            // 나머지 기존 통계는 계산 방식과 표시 값을 유지합니다.
            ['퀘스트 완료율', `${summary.questCompletionRate}%`],
            ['성공 결제액', `${numberFormat.format(summary.paidAmountKrw)}원`],
            ['성공 환불액', `${numberFormat.format(summary.refundedAmountKrw)}원`],

            // 정원 사용률은 조회 기간이 아닌 현재 그룹 상태를 기준으로 합니다.
            ['현재 활성 그룹 정원 사용률', `${summary.capacityUsageRate}%`],
            ['미해결 운영 경고', `${numberFormat.format(summary.operationalWarningCount)}건`]
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
                    ['현재 회원', numberFormat.format(data.capacityUsage.occupied)],
                    ['잔여 정원', numberFormat.format(data.capacityUsage.remaining)]
                ]
            ]

        ];

        for (const [key, headers, rows] of definitions) {
            const container = element.querySelector(`[data-admin-table="${key}"]`);
            container.replaceChildren(createDataTable(headers, rows));
        }
    }

    /**
     * 마지막으로 확정한 필터를 이용해 대시보드 데이터를 조회합니다.
     * applyForm이 true이면 현재 폼의 값을 검증하고 조회 조건에 반영합니다.
     * 자동 갱신과 새로고침은 확정된 조건을 그대로 재사용합니다.
     *
     * @param {boolean} [applyForm=false] 현재 폼의 필터를 적용할지 여부
     * @returns {Promise<void>} 대시보드 조회 완료
     */
    async function loadDashboard(applyForm = false) {
        const version = ++requestVersion;

        // 조회 버튼을 눌렀을 때만 입력한 조건을 확정합니다.
        if (applyForm) {
            let groupId;

            try {
                groupId = readGroupId();
            } catch (error) {
                showStatus(
                    error instanceof Error
                        ? error.message
                        : '그룹 ID를 확인해 주세요.',
                    'danger'
                );
                refreshButton.disabled = false;
                return;
            }

            appliedFilters = {
                range: rangeInput.value,
                groupId
            };
        }

        refreshButton.disabled = true;
        showStatus('대시보드 데이터를 불러오는 중입니다.');

        try {
            // 입력 중인 값이 아니라 확정된 필터로 요청합니다.
            const dashboard = await getAdminDashboard(appliedFilters);
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
     * 조건 조회 버튼으로 제출된 필터를 적용합니다.
     * 기본 HTML Form 제출에 따른 페이지 이동을 차단합니다.
     * 입력값 검증과 API 요청은 loadDashboard에 위임합니다.
     *
     * @param {SubmitEvent} event 조회 폼 제출 이벤트
     * @returns {void}
     */
    function onSubmit(event) {
        event.preventDefault();

        // 그룹 검색이 완료되기 전에는 조건을 확정하지 않습니다.
        if (groupSearchBusy) {
            showStatus('그룹 검색이 끝난 뒤 조건 조회를 진행해 주세요.');
            return;
        }

        void loadDashboard(true);
    }

    // 수동 새로고침은 기존 조회 조건을 유지합니다.
    const onRefresh = () => {
        void loadDashboard();
    };

    const onVisibilityChange = () => {
        if (document.visibilityState === 'visible') void loadDashboard();
    };

    filterForm.addEventListener('submit', onSubmit);
    refreshButton.addEventListener('click', onRefresh);
    groupSearchButton.addEventListener('click', onGroupSearch);
    groupSearchInput.addEventListener('keydown', onGroupSearchKeydown);
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
            refreshButton.removeEventListener('click', onRefresh);
            // 화면 종료 후 검색 응답과 이벤트가 남지 않도록 정리합니다.
            groupSearchVersion += 1;
            groupSearchButton.removeEventListener('click', onGroupSearch);
            groupSearchInput.removeEventListener('keydown', onGroupSearchKeydown);
            document.removeEventListener('visibilitychange', onVisibilityChange);
        }
    };
}





