import assert from 'node:assert/strict'
import test from 'node:test'
import { localizeSpecialtyRole } from '../src/utils/specialtyRoleLabels.js'

test('Chinese workspace shows the built-in dialysis role in Chinese', () => {
  assert.deepEqual(localizeSpecialtyRole({ roleCode: 'specialty_dialysis', roleName: 'Dialysis Patient', description: 'Patient-specific dialysis menu scope' }), {
    roleCode: 'specialty_dialysis', roleName: '透析患者', description: '患者档案的透析专病菜单范围'
  })
})

test('custom role text is preserved', () => {
  const role = { roleCode: 'specialty_dialysis', roleName: '自定义方案', description: '自定义说明' }
  assert.deepEqual(localizeSpecialtyRole(role), role)
})
