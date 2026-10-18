// 只读取封闭的已知展示字段；不推断单位、时区，也不把原始偏移量套用到当前更正值。
const copy = {
  "labDateProjection": "Native LAB record keeps the date only; the full source time stays in original facts",
  "reviewFacts": "Review original facts",
  "hideFacts": "Hide original facts",
  "previewRow": "Accepted preview item",
  "rejectedRow": "Rejected input row",
  "field": "Field",
  "invalidField": "Check this field; the row was rejected.",
  "invalidTimestamp": "Use a valid date or local timestamp with at most 6 fractional digits; it is never corrected or inferred.",
  "missingTimestamp": "A supported source timestamp is required.",
  "invalidInteger": "An exact 32-bit integer is required.",
  "invalidValue": "A valid measurement within the exact native storage range is required.",
  "invalidBpUnit": "An explicitly supplied BP unit must be mmHg.",
  "invalidGlucoseUnit": "Glucose requires an explicit mmol/L or mg/dL unit.",
  "invalidLabField": "A supported value within the field size limit is required.",
  "factsTooLarge": "Original facts exceed the 4,096-byte limit and will not be truncated.",

  "heading": "Original imported facts",
  "previewHeading": "Proposed original facts",
  "help": "This initial imported copy is unverified provenance and may differ from the current corrected record. It is not correction history.",
  "previewHelp": "These accepted source facts are proposed for retention when you confirm the import. They have not been stored by this preview.",
  "notRetained": "Original import facts were not retained",
  "unavailable": "Original import facts could not be loaded safely.",
  "value": "Original value",
  "unit": "Original supplied unit",
  "timestamp": "Selected source timestamp",
  "issuance": "Issuance time basis (issued), not a proven observation time",
  "observation": "Source timestamp basis",
  "dateOnly": "Date only; time and offset not supplied",
  "local": "Local date and time; offset not supplied",
  "explicit": "Explicit offset supplied; this is not a geographic timezone",
  "missing": "Not supplied (missing)",
  "null": "Not supplied (null)",
  "blank": "Blank supplied unit; unit not supplied",
  "SYSTOLIC": "Systolic pressure",
  "DIASTOLIC": "Diastolic pressure",
  "GLUCOSE": "Blood glucose",
  "LAB_SCALAR": "Lab result",
  "deviceUnit": "Supplied DEVICE unit (uninterpreted for BP)",
  "nativeBp": "The current BP contract uses mmHg; no explicit original BP unit was supplied.",
  "projection": "Proposed native local date/time",
  "unknownTime": "Time not supplied",
  "current": "Current record"
}
export const originalFactsText = key => copy[key] || ''
const text = (value, max) => { if (typeof value !== 'string' || value.length > max) throw new Error('INVALID_FACTS'); return value }
function factLabel(fact, max = 500) {
  if (fact?.state === 'MISSING') return copy.missing
  if (fact?.state === 'NULL') return copy.null
  if (fact?.state !== 'STRING') throw new Error('INVALID_FACTS')
  const supplied = text(fact.supplied, max)
  return supplied.trim() ? supplied : copy.blank
}
export function presentOriginalFacts(originalImport, preview = false) {
  if (!preview && originalImport?.status === 'NOT_RETAINED') return { state: 'notRetained' }
  try {
    if (!preview && originalImport?.status !== 'RETAINED') throw new Error('INVALID_FACTS')
    const facts = originalImport?.facts
    if (facts?.version !== 1 || !['FHIR', 'DEVICE'].includes(facts.format)) throw new Error('INVALID_FACTS')
    const metrics = ({ BP: ['SYSTOLIC', 'DIASTOLIC'], BG: ['GLUCOSE'], BP_BG: ['SYSTOLIC', 'DIASTOLIC', 'GLUCOSE'], LAB: ['LAB_SCALAR'] })[facts.kind]
    if (!metrics || !Array.isArray(facts.readings) || facts.readings.length !== metrics.length) throw new Error('INVALID_FACTS')
    const fields = facts.format === 'FHIR' ? ['effectiveDateTime', 'issued', 'effectiveInstant'] : ['observedAt', 'date']
    if (!fields.includes(facts.selectedTimestamp)) throw new Error('INVALID_FACTS')
    const selected = facts.timestamps?.[facts.selectedTimestamp]
    if (selected?.state !== 'STRING') throw new Error('INVALID_FACTS')
    const timestamp = text(selected.supplied, 32), basis = facts.basis
    const localDate = text(basis.localDate, 10), localTime = basis.localTime == null ? null : text(basis.localTime, 15)
    const readings = facts.readings.map((reading, index) => {
      if (reading?.metric !== metrics[index]) throw new Error('INVALID_FACTS')
      const value = reading.value
      if (!['JSON_NUMBER', 'NUMERIC_STRING', 'TEXT'].includes(value?.type)) throw new Error('INVALID_FACTS')
      return {
        label: reading.metric === 'LAB_SCALAR' && reading.display?.state === 'STRING' ? text(reading.display.supplied, 200) : copy[reading.metric],
        value: value.type === 'JSON_NUMBER' ? text(value.exact, 256) : text(value.supplied, value.type === 'TEXT' ? 500 : 256),
        unit: factLabel(reading.unit, 50),
        unknownBpUnit: ['SYSTOLIC', 'DIASTOLIC'].includes(reading.metric) && reading.unit?.state !== 'STRING'
      }
    })
    if (!['NOT_SUPPLIED', 'EXPLICIT'].includes(basis.offsetState)) throw new Error('INVALID_FACTS')
    return { state: 'retained', readings, timestamp, selectedField: facts.selectedTimestamp,
      basis: facts.selectedTimestamp === 'issued' ? copy.issuance : copy.observation,
      precision: !localTime ? copy.dateOnly : basis.offsetState === 'EXPLICIT' ? copy.explicit : copy.local,
      projection: facts.kind === 'LAB' ? localDate + ' · ' + copy.labDateProjection : localDate + (localTime ? ' ' + localTime : ' · ' + copy.unknownTime),
      deviceUnit: facts.format === 'DEVICE' && facts.kind === 'BP' ? factLabel(facts.deviceUnit, 50) : null }
  } catch { return { state: 'unavailable' } }
}

// 固定字段和诊断，不把输入值或未知服务端文本拼入逐字段说明。
export function importProblemText(problem) {
  const fields = ['effectiveDateTime', 'issued', 'effectiveInstant', 'observedAt', 'date', 'timestamp', 'component', 'systolic', 'diastolic', 'glucose', 'value', 'systolicUnit', 'diastolicUnit', 'unit', 'code', 'display', 'metadata', 'measurement']
  const messages = { INVALID_TIMESTAMP: 'invalidTimestamp', MISSING_TIMESTAMP: 'missingTimestamp', INVALID_EXACT_INTEGER: 'invalidInteger', INVALID_MEASUREMENT_VALUE: 'invalidValue', INVALID_BP_UNIT: 'invalidBpUnit', INVALID_GLUCOSE_UNIT: 'invalidGlucoseUnit', INVALID_LAB_FIELD: 'invalidLabField', ORIGINAL_FACTS_TOO_LARGE: 'factsTooLarge' }
  const field = fields.includes(problem?.field) ? problem.field : copy.field
  return field + ': ' + (copy[messages[problem?.code]] || copy.invalidField)
}
