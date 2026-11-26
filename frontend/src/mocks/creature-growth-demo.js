const species = [
    { key: 'cotton-griffin', name: '솜사탕 그리핀', color: '#f5b9dc', backdrop: '#43283f' },
    { key: 'moss-golem', name: '이끼 낀 바위 골렘', color: '#b7df91', backdrop: '#304132' },
    { key: 'abyss-jellyfish', name: '공허 속 우주 해파리', color: '#add9ff', backdrop: '#283b57' },
];
const stages = [
    { name: '알', from: 0, to: 0, duration: 2400, size: 170 },
    { name: '유년기', from: 0, to: 45000, duration: 3300, size: 200 },
    { name: '성장기', from: 45000, to: 97500, duration: 3300, size: 225 },
    { name: '성숙기', from: 97500, to: 150000, duration: 3300, size: 250 },
    { name: '완전체', from: 150000, to: 150000, duration: 3700, size: 275 },
];
const duration = stages.reduce((sum, stage) => sum + stage.duration, 0);
const canvas = document.querySelector('#showcase');
const ctx = canvas.getContext('2d');
const play = document.querySelector('#play');
const reset = document.querySelector('#reset');
const record = document.querySelector('#record');
const status = document.querySelector('#status');
const formatXp = new Intl.NumberFormat('ko-KR');
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)');
let images;
let elapsed = 0;
let frame = null;
let previousTime = 0;
let announcedStage = -1;

/** 시연 시간에 해당하는 단계와 해당 단계 내 경험치를 계산합니다. */
function getGrowthState(time) {
    let start = 0;
    for (const [index, stage] of stages.entries()) {
        if (time < start + stage.duration || index === stages.length - 1) {
            const offset = time - start;
            const progress = index === 0 ? 0 : index === stages.length - 1 ? 1
                : Math.min(1, Math.max(0, (offset - 480) / (stage.duration - 720)));
            return { index, stage, offset, progress, xp: Math.floor(stage.from + (stage.to - stage.from) * progress) };
        }
        start += stage.duration;
    }
}

/** 운영 크리처 이미지를 미리 디코딩해 단계 전환 중 빈 프레임을 방지합니다. */
async function loadImages() {
    return Promise.all(species.map(({ key }) => Promise.all(stages.map(async (_, index) => {
        const image = new Image();
        image.src = new URL(`../../assets/creature/${key}/lv${index + 1}.png`, import.meta.url);
        await image.decode();
        return image;
    }))));
}

function box(x, y, width, height, radius, color) {
    if (width <= 0) return;
    ctx.beginPath();
    ctx.roundRect(x, y, width, height, radius);
    ctx.fillStyle = color;
    ctx.fill();
}

function label(value, x, y, size, color, weight = 500, align = 'left') {
    ctx.font = `${weight} ${size}px "Malgun Gothic", "Apple SD Gothic Neo", system-ui, sans-serif`;
    ctx.fillStyle = color;
    ctx.textAlign = align;
    ctx.textBaseline = 'middle';
    ctx.fillText(value, x, y);
}

