import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { reactive, ref, watch, effectScope, nextTick } from 'vue'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function setup(t, overrides = {}) {
  const patientId = ref(1), messages = [], payloads = [], reads = [], unmountCallbacks = [], scope = effectScope()
  const bindings = {
    ref, reactive, watch, onMounted() {}, onUnmounted: callback => unmountCallbacks.push(callback),
    useCurrentPatient: () => ({ currentPatientId: patientId }),
    useMobile: () => ({ isMobile: ref(false) }),
    ElMessage: Object.fromEntries(['success', 'warning', 'error'].map(kind => [kind, message => messages.push({ kind, message })])),
    listAssessments: async id => { reads.push(id); return { code: 200, data: [{ id: id * 10, patientId: id }] } },
    saveAssessment: async payload => { payloads.push(payload); return { code: 200 } },
    calculateNutrition: async () => ({ code: 200, data: { bmi: 23, nutritionStatus: 'GOOD', sgaGrade: 'A' } }),
    deleteAssessment: async () => ({ code: 200 }),
    ...overrides
  }
  const script = fs.readFileSync(new URL('../src/views/NutritionAssessmentManager.vue', import.meta.url), 'utf8')
    .match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const exposed = 'records,loading,dialogVisible,isEdit,saving,previewLoading,previewResult,formRef,form,loadData,showAddDialog,showEditDialog,handlePreview,handleSave'
  const view = scope.run(() => new Function(...Object.keys(bindings), script + '\nreturn {' + exposed + ', formSaving: typeof formSaving === \"undefined\" ? ref(false) : formSaving}')(...Object.values(bindings)))
  view.formRef.value = { validate: async () => true }
  t.after(() => scope.stop())
  return { ...view, patientId, messages, payloads, reads, unmount() { unmountCallbacks.forEach(callback => callback()); scope.stop() } }
}

function openDraft(view, edit = false) {
  if (edit) view.showEditDialog({ id: 10, patientId: 1, assessmentDate: '2026-10-01', bodyWeight: 63, remark: 'Fictional patient A' })
  else {
    view.showAddDialog()
    Object.assign(view.form, { assessmentDate: '2026-10-01', bodyWeight: 63, remark: 'Fictional patient A' })
  }
}

for (const edit of [false, true]) {
  test(`${edit ? 'edit' : 'new'}: switching patients immediately discards the previous assessment`, async t => {
    const view = setup(t)
    await view.loadData()
    openDraft(view, edit)
    view.previewResult.value = { bmi: 23 }
    view.patientId.value = 2
    assert.equal(view.dialogVisible.value, false)
    assert.equal(view.isEdit.value, false)
    assert.equal(view.form.id, null)
    assert.equal(view.form.patientId, null)
    assert.equal(view.form.bodyWeight, null)
    assert.equal(view.form.remark, '')
    assert.equal(view.previewResult.value, null)
    assert.deepEqual(view.records.value, [])
    await view.handleSave()
    assert.equal(view.payloads.length, 0)
  })
}

test('clearing the selected patient immediately clears the assessment and records', async t => {
  const view = setup(t)
  await view.loadData(); openDraft(view)
  view.patientId.value = null
  assert.equal(view.dialogVisible.value, false)
  assert.equal(view.form.bodyWeight, null)
  assert.deepEqual(view.records.value, [])
  assert.equal(view.loading.value, false)
})

test('refreshing the same patient preserves an open assessment draft', async t => {
  const view = setup(t)
  openDraft(view)
  view.patientId.value = 1
  await view.loadData()
  assert.equal(view.dialogVisible.value, true)
  assert.equal(view.form.patientId, 1)
  assert.equal(view.form.bodyWeight, 63)
  assert.equal(view.form.remark, 'Fictional patient A')
})

test('an old patient row cannot be opened for editing in a new patient context', t => {
  const view = setup(t)
  view.patientId.value = 2
  view.showEditDialog({ id: 10, patientId: 1, bodyWeight: 63 })
  assert.equal(view.dialogVisible.value, false)
  assert.notEqual(view.form.id, 10)
})

