export function localizeSpecialtyRole(role) {
  if (role.roleCode !== 'specialty_dialysis') return role
  return {
    ...role,
    roleName: role.roleName === '透析患者' ? 'Dialysis Patient' : role.roleName,
    description: role.description === '患者档案的透析专病菜单范围'
      ? 'Patient-specific dialysis menu scope' : role.description
  }
}
