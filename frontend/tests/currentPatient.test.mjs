import assert from 'node:assert/strict'
import test from 'node:test'

test('patient list selects the first patient by default and preserves a valid prior choice', async () => {
  const values = new Map()
  globalThis.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key)
  }
  globalThis.window = new EventTarget()

  const { useCurrentPatient } = await import(`../src/composables/useCurrentPatient.js?test=${Date.now()}`)
  const { currentPatientId, setPatientList } = useCurrentPatient()

  setPatientList([{ id: 11, patientName: 'No. one' }, { id: 22, patientName: 'No. two' }])
  assert.equal(currentPatientId.value, 11)
  assert.equal(localStorage.getItem('currentPatientId'), '11')

  currentPatientId.value = 22
  setPatientList([{ id: 11, patientName: 'No. one' }, { id: 22, patientName: 'No. two' }])
  assert.equal(currentPatientId.value, 22)

  setPatientList([{ id: 11, patientName: 'No. one' }])
  assert.equal(currentPatientId.value, 11)

  setPatientList([])
  assert.equal(currentPatientId.value, null)
})
