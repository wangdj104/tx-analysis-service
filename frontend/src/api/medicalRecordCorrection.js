import request from '@/utils/request'

// 只传当前会话和有限选择器；理由、旧值与草稿只在请求正文和当前内存中。
const optionsFor = options => ({ expectedAuth: options.expectedAuth, signal: options.signal, executionReport: true })
const read = (recordId, suffix, patientId, options = {}, extra = {}) => request({
  url: `/medical-record/corrections/${recordId}/${suffix}`, method: 'get',
  params: { patientId, ...extra }, ...optionsFor(options)
})
export const capabilities = (recordId, patientId, options) => read(recordId, 'capabilities', patientId, options)
export const current = (recordId, patientId, options) => read(recordId, 'current', patientId, options)
export const history = (recordId, patientId, options = {}) => read(recordId, 'history', patientId, options, {
  size: options.size ?? 20, ...(options.cursor != null ? { cursor: options.cursor } : {})
})
export const revision = (recordId, patientId, sequence, options) => read(recordId, `history/${sequence}`, patientId, options)
export const command = (body, options = {}) => request({
  url: '/medical-record/corrections', method: 'post', data: body, ...optionsFor(options)
})
