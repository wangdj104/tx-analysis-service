// 只读取封闭的已知展示字段；不推断单位、时区，也不把原始偏移量套用到当前更正值。
const copy = {
  "labDateProjection": "原生检验记录仅保存日期；完整来源时间保留在原始事实中",
  "reviewFacts": "查看原始事实",
  "hideFacts": "收起原始事实",
  "previewRow": "有效预览项",
  "rejectedRow": "已拒绝的输入行",
  "field": "字段",
  "invalidField": "请检查此字段；该行未通过校验。",
  "invalidTimestamp": "需要有效的日期或当地时间，小数部分最多 6 位；不会自动修正或推断。",
  "missingTimestamp": "需要提供支持的来源时间戳。",
  "invalidInteger": "需要可精确保存的 32 位整数。",
  "invalidValue": "需要可在原生范围内精确保存的有效测量值。",
  "invalidBpUnit": "明确提供的血压单位必须为 mmHg。",
  "invalidGlucoseUnit": "血糖需明确提供 mmol/L 或 mg/dL 单位。",
  "invalidLabField": "需提供受支持且在字段长度限制内的值。",
  "factsTooLarge": "原始事实超出 4,096 字节上限，不会截断。",

  "heading": "导入时的原始事实",
  "previewHeading": "拟保留的原始事实",
  "help": "这是尚未核实的初次导入副本，可能与当前更正后的记录不同，不代表更正历史。",
  "previewHelp": "确认导入后将保留这些已通过校验的来源事实；本次预览尚未保存这些事实。",
  "notRetained": "未留存导入时的原始事实",
  "unavailable": "暂时无法安全读取导入原始事实。",
  "value": "原始值",
  "unit": "原始提供单位",
  "timestamp": "所选来源时间戳",
  "issuance": "签发时间依据（issued），不代表已证实的观测时刻",
  "observation": "来源时间依据",
  "dateOnly": "仅提供日期；未提供时间和偏移量",
  "local": "本地日期和时间；未提供偏移量",
  "explicit": "已提供明确偏移量；它不是地理时区",
  "missing": "未提供（字段缺失）",
  "null": "未提供（null）",
  "blank": "提供了空白单位；未提供明确单位",
  "SYSTOLIC": "收缩压",
  "DIASTOLIC": "舒张压",
  "GLUCOSE": "血糖",
  "LAB_SCALAR": "检验结果",
  "deviceUnit": "DEVICE 提供的单位（不作为血压单位解释）",
  "nativeBp": "当前血压约定单位为 mmHg；来源未明确提供原始血压单位。",
  "projection": "拟保存的原生本地日期和时间",
  "unknownTime": "未提供时间",
  "current": "当前记录"
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
