import assert from 'node:assert/strict'
import test from 'node:test'
import { localizeSpecialtyRole } from '../src/utils/specialtyRoleLabels.js'

test('English workspace shows the built-in dialysis role in English', () => {
  assert.deepEqual(localizeSpecialtyRole({ roleCode: 'specialty_dialysis', roleName: '透析患者', description: '患者档案的透析专病菜单范围' }), {
    roleCode: 'specialty_dialysis', roleName: 'Dialysis Patient', description: 'Patient-specific dialysis menu scope'
  })
})

test('custom role text is preserved', () => {
  const role = { roleCode: 'specialty_dialysis', roleName: 'My program', description: 'Custom scope' }
  assert.deepEqual(localizeSpecialtyRole(role), role)
})
