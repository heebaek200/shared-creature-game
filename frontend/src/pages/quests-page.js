// @ts-check
import { createAppShell } from '../components/app-shell.js';
import {
  completeQuestAssignment,
  createCheckIn,
  listCurrentQuests,
  submitQuestPhoto,
} from '../features/quest/api.js';
import { validateQuestPhoto } from '../features/quest/photo-upload.js';

const DIFFICULTY_LABELS = { LOW: '하', MEDIUM: '중', HIGH: '상' };
const VERIFICATION_LABELS = {
  SELF_CHECK: '직접 확인',
  SERVER_EVENT: '서비스 확인',
  AGGREGATE: '자동 집계',
  PHOTO_EXIF: '사진 인증',
};
const PHOTO_FAILURE_MESSAGES = {
  EXIF_GPS_MISSING: '사진에 GPS 위치 정보가 없습니다.',
  EXIF_GPS_OUT_OF_RANGE: '사진의 GPS 위치 정보가 올바르지 않습니다.',
  EXIF_CAPTURED_AT_MISSING: '사진에 촬영 시각 정보가 없습니다.',
  PHOTO_CAPTURED_OUTSIDE_ASSIGNMENT_PERIOD: '퀘스트 기간 밖에 촬영한 사진입니다.',
  PHOTO_PROCESSING_TIMEOUT: '사진 처리 시간이 초과되었습니다. 다시 시도해 주세요.',
  QUEST_PERIOD_ENDED: '사진 처리 중 퀘스트 기간이 종료되었습니다.',
  QUEST_COMPLETION_FAILED: '사진은 확인됐지만 퀘스트 완료 조건을 충족하지 못했습니다.',
  PHOTO_PROCESSING_FAILED: '사진 처리 결과를 확정하지 못했습니다.',
};

/**
 * 현재 퀘스트 조회·완료·체크인·사진 인증 화면을 구성합니다.
 * 모든 상태 변경 후 서버 목록을 다시 조회해 완료·보상 상태를 확정합니다.
 */
