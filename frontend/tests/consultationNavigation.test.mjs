import test from 'node:test';
import assert from 'node:assert/strict';
import { canAccessWorkspace, resolveWorkspaceEntry } from '../src/utils/workspaceAccess.js';

test('parent care journey permissions keep doctor inbox links open without unlocking unknown query scopes', () => {
  const parent = ['/care-journey'];
  for (const tab of ['consultation', 'operations', 'appointments']) {
    const path = '/care-journey?tab=' + tab;
    assert.equal(resolveWorkspaceEntry(path, parent, ['doctor']), path);
  }
  assert.equal(canAccessWorkspace('/care-journey?tab=consultation&consultationId=9', parent, ['doctor']), true);
  assert.equal(canAccessWorkspace('/care-journey?tab=operations', parent, ['patient']), false);
  assert.equal(canAccessWorkspace('/care-journey?tab=operations', ['/care-journey?tab=operations'], ['patient']), false);
  for (const path of ['/care-journey?tab=unknown', '/care-journey?tab=operations&patientId=9', '/care-journey?tab=operations&tab=consultation']) {
    assert.equal(canAccessWorkspace(path, parent, ['doctor']), false);
  }
});


test('authorized consultation deep links survive menu refresh without authorizing other tabs', () => {
  const paths = ['/care-journey?tab=consultation'];
  const link = '/care-journey?tab=consultation&consultationId=9001';
  assert.equal(canAccessWorkspace(link, paths, ['doctor']), true);
  assert.equal(resolveWorkspaceEntry(link, paths, ['doctor']), link);
  assert.equal(canAccessWorkspace(link, [], ['doctor']), false);
  for (const query of ['tab=operations&consultationId=9001', 'tab=consultation&consultationId=-1',
    'tab=consultation&consultationId=9001&patientId=2', 'tab=consultation&tab=operations',
    'tab=consultation&consultationId=9001#other']) {
    assert.equal(canAccessWorkspace('/care-journey?' + query, paths, ['doctor']), false);
  }
});
