import request from '@/utils/request'

// querycomplicationrecordlist
export function listRecords(patientId) {
  return request({
    url: '/complication/list',
    method: 'get',
    params: { patientId }
  })
}

// Addcomplicationrecord
export function saveRecord(data) {
  return request({
    url: '/complication/save',
    method: 'post',
    data
  })
}

// updatecomplicationrecord
export function updateRecord(data) {
  return request({
    url: '/complication/update',
    method: 'put',
    data
  })
}

// Deletecomplicationrecord
export function deleteRecord(id) {
  return request({
    url: `/complication/delete/${id}`,
    method: 'delete'
  })
}

// getcomplicationstatisticsdata
export function getStats(patientId) {
  return request({
    url: '/complication/stats',
    method: 'get',
    params: { patientId }
  })
}