export function questPage({ navigate, currentUser }) {
  const content = document.createElement('div');
  content.className = 'quest-page container-fluid py-4';
  content.innerHTML = `
    <div class="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-4">
      <div>
        <p class="text-app-primary fw-semibold mb-1">QUESTS</p>
        <h1 class="h2 mb-1">일일·주간 퀘스트</h1>
        <p class="text-app-muted mb-0">완료 조건과 보상을 확인하고 공동 크리처를 성장시켜 보세요.</p>
      </div>
      <button type="button" class="btn btn-outline-primary" data-action="refresh">새로고침</button>
    </div>
    <div class="alert d-none" role="status" aria-live="polite" data-page-feedback></div>
    <div class="nav nav-pills quest-period-tabs mb-4" role="tablist" aria-label="퀘스트 기간">
      <button type="button" class="nav-link active" role="tab" aria-selected="true" data-period="DAILY">일일</button>
      <button type="button" class="nav-link" role="tab" aria-selected="false" data-period="WEEKLY">주간</button>
    </div>
    <section data-quest-list aria-live="polite"></section>
  `;

  const element = createAppShell({
    content,
    currentPath: location.pathname,
    currentUser,
    navigate,
  });
  const list = content.querySelector('[data-quest-list]');
  const feedback = content.querySelector('[data-page-feedback]');
  const refreshButton = content.querySelector('[data-action="refresh"]');

  let quests = [];
  let activePeriod = 'DAILY';
  let mounted = true;
  let loadVersion = 0;
  let expiryTimeoutId = null;
  const pendingAssignments = new Set();

  /** 페이지 상단에 현재 작업의 결과를 표시합니다. */
  function showPageFeedback(message, type = 'danger') {
    if (!(feedback instanceof HTMLElement)) return;
    feedback.className = `alert alert-${type}`;
    feedback.textContent = message;
  }

  /** API 오류 중 현재 페이지에서 확정할 수 있는 이동과 안내를 처리합니다. */
  function handlePageError(error, fallback) {
    if (error?.status === 401) {
      navigate('/login');
      return;
    }
    if (error?.status === 403) {
      navigate('/403');
      return;
    }
    showPageFeedback(error?.message || fallback);
  }

  /** 현재 선택된 기간의 퀘스트를 카드 목록으로 표시합니다. */
  function renderQuests() {
    if (!(list instanceof HTMLElement)) return;
    list.replaceChildren();

    const visibleQuests = quests.filter((quest) => quest.periodType === activePeriod);
    if (visibleQuests.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'app-surface-card border border-app p-5 text-center';
      empty.innerHTML = `
        <h2 class="h5">표시할 ${activePeriod === 'DAILY' ? '일일' : '주간'} 퀘스트가 없습니다.</h2>
        <p class="text-app-muted mb-3">기간 초기화 중일 수 있습니다. 잠시 후 다시 조회해 주세요.</p>
        <button type="button" class="btn btn-outline-primary" data-action="refresh">다시 조회</button>
      `;
      list.append(empty);
      return;
    }

    const grid = document.createElement('div');
    grid.className = 'd-grid gap-3';
    visibleQuests.forEach((quest) => grid.append(createQuestCard(quest)));
    list.append(grid);
    scheduleExpiryRender(visibleQuests);
  }

  /** 퀘스트 한 건의 서버 상태와 가능한 동작을 카드로 구성합니다. */
  function createQuestCard(quest) {
    const article = document.createElement('article');
    article.className = 'card app-surface-card border-app quest-card';
    article.dataset.assignmentId = String(quest.assignmentId);

    const body = document.createElement('div');
    body.className = 'card-body p-4';
    const header = document.createElement('div');
    header.className = 'd-flex flex-wrap justify-content-between align-items-start gap-3';
    const titleArea = document.createElement('div');
    titleArea.className = 'flex-grow-1';

    const badges = document.createElement('div');
    badges.className = 'd-flex flex-wrap gap-2 mb-2';
    badges.append(
        badge(DIFFICULTY_LABELS[quest.difficulty] ?? quest.difficulty, 'text-bg-success'),
        badge(VERIFICATION_LABELS[quest.verificationType] ?? quest.verificationType, 'text-bg-secondary'),
    );
    const title = document.createElement('h2');
    title.className = 'h5 mb-1';
    title.textContent = quest.title;
    const description = document.createElement('p');
    description.className = 'text-app-muted mb-0';
    description.textContent = quest.description;
    titleArea.append(badges, title, description);
    header.append(titleArea, createQuestStatus(quest));

    const details = document.createElement('div');
    details.className = 'quest-card__details mt-3 pt-3 border-top border-app';
    const progressCurrent = Number(quest.progress?.current ?? 0);
    const progressTarget = Math.max(1, Number(quest.progress?.target ?? 1));
    const progressPercent = Math.min(100, Math.round(progressCurrent / progressTarget * 100));
    details.innerHTML = `
      <div class="d-flex flex-wrap justify-content-between gap-2 small mb-2">
        <span><strong>보상</strong> 경험치 ${numberText(quest.creatureExperience)} · 기여도 ${numberText(quest.contributionScore)}</span>
        <span class="text-app-muted">${formatDeadline(quest.periodEndAt)}</span>
      </div>
      <div class="d-flex align-items-center gap-3">
        <div class="progress flex-grow-1" role="progressbar" aria-label="퀘스트 진행도" aria-valuenow="${progressCurrent}" aria-valuemin="0" aria-valuemax="${progressTarget}">
          <div class="progress-bar" style="width:${progressPercent}%"></div>
        </div>
        <span class="small fw-semibold">${progressCurrent} / ${progressTarget}</span>
      </div>
    `;

    const actions = createQuestActions(quest);
    const itemFeedback = document.createElement('div');
    itemFeedback.className = 'small mt-3 d-none';
    itemFeedback.setAttribute('role', 'status');
    itemFeedback.dataset.itemFeedback = '';
    body.append(header, details, actions, itemFeedback);
    article.append(body);
    return article;
  }

  /** 완료·기간 종료·진행 상태를 색상과 문구로 함께 표시합니다. */
  function createQuestStatus(quest) {
    if (quest.completed) return badge('완료 ✓', 'text-bg-success');
    if (isExpired(quest)) return badge('기간 종료', 'text-bg-secondary');
    if (pendingAssignments.has(String(quest.assignmentId))) {
      return badge('처리 중', 'text-bg-warning');
    }
    return badge('진행 가능', 'text-bg-primary');
  }

  /** 검증 유형에 맞는 사용자 동작 또는 자동 집계 안내를 만듭니다. */
  function createQuestActions(quest) {
    const area = document.createElement('div');
    area.className = 'mt-3';
    if (quest.completed || isExpired(quest)) return area;

    const assignmentId = String(quest.assignmentId);
    if (quest.verificationType === 'SELF_CHECK') {
      area.append(actionButton('완료하기', 'complete', assignmentId));
    } else if (quest.verificationType === 'SERVER_EVENT') {
      area.append(actionButton('오늘 체크인', 'check-in', assignmentId));
    } else if (quest.verificationType === 'AGGREGATE') {
      const help = document.createElement('p');
      help.className = 'small text-app-muted mb-0';
      help.textContent = '서비스 활동이 쌓이면 서버가 진행도와 완료 여부를 자동으로 갱신합니다.';
      area.append(help);
    } else if (quest.verificationType === 'PHOTO_EXIF') {
      const inputId = `quest-photo-${assignmentId}`;
      area.innerHTML = `
        <label class="form-label fw-semibold" for="${inputId}">JPEG 사진 선택</label>
        <div class="input-group quest-photo-input">
          <input id="${inputId}" class="form-control" type="file" accept="image/jpeg,.jpg,.jpeg" data-photo-input>
          <button type="button" class="btn btn-primary" data-action="photo" data-assignment-id="${assignmentId}">사진 제출</button>
        </div>
        <p class="small text-app-muted mt-2 mb-1">JPEG · 최대 10 MiB · HEIC/HEIF 미지원 · 남은 제출 ${quest.photoAttemptsRemaining ?? 0}회</p>
        <p class="small text-app-muted mb-0">GPS와 촬영 시각만 확인하며 실외 활동이나 실제 이동 여부는 검증하지 않습니다.</p>
      `;
    }
    return area;
  }

  /** 지정 동작을 위임 처리할 버튼을 생성합니다. */
  function actionButton(label, action, assignmentId) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn btn-primary';
    button.textContent = label;
    button.dataset.action = action;
    button.dataset.assignmentId = assignmentId;
    button.disabled = pendingAssignments.has(assignmentId);
    return button;
  }

  /** 기간 종료 순간에만 다시 렌더링해 선택 중인 파일을 불필요하게 지우지 않습니다. */
  function scheduleExpiryRender(visibleQuests) {
    if (expiryTimeoutId !== null) window.clearTimeout(expiryTimeoutId);
    const now = Date.now();
    const nextExpiry = visibleQuests
        .filter((quest) => !quest.completed)
        .map((quest) => Date.parse(quest.periodEndAt))
        .filter((value) => Number.isFinite(value) && value > now)
        .sort((left, right) => left - right)[0];
    if (nextExpiry === undefined) return;
    expiryTimeoutId = window.setTimeout(renderQuests, Math.min(nextExpiry - now + 100, 2_147_000_000));
  }

  /** 최신 퀘스트 Snapshot을 조회하고 현재 탭을 다시 그립니다. */
  async function loadQuests() {
    const version = ++loadVersion;
    if (refreshButton instanceof HTMLButtonElement) refreshButton.disabled = true;
    if (list instanceof HTMLElement) {
      list.innerHTML = `<div class="app-surface-card border border-app p-4" aria-label="퀘스트 불러오는 중">
        <div class="placeholder-glow"><span class="placeholder col-7 mb-3"></span><span class="placeholder col-12"></span></div>
      </div>`;
    }
    try {
      const result = await listCurrentQuests();
      if (!mounted || version !== loadVersion) return;
      quests = Array.isArray(result) ? result : [];
      renderQuests();
    } catch (error) {
      if (!mounted || version !== loadVersion) return;
      handlePageError(error, '퀘스트를 불러오지 못했습니다.');
      if (list instanceof HTMLElement) {
        list.innerHTML = '<button type="button" class="btn btn-outline-primary" data-action="refresh">다시 조회</button>';
      }
    } finally {
      if (mounted && refreshButton instanceof HTMLButtonElement) refreshButton.disabled = false;
    }
  }

  /** 카드 안의 완료·체크인·사진 제출을 한 곳에서 처리합니다. */
  async function onContentClick(event) {
    if (!(event.target instanceof Element)) return;
    const button = event.target.closest('[data-action]');
    if (!(button instanceof HTMLButtonElement) || button.disabled) return;
    const action = button.dataset.action;
    if (action === 'refresh') {
      await loadQuests();
      return;
    }
    const assignmentId = button.dataset.assignmentId;
    if (!assignmentId || pendingAssignments.has(assignmentId)) return;
    const card = button.closest('.quest-card');
    const itemFeedback = card?.querySelector('[data-item-feedback]');
    pendingAssignments.add(assignmentId);
    button.disabled = true;

    try {
      if (action === 'complete') {
        const result = await completeQuestAssignment(assignmentId);
        showPageFeedback(
            `완료되었습니다. 경험치 ${numberText(result.creatureExperienceAwarded)}, 기여도 ${numberText(result.contributionAwarded)}를 받았습니다.`,
            'success',
        );
      } else if (action === 'check-in') {
        const result = await createCheckIn();
        showPageFeedback(
            result.isMorning ? '오전 기상 체크인이 기록되었습니다.' : '오늘의 체크인이 기록되었습니다.',
            'success',
        );
      } else if (action === 'photo') {
        const input = card?.querySelector('[data-photo-input]');
        const file = input instanceof HTMLInputElement ? input.files?.[0] : null;
        const result = await submitQuestPhoto(assignmentId, validateQuestPhoto(file));
        if (result.verificationStatus === 'VALID') {
          showPageFeedback('사진 인증이 완료되어 퀘스트 보상이 반영되었습니다.', 'success');
        } else {
          showPageFeedback(
              PHOTO_FAILURE_MESSAGES[result.failureReason] ?? '사진 인증 조건을 충족하지 못했습니다.',
              'warning',
          );
        }
      }
      await loadQuests();
    } catch (error) {
      if (!mounted) return;
      if (error?.status === 401) {
        navigate('/login');
      } else if (error?.status === 403) {
        navigate('/403');
      } else {
        const message = photoOrQuestErrorMessage(error);
        if (itemFeedback instanceof HTMLElement) {
          itemFeedback.className = 'small mt-3 text-danger';
          itemFeedback.textContent = message;
        }
        if (error?.status === 409 && error?.code !== 'PHOTO_ATTEMPT_LIMIT_EXCEEDED') {
          showPageFeedback(`${message} 최신 퀘스트 상태를 다시 확인했습니다.`, 'warning');
          await loadQuests();
        }
      }
    } finally {
      pendingAssignments.delete(assignmentId);
      if (mounted && button.isConnected) button.disabled = false;
    }
  }

  /** 탭 선택을 변경하고 이미 받은 서버 목록에서 해당 기간만 표시합니다. */
  function onPeriodClick(event) {
    if (!(event.target instanceof HTMLButtonElement) || !event.target.dataset.period) return;
    activePeriod = event.target.dataset.period;
    content.querySelectorAll('[data-period]').forEach((tab) => {
      const active = tab === event.target;
      tab.classList.toggle('active', active);
      tab.setAttribute('aria-selected', String(active));
    });
    renderQuests();
  }

  content.addEventListener('click', onContentClick);
  content.querySelector('.quest-period-tabs')?.addEventListener('click', onPeriodClick);
  void loadQuests();

  return {
    element,
    cleanup: () => {
      mounted = false;
      loadVersion += 1;
      if (expiryTimeoutId !== null) window.clearTimeout(expiryTimeoutId);
      content.removeEventListener('click', onContentClick);
      content.querySelector('.quest-period-tabs')?.removeEventListener('click', onPeriodClick);
    },
  };
}