for (const backToA of [false, true]) {
  test(`late list response cannot replace the current records after A to B${backToA ? ' to A' : ''}`, async t => {
    const pending = deferred(); let calls = 0
    const view = setup(t, { listAssessments: () => ++calls === 1 ? pending.promise : Promise.resolve({ code: 200, data: [{ id: 99 }] }) })
    const oldLoad = view.loadData()
    view.patientId.value = 2
    if (backToA) view.patientId.value = 1
    await nextTick()
    pending.resolve({ code: 200, data: [{ id: 10 }] }); await oldLoad
    assert.deepEqual(view.records.value, [{ id: 99 }])
  })
}

test('an older same-patient refresh cannot replace a newer result or clear its loading state', async t => {
  const first = deferred(), second = deferred(); let calls = 0
  const view = setup(t, { listAssessments: () => ++calls === 1 ? first.promise : second.promise })
  const oldLoad = view.loadData(), newLoad = view.loadData()
  first.resolve({ code: 200, data: [{ id: 10 }] }); await oldLoad
  assert.deepEqual(view.records.value, [])
  assert.equal(view.loading.value, true)
  second.resolve({ code: 200, data: [{ id: 20 }] }); await newLoad
  assert.deepEqual(view.records.value, [{ id: 20 }])
  assert.equal(view.loading.value, false)
})

for (const edit of [false, true]) {
  test(`${edit ? 'edit' : 'new'}: delayed validation cannot submit after switching A to B to A`, async t => {
    const pending = deferred(), view = setup(t)
    openDraft(view, edit)
    view.formRef.value = { validate: () => pending.promise }
    const save = view.handleSave()
    view.patientId.value = 2; view.patientId.value = 1
    view.showAddDialog(); view.form.remark = 'New draft'
    pending.resolve(true); await save
    assert.equal(view.payloads.length, 0)
    assert.equal(view.dialogVisible.value, true)
    assert.equal(view.form.remark, 'New draft')
    assert.equal(view.saving.value, false)
  })
}

test('closing and reopening a same-patient dialog invalidates its pending validation', async t => {
  const pending = deferred(), view = setup(t)
  openDraft(view)
  view.formRef.value = { validate: () => pending.promise }
  const save = view.handleSave()
  view.dialogVisible.value = false; view.showAddDialog()
  pending.resolve(true); await save
  assert.equal(view.payloads.length, 0)
  assert.equal(view.dialogVisible.value, true)
})

test('save is single-flight from validation through network completion', async t => {
  const validation = deferred(), network = deferred(), payloads = []; let validations = 0
  const view = setup(t, { saveAssessment: payload => { payloads.push(payload); return network.promise } })
  openDraft(view)
  view.formRef.value = { validate: () => { validations++; return validation.promise } }
  const first = view.handleSave(), duplicate = view.handleSave()
  assert.equal(validations, 1)
  assert.equal(view.saving.value, true)
  validation.resolve(true); await nextTick()
  assert.equal(payloads.length, 1)
  await view.handleSave()
  assert.equal(payloads.length, 1)
  network.resolve({ code: 200 }); await Promise.all([first, duplicate])
  assert.equal(view.saving.value, false)
  assert.equal(view.dialogVisible.value, false)
  assert.equal(view.messages.filter(x => x.kind === 'success').length, 1)
})

for (const nextPatient of [1, 2]) {
  for (const result of ['success', 'failure']) {
    test(`late ${result} preserves a reopened patient ${nextPatient} draft and does not show stale feedback`, async t => {
      const pending = deferred(), payloads = [], view = setup(t, { saveAssessment: payload => { payloads.push(payload); return pending.promise } })
      openDraft(view, true)
      const save = view.handleSave(); await nextTick()
      view.dialogVisible.value = false
      view.patientId.value = nextPatient
      view.showAddDialog()
      Object.assign(view.form, { bodyWeight: 70, remark: 'New draft' })
      const readsBefore = view.reads.length
      if (result === 'success') pending.resolve({ code: 200 })
      else pending.reject(new Error('offline'))
      await save
      assert.equal(view.dialogVisible.value, true)
      assert.equal(view.form.patientId, nextPatient)
      assert.equal(view.form.bodyWeight, 70)
      assert.equal(view.form.remark, 'New draft')
      assert.equal(payloads[0].patientId, 1)
      assert.equal(payloads[0].id, 10)
      assert.equal(payloads[0].bodyWeight, 63)
      assert.equal(payloads[0].remark, 'Fictional patient A')
      assert.equal(view.messages.length, 0)
      assert.equal(view.reads.length, readsBefore)
      assert.equal(view.saving.value, false)
    })
  }
}

