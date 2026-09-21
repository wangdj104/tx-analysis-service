import assert from 'node:assert/strict';
import test from 'node:test';
import { getFallbackWorkspaceMenus, normalizeWorkspaceMenus } from '../src/utils/workspaceNavigation.js';

function allPaths(items) {
  return items.flatMap(item => [item.path, ...allPaths(item.children || [])]).filter(Boolean);
}

test('parent permissions preserve default pages and existing internal upload actions', () => {
  const result = normalizeWorkspaceMenus([
    { path: '/dialysis', label: 'Dialysis' },
    { path: '/medical-record', label: 'record' },
    { path: '/medication', label: 'medication' },
    { path: '/health-analysis', label: 'analysis' }
  ], { menuPaths: ['/dialysis', '/medical-record', '/medication', '/health-analysis'] });

  assert.deepEqual(allPaths(result), [
    '/dialysis', '/dialysis?tab=data', '/medical-record', '/medical-record?tab=list', '/medical-record?tab=upload', '/medication',
    '/medication?tab=drugs', '/medication?tab=upload', '/medication?tab=category', '/health-analysis',
    '/health-analysis?tab=complication', '/health-analysis?tab=automation'
  ]);
});

test('fine-grained query permissions expose only exact tabs and their bare module', () => {
  const result = normalizeWorkspaceMenus([
    { path: '/dialysis', label: 'Dialysis' },
    { path: '/medication', label: 'medication' },
    { path: '/health-analysis', label: 'analysis' }
  ], { menuPaths: ['/dialysis?tab=analysis', '/medication?tab=logs', '/health-analysis?tab=health-report'] });

  assert.deepEqual(allPaths(result), [
    '/dialysis', '/dialysis?tab=analysis', '/medication', '/medication?tab=logs',
    '/health-analysis', '/health-analysis?tab=health-report'
  ]);
});

test('query access remains exact even with additional parameters or a hash', () => {
  const result = normalizeWorkspaceMenus([
    { path: '/dialysis?tab=analysis', label: 'trend' },
    { path: '/dialysis?tab=analysis&patientId=1', label: 'extra addreferencecount' },
    { path: '/dialysis?tab=analysis#chart', label: 'extra addanchor point' },
    { path: '/dialysis#chart', label: 'moduleanchor point' },
    { path: '/medication?tab=upload&patientId=1', label: 'medicationextra addreferencecount' }
  ], { menuPaths: ['/dialysis?tab=analysis', '/medication'] });

  assert.deepEqual(allPaths(result), ['/dialysis?tab=analysis', '/dialysis#chart']);
});

test('administrators receive all standard tabs despite restricted menu paths', () => {
  const result = normalizeWorkspaceMenus([
    { path: '/dialysis', label: 'Dialysis' },
    { path: '/medical-record', label: 'record' },
    { path: '/medication', label: 'medication' },
    { path: '/health-analysis', label: 'analysis' }
  ], { menuPaths: ['/monitoring'], roleCodes: ['admin'] });

  assert.deepEqual(result.map(item => item.children.length), [4, 4, 5, 8]);
  assert.ok(result.every(item => item.children.every(child => typeof child.icon === 'string')));
});

test('unknown and explicitly empty permissions expose only the safe workspace', () => {
  assert.deepEqual(normalizeWorkspaceMenus([{ path: '/health-analysis', label: 'analysis' }]), []);
  assert.deepEqual(allPaths(getFallbackWorkspaceMenus()), ['/monitoring']);
});

test('backend groups, custom labels and icons take priority over defaults', () => {
  const customIcon = { name: 'custom-icon' };
  const input = [{ label: 'I health', icon: customIcon, children: [
    { path: '/medical-record', label: 'I Report', icon: 'Document', custom: true, children: [
      { path: '/medical-record?tab=upload', label: 'Scan Report', icon: 'Camera', iconTheme: 'custom' }
    ] }
  ] }];
  const result = normalizeWorkspaceMenus(input, { roleCodes: ['admin'] });

  assert.equal(result[0].label, 'I health');
  assert.equal(result[0].icon, customIcon);
  assert.equal(result[0].children[0].label, 'I Report');
  assert.equal(result[0].children[0].custom, true);
  assert.deepEqual(result[0].children[0].children[0], {
    path: '/medical-record?tab=upload', entryPath: '/medical-record?tab=upload', label: 'Scan Report', icon: 'Camera', iconTheme: 'custom', children: []
  });
  assert.equal(input[0].children[0].children.length, 1);
  assert.notEqual(result[0].children, input[0].children);
});

