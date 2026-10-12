// Match HealthDateRange's inclusive Monday-to-Sunday calendar boundaries.
export function exportDateRange(type, value) {
  if (!value || !['year', 'month', 'week'].includes(type)) return null
  if (type !== 'year' && String(value).length < 7) return null
  const text = String(value), parts = text.split('-').map(Number), year = parts[0], month = parts[1] ?? 1, day = parts[2] ?? 1
  if (!/^\d{4}(?:-\d{2})?(?:-\d{2})?$/.test(text) || year < 1 || month < 1 || month > 12 || day < 1 || day > 31) return null
  const date = new Date(0); date.setUTCFullYear(year, month - 1, day); date.setUTCHours(0,0,0,0)
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null
  let from = new Date(date), to = new Date(date)
  if (type === 'year') { from.setUTCMonth(0, 1); to.setUTCMonth(11, 31) }
  else if (type === 'week') { from.setUTCDate(from.getUTCDate() - (from.getUTCDay() + 6) % 7); to = new Date(from); to.setUTCDate(to.getUTCDate() + 6) }
  else { from.setUTCDate(1); to.setUTCMonth(to.getUTCMonth() + 1, 0) }
  return { from: from.toISOString().slice(0,10), to: to.toISOString().slice(0,10) }
}
