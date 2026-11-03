// @ts-check
import 'bootstrap/dist/css/bootstrap.min.css';
import './styles/theme.css';
import { startApplication } from './app/bootstrap.js';

applyStoredTheme();
startApplication(document.querySelector('#app'));

/** 첫 화면 전에 저장된 테마를 적용해 색상 깜빡임을 줄입니다. */
function applyStoredTheme() {
  const preference = localStorage.getItem('theme') ?? 'system';
  const dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  const resolved = preference === 'system' ? (dark ? 'dark' : 'light') : preference;
  document.documentElement.dataset.bsTheme = resolved;
}
