import request from '@/utils/request';

export function getUserList() {
  return request({ url: '/user/list', method: 'get' });
}

export function saveUser(data) {
  return request({ url: '/user/save', method: 'post', data });
}

export function updateUser(data) {
  return request({ url: '/user/update', method: 'put', data });
}

export function deleteUser(id) {
  return request({ url: `/user/delete/${id}`, method: 'delete' });
}

export function resetPassword(data) {
  return request({ url: '/user/resetPassword', method: 'post', data });
}

export function changePassword(data) {
  return request({ url: '/user/changePassword', method: 'post', data });
}

export function assignRoles(data) {
  return request({ url: '/user/assignRoles', method: 'post', data });
}

export function getUserRoles(userId) {
  return request({ url: `/user/roles/${userId}`, method: 'get' });
}

export function getUserDetail(id) {
  return request({ url: `/user/detail/${id}`, method: 'get' });
}
