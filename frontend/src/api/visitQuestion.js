import request from '@/utils/request'
import { captureAuthSession } from '@/utils/authSession'
const optionsFor = options => ({ expectedAuth: { ...captureAuthSession(), actorId: localStorage.getItem('userId') }, ...options })

// 精确患者和来源类型必须同行；这里只发送有限定位信息，不读取诊疗正文。
export const questionId = value => /^(?:[1-9][0-9]*)$/.test(String(value)) && Number.isSafeInteger(Number(value)) ? Number(value) : null
export const contextTypes = ['LEGACY_APPOINTMENT', 'CARE_APPOINTMENT', 'VISIT_RECORD']
export function questionLink(patientId, type, id) {
  if (!questionId(patientId) || !questionId(id) || !contextTypes.includes(type)) return null
  return `/care?patientId=${patientId}&contextType=${type}&contextId=${id}`
}
export function parseQuestionRoute(path) {
  const url = new URL(path, 'https://local.invalid')
  const q = url.searchParams
  if (url.pathname !== '/care' || (!['questionId', 'contextType', 'contextId'].some(k => q.has(k)) && q.get('tab') !== 'questions')) return null
  const invalid = { invalid: true }
  if (['patientId', 'questionId', 'contextType', 'contextId', 'tab'].some(k => q.getAll(k).length > 1)) return invalid
  const patientId = questionId(q.get('patientId'))
  if (!patientId) return invalid
  if (q.get('tab') === 'questions' && !['questionId','contextType','contextId'].some(k=>q.has(k))) return { patientId }
  if (q.has('questionId')) return !q.has('contextType') && !q.has('contextId') && questionId(q.get('questionId')) ? { patientId, questionId: questionId(q.get('questionId')) } : invalid
  return contextTypes.includes(q.get('contextType')) && questionId(q.get('contextId')) ? { patientId, contextType: q.get('contextType'), contextId: questionId(q.get('contextId')) } : invalid
}
const get = (path, params, options) => request({ ...optionsFor(options), url: `/care/questions${path}`, method: 'get', params, visitQuestion: true })
export const capabilities = (patientId, options) => get('/capabilities', { patientId }, options)
export const list = (patientId, filters = {}, options) => get('', { ...filters, patientId }, options)
export const legacy = (patientId, filters = {}, options) => get('/legacy', { ...filters, patientId }, options)
export const detail = (patientId, id, options) => get(`/${id}`, { patientId }, options)
export const legacyDetail = (patientId, id, options) => get(`/legacy/${id}`, { patientId }, options)
export const events = (patientId, id, filters = {}, options) => get(`/${id}/events`, { ...filters, patientId }, options)
export const event = (patientId, id, eventId, options) => get(`/${id}/events/${eventId}`, { patientId }, options)
export const contexts = (patientId, type, filters = {}, options) => get('/contexts', { ...filters, patientId, type }, options)
export const context = (patientId, type, id, options) => get(`/contexts/${type}/${id}`, { patientId }, options)
export const receipt = (patientId, key, options) => get(`/command-receipts/${encodeURIComponent(key)}`, { patientId }, options)
export const command = (envelope, key, options) => request({ ...optionsFor(options), url: `/care/questions${envelope.questionId == null ? '' : `/${envelope.questionId}/commands`}`, method: 'post', data: envelope, headers: { 'Idempotency-Key': key }, visitQuestion: true })

// 旧编辑入口也只提交一个明确命令；普通返回值不是成功回执，必须按原键核对。
export async function ordinaryCommand(envelope, key, options) {
  const saved = await request({ ...optionsFor(options), url: '/care/items', method: 'post', data: { id: envelope.questionId, patientId: envelope.patientId, kind: 'QUESTION', details: { questionCommand: envelope, commandKey: key } }, visitQuestion: true })
  if (saved?.code && saved.code !== 200) return saved
  try {
    const result = await receipt(envelope.patientId, key, options)
    if (result?.code && result.code !== 200) throw Object.assign(new Error('QUESTION_RECEIPT_UNAVAILABLE'), { code: result.code, data: result.data })
    return result
  } catch (error) {
    // 保存已发送；后续回执读取失败不能被当作保存遭拒。
    throw Object.assign(error, { questionOutcomeUnknown: true })
  }
}
