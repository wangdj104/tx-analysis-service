import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
const url = new URL('../src/utils/careExecutionReport.js', import.meta.url)
async function display() { assert.ok(fs.existsSync(url), 'execution report display utilities exist'); return import(url.href) }

test('stable action codes have exact bilingual clinical distinctions', async () => {
  const { reportLabel } = await display()
  const expected = { OPEN: ['To do or supplement', '待执行或补充'], NEEDS_HELP: ['Needs help', '遇到困难'], SUBMITTED: ['Submitted awaiting doctor review', '已提交待医生复核'], CONFIRMED: ['Reviewed by doctor', '医生已复核'] }
  for (const [code, [en, zh]] of Object.entries(expected)) { assert.equal(reportLabel('status', code, 'en'), en); assert.equal(reportLabel('status', code, 'zh-CN'), zh) }
  assert.equal(reportLabel('questionStatus', 'ANSWERED', 'en'), 'Recorded answer')
  assert.equal(reportLabel('questionStatus', 'NEW_UNKNOWN', 'zh-CN'), '未识别')
  assert.equal(reportLabel('questionsAvailability', 'NOT_AUTHORIZED', 'en'), 'Not authorized')
  assert.equal(reportLabel('questionsAvailability', 'NOT_INCLUDED_IN_PLAN_SCOPE', 'zh-CN'), '单计划范围不纳入问题')
})

test('event, role, entry, follow-up, scope and format labels use dictionaries without translating notes', async () => {
  const { reportLabel } = await display()
  for (const [group, codes] of Object.entries({ eventType: ['PLAN_PUBLISHED', 'REVISION_PUBLISHED', 'RECEIPT_SUBMITTED', 'HELP_REQUESTED', 'FOLLOW_UP_RECORDED', 'RECEIPT_RETURNED', 'RECEIPT_CONFIRMED', 'PLAN_CANCELLED', 'PLAN_CLOSED'], actorRole: ['PATIENT', 'FAMILY', 'NURSE', 'DOCTOR'], entryMode: ['SELF', 'ASSISTED'], followUpKind: ['CONTACTED', 'AWAITING_INFORMATION', 'DOCTOR_NOTIFIED'], scope: ['ALL_PLANS', 'SINGLE_PLAN'], format: ['html', 'pdf', 'actions_csv', 'events_csv'] })) {
    for (const code of codes) for (const language of ['en', 'zh-CN']) { assert.notEqual(reportLabel(group, code, language), code); assert.notEqual(reportLabel(group, code, language), reportLabel(group, 'UNKNOWN', language)) }
  }
  assert.equal(reportLabel('status', 'Synthetic private note', 'en'), 'Unrecognized')
})

test('instant display includes chosen zone and correct seasonal offset; legacy time never invents UTC', async () => {
  const { formatReportInstant, formatLegacyQuestionTime } = await display()
  assert.match(formatReportInstant('2026-07-01T12:00:00Z', 'America/New_York', 'en'), /UTC-04:00.*America\/New_York/)
  assert.match(formatReportInstant('2026-01-01T12:00:00Z', 'America/New_York', 'zh-CN'), /UTC-05:00.*America\/New_York/)
  assert.match(formatReportInstant('2026-01-01T12:00:00Z', 'Asia/Shanghai', 'en'), /UTC\+08:00.*Asia\/Shanghai/)
  assert.equal(formatReportInstant(null, 'UTC', 'en'), 'Not recorded')
  assert.throws(() => formatReportInstant('2026-01-01T12:00:00', 'UTC', 'en'), RangeError)
  const legacy = formatLegacyQuestionTime('2026-07-01 12:00:00', 'en')
  assert.match(legacy, /^2026-07-01 12:00:00/); assert.match(legacy, /timezone not recorded/); assert.doesNotMatch(legacy, /UTC|Z$/)
  assert.match(formatLegacyQuestionTime('2026-07-01 12:00:00', 'zh-CN'), /时区未记录/)
})

test('safe source links match server allowlist, patient and evidence identity', async () => {
  const { safeEvidencePath } = await display()
  const evidence = { restricted: false, sourceType: 'MEASUREMENT', sourceId: 42, detailPath: '/care-journey?tab=measurements&patientId=1&measurementId=42' }
  assert.equal(safeEvidencePath(evidence, 1), evidence.detailPath)
  assert.equal(safeEvidencePath({ restricted: false, sourceType: 'MEDICAL_RECORD', sourceId: 42, detailPath: '/medical-record?tab=list&patientId=1&recordId=42' }, 1), '/medical-record?tab=list&patientId=1&recordId=42')
  assert.equal(safeEvidencePath({ ...evidence, restricted: true }, 1), null)
  assert.equal(safeEvidencePath(evidence, 2), null)
  for (const detailPath of ['https://example.com/private', '//example.com', 'javascript:alert(1)', '/care-journey?tab=measurements&patientId=1&measurementId=99', evidence.detailPath + '&note=secret', evidence.detailPath + '#secret', '/care-journey?tab=measurements&patientId=1&measurementId=42\n']) assert.equal(safeEvidencePath({ ...evidence, detailPath }, 1), null)
})

