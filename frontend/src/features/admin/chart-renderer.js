// @ts-check

// 여러 차트가 동일하게 공유하는 공통 Google Chart 로더
let chartsLoadPromise = null;

/**
 * Google Charts 로더와 corechart 패키지를 한 번만 불러온다.
 * 동시 호출은 동일한 Promise를 재사용하고, 실패 시 재시도를 허용한다.
 *
 * @returns {Promise<any>} Google Charts 전역 객체
 */
function loadGoogleCharts() {
    if (chartsLoadPromise) {
        return chartsLoadPromise;
    }

    chartsLoadPromise = new Promise((resolve, reject) => {
        const onLoaderReady = () => {
            const google = /** @type {any} */(window).google;

            if (! google?.charts?.load) {
                reject(new Error('Google Charts 로더를 확인할 수 없습니다.'));
                return;
            }

            // 패키지 로드가 끝나야 visualization API를 사용할 수 있습니다.
            google.charts.load('current', {
                packages: ['corechart'],
                language: 'ko'
            }).then(() => resolve(google), reject);
        };

        if (/** @type {any} */(window).google?.charts?.load) {
            onLoaderReady();
            return;
        }


        const script = document.createElement('script');
        script.src = 'https://www.gstatic.com/charts/loader.js';
        script.async = true;
        script.onload = onLoaderReady;
        script.onerror = () => {
            script.remove();
            reject(new Error('Google Charts 스크립트를 다운로드하지 못했습니다.'));
        };
        document.head.append(script);
    }).catch(error => {
        chartsLoadPromise = null;
        throw error;
    });

    return chartsLoadPromise;
}

/**
 * 대시보드 데이터로 다섯 종류의 차트를 렌더링한다.
 * 화면 종료 또는 새 조회를 감지해 이전 차트 갱신을 방지한다.
 * @param {HTMLElement} root 대시보드 Page 요소
 * @param {any} dashboard 서버 집계 응답의 data
 * @param {() => boolean} isCurrent 최신 요청, 마운트 여부
 * @returns {Promise<() => void>} 차트 리스너 정리 함수
 */
export async function renderDashboardCharts(root, dashboard, isCurrent) {
    const google = await loadGoogleCharts();
    if (! isCurrent()) return () => {};

    const stageName = {
        EGG: '알',
        HATCHLING: '유년기',
        GROWING: '성장기',
        MATURE: '성숙기',
        COMPLETE: '완전체'
    };

    // 원본 데이터를 재집계하지 않고 차트 입력 행으로만 변환한다.
    const definitions = [
        {
            // 차트 1
            key: 'activity',
            type: 'LineChart',
            rows: [
                ['날짜', '활동 사용자', '퀘스트 완료'],
                ... dashboard.activitySeries.map(item => [
                    item.date,
                    Number(item.activeUsers),
                    Number(item.questCompletions)
                ])
            ],
        },

        {
            // 차트 2
            key: 'growth',
            type: 'ColumnChart',
            rows: [
                ['성장 단계', '그룹 수'],
                ... dashboard.growthStages.map(item => [
                    stageName[item.stage] ?? item.stage,
                    Number(item.count)
                ])
            ]
        },

        {
            // 차트 3
            key: 'top-groups',
            type: 'BarChart',
            rows: [
                ['그룹', '시즌 점수'],
                ... dashboard.topGroups.map(item => [
                    item.groupName,
                    Number(item.score)
                ])
            ]
        },

        {
            // 차트 4
            key: 'payments',
            type: 'ComboChart',
            rows: [
                ['날짜', '결제(원)', '환불(원)'],
                ... dashboard.paymentSeries.map(item => [
                    item.date,
                    Number(item.paidAmountKrw),
                    Number(item.refundedAmountKrw)
                ])
            ]
        },

        {
            // 차트 5
            key: 'capacity',
            type: 'PieChart',
            rows: [
                ['정원 구분', '인원'],
                ['사용', Number(dashboard.capacityUsage.occupied)],
                ['잔여', Number(dashboard.capacityUsage.remaining)]
            ]
        },

    ];

    /** @type {Array<{chart: any, node: HTMLElement, data: any, definition: any}>} */
    const charts = [];

    // 데이터가 없는 차트는 표를 유지하고 빈 상태 안내만 표시한다.
    for (const definition of definitions) {
        const node = root.querySelector(`[data-admin-chart="${definition.key}"]`);
        if (! (node instanceof HTMLElement)) continue;

        const noData = definition.rows.length <= 1 ||
            (definition.key === 'capacity' && dashboard.capacityUsage.occupied + dashboard.capacityUsage.remaining === 0);

        if (noData) {
            node.textContent = '표시할 차트 데이터가 없습니다.';
            continue;
        }

        const data = google.visualization.arrayToDataTable(definition.rows);
        const chart = new google.visualization[definition.type](node);
        charts.push({chart, node, data, definition});
    }

    /**
     * 현재 테마와 컨테이너 너비에 맞게 차트를 다시 그린다.
     * 화면 숨김, 종료, 요청 변경 후에는 렌더링하지 않는다.
     * @returns {void}
     */
    function drawCharts() {
        if (! isCurrent()) return;

        const css = getComputedStyle(document.documentElement);
        const textColor = css.getPropertyValue('--app-text').trim() || '#222';

        for (const {chart, node, data, definition} of charts) {
            if (! node.isConnected || node.clientWidth === 0) continue;

            chart.draw(data, {
                backgroundColor: 'transparent',
                height: 280,
                chartArea: {
                    width: '72%',
                    height: '67%'
                },
                legend: {
                    position: 'bottom',
                    textStyle: {color: textColor}
                },
                hAxis: {
                    textStyle: {color: textColor}
                },
                vAxis: {
                    textStyle: {color: textColor},
                    minValue: 0
                },

                ... (definition.key === 'payments'
                    ? {
                        seriesType: 'bars',
                        series: {
                            1: {type: 'line'}
                        }
                    } : {}
                ),

                ... (definition.key === 'capacity'
                    ? {
                        pieHole: 0.55,
                        pieSliceText: 'value'
                    } : {}
                )
            });
        }
    }

    drawCharts();

    // 뷰포트 크기 및 테마 변경 시 현재 데이터를 다시 그린다.
    let resizeTimer = null;
    const scheduleDraw = () => {
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(drawCharts, 150);
    };

    const themeObserver = new MutationObserver(scheduleDraw);
    themeObserver.observe(document.documentElement, {
        attributes: true,
        attributeFilter: ['data-bs-theme']
    });
    window.addEventListener('resize', scheduleDraw);

    return () => {
        window.removeEventListener('resize', scheduleDraw);

        themeObserver.disconnect();
        clearTimeout(resizeTimer);
    };
}