test('failed validation keeps the current draft and allows retry', async t => {
  const view = setup(t)
  openDraft(view)
  view.formRef.value = { validate: async () => { throw new Error('invalid') } }
  await view.handleSave()
  assert.equal(view.payloads.length, 0)
  assert.equal(view.dialogVisible.value, true)
  assert.equal(view.form.bodyWeight, 63)
  assert.equal(view.saving.value, false)
  view.formRef.value = { validate: async () => true }
  await view.handleSave()
  assert.equal(view.payloads.length, 1)
})

test('failed save keeps the current draft and allows retry', async t => {
  let requests = 0
  const view = setup(t, { saveAssessment: async () => { if (++requests === 1) throw new Error('offline'); return { code: 200 } } })
  openDraft(view)
  await view.handleSave()
  assert.equal(view.dialogVisible.value, true)
  assert.equal(view.form.bodyWeight, 63)
  assert.equal(view.saving.value, false)
  assert.equal(view.messages[0].kind, 'error')
  await view.handleSave()
  assert.equal(requests, 2)
  assert.equal(view.dialogVisible.value, false)
})

for (const replacement of ['other patient', 'same patient dialog', 'A to B to A']) {
  test(`late nutrition preview cannot write into a ${replacement} draft`, async t => {
    const pending = deferred(), view = setup(t, { calculateNutrition: () => pending.promise })
    openDraft(view)
    const preview = view.handlePreview()
    if (replacement !== 'same patient dialog') view.patientId.value = 2
    if (replacement === 'A to B to A') view.patientId.value = 1
    view.dialogVisible.value = false; view.showAddDialog()
    view.form.bodyWeight = 70
    pending.resolve({ code: 200, data: { bmi: 18, nutritionStatus: 'AT_RISK', sgaGrade: 'B' } }); await preview
    assert.equal(view.previewResult.value, null)
    assert.equal(view.form.bmi, null)
    assert.equal(view.form.bodyWeight, 70)
    assert.equal(view.previewLoading.value, false)
  })
}

test('an older preview cannot replace a newer preview or clear its loading state', async t => {
  const first = deferred(), second = deferred(); let calls = 0
  const view = setup(t, { calculateNutrition: () => ++calls === 1 ? first.promise : second.promise })
  openDraft(view)
  const oldPreview = view.handlePreview(), newPreview = view.handlePreview()
  first.resolve({ code: 200, data: { bmi: 18 } }); await oldPreview
  assert.equal(view.previewResult.value, null)
  assert.equal(view.previewLoading.value, true)
  second.resolve({ code: 200, data: { bmi: 23 } }); await newPreview
  assert.equal(view.form.bmi, 23)
  assert.equal(view.previewResult.value.bmi, 23)
  assert.equal(view.previewLoading.value, false)
})

for (const operation of ['validation', 'save', 'preview', 'list']) {
  test(`unmounting invalidates a pending ${operation}`, async t => {
    const pending = deferred()
    const api = { save: 'saveAssessment', preview: 'calculateNutrition', list: 'listAssessments' }[operation]
    const view = setup(t, api ? { [api]: () => pending.promise } : {})
    openDraft(view)
    if (operation === 'validation') view.formRef.value = { validate: () => pending.promise }
    const request = operation === 'preview' ? view.handlePreview() : operation === 'list' ? view.loadData() : view.handleSave()
    await nextTick()
    view.unmount()
    pending.resolve({ code: 200, data: operation === 'list' ? [{ id: 10 }] : { bmi: 18 } })
    await request
    assert.equal(view.payloads.length, 0)
    assert.equal(view.messages.length, 0)
    assert.equal(view.previewResult.value, null)
    assert.deepEqual(view.records.value, [])
    assert.equal(view.reads.length, 0)
  })
}

