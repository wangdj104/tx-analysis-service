import request from '@/utils/request';

export function getMenuList() {
  return request({ url: '/menu/list', method: 'get' });
}

export function saveMenu(data) {
  return request({ url: '/menu/save', method: 'post', data });
}

export function deleteMenu(id) {
  return request({ url: `/menu/delete/${id}`, method: 'delete' });
}
