const basePath = path => String(path || '').split('?')[0]

export function specialtyPathAllowed(path, scope) {
  if (!scope) return false
  const restricted = scope.restrictedPaths || []
  const allowed = scope.allowedPaths || []
  const match = restricted.find(entry => entry === path) || restricted.find(entry => entry === basePath(path) && !entry.includes('?'))
  return !match || allowed.includes(match) || allowed.includes(path)
}

export function filterSpecialtyMenus(menus, scope) {
  if (!scope) return []
  return menus.filter(item => specialtyPathAllowed(item.path, scope)).map(item => ({
    ...item,
    children: item.children ? filterSpecialtyMenus(item.children, scope) : item.children
  }))
}
