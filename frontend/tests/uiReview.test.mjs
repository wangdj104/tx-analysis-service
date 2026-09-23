import assert from 'node:assert/strict';
import test, { beforeEach } from 'node:test';
import fs from 'node:fs';
import { ref, reactive, computed, watch, nextTick } from 'vue';
import * as sessions from '../src/utils/authSession.js';
import * as navigation from '../src/utils/workspaceNavigation.js';
import * as access from '../src/utils/workspaceAccess.js';
import * as specialtyNavigation from '../src/utils/patientSpecialtyNavigation.js';

beforeEach(() => {
  const storage = new Map();
  globalThis.localStorage = {
    get length() { return storage.size; },
    key: index => [...storage.keys()][index] ?? null,
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: key => storage.delete(key)
  };
  globalThis.window = Object.assign(new EventTarget(), { location: { pathname: '/monitoring', href: '' } });
  globalThis.document = { title: '', body: { classList: { toggle() {} } } };
});

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

// runactual SFC setup source code, replacechangebrowser/requestborder; not Copybymeasureloadlogic.
function setupSfc(file, bindings, returned) {
  const content = fs.readFileSync(new URL('../src/' + file, import.meta.url), 'utf8');
  const source = content.match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import[\s\S]*?from\s+['"][^'"]+['"];?\r?$/gm, '');
  const dependencies = {
    console: { error() {} }, ref, reactive, computed, watch,
    platformBranding: reactive({ platformName: 'Chengxin Health' }),
    onMounted() {}, onUnmounted() {}, provide() {},
    useMedicationNotifications: () => ({}),
    MarkLineComponent: {},
    localDateKey: () => '2026-09-17',
    replaceTarget: (target, value) => {
      Object.keys(target).forEach(key => delete target[key]);
      if (value) Object.assign(target, value);
    },
    ...sessions, ...navigation, ...access, ...specialtyNavigation,
    clearPatientSpecialtyScope() {}, loadPatientSpecialtyScope: async () => ({ restrictedPaths: [], allowedPaths: [] }), ...bindings
  };
  return new Function(...Object.keys(dependencies), source + '\nreturn {' + returned.join(',') + '}')(...Object.values(dependencies));
}

function info(id, roles = [], menus = ['/monitoring']) {
  return { code: 200, data: { user: { id, username: id }, roles: roles.map(roleCode => ({ roleCode, roleName: roleCode })), menus: menus.map((menuPath, index) => ({ id: index + 1, parentId: 0, menuPath, menuName: menuPath })) } };
}

function setupApp(getUserInfo, getPatientNames = async () => ({ code: 200, data: [] }), initialRoute = {}, ready = async () => {}) {
  const route = reactive({ path: '/monitoring', query: {}, meta: {}, ...initialRoute });
  const patientList = ref([]);
  const redirects = [];
  const app = setupSfc('App.vue', {
    useRoute: () => route,
    useRouter: () => ({ resolve: path => { const url = new URL(path || '/', 'http://test'); return { path: url.pathname, query: Object.fromEntries(url.searchParams) }; }, replace: path => redirects.push(path), isReady: ready }),
    useMobile: () => ({ isMobile: ref(false) }),
    useCurrentPatient: () => ({ currentPatientId: ref(null), setPatientList: value => { patientList.value = value; } }),
    getUserInfo, getPatientNames, ElMessage: { success() {}, info() {} }
  }, ['loadUserMenus', 'resetNavState', 'userInfo', 'userMenus', 'appPatientList', 'navItems', 'menuLoadError']);
  return { ...app, patientList, route, redirects };
}

