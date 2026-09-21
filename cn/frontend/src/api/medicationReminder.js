import request from '@/utils/request'

// queryPatient Reminderlist
export function listReminders(patientId) {
  return request({
    url: '/medication-reminder/list',
    method: 'get',
    params: { patientId }
  })
}

// Saveor updateReminder
export function saveReminder(data) {
  return request({
    url: '/medication-reminder/save',
    method: 'post',
    data
  })
}

// switchEnabled/DisabledStatus
export function toggleEnabled(id) {
  return request({
    url: `/medication-reminder/toggle-enabled/${id}`,
    method: 'put'
  })
}

// DeleteReminder
export function deleteReminder(id) {
  return request({
    url: `/medication-reminder/delete/${id}`,
    method: 'delete'
  })
}
