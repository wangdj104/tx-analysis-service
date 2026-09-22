import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const demoApp = readFileSync(new URL('../app.js', import.meta.url), 'utf8');
const realRoutes = readFileSync(`${projectRoot}/frontend/src/main.js`, 'utf8');
const realNavigation = readFileSync(`${projectRoot}/frontend/src/utils/workspaceNavigation.js`, 'utf8');

test('static demo keeps a page for every real system route family', () => {
  const routeToDemoPage = {
    '/care': 'care',
    '/care-journey': 'journey',
    '/clinical-workbench': 'clinical',
    '/doctor-workspace': 'overview',
    '/dialysis': 'dialysis',
    '/medical-record': 'records',
    '/medication': 'medications',
    '/dry-weight': 'dryweight',
    '/system/user': 'users',
    '/system/role': 'roles',
    '/system/patient': 'patientadmin',
    '/system/menu': 'menus',
    '/system/audit': 'audit',
    '/settings/notifications': 'notifications',
    '/health-analysis': 'analytics',
    '/bp-self-monitor': 'vitals',
    '/family-health': 'careplan',
    '/monitoring': 'monitoring'
  };

  for (const [route, page] of Object.entries(routeToDemoPage)) {
    assert.match(realRoutes, new RegExp(`path: '${route.replaceAll('/', '\\/')}'`));
    assert.match(demoApp, new RegExp(`(?:^|[, {])${page}(?:[:,}])`));
  }
});
test('static demo exposes every real navigation capability as an auditable action', () => {
  const featureKeys = [
    'dialysis-record', 'dialysis-trend', 'dialysis-ai', 'dry-weight',
    'record-list', 'record-upload', 'record-abnormal', 'record-trend',
    'drug-list', 'drug-ocr', 'drug-log', 'drug-category', 'drug-reminder',
    'complication', 'alert', 'bp-pattern', 'nutrition-diary',
    'nutrition-assessment', 'health-report', 'automation', 'data-export',
    'care-today', 'timeline', 'schedule', 'targets', 'visit-summary',
    'channel', 'test-delivery', 'delivery-log'
  ];

  for (const key of featureKeys) assert.ok(demoApp.includes(`['${key}'`), `missing demo feature ${key}`);
  for (const path of ['/dialysis', '/medical-record', '/medication', '/health-analysis', '/family-health', '/system/user']) {
    assert.ok(realNavigation.includes(`path: '${path}`), `real navigation fixture changed: ${path}`);
  }
  assert.match(demoApp, /M\.runFeature\(state,\s*el\.dataset\.key\)/);
  assert.match(demoApp, /function audit\(\)/);
});

test('care journey maps all forty requested medical workflows', () => {
  const block = demoApp.slice(demoApp.indexOf(',journey:['), demoApp.indexOf('    ]', demoApp.indexOf(',journey:[')));
  assert.equal([...block.matchAll(/\['[^']+'/g)].length, 40);
  for (const section of ['Daily chronic-disease management','Appointments and follow-up','Remote consultation','Inpatient and recovery','Home care','Emergency information','Child and maternity care','Mental health','Privacy and access','Clinical operations']) assert.ok(demoApp.includes(section));
});

test('new-user guide uses anchored step confirmation and changes by role', () => {
  assert.match(demoApp, /guideSteps=\{/);
  for (const role of ['doctor','patient','family','admin']) assert.match(demoApp, new RegExp(`${role}:\\[\\[`));
  assert.match(demoApp, /guide-next/);
  assert.match(demoApp, /guide-count/);
  assert.match(demoApp, /Confirm step and continue/);
  assert.match(demoApp, /function positionGuide\(target\)/);
  assert.match(demoApp, /guide-anchor/);
  assert.match(demoApp, /guide-popover--\$\{placement\}/);
  assert.match(demoApp, /guide-gesture/);
});

test('static demo exposes an interactive three-party live consultation', () => {
  assert.match(demoApp, /function consultation\(\)/);
  assert.match(demoApp, /id="chat-form"/);
  assert.match(demoApp, /M\.sendChatMessage/);
  assert.match(demoApp, /M\.addDemoReply/);
  for (const role of ['doctor', 'patient', 'family']) assert.match(demoApp, new RegExp(`${role}: \\{[^\\n]+consultation:`));
});
