const basePath = path => String(path || '').split('?')[0]
const generalPaths = new Set([
  '/care', '/monitoring', '/family-health', '/medication', '/medical-record',
  '/bp-self-monitor', '/care-journey', '/doctor-workspace', '/clinical-workbench',
  '/health-analysis', '/nutrition', '/complication', '/alert', '/health-report',
  '/data-export', '/settings/notifications', '/system'
])

export function knownSpecialtyPath(path) {
  return ['/dialysis', '/dry-weight'].includes(basePath(path))
    || String(path || '').startsWith('/family-health?tab=schedule')
}

function safeGeneralPath(path) {
  return !knownSpecialtyPath(path)
    && (generalPaths.has(basePath(path)) || basePath(path).startsWith('/system/'))
}

export function specialtyPathAllowed(path, scope) {
  if (!scope) return safeGeneralPath(path)
  const restricted = scope.restrictedPaths || []
  const allowed = scope.allowedPaths || []
  const match = restricted.find(entry => entry === path) || restricted.find(entry => entry === basePath(path) && !entry.includes('?'))
  return !match || allowed.includes(match) || allowed.includes(path)
}

export function filterSpecialtyMenus(menus, scope) {
  return menus.flatMap(item => {
    const children = item.children ? filterSpecialtyMenus(item.children, scope) : item.children
    if (!specialtyPathAllowed(item.path, scope) && !(children?.length && !item.path)) return []
    return [{ ...item, children }]
  })
}