for (const [field, value] of [['bodyWeight', 90], ['height', 180], ['sgaScore', 2], ['albumin', 25]]) {
  test(`editing ${field} discards a pending preview and allows recalculation`, async t => {
    const pending = deferred(), payloads = []; let requests = 0
    const view = setup(t, { calculateNutrition: payload => {
      payloads.push(payload)
      return ++requests === 1 ? pending.promise : Promise.resolve({ code: 200, data: { bmi: 28, nutritionStatus: 'AT_RISK', sgaGrade: 'B' } })
    } })
    openDraft(view)
    const request = view.handlePreview()
    view.form[field] = value
    pending.resolve({ code: 200, data: { bmi: 18, nutritionStatus: 'GOOD', sgaGrade: 'A', supplementAdvice: 'Old advice' } })
    await request
    assert.equal(view.previewResult.value, null)
    assert.equal(view.form.bmi, null)
    assert.equal(view.form.supplementAdvice, null)
    assert.equal(view.previewLoading.value, false)
    await view.handlePreview()
    assert.equal(payloads[1][field], value)
    assert.equal(view.form.bmi, 28)
  })
}

test('editing the assessment during validation cancels that save and allows retry', async t => {
  const validation = deferred(), view = setup(t)
  openDraft(view)
  view.formRef.value = { validate: () => validation.promise }
  const request = view.handleSave()
  view.form.assessmentDate = ''
  validation.resolve(true); await request
  assert.equal(view.payloads.length, 0)
  assert.equal(view.dialogVisible.value, true)
  assert.equal(view.saving.value, false)
  view.form.assessmentDate = '2026-10-02'
  view.formRef.value = { validate: async () => true }
  await view.handleSave()
  assert.equal(view.payloads[0].assessmentDate, '2026-10-02')
})

test('a save completion preserves changes made in the same dialog while the request was pending', async t => {
  const pending = deferred(), payloads = [], view = setup(t, { saveAssessment: payload => { payloads.push(payload); return pending.promise } })
  openDraft(view)
  const request = view.handleSave(); await nextTick()
  view.form.bodyWeight = 90
  pending.resolve({ code: 200 }); await request
  assert.equal(payloads[0].bodyWeight, 63)
  assert.equal(view.form.bodyWeight, 90)
  assert.equal(view.dialogVisible.value, true)
  assert.equal(view.messages.length, 0)
  assert.equal(view.reads.length, 0)
})

test('saving locks the originating inputs and preview before validation, but not a reopened draft', async t => {
  const validation = deferred(); let previews = 0
  const view = setup(t, { calculateNutrition: async () => { previews++; return { code: 200, data: {} } } })
  openDraft(view); view.formRef.value = { validate: () => validation.promise }
  const request = view.handleSave()
  assert.equal(view.formSaving.value, true)
  await view.handlePreview(); assert.equal(previews, 0)
  const source = fs.readFileSync(new URL('../src/views/NutritionAssessmentManager.vue', import.meta.url), 'utf8')
  assert.match(source, /<el-form[^>]*:disabled="formSaving"/)
  assert.match(source, /<el-button[^>]*@click="handlePreview"[^>]*:disabled="formSaving"/)
  view.dialogVisible.value = false; view.showAddDialog()
  assert.equal(view.formSaving.value, false)
  validation.resolve(true); await request
  assert.equal(view.dialogVisible.value, true)
})
test('a pending preview cannot turn a successful create into a duplicate retry', async t => {
  const preview = deferred(), save = deferred(); const payloads = []
  const view = setup(t, { calculateNutrition: () => preview.promise, saveAssessment: payload => { payloads.push(payload); return save.promise } })
  openDraft(view)
  const calculation = view.handlePreview(), request = view.handleSave(); await nextTick()
  preview.resolve({ code: 200, data: { bmi: 88, nutritionStatus: 'AT_RISK' } }); await calculation
  assert.equal(view.form.bmi, null)
  save.resolve({ code: 200 }); await request
  assert.equal(view.dialogVisible.value, false)
  assert.equal(view.messages.filter(message => message.kind === 'success').length, 1)
  await view.handleSave(); assert.equal(payloads.length, 1)
  assert.equal(view.formSaving.value, false)
})
