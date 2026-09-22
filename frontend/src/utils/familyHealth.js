export function localDateKey(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

export function localDateTimeKey(date = new Date()) {
  return `${localDateKey(date)} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`
}

export function replaceTarget(target, data) {
  Object.keys(target).forEach(key => delete target[key])
  Object.assign(target, { systolicMin: 90, systolicMax: 140, diastolicMin: 60, diastolicMax: 90 }, data || {})
}
