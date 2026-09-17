import request from '@/utils/request'

// Exportdatafor CSV
export function exportCsv(data) {
  return request({
    url: '/data-export/csv',
    method: 'post',
    data,
    responseType: 'blob'
  })
}
