import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, effectScope } from 'vue'

function setup(t, path, bindings, exposed) {
  const source = fs.readFileSync(new URL('../src/' + path, import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[\s\S]*?from\s+['"][^'"]+['"];?\r?$/gm, '')
  const deps = { computed, reactive, ref, watch, onMounted() {}, onUnmounted() {}, use() {}, CanvasRenderer: {}, LineChart: {}, GridComponent: {}, LegendComponent: {}, MarkLineComponent: {}, TooltipComponent: {}, ElMessage: { success() {}, warning() {}, error() {} }, ...bindings }
  const scope = effectScope()
  const view = scope.run(() => new Function(...Object.keys(deps), script + '\nreturn {' + exposed + '}')(...Object.values(deps)))
  t.after(() => scope.stop())
  return { ...view, source }
}
function trend(t, records) {
  return setup(t, 'components/VitalsTrendPanel.vue', { defineProps: () => ({ records }) }, 'bgOption,abnormalCount,bgAbnormal')
}
const row = (bloodGlucose, bgUnit, measurePeriod = 'Fasting') => ({ recordDate: '2026-09-22', bloodGlucose, bgUnit, measurePeriod })

test('glucose trend converts mg/dL to its mmol/L axis and preserves source records', t => {
  const records = [row(108, 'mg/dL'), row(6, 'mmol/L'), row('90', 'MG/DL'), row(5, undefined)]
  const before = structuredClone(records), view = trend(t, records)
  assert.deepEqual(view.bgOption.value.series[0].data, [6, 6, 5, 5])
  assert.equal(view.abnormalCount.value, 0)
  assert.deepEqual(records, before)
})

test('glucose trend handles post-meal aliases and low values with unchanged thresholds', t => {
  const view = trend(t, [])
  for (const period of ['After Meal', 'After Meal2h']) {
    for (const unit of ['mmol/L', 'mg/dL']) {
      const scale = unit === 'mg/dL' ? 18 : 1
      for (const [value, abnormal] of [[2, true], [3.9, false], [7, false], [7.8, false], [7.9, true]]) {
        assert.equal(Boolean(view.bgAbnormal(row(value * scale, unit, period))), abnormal, `${value} ${unit} ${period}`)
      }
    }
  }
  assert.equal(Boolean(view.bgAbnormal(row(6.1, 'mmol/L'))), false)
  assert.equal(Boolean(view.bgAbnormal(row(6.2, 'mmol/L'))), true)
})

test('malformed and missing glucose readings are chart gaps, not clinical assessments', t => {
  const records = [null, undefined, '', 'invalid', Infinity, true, {}, []].map(value => row(value, 'mmol/L'))
  records.push(row(6, 'mg/L'))
  const view = trend(t, records)
  assert.deepEqual(view.bgOption.value.series[0].data, records.map(() => null))
  assert.equal(view.abnormalCount.value, 0)
})

test('monitoring-center mixed-unit points use the mmol/L axis without mutating the snapshot', t => {
  const view = setup(t, 'views/MonitoringCenter.vue', { useCurrentPatient: () => ({ currentPatientId: ref(null) }), window: { setInterval() {}, clearInterval() {} } }, 'snapshot,chartOption')
  const points = [{ glucose: 108, glucoseUnit: 'mg/dL' }, { glucose: 6, glucoseUnit: 'mmol/L' }, { glucose: null }, { glucose: 'invalid' }, { glucose: 90, glucoseUnit: 'MG/DL' }]
  view.snapshot.value.vitalTrend = points
  assert.deepEqual(view.chartOption.value.series[2].data.map(item => item.value), [6, 6, null, null, 5])
  assert.equal(points[0].glucose, 108)
})

function manager(t) {
  const saved = []
  const view = setup(t, 'views/BpSelfMonitorManager.vue', {
    localDateKey: () => '2026-09-22', useCurrentPatient: () => ({ currentPatientId: ref(1) }),
    saveBpSelfMonitorRecord: async data => { saved.push(data); return { code: 200 } },
    updateBpSelfMonitorRecord: async data => { saved.push(data); return { code: 200 } },
    listBpSelfMonitorRecords: async () => ({ code: 200, data: [] })
  }, 'form,formRules,formRef,handleSave,handleEdit')
  view.formRef.value = { resetFields() {}, async validate() {
    for (const [field, rules] of Object.entries(view.formRules)) {
      for (const rule of rules) {
        const value = view.form[field]
        if (rule.required && (value == null || value === '')) throw new Error('required')
        if (rule.validator) await new Promise((resolve, reject) => rule.validator(rule, value, error => error ? reject(error) : resolve()))
      }
    }
  } }
  return { ...view, saved }
}

test('glucose input keeps equivalent unit-specific ceilings so 108 mg/dL is not clamped', t => {
  const view = manager(t)
  const input = view.source.match(/<el-input-number[^>]*v-model="form.bloodGlucose"[^>]*>/)[0]
  const maxExpression = input.match(/:max="([^"]+)"/)[1]
  assert.equal(new Function('form', 'return ' + maxExpression)({ bgUnit: 'mmol/L' }), 50)
  assert.equal(new Function('form', 'return ' + maxExpression)({ bgUnit: 'mg/dL' }), 900)
  assert.equal(new Function('form', 'return ' + maxExpression)({ bgUnit: ' MG/DL ' }), 900)
})