/** Bootstrap Badge를 안전한 텍스트로 생성합니다. */
function badge(label, className) {
  const element = document.createElement('span');
  element.className = `badge ${className}`;
  element.textContent = label;
  return element;
}

/** 기간 종료 여부를 서버의 UTC 종료 시각으로 판단합니다. */
function isExpired(quest) {
  const endAt = Date.parse(quest.periodEndAt);
  return Number.isFinite(endAt) && Date.now() >= endAt;
}

/** 기간 종료 시각을 한국어 로컬 시각으로 표시합니다. */
function formatDeadline(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '기간 정보 없음';
  return `${new Intl.DateTimeFormat('ko-KR', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(date)}까지`;
}

/** 점수와 보상 숫자를 한국어 자릿수 구분으로 표시합니다. */
function numberText(value) {
  return Number(value ?? 0).toLocaleString('ko-KR');
}

/** HTTP·사진 선택 오류를 퀘스트 화면 문구로 변환합니다. */
function photoOrQuestErrorMessage(error) {
  const messages = {
    FILE_TOO_LARGE: '사진은 최대 10 MiB까지 제출할 수 있습니다.',
    PHOTO_ATTEMPT_LIMIT_EXCEEDED: '이 퀘스트의 사진 제출 가능 횟수를 모두 사용했습니다.',
    RATE_LIMIT_EXCEEDED: '사진 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
    QUEST_PERIOD_ENDED: '퀘스트 기간이 종료되었습니다.',
    QUEST_COMPLETION_NOT_ALLOWED: '이 퀘스트는 해당 방식으로 직접 완료할 수 없습니다.',
    CONCURRENT_MODIFICATION: '다른 요청으로 상태가 변경되었습니다.',
  };
  return messages[error?.code] ?? error?.message ?? '요청을 처리하지 못했습니다.';
}
