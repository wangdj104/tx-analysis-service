import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { computed, reactive, ref, watch, nextTick, effectScope } from 'vue'

function deferred() { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }
function setup(t, overrides = {}, roles = [], initialTab = 'measurements') {
  const patient = ref(10), route = reactive({ query: { tab: initialTab } }), warnings = [], storage = new Map([['userRoleCodes', JSON.stringify(roles)], ['userId', '8']])
  const api = {
    listClinicians: async () => ({ data: [] }), listMeasurements: async () => ({ data: [] }), listMentalAssessments: async () => ({ data: [] }), listMentalSchedules: async () => ({ data: [] }),
    listAppointments: async () => ({ data: [] }), listVisits: async () => ({ data: [] }), listPrescriptions: async () => ({ data: [] }), listEmergencies: async () => ({ data: [] }),
    ...overrides
  }
  const content = fs.readFileSync(new URL('../src/views/CareJourneyManager.vue', import.meta.url), 'utf8')
  const source = content.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const successes = []
  const deps = { computed, reactive, ref, watch, onMounted() {}, useRoute: () => route, useRouter: () => ({ replace: value => { route.query = value.query } }), useCurrentPatient: () => ({ currentPatientId: patient, currentPatientName: ref('Patient') }), ElMessage: { success: message => successes.push(message), warning: message => warnings.push(message) }, ElMessageBox: { confirm: async () => {} }, api, localStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }, navigator: { geolocation: null } }
  const scope = effectScope(), exposed = ['loadMeasurements','measurements','measurement','recordMeasurement','loadEmergencyCard','emergencyCard','loadEmergencyEvents','emergencyEvents','openEmergency','selectedEmergency','sos','loadMental','mentalSchedules','disableMentalSchedule','mental','mentalAnswers','submitMental','mentalSchedule','scheduleMental','loadTab','busy','tab','saveSpecial','specialtyRows','schedule','createSchedule','appointmentInbox','loadAppointmentInbox','cancelInboxAppointment','completeInboxAppointment','syncTab']
  const view = scope.run(() => new Function(...Object.keys(deps), source + '\nreturn {' + exposed.map(name => `${name}: typeof ${name} === 'undefined' ? undefined : ${name}`).join(',') + '}')(...Object.values(deps)))
  t.after(() => scope.stop())
  return { ...view, patient, route, warnings, successes, storage }
}

test('a failed clinician directory does not block independent patient tabs', async t => {
  const view = setup(t, {
    listClinicians: async () => { throw new Error('directory unavailable') },
    listMeasurements: async () => ({ data: [{ id: 17, patient_id: 10 }] })
  })
  await view.loadTab()
  assert.deepEqual(view.measurements.value.map(row => row.id), [17])
})

test('mental schedules are visible after creation and can be disabled', async t => {
  const schedules = [{ id: 4, enabled: 1 }]
  const view = setup(t, {
    listMentalSchedules: async () => ({ data: schedules.map(row => ({ ...row })) }),
    saveMentalSchedule: async () => { schedules.push({ id: 5, enabled: 1 }) },
    disableMentalSchedule: async id => { schedules.find(row => row.id === id).enabled = 0 }
  })
  await view.loadMental()
  assert.equal(view.mentalSchedules.value.length, 1)
  await view.scheduleMental()
  assert.equal(view.mentalSchedules.value.length, 2)
  await view.disableMentalSchedule(view.mentalSchedules.value[0])
  assert.equal(view.mentalSchedules.value[0].enabled, 0)
})

test('emergency reports zero delivered notifications without claiming dispatch', async t => {
  const view = setup(t, { triggerEmergency: async () => ({ data: { deliveryCount: 0, recipientCount: 2 } }) })
  await view.sos()
  assert.equal(view.successes.length, 0)
  assert.equal(view.warnings.length, 1)
})

test('emergency event details never appear under another selected patient', async t => {
  const pending = deferred()
  const view = setup(t, {
    listEmergencies: async () => ({ data: [{ id: 31, patient_id: 10 }] }),
    getEmergency: async () => pending.promise
  })
  await view.loadEmergencyEvents()
  assert.equal(view.emergencyEvents.value[0].id, 31)
  const opening = view.openEmergency(view.emergencyEvents.value[0])
  view.patient.value = 20
  await nextTick()
  pending.resolve({ data: { id: 31, patient_id: 10, snapshot: { patient: { name: 'Old patient' } } } })
  await opening
  assert.equal(view.selectedEmergency.value, null)
})

test('care journey ignores measurements returned for a previously selected patient', async t => {
  const pending = deferred()
  const view = setup(t, { listMeasurements: async ({ patientId }) => patientId === 10 ? pending.promise : { data: [{ patient_id: 20 }] } })
  const first = view.loadMeasurements()
  view.patient.value = 20
  await nextTick()
  await view.loadMeasurements()
  pending.resolve({ data: [{ patient_id: 10 }] })
  await first
  assert.deepEqual(view.measurements.value, [{ patient_id: 20 }])
})

test('administrator must select an active doctor when creating schedule availability', async t => {
  const requests = []
  const view = setup(t, { saveDoctorSchedule: async value => { requests.push(value); return {} } }, ['admin'])
  await view.createSchedule()
  assert.equal(requests.length, 0)
  assert.equal(view.warnings.length, 1)
  view.schedule.doctorUserId = 42
  await view.createSchedule()
  assert.equal(requests[0].doctorUserId, 42)
})