test('duplicate paths are merged while the first backend label is preserved', () => {
  const result = normalizeWorkspaceMenus([
    { path: '/medication', label: 'I Medication', children: [
      { path: '/medication?tab=logs', label: 'I medication intakehistory', icon: 'Notebook' }
    ] },
    { path: '/medication', label: 'duplicateMedication', children: [
      { path: '/medication?tab=upload', label: 'Scan medicationbox', icon: 'Camera' }
    ] },
    { path: '/medication?tab=logs', label: 'duplicaterecord' }
  ], { roleCodes: ['admin'] });
  const paths = allPaths(result);

  assert.equal(result.length, 1);
  assert.equal(result[0].label, 'I Medication');
  assert.equal(result[0].children[0].label, 'I medication intakehistory');
  assert.equal(result[0].children[1].label, 'Scan medicationbox');
  assert.equal(paths.length, new Set(paths).size);
  assert.equal(result[0].children.length, 5);
});

test('existing backend tabs remain in their original group instead of being duplicated', () => {
  const result = normalizeWorkspaceMenus([
    { path: '/health-analysis', label: 'analysis' },
    { label: 'commonReport', children: [
      { path: '/health-analysis?tab=health-report', label: 'I Health Report', icon: 'Document' }
    ] }
  ], { roleCodes: ['admin'] });

  assert.equal(result[0].children.some(item => item.path === '/health-analysis?tab=health-report'), false);
  assert.equal(result[1].children[0].label, 'I Health Report');
  assert.equal(allPaths(result).filter(path => path === '/health-analysis?tab=health-report').length, 1);
});

test('an inaccessible parent becomes a group when an accessible child remains', () => {
  const result = normalizeWorkspaceMenus([
    { path: '/unavailable', label: 'healthrecord', icon: 'Folder', children: [
      { path: '/medical-record?tab=list', label: 'record' },
      { path: '/system/user', label: 'user' }
    ] },
    { path: '/also-unavailable', label: 'Noneentry', children: [
      { path: '/system/role', label: 'Role' }
    ] }
  ], { menuPaths: ['/medical-record?tab=list'] });

  assert.equal(result.length, 1);
  assert.equal(result[0].path, '');
  assert.equal(result[0].label, 'healthrecord');
  assert.deepEqual(allPaths(result), ['/medical-record?tab=list']);
});

test('monitoring remains accessible even with unrelated permissions', () => {
  const result = normalizeWorkspaceMenus([
    { path: '/monitoring', label: 'overview' },
    { path: '/monitoring?view=today', label: 'today Day' },
    { path: '/system/user', label: 'user' }
  ], { menuPaths: ['/dialysis'] });

  assert.deepEqual(allPaths(result), ['/monitoring', '/monitoring?view=today']);
});

test('fallback navigation includes only authorized entries and preserves orphaned children', () => {
  const result = getFallbackWorkspaceMenus(['/dry-weight', '/health-analysis?tab=data-export', '/system/patient']);

  assert.deepEqual(allPaths(result), [
    '/monitoring', '/dry-weight', '/health-analysis', '/health-analysis?tab=data-export', '/system/patient'
  ]);
  assert.equal(result.find(item => item.label === '透析管理').path, '');
});

test('normalization is idempotent and fallback definitions are not mutated between calls', () => {
  const options = { menuPaths: ['/medication'] };
  const result = getFallbackWorkspaceMenus(options.menuPaths);
  assert.deepEqual(normalizeWorkspaceMenus(result, options), result);
  assert.equal(getFallbackWorkspaceMenus([], ['admin']).find(item => item.path === '/medication').children.length, 5);
  assert.deepEqual(getFallbackWorkspaceMenus(options.menuPaths), result);
});


test('report-only module entry goes to report without authorizing complication', () => {
  const menus = ['/health-analysis?tab=health-report'];
  const result = getFallbackWorkspaceMenus(menus);
  const module = result.find(item => item.path === '/health-analysis');
  assert.equal(module.entryPath, '/health-analysis?tab=health-report');
  assert.deepEqual(module.children.map(item => item.path), menus);
});
