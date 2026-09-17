import request from '@/utils/request'

// queryBlood GlucoseBlood Pressurerecordlist
export function listBpSelfMonitorRecords(patientId, measureType) {
  return request({
    url: '/bp-self-monitor/list',
    method: 'get',
    params: { patientId, measureType }
  })
}

// AddBlood GlucoseBlood Pressurerecord
export function saveBpSelfMonitorRecord(data) {
  return request({
    url: '/bp-self-monitor/save',
    method: 'post',
    data
  })
}

// updateBlood GlucoseBlood Pressurerecord
export function updateBpSelfMonitorRecord(data) {
  return request({
    url: '/bp-self-monitor/update',
    method: 'put',
    data
  })
}

// DeleteBlood GlucoseBlood Pressurerecord
export function deleteBpSelfMonitorRecord(id) {
  return request({
    url: `/bp-self-monitor/delete/${id}`,
    method: 'delete'
  })
}