test('late account A info cannot overwrite account B identity, permissions or navigation', async () => {
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  const pending = deferred();
  const app = setupApp(() => localStorage.getItem('token') === 'A' ? pending.promise : Promise.resolve(info('B')));
  const first = app.loadUserMenus();
  sessions.clearAuthSession(); app.resetNavState();
  sessions.saveAuthSession({ token: 'B', userId: 'B' });
  await app.loadUserMenus();
  pending.resolve(info('A', ['admin'], ['/system/user']));
  await first;
  assert.equal(localStorage.getItem('token'), 'B');
  assert.equal(localStorage.getItem('userId'), 'B');
  assert.equal(app.userInfo.value.username, 'B');
  assert.deepEqual(sessions.readPermissionCache().roleCodes, []);
  assert.deepEqual(app.navItems.value.map(item => item.path), ['/care', '/monitoring']);
});

test('late account A patient list cannot overwrite account B patients', async () => {
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  const pending = deferred(), patientRequested = deferred();
  const app = setupApp(async () => info(localStorage.getItem('userId')), () => {
    if (localStorage.getItem('token') === 'A') { patientRequested.resolve(); return pending.promise; }
    return Promise.resolve({ code: 200, data: [{ id: 2, patientName: 'BFamily Member' }] });
  });
  const first = app.loadUserMenus();
  await patientRequested.promise;
  sessions.clearAuthSession(); app.resetNavState();
  sessions.saveAuthSession({ token: 'B', userId: 'B' });
  await app.loadUserMenus();
  pending.resolve({ code: 200, data: [{ id: 1, patientName: 'AFamily Member' }] });
  await first;
  assert.deepEqual(app.appPatientList.value.map(item => item.id), [2]);
  assert.deepEqual(app.patientList.value.map(item => item.id), [2]);
});

test('newest menu request wins within the same session', async () => {
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  const pending = deferred(); let calls = 0;
  const app = setupApp(() => ++calls === 1 ? pending.promise : Promise.resolve(info('A', [], ['/medical-record'])));
  const first = app.loadUserMenus();
  await app.loadUserMenus();
  pending.resolve(info('A', ['admin'], ['/system/user'])); await first;
  assert.deepEqual(app.userMenus.value, ['/medical-record']);
  assert.deepEqual(sessions.readPermissionCache().roleCodes, []);
});

test('unknown permissions fail closed with retry state; same-session verified cache remains usable', async () => {
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  const app = setupApp(async () => { throw new Error('offline'); });
  await app.loadUserMenus();
  assert.deepEqual(app.navItems.value.map(item => item.path), ['/care', '/monitoring']);
  assert.ok(app.menuLoadError.value);
  sessions.savePermissionCache({ menuPaths: ['/medical-record'] });
  await app.loadUserMenus();
  assert.ok(app.navItems.value.some(item => item.path === '/medical-record'));
  sessions.saveAuthSession({ token: 'B', userId: 'B' });
  assert.equal(sessions.readPermissionCache(), null);
  assert.equal(localStorage.getItem('userMenus'), null);
});

test('logout clears patient selection and cached permissions; malformed caches are untrusted', () => {
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  sessions.savePermissionCache({ menuPaths: ['/system/user'], roleCodes: ['admin'] });
  localStorage.setItem('currentPatientId', '1');
  localStorage.setItem('userMenus', '{}');
  assert.equal(sessions.readPermissionCache(), null);
  const oldSession = sessions.captureAuthSession();
  sessions.clearAuthSession();
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  assert.equal(sessions.isAuthSessionCurrent(oldSession), false);
  assert.equal(localStorage.getItem('currentPatientId'), null);
  assert.equal(localStorage.getItem('userRoleCodes'), null);
});

test('logout deletes medical offline copies without clearing unrelated preferences', () => {
  localStorage.setItem('offlineEmergencyCard:7:1', '{"patient":{"id":1}}');
  localStorage.setItem('offlineEmergencyCard:1', 'legacy copy');
  localStorage.setItem('care-profile:1', '{}');
  localStorage.setItem('table-cols:patient', '[]');
  sessions.clearAuthSession();
  assert.equal(localStorage.getItem('offlineEmergencyCard:7:1'), null);
  assert.equal(localStorage.getItem('offlineEmergencyCard:1'), null);
  assert.equal(localStorage.getItem('care-profile:1'), null);
  assert.equal(localStorage.getItem('table-cols:patient'), '[]');
});

