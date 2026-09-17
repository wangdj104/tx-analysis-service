import request from '@/utils/request'

// ---- rulemanagement ----

export function listRules(patientId) {
  return request({ url: '/alert/rules', method: 'get', params: { patientId } })
}

export function saveRule(data) {
  return request({ url: '/alert/rule/save', method: 'post', data })
}

export function toggleRuleEnabled(id) {
  return request({ url: `/alert/rule/toggle-enabled/${id}`, method: 'put' })
}

export function deleteRule(id) {
  return request({ url: `/alert/rule/delete/${id}`, method: 'delete' })
}

// ---- recordmanagement ----

export function listRecords(patientId, status) {
  return request({ url: '/alert/records', method: 'get', params: { patientId, status } })
}

export function acknowledge(id) {
  return request({ url: `/alert/record/acknowledge/${id}`, method: 'post' })
}

export function resolve(id, handlingNote) {
  return request({ url: `/alert/record/resolve/${id}`, method: 'post', data: { handlingNote } })
}

export function deleteRecord(id) {
  return request({ url: `/alert/record/delete/${id}`, method: 'delete' })
}

// ---- alertExamination ----

export function checkThresholds(patientId) {
  return request({ url: `/alert/check/${patientId}`, method: 'post' })
}

// ---- statistics ----

export function getStats(patientId) {
  return request({ url: '/alert/stats', method: 'get', params: { patientId } })
}
