import {
    getPublicInvitation,
    acceptInvitation,
    rejectInvitation,
} from '../features/group/api.js';


/**
 * 초대 정보를 표시하고 수락·거절을 하는 Page 입니다.
 */
export function invitePage({ navigate }) {
    const element = document.createElement('main');
    element.className = 'app-shell';

    element.innerHTML = `
    <header class="page-header">
      <div>
        <p class="eyebrow">함께 키우는 공동체</p>
        <h1>그룹 초대</h1>
        <p>초대받은 그룹의 정보를 확인해 보세요</p>
      </div>
      <button class="btn btn-secondary theme-toggle" type="button">
      테마 전환
      </button>
    </header>
    
    <section class="app-card invite-card">
        <div class="invite-content">
            <p class="eyebrow">GROUP INVITATION</p>
            <h2 class="group-name">초대 정보를 불러오는 중...</h2>
            <p class="group-description"></p>
            
            <div class="group-info">
            <div>
              <span>현재 인원</span>   
              <strong class="member-count">-</strong>              
            </div>
            <div>
              <span>그룹 정원</span>
              <strong class="capacity">-</strong>
            </div>
            <div>
              <span>가입 가능 인원</span>
              <strong class="remaining-capacity">-</strong>
            </div>
          </div>
        </div>   
        
        <div class="alert d-none" role="status" aria-live="polite"></div>
        
        <div class="invite-actions">
            <button class="btn btn-primary accept-button" type="button" disabled>초대 수락</button>
            <button class="btn btn-outline-secondary reject-button" type="button" disabled>초대 거절</button>
        </div>
    </section>
`

    const groupName = element.querySelector('.group-name')
    const description  = element.querySelector('.group-description')
    const memberCount  = element.querySelector('.member-count')
    const capacity  = element.querySelector('.capacity')
    const remainingCapacity  = element.querySelector('.remaining-capacity')
    const alert  = element.querySelector('.alert')
    const acceptButton  = element.querySelector('.accept-button')
    const rejectButton  = element.querySelector('.reject-button')
    const themeButton = element.querySelector('.theme-toggle')


    const token = location.pathname.split('/').filter(Boolean).at(-1);

    let submitting = false;

    const showMessage = (message, type = 'danger') => {
        alert.className = `alert alert-${type}`;
        alert.textContent = message;
    };

    const getBlockedMessage = (reason) => {
        const messages = {
            GROUP_FULL: '그룹 정원이 다 찾습니다',
            JOIN_COOLDOWN_ACTIVE: '24시간 가입 제한이 적용 중입니다',
            INVITE_INVALID: '유효하지 않거나 만료된 초대 링크입니다',
            ALREADY_IN_GROUP: '이미 다른 그룹에 가입되어 있습니다'
        };

        return messages[reason] ?? '현재 그룹에 가입할 수 없습니다';
    };

    const setButtonDisabled = (disabled) => {
        acceptButton.disabled = disabled;
        rejectButton.disabled = disabled;
    };

    const loadInvitation = async () => {
        if (!token) {
            groupName.textContent = '초대 링크 요청';
            showMessage('초대 token을 확인할 수 없습니다');
            return;
        }

        setButtonDisabled(true);

        try {
            const data = await getPublicInvitation(token);

            groupName.textContent = data.groupName;
            description.textContent = data.description;

            memberCount.textContent = `${data.currentMemberCount}명`;
            capacity.textContent = `${data.capacity}명`
            remainingCapacity.textContent = `${data.capacity - data.currentMemberCount}명`

            if (data.joinable) {
                showMessage('그룹에 가입할 수 있습니다', 'success');
                acceptButton.disabled = false;
            }
        } catch (error) {
            groupName.textContent = '초대 정보를 확인할 수 없습니다';

            if (error.code === 'INVITE_INVALID') {
                showMessage('초대 링크가 만료되었거나 사용할 수 없습니다.');
            } else if (error.code === 'RESOURCE_NOT_FOUND') {
                showMessage('존재하지 않는 초대 링크입니다.');
            } else {
                showMessage(error.message || '초대 정보를 불러오지 못했습니다.');
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
            navigate(result.nextRoute);
        } catch (error) {
            showMessage(
                error.message || '초대를 수락하지 못했습니다.'
            );
            submitting = false;
            rejectButton.disabled = false;
        }
    };

    const onReject = async () => {
        if (submitting || rejectButton.disabled) return;

        submitting = true;
        setButtonDisabled(true);
        showMessage('초대를 거절하는 중입니다', 'info');

        try {
            const result = await rejectInvitation(token);
            navigate(result.nextRoute);
        } catch (error) {
            showMessage(
                error.message || '초대를 거절하지 못했습니다.'
            );
            submitting = false;
            rejectButton.disabled = false;
        }
    };

    const onTheme = () => {
        const currentTheme = document.documentElement.dataset.bsTheme;
        const nextTheme = currentTheme === 'dark' ? 'light' : 'dark';

        document.documentElement.dataset.bsTheme = nextTheme;
        localStorage.setItem('theme', nextTheme);
    };

    acceptButton.addEventListener('click', onAccept)
    rejectButton.addEventListener('click', onReject)
    themeButton.addEventListener('click', onTheme)

    loadInvitation();

    return {
        element,
        cleanup: () => {
            acceptButton.removeEventListener('click', onAccept)
            rejectButton.removeEventListener('click', onReject)
            themeButton.removeEventListener('click', onTheme)
        },
    };
}