function setupRequestInterceptors() {
  let beforeRequest, onResponse, onError;
  const request = { interceptors: { request: { use: handler => { beforeRequest = handler; } }, response: { use: (success, failure) => { onResponse = success; onError = failure; } } } };
  const source = fs.readFileSync(new URL('../src/utils/request.js', import.meta.url), 'utf8')
    .replace(/^import.*$/gm, '').replace('export default request;', '');
  const bindings = { console: { error() {} }, axios: { create: () => request }, ElMessage: { error() {} }, ...sessions };
  new Function(...Object.keys(bindings), source)(...Object.values(bindings));
  return { beforeRequest, onResponse, onError };
}

test('late HTTP and business 401 from A cannot clear or redirect B, while current 401 expires B', async () => {
  const { beforeRequest, onResponse, onError } = setupRequestInterceptors();
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  const oldConfig = beforeRequest({ headers: {} });
  sessions.saveAuthSession({ token: 'B', userId: 'B' });
  await assert.rejects(onResponse({ config: oldConfig, data: { code: 401 } }));
  await assert.rejects(onError({ config: oldConfig, response: { status: 401 } }));
  assert.equal(localStorage.getItem('token'), 'B');
  assert.equal(window.location.href, '');
  const currentConfig = beforeRequest({ headers: {} });
  await assert.rejects(onResponse({ config: currentConfig, data: { code: 401 } }));
  assert.equal(localStorage.getItem('token'), null);
  assert.equal(window.location.href, '/login');
});

test('binary downloads reject JSON error envelopes rather than saving invalid files', async () => {
  const { beforeRequest, onResponse, onError } = setupRequestInterceptors();
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  const config = beforeRequest({ headers: {}, responseType: 'blob' });
  const csv = new Blob(['date,value\n2026-09-22,120'], { type: 'text/csv' });
  assert.equal(await onResponse({ config, data: csv }), csv);
  await assert.rejects(onResponse({ config, data: new Blob([JSON.stringify({ code: 403, msg: 'denied' })], { type: 'application/json' }) }), /denied/);
  const error = { config, response: { status: 400, data: new Blob([JSON.stringify({ code: 400, msg: 'invalid' })], { type: 'application/json' }) } };
  await assert.rejects(onError(error));
  assert.equal(error.response.data.msg, 'invalid');
  await assert.rejects(onResponse({ config, data: new Blob([JSON.stringify({ code: 401 })], { type: 'application/json' }) }));
  assert.equal(localStorage.getItem('token'), null);
});

function setupMonitoring(getMonitoringSnapshot) {
  return setupSfc('views/MonitoringCenter.vue', {
    use() {}, CanvasRenderer: {}, LineChart: {}, GridComponent: {}, TooltipComponent: {}, LegendComponent: {},
    ElMessage: { error() {}, success() {} },
    inject: () => ref([]), watch() {},
    useCurrentPatient: () => ({ currentPatientId: ref(1), currentPatientName: ref('test') }),
    getMonitoringSnapshot
  }, ['loadSnapshot', 'days', 'loadedDays', 'snapshot', 'loadError', 'chartOption']);
}

test('failed 7-day reload restores the selector to the displayed 90-day dataset', async () => {
  let fail = false;
  const view = setupMonitoring(async (_patient, days) => { if (fail) throw new Error('offline'); return { code: 200, data: { vitalTrend: [{ systolic: days }] } }; });
  view.days.value = 90; await view.loadSnapshot();
  fail = true; view.days.value = 7; await view.loadSnapshot();
  assert.equal(view.days.value, 90);
  assert.equal(view.loadedDays.value, 90);
  assert.equal(view.snapshot.value.vitalTrend[0].systolic, 90);
  assert.equal(view.loadError.value, 'offline');
});

