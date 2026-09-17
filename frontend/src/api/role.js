import request from '@/utils/request';

export function getRoleList() {
  return request({ url: '/role/list', method: 'get' });
}

export function saveRole(data) {
  return request({ url: '/role/save', method: 'post', data });
}

export function deleteRole(id) {
  return request({ url: `/role/delete/${id}`, method: 'delete' });
}

export function getRoleMenus(roleId) {
  return request({ url: `/role/menus/${roleId}`, method: 'get' });
}

export function assignMenus(data) {
  return request({ url: '/role/assignMenus', method: 'post', data });
}

export function getRoleDetail(id) {
  return request({ url: `/role/detail/${id}`, method: 'get' });
}
