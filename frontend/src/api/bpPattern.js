import request from '@/utils/request'

// triggerBlood Pressure Pattern Analysis
export function analyzeBpPattern(patientId, timeType, timeValue) {
  return request({
    url: '/bp-pattern/analyze',
    method: 'post',
    params: { patientId, timeType, timeValue }
  })
}

// queryBlood Pressureanalysisrecordlist
export function listBpPatterns(patientId) {
  return request({
    url: '/bp-pattern/list',
    method: 'get',
    params: { patientId }
  })
}

// DeleteBlood Pressureanalysisrecord
export function deleteBpPattern(id) {
  return request({
    url: `/bp-pattern/delete/${id}`,
    method: 'delete'
  })
}
