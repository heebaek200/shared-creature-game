// @ts-check
import 'bootstrap/dist/css/bootstrap.min.css';
import './styles/theme.css';
import { startApplication } from './app/bootstrap.js';
import { initializeTheme } from './components/app-theme.js';

initializeTheme();
startApplication(document.querySelector('#app'));
