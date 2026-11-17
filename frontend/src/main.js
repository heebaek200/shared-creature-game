// @ts-check
import 'bootstrap/dist/css/bootstrap.min.css';
import './styles/theme.css';
import './styles/responsive.css';
import { startApplication } from './app/bootstrap.js';
import { initializeTheme } from './components/app-theme.js';
import { initializeKeyboardFocusScroll } from './app/keyboard-focus-scroll.js';

initializeTheme();
initializeKeyboardFocusScroll();
startApplication(document.querySelector('#app'));
