import assert from 'node:assert/strict';
import { test } from 'node:test';
import fs from 'node:fs';

const utility = new URL('../src/utils/healthAppearance.js', import.meta.url);
const exists = fs.existsSync(utility);
let theme;
function browser(saved = null) {
  const storage = new Map(saved == null ? [] : [['chengxin-health-appearance-v1', saved]]);
  const properties = new Map(), listeners = new Map();
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key)
  };
  globalThis.document = { documentElement: { dataset: {}, style: {
    setProperty: (key, value) => properties.set(key, value),
    removeProperty: key => properties.delete(key)
  } } };
  globalThis.window = { location: { href: 'http://localhost/' }, addEventListener: (type, handler) => listeners.set(type, handler), removeEventListener: type => listeners.delete(type) };
  return { storage, properties, listeners };
}

test('appearance utility exists for the approved preference flow', async () => {
  assert.ok(exists, 'missing healthAppearance.js preference implementation');
  theme = await import(utility);
});

const run = (name, fn) => test(name, { skip: !exists }, fn);
run('offers exactly five approved presets plus platform branding', () => {
  assert.deepEqual(theme.APPEARANCE_THEME_IDS, ['platform', 'white', 'blue', 'mint', 'sand', 'dark']);
});
run('starts from platform branding without changing unrelated browser preferences', () => {
  const env = browser(); env.storage.set('care-senior', 'true'); env.storage.set('currentPatientId', '7');
  theme.initializeAppearance();
  assert.equal(theme.appearanceTheme.value, 'platform');
  assert.equal(document.documentElement.dataset.healthTheme, 'platform');
  assert.equal(env.storage.get('care-senior'), 'true');
  assert.equal(env.storage.get('currentPatientId'), '7');
});
run('persists an explicit selection and restores it across a new locale boot', () => {
  const env = browser(); theme.initializeAppearance();
  assert.equal(theme.setAppearanceTheme('blue'), true);
  assert.equal(document.documentElement.dataset.healthTheme, 'blue');
  const saved = env.storage.get('chengxin-health-appearance-v1');
  assert.deepEqual(JSON.parse(saved), { version: 1, theme: 'blue' });
  browser(saved); theme.initializeAppearance();
  assert.equal(theme.appearanceTheme.value, 'blue');
});
run('accepts a legacy raw preset without touching branding storage', () => {
  const env = browser('mint'); env.storage.set('chengxin-demo-branding', '{"pageBackground":"#123456"}');
  theme.initializeAppearance();
  assert.equal(theme.appearanceTheme.value, 'mint');
  assert.equal(env.storage.get('chengxin-demo-branding'), '{"pageBackground":"#123456"}');
});
run('invalid, corrupt, wrong-version and prototype-like saved choices fall back safely', () => {
  for (const value of ['{broken', '"dark"', '{"version":2,"theme":"dark"}', '{"version":1,"theme":"constructor"}', '{"version":1,"theme":{}}', 'null']) {
    browser(value); theme.initializeAppearance();
    assert.equal(theme.appearanceTheme.value, 'platform', value);
  }
});
run('blocked storage reads fall back; failed writes still apply the live theme', () => {
  browser(); localStorage.getItem = () => { throw new Error('blocked'); };
  theme.initializeAppearance(); assert.equal(theme.appearanceTheme.value, 'platform');
  localStorage.setItem = () => { throw new Error('quota'); };
  assert.equal(theme.setAppearanceTheme('dark'), false);
  assert.equal(theme.appearanceTheme.value, 'dark');
  assert.equal(document.documentElement.dataset.healthTheme, 'dark');
});
run('invalid programmatic choices cannot become dataset or persisted CSS values', () => {
  const env = browser(); theme.initializeAppearance(); theme.setAppearanceTheme('url(https://untrusted.invalid)');
  assert.equal(document.documentElement.dataset.healthTheme, 'platform');
  assert.equal(JSON.parse(env.storage.get('chengxin-health-appearance-v1')).theme, 'platform');
});
run('late branding loads cannot override a personal preset; default preserves the branding color', () => {
  const env = browser(); theme.initializeAppearance(); theme.setAppearanceTheme('dark');
  theme.applyPlatformBackground('#BADCFE');
  assert.equal(env.properties.get('--platform-page-bg'), '#badcfe');
  assert.equal(theme.appearanceTheme.value, 'dark');
  assert.equal(env.properties.has('--app-page-bg'), false);
  theme.setAppearanceTheme('platform');
  assert.equal(env.properties.get('--platform-page-bg'), '#badcfe');
  assert.equal(document.documentElement.dataset.healthTheme, 'platform');
  theme.applyPlatformBackground('url(untrusted)');
  assert.equal(env.properties.get('--platform-page-bg'), '#f5f7fb');
});
run('other tabs synchronize choices without rewriting unrelated storage', () => {
  const env = browser(); const stop = theme.initializeAppearance();
  env.listeners.get('storage')({ key: 'chengxin-health-appearance-v1', newValue: '{"version":1,"theme":"sand"}' });
  assert.equal(theme.appearanceTheme.value, 'sand');
  assert.equal(env.storage.size, 0);
  env.listeners.get('storage')({ key: 'currentPatientId', newValue: '23' });
  assert.equal(theme.appearanceTheme.value, 'sand');
  env.listeners.get('storage')({ key: 'chengxin-health-appearance-v1', newValue: null });
  assert.equal(theme.appearanceTheme.value, 'platform');
  stop(); assert.equal(env.listeners.size, 0);
});
run('app selector is labeled and uses native keyboard navigation without changing patient context', () => {
  const selectorPath = new URL('../src/components/AppearanceSelector.vue', import.meta.url);
  assert.ok(fs.existsSync(selectorPath), 'missing labeled appearance selector');
  const selector = fs.readFileSync(selectorPath, 'utf8');
  assert.match(selector, /<label[^>]*class="appearance-selector"/);
  assert.match(selector, /<select[^>]*aria-label="[^"]+"/);
  assert.match(selector, /setAppearanceTheme/);
  assert.match(selector, /role="status"/);
  assert.doesNotMatch(selector, /currentPatient|router|location\.reload/);
  const app = fs.readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8');
  assert.match(app, /<AppearanceSelector\s*\/>/);
});
run('branding preview surfaces and text consume the active palette', () => {
  const view = fs.readFileSync(new URL('../src/views/PlatformBrandingManager.vue', import.meta.url), 'utf8');
  assert.match(view, /\.preview-window\{[^}]*background:var\(--paper\)/);
  assert.match(view, /\.branding-heading p\{[^}]*color:var\(--ink-500\)/);
});
run('branding application delegates the page color without writing a preset override', async () => {
  const env = browser();
  document.head = { appendChild() {} }; document.querySelector = () => ({ href: '' });
  const branding = await import('../src/utils/platformBranding.js');
  theme.initializeAppearance(); theme.setAppearanceTheme('dark');
  branding.applyPlatformBranding({ pageBackground: '#aabbcc', platformName: 'Example Clinic' });
  assert.equal(env.properties.get('--platform-page-bg'), '#aabbcc');
  assert.equal(env.properties.has('--app-page-bg'), false);
  assert.equal(document.documentElement.dataset.healthTheme, 'dark');
  assert.equal(document.title, 'Example Clinic');
});
