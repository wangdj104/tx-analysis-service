import request from '@/utils/request'

export const getMedicalReviewPreview = (recordId, options = {}) => request({
  url: `/medical-record/${recordId}/review-preview`, method: 'get',
  expectedAuth: options.expectedAuth, signal: options.signal, executionReport: true
})

export const submitMedicalReview = (recordId, body, workspace = false, options = {}) => request({
  url: workspace ? '/doctor-workspace/reviews' : `/medical-record/${recordId}/review`, method: 'post',
  data: workspace ? { sourceType: 'MEDICAL_RECORD', sourceId: recordId, decision: body.approved ? 'APPROVED' : 'REJECTED', reviewNote: body.note, expectedContentVersion: body.expectedContentVersion } : body,
  expectedAuth: options.expectedAuth, signal: options.signal, executionReport: true
})
