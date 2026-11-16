
import {
    getPublicInvitation,
    acceptInvitation,
    rejectInvitation,
} from '../features/group/api.js';
import {createHeaderLayout} from "../components/app-shell.js";

/**
 * 초대 정보를 표시하고 수락·거절을 하는 Page입니다.
 */
export function invitePage({ navigate, currentUser }) {
    const content = document.createElement('main');
    content.className = 'app-shell';

    content.innerHTML = `
    <section class="card border-0 shadow-sm rounded-4">
        <div class="card-body p-3 p-md-5">
            <div class="mb-4">
                <span class="badge rounded-pill text-bg-success mb-3">
                    GROUP INVITATION
                </span>

                <h2 class="h4 fw-bold mb-2">
                    새로운 그룹에 초대받았습니다
                </h2>

                <p class="text-secondary mb-0">
                    함께 생명체를 키워 나갈 동료를 만나보세요.
                </p>
            </div>

            <div class="row align-items-center g-4 mb-4">

                <div class="col-md-4">
                    <div
                        class="rounded-4 bg-success-subtle d-flex
                        align-items-center justify-content-center
                        mx-auto"
                        style="width: 100%; min-height: 190px;"
                    >
                        <span class="display-1" aria-hidden="true">
                            
                        </span>
                    </div>
                </div>

                <div class="col-md-8">

                    <span class="badge text-bg-success mb-2">
                        공개 그룹
                    </span>

                    <h3 class="group-name fw-bold mb-2">
                        초대 정보를 불러오는 중...
                    </h3>

                    <p class="group-description text-secondary mb-3">
                        그룹 정보를 불러오고 있습니다.
                    </p>

                    <div class="row g-2">

                        <div class="col-4">
                            <div class="bg-body-tertiary rounded-3 p-3 h-100">
                                <p class="small text-secondary mb-1">
                                    현재 인원
                                </p>
                                <strong class="fs-5 member-count">-</strong>
                            </div>
                        </div>

                        <div class="col-4">
                            <div class="bg-body-tertiary rounded-3 p-3 h-100">
                                <p class="small text-secondary mb-1">
                                    그룹 정원
                                </p>
                                <strong class="fs-5 capacity">-</strong>
                            </div>
                        </div>

                        <div class="col-4">
                            <div class="bg-success-subtle rounded-3 p-3 h-100">
                                <p class="small text-success mb-1">
                                    남은 자리
                                </p>
                                <strong class="fs-5 text-success remaining-capacity">-</strong>
                            </div>
                        </div>

                    </div>
                </div>

            </div>

            <div class="alert alert-success border-0 rounded-3 mb-4">
                <h5 class="fw-bold mb-2">
                    🌿 함께 성장하는 그룹
                </h5>

                <p class="small mb-0">
                    그룹에 참여하면 다른 구성원들과 함께
                    생명체를 키우고 다양한 퀘스트를 수행할 수 있습니다.
                </p>
            </div>

            <div class="border-top pt-4">

                <h5 class="fw-bold mb-2">
                    초대 안내
                </h5>

                <ul class="text-secondary small ps-3 mb-4">
                    <li class="mb-1">
                        초대를 수락하면 해당 그룹의 멤버로 참여합니다.
                    </li>
                    <li class="mb-1">
                        그룹 정원이 가득 찬 경우 참여할 수 없습니다.
                    </li>
                    <li>
                        이미 다른 그룹에 가입되어 있다면 참여할 수 없습니다.
                    </li>
                </ul>

                    <div class="alert d-none invite-feedback"
                         role="status"
                         aria-live="polite">
                    </div>

                <div class="d-grid gap-2 d-sm-flex">

                    <button
                        class="btn btn-primary btn-lg flex-fill accept-button"
                        type="button"
                        disabled
                    >
                        초대 수락하기
                    </button>

                    <button
                        class="btn btn-outline-secondary btn-lg flex-fill reject-button"
                        type="button"
                        disabled
                    >
                        초대 거절하기
                    </button>

                </div>

            </div>

        </div>
    </section>
    `;

    // 공통 헤더
    const element = createHeaderLayout({ content, currentUser, navigate });

    const groupName = element.querySelector('.group-name');
    const description = element.querySelector('.group-description');
    const memberCount = element.querySelector('.member-count');
    const capacity = element.querySelector('.capacity');
    const remainingCapacity = element.querySelector('.remaining-capacity');
    const alert = element.querySelector('.invite-feedback');
    const acceptButton = element.querySelector('.accept-button');
    const rejectButton = element.querySelector('.reject-button');

    const token = location.pathname.split('/').filter(Boolean).at(-1);

    let submitting = false;
    let joinable = false;

    const showMessage = (message, type = 'danger') => {
        alert.className = `alert alert-${type}`;
        alert.textContent = message;
    };

    const getBlockedMessage = (reason) => {
        const messages = {
            GROUP_FULL: '그룹 정원이 가득 찼습니다.',
            JOIN_COOLDOWN_ACTIVE: '24시간 가입 제한이 적용 중입니다.',
            INVITE_INVALID: '유효하지 않거나 만료된 초대 링크입니다.',
            ALREADY_IN_GROUP: '이미 다른 그룹에 가입되어 있습니다.',
        };

        return messages[reason] ?? '현재 그룹에 가입할 수 없습니다.';
    };

    const setButtonDisabled = (disabled) => {
        acceptButton.disabled = disabled;
        rejectButton.disabled = disabled;
    };

    const loadInvitation = async () => {
        if (!token) {
            groupName.textContent = '초대 링크를 확인할 수 없습니다.';
            showMessage('초대 토큰이 존재하지 않습니다.');
            return;
        }

        setButtonDisabled(true);

        try {
            const data = await getPublicInvitation(token);

            groupName.textContent = data.groupName;
            description.textContent =
                data.description || '그룹 소개가 없습니다.';

            memberCount.textContent = `${data.currentMemberCount}명`;
            capacity.textContent = `${data.capacity}명`;

            remainingCapacity.textContent =
                `${data.capacity - data.currentMemberCount}명`;

            joinable = data.joinable;

            acceptButton.disabled = !joinable;
            rejectButton.disabled = false;

            if (joinable) {
                showMessage(
                    '초대를 수락하고 그룹에 참여할 수 있습니다.',
                    'success'
                );
            } else {
                showMessage(
                    getBlockedMessage(data.joinBlockedReason),
                    'warning'
                );
            }

        } catch (error) {
            groupName.textContent = '초대 정보를 확인할 수 없습니다.';

            if (error.code === 'INVITE_INVALID') {
                showMessage(
                    '초대 링크가 만료되었거나 사용할 수 없습니다.'
                );
            } else if (
                error.code === 'RESOURCE_NOT_FOUND' ||
                error.code === 'NOT_FOUND'
            ) {
                showMessage('존재하지 않는 초대 링크입니다.');
            } else {
                showMessage(
                    error.message || '초대 정보를 불러오지 못했습니다.'
                );
            }
        }
    };

    const onAccept = async () => {
        if (submitting || acceptButton.disabled) return;

        submitting = true;
        setButtonDisabled(true);

        showMessage('초대를 수락하는 중입니다.', 'info');

        try {
            const result = await acceptInvitation(token);

            // 초대 수락 후 로그인이 필요한 경우 초대 흐름을 유지합니다.
            const nextRoute = result.nextRoute === '/login'
                ? '/login?returnRoute=INVITE'
                : result.nextRoute;

            navigate(nextRoute);

        } catch (error) {
            showMessage(
                error.message || '초대를 수락하지 못했습니다.'
            );

            submitting = false;
            acceptButton.disabled = !joinable;
            rejectButton.disabled = false;
        }
    };

    const onReject = async () => {
        if (submitting || rejectButton.disabled) return;

        submitting = true;
        setButtonDisabled(true);

        showMessage('초대를 거절하는 중입니다.', 'info');

        try {
            const result = await rejectInvitation(token);
            navigate(result.nextRoute);

        } catch (error) {
            showMessage(
                error.message || '초대를 거절하지 못했습니다.'
            );

            submitting = false;
            acceptButton.disabled = !joinable;
            rejectButton.disabled = false;
        }
    };

    acceptButton.addEventListener('click', onAccept);
    rejectButton.addEventListener('click', onReject);

    loadInvitation();

    return {
        element,
        cleanup: () => {
            acceptButton.removeEventListener('click', onAccept);
            rejectButton.removeEventListener('click', onReject);
        },
    };
}
