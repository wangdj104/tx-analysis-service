import { formatPlanTime } from './carePlanTime.js'

// Only fixed labels are localized. Clinical free text is never passed through this dictionary.
const labels = {
  status: { OPEN: ['To do or supplement', '待执行或补充'], NEEDS_HELP: ['Needs help', '遇到困难'], SUBMITTED: ['Submitted awaiting doctor review', '已提交待医生复核'], CONFIRMED: ['Reviewed by doctor', '医生已复核'] },
  eventType: { PLAN_PUBLISHED: ['Plan published', '计划已发布'], REVISION_PUBLISHED: ['Revision published', '修订已发布'], RECEIPT_SUBMITTED: ['Receipt submitted', '回执已提交'], HELP_REQUESTED: ['Help requested', '已记录困难'], FOLLOW_UP_RECORDED: ['Administrative follow-up recorded', '已记录行政跟进'], RECEIPT_RETURNED: ['Receipt returned for supplement', '回执已退回补充'], RECEIPT_CONFIRMED: ['Receipt reviewed by doctor', '回执已由医生复核'], PLAN_CANCELLED: ['Plan cancelled', '计划已取消'], PLAN_CLOSED: ['Plan closed', '计划已关闭'] },
  actorRole: { PATIENT: ['Patient', '患者'], FAMILY: ['Family member', '家属'], NURSE: ['Nurse', '护理人员'], DOCTOR: ['Doctor', '医生'] },
  entryMode: { SELF: ['Self-reported', '本人自报'], ASSISTED: ['Assisted entry', '协助代录'] },
  followUpKind: { CONTACTED: ['Contacted', '已联系'], AWAITING_INFORMATION: ['Awaiting information', '等待补充信息'], DOCTOR_NOTIFIED: ['Doctor notified', '已通知医生'] },
  questionStatus: { OPEN: ['To discuss', '待讨论'], ANSWERED: ['Recorded answer', '已记录答复'], DONE: ['Marked done', '标记完成'], RESOLVED: ['Marked resolved', '标记解决'], CANCELLED: ['Cancelled', '已取消'] },
  questionsAvailability: { AVAILABLE: ['Available', '可读取'], NOT_AUTHORIZED: ['Not authorized', '无权读取问题'], NOT_INCLUDED_IN_PLAN_SCOPE: ['Questions not included in single-plan scope', '单计划范围不纳入问题'] },
  scope: { ALL_PLANS: ['All plans', '全部计划'], SINGLE_PLAN: ['Only this plan', '仅此计划'] },
  format: { html: ['HTML report', 'HTML报告'], pdf: ['PDF report', 'PDF报告'], actions_csv: ['Current actions CSV', '当前事项CSV'], events_csv: ['Period events CSV', '期间事件CSV'] },
  error: { INVALID_REQUEST: ['Check the report scope, dates and timezone.', '请检查报告范围、日期和时区。'], ACCESS_DENIED: ['You cannot currently access this report.', '当前无权访问此报告。'], FEATURE_DISABLED: ['Care execution reports are unavailable.', '照护执行报告暂不可用。'], REPORT_ACCESS_CHANGED: ['Access changed. Generate the report again.', '访问权限已变化，请重新生成报告。'], REPORT_LIMIT_EXCEEDED: ['The report exceeds a limit. Narrow the scope or choose a smaller export.', '报告超出限制，请缩小范围或选择较小的导出类型。'], REPORT_DATA_INCONSISTENT: ['An accurate report cannot be generated from these records.', '记录存在异常，无法生成准确报告。'], REPORT_RENDER_UNAVAILABLE: ['PDF rendering is unavailable. Try HTML.', 'PDF暂无法生成，请尝试HTML。'], REPORT_TIMEOUT: ['Report generation timed out. Narrow the scope and retry.', '报告生成超时，请缩小范围后重试。'] }
}
export function reportLabel(group, code, language = 'en') {
  const index = language === 'zh-CN' ? 1 : 0
  return Object.hasOwn(labels, group) && Object.hasOwn(labels[group], code) ? labels[group][code][index] : ['Unrecognized', '未识别'][index]
}
export function formatReportInstant(instant, timeZone, language = 'en') {
  if (instant == null || instant === '') return language === 'zh-CN' ? '未记录' : 'Not recorded'
  // Java Instant may carry nanoseconds. This seconds-level display need not reject them.
  const displayInstant = typeof instant === 'string' ? instant.replace(/(\.\d{6})\d{1,3}(?=Z$|[+-]\d{2}:\d{2}$)/, '$1') : instant
  return formatPlanTime(displayInstant, language, { timeZone })
}
export function formatLegacyQuestionTime(value, language = 'en') {
  if (value == null || value === '') return language === 'zh-CN' ? '未记录' : 'Not recorded'
  return `${value} (${language === 'zh-CN' ? '旧记录本地时间，时区未记录' : 'Legacy local time, timezone not recorded'})`
}
export function safeEvidencePath(evidence, patientId) {
  if (evidence?.restricted !== false || !Number.isSafeInteger(patientId) || patientId <= 0 || !Number.isSafeInteger(evidence.sourceId) || evidence.sourceId <= 0) return null
  const paths = { MEASUREMENT: `/care-journey?tab=measurements&patientId=${patientId}&measurementId=${evidence.sourceId}`, MEDICAL_RECORD: `/medical-record?tab=list&patientId=${patientId}&recordId=${evidence.sourceId}` }
  return Object.hasOwn(paths, evidence.sourceType) && evidence.detailPath === paths[evidence.sourceType] ? evidence.detailPath : null
}

