import request from '@/utils/request'

// based onPatientIDgetclinicalinformation
export function getClinicalByPatient(patientId) {
  return request({
    url: `/patient-clinical/detail/${patientId}`,
    method: 'get'
  })
}

// Saveor updatePatientclinicalinformation
export function saveClinical(data) {
  return request({
    url: '/patient-clinical/save',
    method: 'post',
    data
  })
}

// DeletePatientclinicalinformation
export function deleteClinical(id) {
  return request({
    url: `/patient-clinical/delete/${id}`,
    method: 'delete'
  })
}