test('emergency offline cache is isolated to the signed-in user and patient', async t => {
  const view = setup(t, { getEmergencyCard: async () => { throw Object.assign(new Error('offline'), { code: 'ERR_NETWORK' }) } })
  view.storage.set('offlineEmergencyCard:10', JSON.stringify({ legacyPrivateRecord: true }))
  view.storage.set('offlineEmergencyCard:9:10', JSON.stringify({ otherAccountPrivateRecord: true }))
  await view.loadEmergencyCard()
  assert.equal(view.emergencyCard.value, null)
  const ownRecord = { patient: { id: 10 }, allergies: 'own record' }
  view.storage.set('offlineEmergencyCard:8:10', JSON.stringify(ownRecord))
  await view.loadEmergencyCard()
  assert.deepEqual(view.emergencyCard.value, ownRecord)
})

test('authorization and business errors erase cached emergency data without displaying it', async t => {
  let error = { response: { status: 403 } }
  const view = setup(t, { getEmergencyCard: async () => { throw error } })
  for (const denied of [{ response: { status: 403 } }, { code: 403 }, { code: 401 }, { response: { status: 500 } }]) {
    error = denied
    view.storage.set('offlineEmergencyCard:8:10', JSON.stringify({ privateRecord: true }))
    await view.loadEmergencyCard()
    assert.equal(view.emergencyCard.value, null)
    assert.equal(view.storage.has('offlineEmergencyCard:8:10'), false)
  }
})

test('a successful emergency load caches only the current signed-in account', async t => {
  const record = { patient: { id: 10 } }
  const view = setup(t, { getEmergencyCard: async () => ({ data: record }) })
  await view.loadEmergencyCard()
  assert.deepEqual(JSON.parse(view.storage.get('offlineEmergencyCard:8:10')), record)
  assert.equal(view.storage.has('offlineEmergencyCard:10'), false)
})


test('emergency card cannot be cached under a newly selected patient', async t => {
  const pending = deferred()
  const view = setup(t, { getEmergencyCard: async () => pending.promise })
  const first = view.loadEmergencyCard()
  view.patient.value = 20
  await nextTick()
  pending.resolve({ data: { patient: { id: 10 }, allergies: 'private' } })
  await first
  assert.equal(view.emergencyCard.value, null)
  assert.equal(view.storage.has('offlineEmergencyCard:20'), false)
})

test('external consultation route changes select its content without requiring a global patient', async t => {
  const view = setup(t)
  view.route.query = { tab: 'consultation' }
  await nextTick()
  assert.equal(view.tab.value, 'consultation')
  view.route.query = { tab: 'operations' }
  await nextTick()
  assert.equal(view.tab.value, 'measurements')
  assert.equal(view.route.query.tab, 'measurements')
  view.route.query = { tab: 'consultation', consultationId: '9001' }
  await nextTick()
  view.syncTab('appointments')
  assert.deepEqual(view.route.query, { tab: 'appointments' })
})

test('changing health metric updates units and clears previous measurement values', async t => {
  const view = setup(t)
  view.measurement.metricType = 'WEIGHT'
  await nextTick()
  assert.equal(view.measurement.unit, 'kg')
  assert.equal(view.measurement.valuePrimary, null)
  assert.equal(view.measurement.valueSecondary, null)
})

test('empty questionnaire is rejected and selected questionnaire drives its schedule', async t => {
  const saved = [], schedules = []
  const view = setup(t, { saveMentalAssessment: async value => { saved.push(value); return {} }, saveMentalSchedule: async value => { schedules.push(value); return {} } })
  view.mentalAnswers.value = ''
  await view.submitMental()
  assert.equal(saved.length, 0)
  assert.equal(view.warnings.length, 1)
  view.mental.scaleCode = 'GAD7'
  view.mentalAnswers.value = '0,1,2,1,0,1,0'
  await view.submitMental()
  assert.equal(saved[0].answers.length, 7)
  await view.scheduleMental()
  assert.equal(schedules[0].scaleCode, 'GAD7')
})

test('doctor appointment inbox works without granting the patient global access', async t => {
  const view = setup(t, { listAppointmentInbox: async () => ({ data: [{ id: 99, patient_id: 32, patient_name: 'Invited patient', status: 'BOOKED' }] }) }, ['doctor'])
  view.patient.value = null
  await nextTick()
  await view.loadAppointmentInbox()
  assert.equal(view.appointmentInbox.value[0].patient_id, 32)
  assert.equal(view.patient.value, null)
})

test('doctor cancels or completes only booked inbox appointments and prevents duplicate submissions', async t => {
  const pending = deferred(), cancelled = [], completed = []
  const view = setup(t, { listAppointmentInbox: async () => ({ data: [] }), cancelAppointment: async id => { cancelled.push(id); return pending.promise }, completeAppointment: async id => { completed.push(id); return {} } }, ['doctor'])
  const booked = { id: 99, status: 'BOOKED' }
  const first = view.cancelInboxAppointment(booked)
  await Promise.resolve()
  await view.cancelInboxAppointment(booked)
  assert.deepEqual(cancelled, [99])
  pending.resolve({})
  await first
  await view.completeInboxAppointment({ id: 100, status: 'CANCELLED' })
  assert.deepEqual(completed, [])
  await view.completeInboxAppointment(booked)
  assert.deepEqual(completed, [99])
})

test('busy guard prevents duplicate measurement writes', async t => {
  const pending = deferred(), calls = []
  const view = setup(t, { saveMeasurement: async value => { calls.push(value); return pending.promise } })
  const first = view.recordMeasurement()
  await view.recordMeasurement()
  assert.equal(calls.length, 1)
  pending.resolve({})
  await first
  assert.equal(view.busy.value, false)
})