test('nanosecond report Instant values remain displayable without altering source strings', async () => {
  const { formatReportInstant } = await display(), value = '2026-10-04T17:30:00.123456789Z'
  assert.match(formatReportInstant(value, 'UTC', 'en'), /UTC\+00:00/)
  assert.equal(value, '2026-10-04T17:30:00.123456789Z')
})

test('raw filenames reject control characters, duplicate parameters and unsupported formats', async () => {
  const { safeReportFileName } = await display()
  const name = 'care-execution-report-en-20261004T173000Z-pdf.pdf'
  assert.equal(safeReportFileName(`attachment; filename="${name}\n"`, 'en', 'pdf'), 'care-execution-report-download.pdf')
  assert.equal(safeReportFileName(`attachment; filename="${name}"; filename="${name}"`, 'en', 'pdf'), 'care-execution-report-download.pdf')
  assert.throws(() => safeReportFileName(null, 'en', 'exe'), /format/)
})

test('bounded streaming CSV scan handles BOM, escaped quotes, embedded newlines and chunk boundaries', async () => {
  const { countReportCsvRows } = await display()
  const csv = '\uFEFF"列一","Header two"\r\n"Synthetic, note","quoted ""text""\r\nwith newline"\r\n"second","line"\r\n'
  const blob = new Blob([csv]), bytes = new TextEncoder().encode(csv)
  blob.stream = () => new ReadableStream({ start(controller) { for (const byte of bytes) controller.enqueue(new Uint8Array([byte])); controller.close() } })
  assert.equal(await countReportCsvRows(blob, () => true), 2)
  for (const text of ['header', 'header\r\n', '\uFEFF"header"\n']) assert.equal(await countReportCsvRows(new Blob([text]), () => true), 0)
  assert.equal(await countReportCsvRows(new Blob(['one,two\r"first",\r"second","value"']), () => true), 2)
})

test('streaming CSV scan rejects unbounded or malformed input without retaining clinical text', async () => {
  const { countReportCsvRows } = await display()
  for (const csv of ['header\n"unterminated', 'header\n"closed"junk', 'header\nunquoted"quote']) await assert.rejects(countReportCsvRows(new Blob([csv]), () => true))
  await assert.rejects(countReportCsvRows(new Blob([new Uint8Array(32 * 1024 * 1024 + 1)]), () => true), /limit/i)
  await assert.rejects(countReportCsvRows(new Blob(['header\nrow']), () => false), error => error.name === 'AbortError')
})

test('CSV headers larger than 4096 bytes remain valid header-only exports', async () => {
  const { countReportCsvRows } = await display()
  const header = Array.from({ length: 121 }, (_, index) => `"Synthetic metadata field ${index} with a sufficiently long label"`).join(',') + '\r\n'
  assert.ok(new Blob([header]).size > 4096)
  assert.equal(await countReportCsvRows(new Blob(['\uFEFF', header]), () => true), 0)
})

for (const stamp of ['20260231T120000Z', '20261301T120000Z', '20260001T120000Z', '20260100T120000Z', '20260101T240000Z', '20260101T126000Z', '20260101T120060Z', '20260231T256199Z']) {
  test(`impossible UTC generation timestamp falls back for filename and metadata: ${stamp}`, async () => {
    const { safeReportFileName, reportExportMetadata } = await display()
    const name = `care-execution-report-en-${stamp}-actions.csv`
    assert.equal(safeReportFileName(`attachment; filename="${name}"`, 'en', 'actions_csv'), 'care-execution-report-download.csv')
    assert.equal(reportExportMetadata({ patientId: 1, language: 'en', timeZone: 'UTC' }, 'actions_csv', name, 0).generatedAt, null)
  })
}

test('valid leap-day generation timestamp is accepted and shared with metadata', async () => {
  const { safeReportFileName, reportExportMetadata } = await display()
  const name = 'care-execution-report-zh-CN-20280229T235959Z-events.csv'
  assert.equal(safeReportFileName(`attachment; filename*=UTF-8''${name}`, 'zh-CN', 'events_csv'), name)
  assert.equal(reportExportMetadata({ patientId: 1, language: 'zh-CN', timeZone: 'Asia/Shanghai' }, 'events_csv', name, 0).generatedAt, '2028-02-29T23:59:59Z')
})
