import request from '@/utils/request'

export function listDiaries(patientId) {
  return request({ url: '/nutrition-diary/list', method: 'get', params: { patientId } })
}

export function saveDiary(data) {
  return request({ url: '/nutrition-diary/save', method: 'post', data })
}

export function updateDiary(data) {
  return request({ url: '/nutrition-diary/update', method: 'put', data })
}

export function deleteDiary(id) {
  return request({ url: `/nutrition-diary/delete/${id}`, method: 'delete' })
}
