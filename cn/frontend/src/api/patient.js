import request from '@/utils/request';

export function getPatientList() {
  return request({ url: '/patient/list', method: 'get' });
}

export function savePatient(data) {
  return request({ url: '/patient/save', method: 'post', data });
}

export function updatePatient(data) {
  return request({ url: '/patient/update', method: 'put', data });
}

export function deletePatient(id) {
  return request({ url: `/patient/delete/${id}`, method: 'delete' });
}

export function getPatientNames() {
  return request({ url: '/patient/names', method: 'get' });
}

export const getSpecialtyRoles = () => request({ url: '/patient/specialty-roles', method: 'get' });
export const getPatientSpecialtyRoles = id => request({ url: `/patient/${id}/specialty-roles`, method: 'get' });
export const getPatientSpecialtyMenuScope = patientId => request({ url: '/patient/specialty-menu-scope', method: 'get', params: patientId ? { patientId } : {} });
