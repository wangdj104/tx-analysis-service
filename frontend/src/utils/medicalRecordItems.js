// Results with the same number can describe different tests. Only collapse a
// complete duplicate, and keep unnamed/unfilled rows available for review.
export function dedupeRecognizedItems(items) {
  const seen = new Set()
  const normalize = value => String(value ?? '').normalize('NFKC').trim().replace(/\s+/g, ' ').toLowerCase()
  return items.filter(item => {
    const name = normalize(item.itemName)
    const value = normalize(item.resultValue)
    if (!name || !value) return true
    const key = JSON.stringify([name, value, normalize(item.unit), normalize(item.referenceRange), item.isAbnormal ?? 0])
    if (seen.has(key)) return false
    seen.add(key)
    return true
  }).map(item => ({ ...item }))
}
