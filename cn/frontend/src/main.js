import { createApp } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import App from './App.vue';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import 'element-plus/dist/index.css';
import '@/styles/app-theme.css';
import '@/styles/mobile-optimization.css';
import '@/styles/health-redesign.css';
import '@/styles/care-accessibility.css';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';
import faviconUrl from '@/assets/logo.svg?url';
import i18n from '@/i18n';

function applyFavicon(href) {
  if (!href) return;
  let link = document.querySelector("link[rel='icon']");
  if (!link) {
    link = document.createElement('link');
    link.rel = 'icon';
    document.head.appendChild(link);
  }
  link.type = 'image/svg+xml';
  link.href = href;
}

applyFavicon(faviconUrl);

const app = createApp(App);
app.use(i18n);

// routeconfiguration
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/Login.vue'), meta: { hideNav: true } },
    { path: '/', redirect: '/care' },
    { path: '/care', component: () => import('./views/CareCenter.vue') },
    { path: '/clinical-workbench', component: () => import('./views/ClinicalWorkbench.vue') },
    { path: '/doctor-workspace', component: () => import('./views/DoctorWorkspace.vue') },
    { path: '/dashboard', redirect: '/monitoring' },
    { path: '/dialysis', component: () => import('./views/DialysisManager.vue') },
    { path: '/medical-record', component: () => import('./views/MedicalRecordManager.vue') },
    { path: '/medication', component: () => import('./views/MedicationManager.vue') },
    { path: '/dry-weight', component: () => import('./views/DryWeightManager.vue') },
    { path: '/system/user', component: () => import('./views/UserManager.vue') },
    { path: '/system/role', component: () => import('./views/RoleManager.vue') },
    { path: '/system/patient', component: () => import('./views/PatientManager.vue') },
    { path: '/system/menu', component: () => import('./views/MenuManager.vue') },
    { path: '/system/audit', component: () => import('./views/AuditLogManager.vue') },
    { path: '/settings/notifications', component: () => import('./views/NotificationSettings.vue') },
    { path: '/health-analysis', component: () => import('./views/HealthAnalysisManager.vue') },
    { path: '/complication', redirect: '/health-analysis?tab=complication' },
    { path: '/alert', redirect: '/health-analysis?tab=alert' },
    { path: '/bp-pattern', redirect: '/health-analysis?tab=bp-pattern' },
    { path: '/nutrition', redirect: '/health-analysis?tab=nutrition' },
    { path: '/health-report', redirect: '/health-analysis?tab=health-report' },
    { path: '/data-export', redirect: '/health-analysis?tab=data-export' },
    { path: '/bp-self-monitor', component: () => import('./views/BpSelfMonitorManager.vue') },
    { path: '/family-health', component: () => import('./views/FamilyHealthManager.vue') },
    { path: '/monitoring', component: () => import('./views/MonitoringCenter.vue') }
  ]
});

// routeguard
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token');

  if (to.path === '/login') {
    next();
    return;
  }

  if (!token) {
    next('/login');
    return;
  }

  let menuPaths = [];
  let roleCodes = [];
  try {
    menuPaths = JSON.parse(localStorage.getItem('userMenus') || '[]');
    roleCodes = JSON.parse(localStorage.getItem('userRoleCodes') || '[]');
  } catch {
    menuPaths = [];
  }

  const isAdmin = roleCodes.includes('admin');
  const exactTarget = to.fullPath;
  const hasQuery = exactTarget.includes('?');
  // medicationmodule Upload and Recognize, Browse Categoriesoriginalthis onlystorein pagewithinsidebar, currentalready andentermainnavigation;
  // onlyneeduserhavehas medicationmodulePermission, thencontinueallowaccessalreadyhas featurepagetagandRemindersettings.
  const medicationLegacyTabAllowed =
    ['/medication?tab=upload', '/medication?tab=category', '/medication?tab=remind'].includes(exactTarget) &&
    menuPaths.includes('/medication');
  const basicCarePath = ['/care','/family-health','/medication','/bp-self-monitor','/medical-record','/settings/notifications'].includes(to.path);
  const allowed = basicCarePath || isAdmin || medicationLegacyTabAllowed || menuPaths.length === 0 || menuPaths.includes(exactTarget) ||
    (!hasQuery && (menuPaths.includes(to.path) || menuPaths.some(path => path.startsWith(`${to.path}?`))));

  if (!allowed) {
    next('/monitoring');
    return;
  }
  next();
});
app.use(router);
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component);
}
app.use(ElementPlus, {
  locale: zhCn,
  zIndex: 3000,
  message: {
    duration: 2500,
    showClose: true,
    grouping: true
  },
  notification: {
    duration: 3500,
    showClose: true
  }
});
app.mount('#app');
