import request from '@/utils/request'

const id = value => { if (!Number.isSafeInteger(value) || value < 1) throw new Error('MEDICAL_REVIEW_INVALID_REQUEST'); return String(value) }
const channelPath = value => { if (value === 'SOURCE') return 'source'; if (value === 'DOCTOR') return 'doctor'; throw new Error('MEDICAL_REVIEW_INVALID_REQUEST') }
const cursorValue = value => { if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,320}$/.test(value)) throw new Error('MEDICAL_REVIEW_INVALID_REQUEST'); return value }
const base = recordId => `/medical-record/reviews/${id(recordId)}`
const patient = value => value === null ? 'null' : id(value)
const transport = options => ({ expectedAuth: options?.expectedAuth, signal: options?.signal, executionReport: true })
function post(recordId, channel, body, endpoint, options) {
  if (typeof body !== 'string' || new TextEncoder().encode(body).length > 16384) throw new Error('MEDICAL_REVIEW_INVALID_REQUEST')
  return request({ ...transport(options), url: `${base(recordId)}/${channelPath(channel)}/${endpoint}`, method: 'post', data: body,
    headers: { 'Content-Type': 'application/json' }, transformRequest: [value => value] })
}
export function current(recordId, patientId, options) {
  return request({ ...transport(options), url: `${base(recordId)}/current`, method: 'get', params: { patientId: patient(patientId) } })
}
export function history(recordId, patientId, channel, cursor, options) {
  return request({ ...transport(options), url: `${base(recordId)}/${channelPath(channel)}/history`, method: 'get',
    params: { patientId: patient(patientId), size: '20', ...(cursor === null ? {} : { cursor: cursorValue(cursor) }) } })
}
export function detail(recordId, patientId, channel, decisionVersion, cursor, options) {
  return request({ ...transport(options), url: `${base(recordId)}/${channelPath(channel)}/history/${id(decisionVersion)}`, method: 'get',
    params: { patientId: patient(patientId), cursor: cursorValue(cursor) } })
}
export const command = (recordId, channel, body, options) => post(recordId, channel, body, 'commands', options)
export const receipt = (recordId, channel, body, options) => post(recordId, channel, body, 'receipt', options)
