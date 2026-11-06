// @ts-check

import creatureLv0 from '../../../assets/creature/lv0.png';

/** @type {Record<string, string>} */
const creatureImages = import.meta.glob('../../../assets/creature/*/lv*.png', {
    eager: true,
    query: '?url',
    import: 'default',
});

/**
 * Snapshot과 종 목록을 화면의 크리처·성장·경험치 요소에 반영합니다.
 * API 호출과 타이머 생성/정리는 호출자가 담당합니다.
 *
 * @param {{
 *   element: HTMLElement,
 *   creatureData: Record<string, any>,
 *   seasonData: Record<string, any>,
 *   speciesList: Array<Record<string, any>>,
 *   currentUserId: number|string|null,
 *   isMounted: () => boolean,
 *   scheduleTimeout: (callback: () => void, delay: number) => number
 * }} options
 * @returns {number} 시즌 시작 후 성장 연출을 열 시각(epoch ms)
 */
export function renderCreatureGrowth({
    element,
    creatureData,
    seasonData,
    speciesList,
    currentUserId,
    isMounted,
    scheduleTimeout,
}) {
    const creatureInfo = creatureData.creature || {};
    const creatureTitleName = element.querySelector('#creature-title-name');
    const speciesSubtitle = element.querySelector('#species-subtitle');
    const creatureDisplay = element.querySelector('#creature-display');
    const creatureImageEl = element.querySelector('#creature-image');
    const growthStageEl = element.querySelector('#growth-stage');
    const expText = element.querySelector('#exp-text span');
    const expBar = element.querySelector('#exp-bar');

    const selectedSpeciesId = creatureInfo.creatureSpeciesId ?? null;
    const selectedSpecies = speciesList.find((species) =>
        String(species.creatureSpeciesId) === String(selectedSpeciesId));
    const getCreatureImage = (level) => {
        const assetKey = selectedSpecies?.assetKey;
        return assetKey
            ? creatureImages[`../../../assets/creature/${assetKey}/lv${level}.png`] || creatureLv0
            : creatureLv0;
    };

    // 시즌 시작 시각으로부터 72시간이 지난 뒤에만 경험치/성장 연출을 허용합니다.
    const isSelected = selectedSpeciesId !== null && selectedSpeciesId !== undefined;
    const nowTime = Date.now();
    const seasonStartAt = seasonData?.startAt ? new Date(seasonData.startAt).getTime() : NaN;
    const fallbackDeadlineAt = creatureInfo.selectionDeadlineAt
        ? new Date(creatureInfo.selectionDeadlineAt).getTime()
        : NaN;
    const growthUnlockAt = Number.isFinite(seasonStartAt)
        ? seasonStartAt + 72 * 60 * 60 * 1000
        : fallbackDeadlineAt;
    const isDeadlinePassed = Number.isFinite(growthUnlockAt) && nowTime >= growthUnlockAt;
    const isWaitingForAssignment = isDeadlinePassed && !isSelected;
    const isTrulyEggState = !isDeadlinePassed || isWaitingForAssignment;
    const hasSpeciesName = typeof selectedSpecies?.name === 'string'
        && selectedSpecies.name.trim().length > 0;
    const shouldRevealSpeciesName = isSelected || (isDeadlinePassed && hasSpeciesName);
    const eggImage = shouldRevealSpeciesName ? getCreatureImage(1) : creatureLv0;
    const seasonId = seasonData?.seasonId ?? 'current';
    const groupId = creatureData.groupId ?? 'unknown';
    const seasonAnimationKey = `creature-growth-animation:${currentUserId}:${groupId}:${seasonId}`;
    const legacySeasonAnimationKey = `creature-growth-animation:${seasonId}`;
    let hasPlayedGrowthAnimation = false;
    try {
        hasPlayedGrowthAnimation = localStorage.getItem(seasonAnimationKey) === 'played';
        localStorage.removeItem(legacySeasonAnimationKey);
    } catch (error) {
        console.warn('성장 연출 재생 상태를 읽지 못했습니다:', error);
    }

    const targetExp = creatureInfo.totalExperience ?? 0;
    const targetGrowthStage = creatureInfo.growthStage === 'EGG'
        ? 'HATCHLING'
        : (creatureInfo.growthStage || 'HATCHLING');

    const speciesName = shouldRevealSpeciesName && hasSpeciesName
        ? selectedSpecies.name
        : '???';
    if (creatureTitleName) {
        creatureTitleName.textContent = shouldRevealSpeciesName
            ? `시즌 크리처 - ${speciesName}`
            : '시즌 크리처 - ???';
    }
    if (speciesSubtitle) {
        speciesSubtitle.textContent = shouldRevealSpeciesName ? speciesName : '???';
    }

    function getStageConfig(stage) {
        const configured = (() => {
            switch (stage) {
                case 'EGG':
                case 'HATCHLING':
                    return { name: '유년기', min: 0, max: 45000, img: getCreatureImage(2), width: '130px' };
                case 'GROWING':
                    return { name: '성장기', min: 45000, max: 97500, img: getCreatureImage(3), width: '150px' };
                case 'MATURE':
                    return { name: '성숙기', min: 97500, max: 150000, img: getCreatureImage(4), width: '200px' };
                case 'COMPLETE':
                    return { name: '완전체', min: 150000, max: 150000, img: getCreatureImage(5), width: '220px' };
                default:
                    return { name: '유년기', min: 0, max: 45000, img: getCreatureImage(2), width: '130px' };
            }
        })();
        if (stage !== 'COMPLETE' && Number.isFinite(creatureInfo.nextStageAtExperience)) {
            configured.max = creatureInfo.nextStageAtExperience;
        }
        return configured;
    }

    // 알 단계에서는 선택 크리처의 알(lv1) 또는 공통 알(lv0)을 표시합니다.
    if (isTrulyEggState) {
        if (creatureImageEl) {
            creatureImageEl.setAttribute('src', eggImage);
            creatureImageEl.style.width = '130px';
            creatureImageEl.style.height = 'auto';
        }
        if (growthStageEl) growthStageEl.textContent = '알';
        if (expText) expText.textContent = `${targetExp.toLocaleString()} / - XP`;
        if (expBar) {
            expBar.style.width = '0%';
            expBar.setAttribute('aria-valuenow', '0');
            expBar.setAttribute('aria-valuetext', `${targetExp.toLocaleString()} XP, 부화 대기 중`);
        }
    } else if (!hasPlayedGrowthAnimation) {
        const stageOrder = ['HATCHLING', 'GROWING', 'MATURE', 'COMPLETE'];
        const targetStageIndex = Math.max(0, stageOrder.indexOf(targetGrowthStage));
        const stagesToVisit = stageOrder.slice(0, targetStageIndex + 1);
        let currentStepIndex = 0;

        if (creatureImageEl) {
            creatureImageEl.setAttribute('src', eggImage);
            creatureImageEl.style.width = '130px';
            creatureImageEl.style.height = 'auto';
        }
        if (growthStageEl) growthStageEl.textContent = '알';
        if (expBar) {
            expBar.style.width = '0%';
            expBar.setAttribute('aria-valuenow', '0');
            expBar.setAttribute('aria-valuetext', '알에서 성장 연출 시작 대기 중');
        }

        function playEvolutionStep() {
            if (!isMounted()) return;
            const stageKey = stagesToVisit[currentStepIndex];
            const config = getStageConfig(stageKey);

            if (creatureImageEl) {
                creatureImageEl.setAttribute('src', config.img);
                creatureImageEl.style.width = config.width;
            }
            if (growthStageEl) growthStageEl.textContent = config.name;

            let percent = 0;
            let displayText = '';
            const isLastStep = currentStepIndex === stagesToVisit.length - 1;
            if (isLastStep) {
                if (stageKey === 'COMPLETE') {
                    percent = 100;
                    displayText = `${targetExp.toLocaleString()} XP`;
                } else {
                    const range = config.max - config.min;
                    const currentInStage = targetExp - config.min;
                    percent = range > 0
                        ? Math.min(100, Math.max(0, (currentInStage / range) * 100))
                        : 0;
                    displayText = `${targetExp.toLocaleString()} / ${config.max.toLocaleString()} XP`;
                }
            } else {
                percent = 100;
                displayText = `${config.max.toLocaleString()} / ${config.max.toLocaleString()} XP`;
            }

            if (expText) expText.textContent = displayText;
            if (expBar) {
                expBar.setAttribute('aria-valuetext', '성장 단계 진행 중');
                expBar.style.width = '0%';
                expBar.setAttribute('aria-valuenow', '0');
                scheduleTimeout(() => {
                    if (!isMounted()) return;
                    expBar.style.width = `${percent}%`;
                    expBar.setAttribute('aria-valuenow', String(percent));
                    expBar.setAttribute('aria-valuetext', displayText);
                }, 50);
            }

            if (isLastStep) {
                scheduleTimeout(() => {
                    if (!isMounted()) return;
                    try {
                        localStorage.setItem(seasonAnimationKey, 'played');
                    } catch (error) {
                        console.warn('성장 연출 재생 상태를 저장하지 못했습니다:', error);
                    }
                }, 2850);
            }

            currentStepIndex++;
            if (currentStepIndex < stagesToVisit.length) {
                scheduleTimeout(playEvolutionStep, 3400);
            }
        }

        scheduleTimeout(playEvolutionStep, 800);
    } else {
        const finalConfig = getStageConfig(targetGrowthStage);
        const finalPercent = targetGrowthStage === 'COMPLETE'
            ? 100
            : Math.min(100, Math.max(0,
                ((targetExp - finalConfig.min) / (finalConfig.max - finalConfig.min)) * 100));

        if (creatureImageEl) {
            creatureImageEl.setAttribute('src', finalConfig.img);
            creatureImageEl.style.width = finalConfig.width;
            creatureImageEl.style.height = 'auto';
        }
        if (growthStageEl) growthStageEl.textContent = finalConfig.name;
        const finalDisplayText = targetGrowthStage === 'COMPLETE'
            ? `${targetExp.toLocaleString()} XP`
            : `${targetExp.toLocaleString()} / ${finalConfig.max.toLocaleString()} XP`;
        if (expText) expText.textContent = finalDisplayText;
        if (expBar) {
            expBar.style.width = `${finalPercent}%`;
            expBar.setAttribute('aria-valuenow', String(finalPercent));
            expBar.setAttribute('aria-valuetext', finalDisplayText);
        }
    }

    if (creatureDisplay) creatureDisplay.style.visibility = 'visible';
    return growthUnlockAt;
}
