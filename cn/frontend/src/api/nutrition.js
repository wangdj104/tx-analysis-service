import request from '@/utils/request'

export function listAssessments(patientId) {
  return request({ url: '/nutrition/list', method: 'get', params: { patientId } })
}

export function saveAssessment(data) {
  return request({ url: '/nutrition/save', method: 'post', data })
}

export function calculateNutrition(data) {
  return request({ url: '/nutrition/calculate', method: 'post', data })
}

export function getAssessmentDetail(id) {
  return request({ url: `/nutrition/detail/${id}`, method: 'get' })
}

export function deleteAssessment(id) {
  return request({ url: `/nutrition/delete/${id}`, method: 'delete' })
}
