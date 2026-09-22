import { localizeServerText } from './serverText.js'

const SYSTEM_EVENT_TYPES = new Set([
  'MEASUREMENT',
  'DIALYSIS',
  'INTAKE',
  'MEDICATION_LOG',
  'CONSULTATION',
  'VISIT'
])

const SYSTEM_TITLES = {
  'Remote consultation': '远程问诊',
  'Visit summary': '就诊小结',
  'Blood Pressure & Glucoserecord': '血压与血糖记录'
}

function localizeSystemTitle(value) {
  if (!value) return value
  if (SYSTEM_TITLES[value]) return SYSTEM_TITLES[value]
  if (/^Taken:\s*/i.test(value)) return value.replace(/^Taken:\s*/i, '已服用：')
  if (/^medicationrecord:\s*/i.test(value)) return value.replace(/^medicationrecord:\s*/i, '用药记录：')
  return localizeServerText(value)
}

function localizeSystemSummary(value) {
  if (!value) return value
  return localizeServerText(value)
    .replace(/^Blood Pressure\s*/i, '血压 ')
    .replace(/;\s*Blood Glucose\s*/i, '；血糖 ')
    .replace(/^Blood Glucose\s*/i, '血糖 ')
}

export function localizeHealthTimelineEntry(entry) {
  if (!entry || typeof entry !== 'object') return entry
  const sourceType = String(entry.sourceType || '').toUpperCase()
  if (!SYSTEM_EVENT_TYPES.has(sourceType)) return entry
  return {
    ...entry,
    title: localizeSystemTitle(entry.title),
    summary: localizeSystemSummary(entry.summary),
    remark: localizeSystemSummary(entry.remark)
  }
}