for (const type of ['BP', 'BG', 'BP_BG']) {
  test(`${type} form refuses empty or invalid required measurements before saving`, async t => {
    const view = manager(t)
    view.form.measureType = type
    await view.handleSave()
    assert.equal(view.saved.length, 0, 'empty measurements must not save')
    Object.assign(view.form, { systolicBp: 120, diastolicBp: 80, bloodGlucose: 6 })
    const field = type === 'BG' ? 'bloodGlucose' : 'diastolicBp'
    for (const value of [null, undefined, '', 'invalid', 0, -1, Infinity]) {
      view.form[field] = value
      await view.handleSave()
      assert.equal(view.saved.length, 0, String(value))
    }
  })
}

test('valid mg/dL form submits the original reading and unit', async t => {
  const view = manager(t)
  Object.assign(view.form, { measureType: 'BG', bloodGlucose: 108, bgUnit: 'mg/dL' })
  await view.handleSave()
  assert.equal(view.saved.length, 1)
  assert.equal(view.saved[0].bloodGlucose, 108)
  assert.equal(view.saved[0].bgUnit, 'mg/dL')
})

for (const sample of [
  { measureType: 'BP', systolicBp: 50, diastolicBp: 30 },
  { measureType: 'BP', systolicBp: 250, diastolicBp: 150 },
  { measureType: 'BG', bloodGlucose: 0.1, bgUnit: 'mmol/L' },
  { measureType: 'BG', bloodGlucose: 50, bgUnit: 'mmol/L' },
  { measureType: 'BG', bloodGlucose: 900, bgUnit: 'mg/dL' }
]) {
  test(`valid existing input boundary ${JSON.stringify(sample)} saves`, async t => {
    const view = manager(t)
    Object.assign(view.form, sample)
    await view.handleSave()
    assert.equal(view.saved.length, 1)
  })
}

test('values outside existing input limits and unsupported units do not save', async t => {
  const view = manager(t)
  for (const sample of [
    { measureType: 'BP', systolicBp: 49, diastolicBp: 80 },
    { measureType: 'BP', systolicBp: 251, diastolicBp: 80 },
    { measureType: 'BP', systolicBp: 120, diastolicBp: 29 },
    { measureType: 'BP', systolicBp: 120, diastolicBp: 151 },
    { measureType: 'BP', systolicBp: 120.5, diastolicBp: 80 },
    { measureType: 'BG', bloodGlucose: 50.1, bgUnit: 'mmol/L' },
    { measureType: 'BG', bloodGlucose: 901, bgUnit: 'mg/dL' },
    { measureType: 'BG', bloodGlucose: 6, bgUnit: 'mg/L' }
  ]) {
    Object.assign(view.form, sample)
    await view.handleSave()
    assert.equal(view.saved.length, 0, JSON.stringify(sample))
  }
})

test('editing a historical case-insensitive mg/dL unit preserves its value and unit', async t => {
  const view = manager(t)
  Object.assign(view.form, { measureType: 'BG', bloodGlucose: 108, bgUnit: ' MG/DL ' })
  await view.handleSave()
  assert.equal(view.saved.length, 1)
  assert.equal(view.saved[0].bloodGlucose, 108)
  assert.equal(view.saved[0].bgUnit, ' MG/DL ')
})

test('remark-only edits of legacy records default blank units to mmol/L without changing glucose', async t => {
  for (const unit of [null, undefined, '', '  ', '\t\n']) {
    const view = manager(t)
    const original = { id: 51, patientId: 1, recordDate: '2026-09-22', measureType: 'BG', bloodGlucose: 6, bgUnit: unit, remark: 'Original' }
    view.handleEdit(original)
    view.form.remark = 'Reviewed'
    await view.handleSave()
    assert.equal(view.saved.length, 1, JSON.stringify(unit))
    assert.equal(view.saved[0].id, 51)
    assert.equal(view.saved[0].bloodGlucose, 6)
    assert.equal(view.saved[0].bgUnit, 'mmol/L')
    assert.equal(view.saved[0].remark, 'Reviewed')
    assert.equal(original.bgUnit, unit)
  }
})
