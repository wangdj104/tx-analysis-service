import test from 'node:test'
import assert from 'node:assert/strict'
import { localDateKey, replaceTarget } from '../src/utils/familyHealth.js'

test('switching from configured family member to a new member drops identity and optional fields', () => {
  const target = { id: 11, patientId: 1, systolicMax: 125, emergencyPhone: 'old-phone' }
  replaceTarget(target, null)
  assert.equal(target.id, undefined)
  assert.equal(target.patientId, undefined)
  assert.equal(target.emergencyPhone, undefined)
  assert.equal(target.systolicMax, 140)
  replaceTarget(target, { id: 22, patientId: 2, systolicMax: 135 })
  assert.equal(target.id, 22)
  assert.equal(target.systolicMax, 135)
})

test('calendar day uses local time during Shanghai early morning', () => {
  process.env.TZ = 'Asia/Shanghai'
  assert.equal(localDateKey(new Date('2026-09-11T07:00:00+08:00')), '2026-09-11')
  assert.equal(localDateKey(new Date('2026-01-01T00:01:00+08:00')), '2026-01-01')
})
