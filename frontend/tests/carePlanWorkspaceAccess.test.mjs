import assert from 'node:assert/strict'
import test from 'node:test'
import { canAccessWorkspace, resolveWorkspaceEntry } from '../src/utils/workspaceAccess.js'

// Patient/family SQL seeds expose /family-health, but never a /care menu.
for (const role of ['patient', 'family']) {
  test(`${role} can enter care after canonical menus load without a care menu`, () => {
    const menus = ['/monitoring', '/family-health']
    assert.equal(canAccessWorkspace('/care', menus, [role]), true)
    assert.equal(resolveWorkspaceEntry('/care', menus, [role]), '/care')
  })
}

test('care entry remains available to a module-only participant with no workspace menus', () => {
  assert.equal(canAccessWorkspace('/care', [], ['family']), true)
  assert.equal(resolveWorkspaceEntry('/care', [], ['family']), '/care')
  assert.equal(resolveWorkspaceEntry('/care', [], []), '/care')
})

test('the basic care entry does not authorize query, hash, descendant or lookalike routes', () => {
  for (const path of ['/care?tab=today', '/care?patientId=1', '/care#today', '/care?tab=today#tasks', '/care/', '/care/context', '/care-plans', '/careful']) {
    assert.equal(canAccessWorkspace(path, [], ['family']), false, path)
    assert.equal(resolveWorkspaceEntry(path, [], ['family']), '', path)
  }
})

test('care entry does not grant unrelated workspaces or another permission tab', () => {
  const menus = ['/health-analysis?tab=health-report']
  for (const path of ['/medical-record', '/medication', '/family-health', '/system/user', '/health-analysis?tab=complication']) {
    assert.equal(canAccessWorkspace(path, menus, ['family']), false, path)
  }
  assert.equal(resolveWorkspaceEntry('/health-analysis', menus, ['family']), '/health-analysis?tab=health-report')
})

test('care entry preserves clinician role gates and existing administrator access', () => {
  assert.equal(canAccessWorkspace('/doctor-workspace', ['/doctor-workspace'], ['nurse']), false)
  assert.equal(canAccessWorkspace('/nurse-workspace', ['/nurse-workspace'], ['doctor']), false)
  assert.equal(canAccessWorkspace('/nurse-workspace', [], ['nurse']), true)
  assert.equal(canAccessWorkspace('/nurse-workspace?patientId=1', [], ['nurse']), false)
  assert.equal(canAccessWorkspace('/care-journey?tab=operations', ['/care-journey'], ['nurse']), false)
  assert.equal(canAccessWorkspace('/doctor-workspace', [], ['doctor']), false)
  assert.equal(canAccessWorkspace('/doctor-workspace', ['/doctor-workspace'], ['doctor']), true)
  assert.equal(canAccessWorkspace('/system/user', [], ['admin']), true)
  assert.equal(canAccessWorkspace('/care?tab=today', [], ['admin']), true)
  assert.equal(canAccessWorkspace('/nurse-workspace', [], ['admin']), false)
})

test('care plan locators keep their exact positive-ID route contract', () => {
  assert.equal(resolveWorkspaceEntry('/care-plans/17', [], ['family']), '/care-plans/17')
  for (const path of ['/care-plans/0', '/care-plans/17?patientId=1', '/care-plans/17#action']) {
    assert.equal(canAccessWorkspace(path, [], ['family']), false, path)
  }
})
