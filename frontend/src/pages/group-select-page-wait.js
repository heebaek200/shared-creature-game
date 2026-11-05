// // @ts-check
// import { createGroup } from '../features/group/api.js';
//
// /** createGroup 견본 Form과 결과 표시 Page를 생성합니다. */
// export function groupSelectPage() {
//   const element = document.createElement('main');
//   element.className = 'app-shell';
//   element.innerHTML = `<header class="page-header"><div><p class="eyebrow">새로운 공동체</p><h1>그룹 만들기</h1><p>함께 활동할 공개 그룹을 만들고 최초 초대 링크를 받아보세요.</p></div><button class="btn btn-outline-secondary theme-toggle" type="button">테마 전환</button></header><form class="app-card group-form"><div><label class="form-label" for="group-name">그룹 이름</label><input class="form-control" id="group-name" minlength="2" maxlength="30" required><div class="invalid-feedback"></div></div><div><label class="form-label" for="group-description">그룹 소개</label><textarea class="form-control" id="group-description" maxlength="500" rows="4"></textarea></div><button class="btn btn-primary" type="submit">그룹 생성</button><div class="alert d-none" role="status"></div></form>`;
//   const form = element.querySelector('form');
//   const submit = element.querySelector('[type="submit"]');
//   const alert = element.querySelector('.alert');
//   const theme = element.querySelector('.theme-toggle');
//
//   const onTheme = () => {
//     const next = document.documentElement.dataset.bsTheme === 'dark' ? 'light' : 'dark';
//     document.documentElement.dataset.bsTheme = next;
//     localStorage.setItem('theme', next);
//   };
//   const onSubmit = async (event) => {
//     event.preventDefault();
//     submit.disabled = true;
//     alert.className = 'alert d-none';
//     try {
//       const result = await createGroup({
//         name: element.querySelector('#group-name').value,
//         description: element.querySelector('#group-description').value,
//       });
//       alert.className = 'alert alert-success';
//       alert.textContent = `그룹을 만들었습니다. 초대 링크: ${result.initialInvitationUrl}`;
//       form.reset();
//     } catch (error) {
//       alert.className = 'alert alert-danger';
//       alert.textContent = error.message;
//     } finally {
//       submit.disabled = false;
//     }
//   };
//   form.addEventListener('submit', onSubmit);
//   theme.addEventListener('click', onTheme);
//   return {
//     element,
//     cleanup: () => {
//       form.removeEventListener('submit', onSubmit);
//       theme.removeEventListener('click', onTheme);
//     },
//   };
// }