test('fast range changes ignore late success and failure from older ranges', async () => {
  const pending90 = deferred(), pending30 = deferred();
  const view = setupMonitoring((_patient, days) => days === 90 ? pending90.promise : days === 30 ? pending30.promise : Promise.resolve({ code: 200, data: { vitalTrend: [{ systolic: 7 }] } }));
  view.days.value = 90; const first = view.loadSnapshot();
  view.days.value = 30; const second = view.loadSnapshot();
  view.days.value = 7; await view.loadSnapshot();
  pending90.resolve({ code: 200, data: { vitalTrend: [{ systolic: 90 }] } });
  pending30.reject(new Error('old failure')); await Promise.all([first, second]);
  assert.equal(view.days.value, 7);
  assert.equal(view.loadedDays.value, 7);
  assert.equal(view.snapshot.value.vitalTrend[0].systolic, 7);
  assert.equal(view.loadError.value, '');
});

test('combined vital chart explains shared abnormal flags and uses non-HTML tooltips', () => {
  const view = setupMonitoring(async () => ({}));
  view.snapshot.value = { vitalTrend: [{ systolic: 120, diastolic: 80, glucose: 12, abnormal: true }] };
  const chart = view.chartOption.value;
  assert.equal(chart.tooltip.renderMode, 'richText');
  assert.match(chart.tooltip.formatter([{ dataIndex: 0, axisValueLabel: 'measurementTime', seriesName: 'indicator', value: 120 }]), /abnormal marker/);
  assert.equal(chart.series[0].data[0].symbol, 'diamond');
});

test('fine-grained list permission does not grant upload, while explicit parent preserves it', () => {
  assert.equal(access.canAccessWorkspace('/medical-record?tab=upload', ['/medical-record?tab=list']), false);
  assert.equal(access.canAccessWorkspace('/medical-record?tab=upload', ['/medical-record']), true);
  assert.equal(access.canAccessWorkspace('/family-health?tab=today', ['/family-health?tab=schedule']), false);
  assert.equal(access.canAccessWorkspace('/family-health?tab=target', ['/family-health']), true);
});


test('loaded permissions reactively revoke cached upload and family tabs', () => {
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  sessions.savePermissionCache({ roleCodes: ['admin'] });
  const menus = ref([]);
  const dependencies = {
    inject: () => menus,
    useRoute: () => reactive({ path: '/medical-record', query: { tab: 'list' } }),
    useRouter: () => ({ push() {} }),
    useCurrentPatient: () => ({ currentPatientId: ref(null) }),
    useMobile: () => ({ isMobile: ref(false) }),
    useTableColumns: () => ({}),
    use() {}, CanvasRenderer: {}, EchartsLineChart: {}, GridComponent: {}, TooltipComponent: {}, LegendComponent: {}, TitleComponent: {}
  };
  const medical = setupSfc('views/MedicalRecordManager.vue', dependencies, ['canUpload']);
  const family = setupSfc('views/FamilyHealthManager.vue', dependencies, ['availableTabs']);
  assert.equal(medical.canUpload.value, true);
  assert.equal(family.availableTabs.value.length, 5);
  sessions.savePermissionCache({ menuPaths: ['/medical-record?tab=list', '/family-health?tab=schedule'] });
  menus.value = ['/medical-record?tab=list', '/family-health?tab=schedule'];
  assert.equal(medical.canUpload.value, false);
  assert.deepEqual(family.availableTabs.value, ['schedule']);
});


