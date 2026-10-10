import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
for (const name of ['MedicalRecordManager', 'MedicationManager']) {
  for (const code of ['ECONNABORTED', 'ETIMEDOUT']) test(`${name} recognizes ${code} after transport error text is localized`, async () => {
    const source = fs.readFileSync(new URL(`../src/views/${name}.vue`, import.meta.url), 'utf8')
    const method = source.match(/async function startRecognize\(\) \{[\s\S]*?\n\}/)[0]
    const messages = [], deps = { viewActive: true, recognizeSaving: { value: false }, recognizeLoading: { value: false }, uploadSaving: { value: false }, uploadReady: () => true, selectedFiles: { value: [{}] }, uploadForm: { patientId: 1 }, invalidateUploadResult() {}, uploadVersion: 1, api: { uploadAndRecognize: async () => { throw Object.assign(new Error('请求超时'), { code }) } }, ElMessage: { error: text => messages.push(text) } }
    const start = new Function(...Object.keys(deps), `return (${method})`)(...Object.values(deps))
    await start()
    assert.equal(messages.length, 1)
    assert.match(messages[0], /^识别超时/)
    assert.equal(deps.recognizeLoading.value, false)
  })
}
