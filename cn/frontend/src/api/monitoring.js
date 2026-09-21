import request from '@/utils/request'

export function getMonitoringSnapshot(patientId, days = 7) {
  return request({
    url: '/monitoring/snapshot',
    method: 'get',
    params: { patientId, days }
  })
}