test('medical upload navigation updates URL, resets a new upload, and keeps drafts across tab-only navigation', async () => {
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  sessions.savePermissionCache({ menuPaths: ['/medical-record'] });
  const route = reactive({ path: '/medical-record', query: { tab: 'list' } });
  const view = setupSfc('views/MedicalRecordManager.vue', {
    inject: () => ref(['/medical-record']),
    useRoute: () => route,
    useRouter: () => ({ push: async target => { route.query = target.query; } }),
    useCurrentPatient: () => ({ currentPatientId: ref(null) }),
    useMobile: () => ({ isMobile: ref(false) }),
    useTableColumns: () => ({}),
    api: { listRecords: async () => ({ code: 200, data: [] }) },
    use() {}, CanvasRenderer: {}, EchartsLineChart: {}, GridComponent: {}, TooltipComponent: {}, LegendComponent: {}, TitleComponent: {}
  }, ['goUpload', 'activeMenu', 'uploadForm']);
  view.uploadForm.patientName = 'old draft';
  await view.goUpload(); await nextTick();
  assert.equal(route.query.tab, 'upload');
  assert.equal(view.activeMenu.value, 'upload');
  assert.equal(view.uploadForm.patientName, '');
  view.uploadForm.patientName = 'new draft';
  route.query = { tab: 'list' }; await nextTick();
  assert.equal(view.activeMenu.value, 'list');
  route.query = { tab: 'upload' }; await nextTick();
  assert.equal(view.activeMenu.value, 'upload');
  assert.equal(view.uploadForm.patientName, 'new draft');
});

test('family internal tabs and top navigation share the route state', async () => {
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  sessions.savePermissionCache({ menuPaths: ['/family-health'] });
  const route = reactive({ path: '/family-health', query: { tab: 'schedule' } });
  const view = setupSfc('views/FamilyHealthManager.vue', {
    inject: () => ref(['/family-health']),
    useRoute: () => route,
    useRouter: () => ({ push: async target => { route.query = target.query; } }),
    useCurrentPatient: () => ({ currentPatientId: ref(null) })
  }, ['tab']);
  assert.equal(view.tab.value, 'schedule');
  view.tab.value = 'today'; await nextTick();
  assert.equal(route.query.tab, 'today');
  route.query = { tab: 'schedule' }; await nextTick();
  assert.equal(view.tab.value, 'schedule');
});


test('permission refresh also leaves a current page that is no longer authorized', async () => {
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  sessions.savePermissionCache({ roleCodes: ['admin'] });
  const app = setupApp(async () => info('A', [], ['/medical-record?tab=list']), undefined, { path: '/medical-record', fullPath: '/medical-record?tab=upload' });
  await app.loadUserMenus();
  assert.equal(app.redirects.at(-1), '/monitoring');
});


test('initial route verification waits for router readiness before resolving a report-only entry', async () => {
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  const pendingReady = deferred(), readyEntered = deferred();
  const app = setupApp(async () => info('A', [], ['/health-analysis?tab=health-report']), undefined,
    { path: '/', fullPath: '/' }, () => { readyEntered.resolve(); return pendingReady.promise; });
  const loading = app.loadUserMenus();
  await readyEntered.promise;
  assert.deepEqual(app.redirects, []);
  app.route.path = '/health-analysis';
  app.route.fullPath = '/health-analysis';
  pendingReady.resolve(); await loading;
  assert.deepEqual(app.redirects, ['/health-analysis?tab=health-report']);
});

test('switching sessions while router readiness is pending invalidates the old redirect and patient load', async () => {
  sessions.saveAuthSession({ token: 'A', userId: 'A' });
  const pendingReady = deferred(), readyEntered = deferred();
  const patientCalls = [];
  const app = setupApp(async () => localStorage.getItem('token') === 'A'
    ? info('A', [], ['/health-analysis?tab=health-report']) : info('B', [], ['/medical-record']),
    async () => { patientCalls.push(localStorage.getItem('token')); return { code: 200, data: [{ id: 2 }] }; },
    { path: '/medical-record', fullPath: '/medical-record' },
    () => { if (localStorage.getItem('token') === 'A') { readyEntered.resolve(); return pendingReady.promise; } return Promise.resolve(); });
  const first = app.loadUserMenus(); await readyEntered.promise;
  sessions.clearAuthSession(); app.resetNavState();
  sessions.saveAuthSession({ token: 'B', userId: 'B' });
  await app.loadUserMenus();
  pendingReady.resolve(); await first;
  assert.deepEqual(app.redirects, []);
  assert.deepEqual(patientCalls, ['B']);
  assert.equal(app.userInfo.value.username, 'B');
  assert.deepEqual(app.userMenus.value, ['/medical-record']);
});
