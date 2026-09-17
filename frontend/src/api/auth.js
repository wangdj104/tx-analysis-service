import request from '@/utils/request';
import { clearAuthSession, saveAuthSession } from '@/utils/authSession';

export { saveAuthSession, clearAuthSession, getAuthSessionKey } from '@/utils/authSession';

/**
 * userSign In
 */
export function login(data) {
  return request({
    url: '/auth/login',
    method: 'post',
    data
  });
}

/**
 * getuserinformation
 */
export function getUserInfo() {
  return request({
    url: '/auth/info',
    method: 'get'
  });
}

/**
 * Sign Out (clearthis willmessage)
 */
export function logout() {
  clearAuthSession();
}