/** 세 종의 현재 모습과 단계별 진행률을 영상과 동일한 캔버스에 그립니다. */
function draw(time) {
    const { index, stage, offset, progress, xp } = getGrowthState(time);
    const background = ctx.createLinearGradient(0, 0, 1280, 720);
    background.addColorStop(0, '#1e3233');
    background.addColorStop(1, '#161c2e');
    ctx.fillStyle = background;
    ctx.fillRect(0, 0, 1280, 720);

    label('다 같이 키우는 생명체', 48, 48, 19, '#a9d8cc', 700);
    label('공동 크리처 성장', 48, 101, 49, '#f3faf7', 700);
    label('퀘스트로 모은 경험치가 크리처의 모습을 바꿉니다.', 50, 151, 21, '#c4d4d4');
    box(1044, 78, 188, 48, 24, '#2b4446');
    label('시연용 Mock', 1138, 103, 19, '#e3f4ed', 700, 'center');

    species.forEach(({ name, color, backdrop }, speciesIndex) => {
        const x = 48 + 398 * speciesIndex;
        const center = x + 194;
        const asset = images[speciesIndex][index];
        const transition = reduceMotion.matches || index === 0 ? 1 : Math.min(1, offset / 520);
        box(x, 196, 388, 395, 23, '#25303d');
        box(x + 12, 208, 364, 300, 18, backdrop);
        ctx.save();
        ctx.globalAlpha = 0.55 + transition * 0.45;
        const glow = ctx.createRadialGradient(center, 357, 8, center, 357, 130);
        glow.addColorStop(0, `${color}40`);
        glow.addColorStop(1, `${color}00`);
        ctx.fillStyle = glow;
        ctx.fillRect(x + 12, 208, 364, 300);
        const scale = stage.size * (0.92 + transition * 0.08) / Math.max(asset.width, asset.height);
        const width = asset.width * scale;
        const height = asset.height * scale;
        ctx.drawImage(asset, center - width / 2, 358 - height / 2, width, height);
        ctx.restore();
        label(name, center, 537, 24, '#f3faf7', 700, 'center');
        label(stage.name, center, 568, 18, color, 700, 'center');
    });

    stages.forEach(({ name }, i) => {
        const x = 48 + i * 153;
        box(x, 622, 141, 41, 20, i === index ? '#b6e5ca' : '#30404c');
        label(name, x + 70, 643, 17, i === index ? '#152b27' : '#e5efed', 700, 'center');
    });
    const xpText = index === 0 ? '부화 대기'
        : index === stages.length - 1 ? `${formatXp.format(xp)} XP · 완전체 달성`
            : `${formatXp.format(xp)} / ${formatXp.format(stage.to)} XP`;
    label(xpText, 1232, 638, 19, '#f3faf7', 700, 'right');
    box(827, 657, 405, 9, 5, '#384a57');
    box(827, 657, 405 * progress, 9, 5, '#b6e5ca');
    if (index !== announcedStage) {
        status.textContent = `성장 단계 ${index + 1}/${stages.length} · ${stage.name}`;
        announcedStage = index;
    }
}

/** 자동 재생을 멈추고 현재 성장 단계에 화면을 고정합니다. */
function stop() {
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
    play.textContent = elapsed >= duration ? '다시 재생' : '재생';
}

/** 일시정지 지점부터 공유 시간축을 재생합니다. */
function start(onComplete = () => {}) {
    previousTime = performance.now();
    play.textContent = '일시정지';
    function tick(now) {
        elapsed = Math.min(duration, elapsed + Math.max(0, now - previousTime));
        previousTime = now;
        draw(elapsed);
        if (elapsed < duration) {
            frame = requestAnimationFrame(tick);
        } else {
            stop();
            onComplete();
        }
    }
    frame = requestAnimationFrame(tick);
}

/** 처음부터 완전체까지 캔버스를 녹화하여 브라우저 다운로드로 제공합니다. */
async function recordShowcase() {
    stop();
    elapsed = 0;
    draw(0);
    const stream = canvas.captureStream(30);
    try {
        const recorder = new MediaRecorder(stream, { videoBitsPerSecond: 5_000_000 });
        const chunks = [];
        const video = new Promise((resolve, reject) => {
            recorder.ondataavailable = ({ data }) => { if (data.size) chunks.push(data); };
            recorder.onstop = () => resolve(new Blob(chunks, { type: recorder.mimeType }));
            recorder.onerror = () => reject(new Error('영상 인코딩에 실패했습니다.'));
        });
        recorder.start();
        start(() => recorder.stop());
        return await video;
    } finally {
        stop();
        stream.getTracks().forEach((track) => track.stop());
    }
}

try {
    images = await loadImages();
    draw(0);
    play.disabled = reset.disabled = false;
    record.disabled = !canvas.captureStream || typeof MediaRecorder === 'undefined';
    play.addEventListener('click', () => {
        if (frame !== null) return stop();
        if (elapsed >= duration) {
            elapsed = 0;
            draw(0);
        }
        start();
    });
    reset.addEventListener('click', () => {
        elapsed = 0;
        stop();
        draw(0);
    });
    record.addEventListener('click', async () => {
        play.disabled = reset.disabled = record.disabled = true;
        try {
            const video = await recordShowcase();
            const url = URL.createObjectURL(video);
            const link = document.createElement('a');
            link.href = url;
            link.download = `creature-growth-showcase.${video.type.startsWith('video/mp4') ? 'mp4' : 'webm'}`;
            link.click();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
            status.textContent = '완전체까지 녹화했습니다.';
        } catch {
            status.textContent = '영상 저장에 실패했습니다. 재생은 계속 사용할 수 있습니다.';
        } finally {
            play.disabled = reset.disabled = record.disabled = false;
        }
    });
} catch {
    status.textContent = '성장 이미지를 불러오지 못했습니다. 페이지를 새로고침해 주세요.';
}
