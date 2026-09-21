import request from '@/utils/request'

export const getClinicalWorkbenchOverview = patientId => request({ url: '/clinical-workbench/overview', params: { patientId } })
export const getDialysisQuality = (patientId, days) => request({ url: '/clinical-workbench/dialysis-quality', params: { patientId, days } })
export const reviewMedicalRecord = (id, approved, note = '') => request({ url: `/medical-record/${id}/review`, method: 'post', data: { approved, note } })
export const reviewAnalysisDraft = (id, approved, notify = false) => request({ url: `/health-analysis/automations/analysis/${id}/review`, method: 'post', data: { approved, notify } })
export const generateDialysisSchedule = data => request({ url: '/family-health/dialysis-schedules/generate', method: 'post', data })
export const previewClinicalImport = data => request({ url: '/clinical-import/preview', method: 'post', data })
export const commitClinicalImport = data => request({ url: '/clinical-import/commit', method: 'post', data: { ...data, confirmed: true } })
