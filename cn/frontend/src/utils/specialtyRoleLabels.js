export function localizeSpecialtyRole(role) {
  if (role.roleCode !== 'specialty_dialysis') return role
  return {
    ...role,
    roleName: role.roleName === 'Dialysis Patient' ? '透析患者' : role.roleName,
    description: role.description === 'Patient-specific dialysis menu scope'
      ? '患者档案的透析专病菜单范围' : role.description
  }
}
