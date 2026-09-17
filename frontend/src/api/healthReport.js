import request from '@/utils/request'

// generateHealth Report (Backtwoentersystemflow)
export function generateReport(data) {
  return request({
    url: '/health-report/generate',
    method: 'post',
    data,
    responseType: 'blob'
  })
}