const formats = { html: ['html', 'html'], pdf: ['pdf', 'pdf'], actions_csv: ['actions', 'csv'], events_csv: ['events', 'csv'] }
function parseReportTimestamp(value) {
  const match = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(value)
  if (!match) return null
  const [, year, month, day, hour, minute, second] = match
  const iso = `${year}-${month}-${day}T${hour}:${minute}:${second}Z`
  const parsed = new Date(iso)
  // Reject normalization such as February 31 or 24:00, as well as invalid components.
  return Number.isFinite(parsed.getTime()) && parsed.toISOString() === iso.replace('Z', '.000Z') ? iso : null
}
export function safeReportFileName(disposition, language, format) {
  if (!Object.hasOwn(formats, format)) throw new TypeError('Unsupported report format.')
  const [kind, extension] = formats[format], fallback = `care-execution-report-download.${extension}`
  if (!['en', 'zh-CN'].includes(language) || typeof disposition !== 'string' || /[\x00-\x1f\x7f]/.test(disposition)) return fallback
  const plain = [...disposition.matchAll(/(?:^|;)\s*filename\s*=\s*(?:"([^"]*)"|([^;\s]+))\s*(?=;|$)/gi)]
  const encoded = [...disposition.matchAll(/(?:^|;)\s*filename\*\s*=\s*([^;\s]+)\s*(?=;|$)/gi)]
  if (plain.length > 1 || encoded.length > 1 || (!plain.length && !encoded.length)) return fallback
  let name = plain[0]?.[1] ?? plain[0]?.[2]
  if (encoded.length) {
    const match = /^UTF-8''(.+)$/i.exec(encoded[0][1])
    if (!match) return fallback
    try { name = decodeURIComponent(match[1]) } catch { return fallback }
  }
  const match = new RegExp(`^care-execution-report-${language}-([0-9]{8}T[0-9]{6}Z)-${kind}\\.${extension}$`).exec(name ?? '')
  return match && parseReportTimestamp(match[1]) ? name : fallback
}

const errorCodes = Object.keys(labels.error)
const limitKinds = ['CURRENT_ACTIONS', 'PERIOD_EVENTS', 'QUESTIONS', 'SOURCE_TEXT_BYTES', 'OUTPUT_BYTES']
/** Do not retain Axios errors: their config/headers/body can contain private data. */
export function reportError(error) {
  const status = Number(error?.response?.status || error?.code)
  const data = error?.response?.data?.data ?? error?.data
  const result = { status: Number.isInteger(status) && status >= 400 && status <= 599 ? status : 0, errorCode: errorCodes.includes(data?.errorCode) ? data.errorCode : null }
  if (result.errorCode === 'REPORT_LIMIT_EXCEEDED' && limitKinds.includes(data?.limitKind) && Number.isSafeInteger(data?.limit) && data.limit >= 0) {
    result.limitKind = data.limitKind; result.limit = data.limit
  }
  return result
}

/** Count logical CSV records without retaining source text or assuming a header byte length. */
export async function countReportCsvRows(blob, isCurrent) {
  const byteLimit = 32 * 1024 * 1024
  const stale = () => { if (!isCurrent()) throw Object.assign(new Error('Report context changed.'), { name: 'AbortError' }) }
  stale()
  if (blob.size > byteLimit) throw new RangeError('Report output exceeds the byte limit.')
  const reader = blob.stream().getReader(), decoder = new TextDecoder('utf-8', { fatal: true })
  let bytes = 0, records = 0, quoted = false, quotePending = false, fieldStart = true, rowStarted = false, afterCR = false, done = false
  function scan(text) {
    for (const char of text) {
      if (quoted) {
        if (!quotePending) { if (char === '"') quotePending = true; continue }
        if (char === '"') { quotePending = false; continue }
        quoted = false; quotePending = false
        if (![',', '\r', '\n'].includes(char)) throw new Error('Invalid report CSV.')
      }
      if (char === '\n' && afterCR) { afterCR = false; continue }
      afterCR = false
      if (char === '"') {
        if (!fieldStart) throw new Error('Invalid report CSV.')
        quoted = true; fieldStart = false; rowStarted = true
      } else if (char === ',') { fieldStart = true; rowStarted = true }
      else if (char === '\r' || char === '\n') {
        records++; rowStarted = false; fieldStart = true; afterCR = char === '\r'
      } else { fieldStart = false; rowStarted = true }
    }
  }
  try {
    while (true) {
      stale()
      const chunk = await reader.read()
      stale()
      if (chunk.done) { done = true; break }
      bytes += chunk.value.byteLength
      if (bytes > byteLimit) throw new RangeError('Report output exceeds the byte limit.')
      scan(decoder.decode(chunk.value, { stream: true }))
    }
    scan(decoder.decode())
    if (quoted && !quotePending) throw new Error('Invalid report CSV.')
    if (rowStarted) records++
    if (!records) throw new Error('The report CSV has no header.')
    return records - 1
  } finally {
    if (!done) await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

/** Bounded public metadata only. Omitted dates cannot be inferred from server generation time. */
export function reportExportMetadata(body, format, fileName, rowCount) {
  const expected = safeReportFileName(`attachment; filename="${fileName}"`, body.language, format)
  const stamp = expected === fileName && /-([0-9]{8}T[0-9]{6}Z)-/.exec(fileName)
  const generatedAt = stamp ? parseReportTimestamp(stamp[1]) : null
  const rangeKnown = /^\d{4}-\d{2}-\d{2}$/.test(body.fromDate ?? '') && /^\d{4}-\d{2}-\d{2}$/.test(body.toDate ?? '')
  return { format, reportSchemaVersion: 1, scope: { patientId: body.patientId, planId: body.planId ?? null, label: body.planId == null ? 'ALL_PLANS' : 'SINGLE_PLAN' },
    fromDate: rangeKnown ? body.fromDate : null, toDate: rangeKnown ? body.toDate : null, rangeKnown,
    timeZone: body.timeZone, language: body.language, generatedAt, rowCount, isEmpty: rowCount == null ? null : rowCount === 0 }
}

/** Calendar arithmetic only: the server computes timezone-aware instant boundaries. */
export function defaultReportDates(timeZone, now = new Date()) {
  if (timeZone !== 'UTC' && !/^[A-Za-z_]+(?:\/[A-Za-z0-9_+.-]+)+$/.test(timeZone)) throw new RangeError('Choose an IANA timezone.')
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now).map(part => [part.type, part.value]))
  const toDate = `${parts.year}-${parts.month}-${parts.day}`
  const date = new Date(`${toDate}T12:00:00Z`)
  date.setUTCDate(date.getUTCDate() - 29)
  return { fromDate: date.toISOString().slice(0, 10), toDate }
}

/** Presentation only: compute elapsed seconds from the two immutable DTO instants. */
export function reportReviewWaitSeconds(since, currentAsOf) {
  const nanos = value => {
    const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value || '')
    if (!match || !Number.isFinite(Date.parse(match[1] + 'Z'))) throw new RangeError('Invalid report instant.')
    return BigInt(Date.parse(match[1] + 'Z')) * 1000000n + BigInt((match[2] || '').padEnd(9, '0'))
  }
  try { const elapsed = nanos(currentAsOf) - nanos(since); return elapsed < 0n ? null : Number(elapsed / 1000000000n) } catch { return null }
}
