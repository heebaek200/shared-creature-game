// @ts-check
import { createAppShell } from '../components/app-shell.js';
import { getGroupRanking, getMemberRanking } from '../features/ranking/api.js';
import { listRankableSeasons } from '../features/season/api.js';

const PAGE_SIZE = 20;
const PARTICIPANT_LABELS = {
  ACTIVE: '활동 중',
  LEFT: '탈퇴',
  REMOVED: '강퇴',
};

/** 개인·그룹 시즌 랭킹과 현재 사용자의 전체 순위 요약을 구성합니다. */
export function rankingsPage({ navigate, currentUser }) {
  const content = document.createElement('div');
  content.className = 'rankings-page container-fluid py-4';
  content.innerHTML = `
    <div class="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-4">
      <div>
        <p class="text-app-primary fw-semibold mb-1">RANKINGS</p>
        <h1 class="h2 mb-1">시즌 랭킹</h1>
        <p class="text-app-muted mb-0">서버에서 확정한 점수와 순서를 그대로 표시합니다.</p>
      </div>
      <label class="ranking-season-field">
        <span class="form-label fw-semibold">시즌</span>
        <select class="form-select" data-season-select aria-label="랭킹 시즌 선택"></select>
      </label>
    </div>
    <div class="alert d-none" role="status" aria-live="polite" data-ranking-feedback></div>
    <div class="nav nav-pills mb-4" role="tablist" aria-label="랭킹 종류" data-ranking-tabs>
      <button type="button" class="nav-link active" role="tab" aria-selected="true" data-kind="members">그룹 내 개인</button>
      <button type="button" class="nav-link" role="tab" aria-selected="false" data-kind="groups">전체 그룹</button>
    </div>
    <section class="app-surface-card border border-app p-3 p-md-4" aria-live="polite">
      <div class="ranking-current-summary mb-4" data-current-summary></div>
      <div class="table-responsive">
        <table class="table align-middle mb-0 ranking-table">
          <thead data-ranking-head></thead>
          <tbody data-ranking-body></tbody>
        </table>
      </div>
      <div class="d-flex flex-wrap justify-content-between align-items-center gap-3 mt-4" data-pagination></div>
    </section>
  `;

  const element = createAppShell({
    content,
    currentPath: location.pathname,
    currentUser,
    navigate,
  });
  const seasonSelect = content.querySelector('[data-season-select]');
  const feedback = content.querySelector('[data-ranking-feedback]');
  const summary = content.querySelector('[data-current-summary]');
  const tableHead = content.querySelector('[data-ranking-head]');
  const tableBody = content.querySelector('[data-ranking-body]');
  const pagination = content.querySelector('[data-pagination]');

  let mounted = true;
  let loadVersion = 0;
  let kind = 'members';
  let page = 0;
  let selectedSeasonId = null;
  let seasons = [];

  /** 시즌 선택 목록을 불러온 뒤 기본 시즌의 랭킹을 조회합니다. */
  async function initialize() {
    setLoading();
    try {
      const result = await listRankableSeasons();
      if (!mounted) return;
      seasons = Array.isArray(result) ? result : [];
      renderSeasonOptions();
      if (seasons.length === 0) {
        renderEmpty('조회할 수 있는 현재 또는 종료 시즌이 없습니다.');
        return;
      }
      selectedSeasonId = seasons[0].seasonId;
      if (seasonSelect instanceof HTMLSelectElement) {
        seasonSelect.value = String(selectedSeasonId);
      }
      await loadRanking();
    } catch (error) {
      if (!mounted) return;
      handleError(error, '시즌 목록을 불러오지 못했습니다.');
      renderRetry();
    }
  }

  /** 현재·종료 상태와 기간을 함께 표시하는 시즌 선택지를 구성합니다. */
  function renderSeasonOptions() {
    if (!(seasonSelect instanceof HTMLSelectElement)) return;
    seasonSelect.replaceChildren();
    seasons.forEach((season) => {
      const option = document.createElement('option');
      option.value = String(season.seasonId);
      option.textContent = `시즌 ${season.seasonNumber} · ${season.status === 'ACTIVE' ? '진행 중' : '종료'}`;
      seasonSelect.append(option);
    });
    seasonSelect.disabled = seasons.length < 2;
  }

  /** 선택한 종류·시즌·페이지의 랭킹을 서버에서 조회합니다. */
  async function loadRanking() {
    if (selectedSeasonId == null) return;
    const version = ++loadVersion;
    setLoading();
    hideFeedback();
    try {
      const loader = kind === 'members' ? getMemberRanking : getGroupRanking;
      const result = await loader({ seasonId: selectedSeasonId, page, size: PAGE_SIZE });
      if (!mounted || version !== loadVersion) return;
      const data = Array.isArray(result?.data) ? result.data : [];
      const meta = result?.meta ?? {};
      renderRanking(data, meta);
    } catch (error) {
      if (!mounted || version !== loadVersion) return;
      handleError(error, '랭킹을 불러오지 못했습니다.');
      renderRetry();
    }
  }

  /** 랭킹 표, 현재 사용자·그룹 요약과 페이지 이동을 표시합니다. */
  function renderRanking(rows, meta) {
    renderTableHead();
    if (tableBody instanceof HTMLElement) tableBody.replaceChildren();
    renderCurrentSubject(meta.currentSubject);

    if (rows.length === 0) {
      renderEmpty('선택한 시즌에 표시할 랭킹이 없습니다.');
    } else {
      rows.forEach((row) => tableBody?.append(createRankingRow(row)));
    }
    renderPagination(meta);
  }

  /** 개인·그룹 응답 필드 차이에 맞는 표 머리글을 구성합니다. */
  function renderTableHead() {
    if (!(tableHead instanceof HTMLElement)) return;
    const row = document.createElement('tr');
    ['순위', kind === 'members' ? '사용자' : '그룹', '점수']
        .concat(kind === 'members' ? ['상태'] : [])
        .forEach((label) => {
          const cell = document.createElement('th');
          cell.scope = 'col';
          cell.textContent = label;
          row.append(cell);
        });
    tableHead.replaceChildren(row);
  }

  /** 서버가 준 순서와 점수를 그대로 사용해 한 행을 생성합니다. */
  function createRankingRow(row) {
    const element = document.createElement('tr');
    const name = kind === 'members' ? row.nickname : row.groupName;
    const score = kind === 'members' ? row.contributionScore : row.score;
    element.append(tableCell(`${numberText(row.rank)}위`), tableCell(name), tableCell(`${numberText(score)}점`));
    if (kind === 'members') {
      const statusCell = document.createElement('td');
      statusCell.append(statusBadge(row.participantStatus));
      element.append(statusCell);
    }
    return element;
  }

  /** 현재 사용자 또는 현재 그룹의 페이지 밖 전체 순위도 고정 요약으로 표시합니다. */
  function renderCurrentSubject(currentSubject) {
    if (!(summary instanceof HTMLElement)) return;
    summary.replaceChildren();
    const title = document.createElement('p');
    title.className = 'small text-app-muted mb-1';
    title.textContent = kind === 'members' ? '내 전체 순위' : '우리 그룹 전체 순위';
    const value = document.createElement('strong');
    value.className = 'fs-5';
    value.textContent = currentSubject == null || currentSubject.rank == null
        ? '순위 없음'
        : `${numberText(currentSubject.rank)}위 · ${numberText(currentSubject.score)}점`;
    summary.append(title, value);
  }

  /** 페이지 경계와 총 결과 수를 사용해 이전·다음 버튼을 구성합니다. */
  function renderPagination(meta) {
    if (!(pagination instanceof HTMLElement)) return;
    const totalPages = Number(meta.totalPages ?? 0);
    const totalElements = Number(meta.totalElements ?? 0);
    const pageNumber = Number(meta.page ?? page);
    const info = document.createElement('span');
    info.className = 'small text-app-muted';
    info.textContent = totalPages === 0
        ? `총 ${numberText(totalElements)}건`
        : `${pageNumber + 1} / ${totalPages} 페이지 · 총 ${numberText(totalElements)}건`;
    const controls = document.createElement('div');
    controls.className = 'btn-group';
    controls.append(
        pageButton('이전', pageNumber - 1, pageNumber <= 0),
        pageButton('다음', pageNumber + 1, totalPages === 0 || pageNumber >= totalPages - 1),
    );
    pagination.replaceChildren(info, controls);
  }

  /** 조회 중 표 구조를 유지하는 간단한 Skeleton을 표시합니다. */
  function setLoading() {
    renderTableHead();
    if (summary instanceof HTMLElement) {
      summary.innerHTML = '<span class="placeholder-glow"><span class="placeholder col-4"></span></span>';
    }
    if (tableBody instanceof HTMLElement) {
      tableBody.innerHTML = Array.from({ length: 3 }, () =>
        '<tr class="placeholder-glow"><td><span class="placeholder col-6"></span></td><td><span class="placeholder col-8"></span></td><td><span class="placeholder col-5"></span></td><td></td></tr>',
      ).join('');
    }
    if (pagination instanceof HTMLElement) pagination.replaceChildren();
  }

  /** 빈 목록의 원인과 다음 행동을 표 안에 표시합니다. */
  function renderEmpty(message) {
    renderTableHead();
    if (!(tableBody instanceof HTMLElement)) return;
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.colSpan = kind === 'members' ? 4 : 3;
    cell.className = 'text-center text-app-muted py-5';
    cell.textContent = message;
    row.append(cell);
    tableBody.replaceChildren(row);
    if (pagination instanceof HTMLElement) pagination.replaceChildren();
  }

  /** 조회 실패 후 사용자가 같은 조건으로 재시도할 수 있게 합니다. */
  function renderRetry() {
    renderEmpty('랭킹을 표시하지 못했습니다.');
    if (!(pagination instanceof HTMLElement)) return;
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'btn btn-outline-primary';
    retry.dataset.action = 'retry';
    retry.textContent = '다시 조회';
    pagination.replaceChildren(retry);
  }

  /** 인증·권한 오류는 Route로 보내고 나머지는 현재 조회 조건과 함께 안내합니다. */
  function handleError(error, fallback) {
    if (error?.status === 401) {
      navigate('/login');
      return;
    }
    if (error?.status === 403) {
      navigate('/403');
      return;
    }
    if (feedback instanceof HTMLElement) {
      feedback.className = 'alert alert-danger';
      feedback.textContent = error?.message || fallback;
    }
  }

  function hideFeedback() {
    if (!(feedback instanceof HTMLElement)) return;
    feedback.className = 'alert d-none';
    feedback.textContent = '';
  }

  /** 탭 변경 시 선택 시즌을 유지하고 첫 페이지부터 다시 조회합니다. */
  function onTabClick(event) {
    if (!(event.target instanceof HTMLButtonElement) || !event.target.dataset.kind) return;
    kind = event.target.dataset.kind;
    page = 0;
    content.querySelectorAll('[data-kind]').forEach((tab) => {
      const active = tab === event.target;
      tab.classList.toggle('active', active);
      tab.setAttribute('aria-selected', String(active));
    });
    void loadRanking();
  }

  /** 시즌 변경 시 첫 페이지부터 선택한 시즌 랭킹을 조회합니다. */
  function onSeasonChange(event) {
    if (!(event.target instanceof HTMLSelectElement)) return;
    const value = Number(event.target.value);
    if (!Number.isSafeInteger(value) || value < 1) return;
    selectedSeasonId = value;
    page = 0;
    void loadRanking();
  }

  /** 페이지·재시도 버튼을 위임 처리합니다. */
  function onPaginationClick(event) {
    if (!(event.target instanceof HTMLButtonElement) || event.target.disabled) return;
    if (event.target.dataset.action === 'retry') {
      if (selectedSeasonId == null) void initialize();
      else void loadRanking();
      return;
    }
    const targetPage = Number(event.target.dataset.page);
    if (!Number.isInteger(targetPage) || targetPage < 0) return;
    page = targetPage;
    void loadRanking();
  }

  content.querySelector('[data-ranking-tabs]')?.addEventListener('click', onTabClick);
  seasonSelect?.addEventListener('change', onSeasonChange);
  pagination?.addEventListener('click', onPaginationClick);
  void initialize();

  return {
    element,
    cleanup: () => {
      mounted = false;
      loadVersion += 1;
      content.querySelector('[data-ranking-tabs]')?.removeEventListener('click', onTabClick);
      seasonSelect?.removeEventListener('change', onSeasonChange);
      pagination?.removeEventListener('click', onPaginationClick);
    },
  };
}

/** 표 셀에 서버 텍스트를 안전하게 배치합니다. */
function tableCell(value) {
  const cell = document.createElement('td');
  cell.textContent = String(value ?? '');
  return cell;
}

/** 탈퇴·강퇴 상태를 색상과 문구로 함께 표시합니다. */
function statusBadge(status) {
  const badge = document.createElement('span');
  badge.className = `badge ${status === 'ACTIVE' ? 'text-bg-success' : 'text-bg-secondary'}`;
  badge.textContent = PARTICIPANT_LABELS[status] ?? status;
  return badge;
}

/** 페이지 이동 버튼을 생성합니다. */
function pageButton(label, targetPage, disabled) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'btn btn-outline-primary';
  button.textContent = label;
  button.dataset.page = String(targetPage);
  button.disabled = disabled;
  return button;
}

/** 점수·순위·건수를 한국어 자릿수 구분으로 표시합니다. */
function numberText(value) {
  return Number(value ?? 0).toLocaleString('ko-KR');
}
