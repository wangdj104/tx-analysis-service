import request from '@/utils/request'
import { captureAuthSession } from '@/utils/authSession'

export const getClinicalWorkbenchOverview = patientId => request({ url: '/clinical-workbench/overview', params: { patientId } })
export const getDialysisQuality = (patientId, days) => request({ url: '/clinical-workbench/dialysis-quality', params: { patientId, days } })
export const reviewMedicalRecord = (id, approved, note, expectedContentVersion) => request({ url: `/medical-record/${id}/review`, method: 'post', data: { approved, note, expectedContentVersion } })
export const reviewAnalysisDraft = (id, approved, notify = false) => request({ url: `/health-analysis/automations/analysis/${id}/review`, method: 'post', data: { approved, notify } })
export const generateDialysisSchedule = data => request({ url: '/family-health/dialysis-schedules/generate', method: 'post', data })

// 导入沿用共享请求守卫；指定的会话必须完整，不能借用后来登录者的身份。
function clinicalImportOptions(options) {
  const auth = Object.prototype.hasOwnProperty.call(options, 'expectedAuth')
    ? options.expectedAuth : { ...captureAuthSession(), actorId: localStorage.getItem('userId') }
  if (!auth || typeof auth.token !== 'string' || !auth.token || !Number.isSafeInteger(auth.revision) || auth.revision < 0 ||
      typeof auth.actorId !== 'string' || !auth.actorId) throw new Error('IMPORT_AUTH_CONTEXT_REQUIRED')
  return { executionReport: true, signal: options.signal,
    expectedAuth: { token: auth.token, revision: auth.revision, actorId: auth.actorId } }
}
export const previewClinicalImport = (data, options = {}) => request({
  url: '/clinical-import/preview', method: 'post', data, ...clinicalImportOptions(options)
})
export const commitClinicalImport = (data, options = {}) => request({
  url: '/clinical-import/commit', method: 'post',
  // 已准备的 JSON 文本必须原样发送，不能展开字符串或重新序列化其中数值。
  data: typeof data === 'string' ? data : { ...data, confirmed: true }, ...clinicalImportOptions(options)
})
