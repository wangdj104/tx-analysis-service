import assert from 'node:assert/strict'
import test from 'node:test'
import { filterSpecialtyMenus, specialtyPathAllowed } from '../src/utils/patientSpecialtyNavigation.js'

const menus = [
  { path: '/medical-record', children: [] },
  { path: '/dialysis', children: [{ path: '/dialysis?tab=data' }, { path: '/dry-weight' }] },
  { path: '/family-health', children: [{ path: '/family-health?tab=today' }, { path: '/family-health?tab=schedule' }] }
]
const restrictedPaths = ['/dialysis', '/dialysis?tab=data', '/dry-weight', '/family-health?tab=schedule']

test('specialty menus follow the selected patient and preserve general menus', () => {
  const dialysis = { restrictedPaths, allowedPaths: restrictedPaths }
  const general = { restrictedPaths, allowedPaths: [] }
  assert.equal(filterSpecialtyMenus(menus, dialysis).length, 3)
  const visible = filterSpecialtyMenus(menus, general)
  assert.deepEqual(visible.map(item => item.path), ['/medical-record', '/family-health'])
  assert.deepEqual(visible[1].children.map(item => item.path), ['/family-health?tab=today'])
  assert.equal(specialtyPathAllowed('/dialysis?tab=data', general), false)
  assert.equal(specialtyPathAllowed('/dialysis?tab=data', dialysis), true)
  assert.equal(specialtyPathAllowed('/medical-record', general), true)
})

test('scope loading failure hides specialty navigation', () => {
  assert.deepEqual(filterSpecialtyMenus(menus, null), [])
  assert.equal(specialtyPathAllowed('/dialysis', null), false)
})
