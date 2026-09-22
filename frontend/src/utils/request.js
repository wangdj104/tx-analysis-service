import axios from 'axios';
import { ElMessage } from 'element-plus';
import { captureAuthSession, isAuthSessionCurrent, clearAuthSession } from '@/utils/authSession';

// Createaxiosinstance
const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
});

// requestinterceptor
request.interceptors.request.use(
  (config) => {
    config.authSession = captureAuthSession();
    const token = config.authSession.token;
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`;
    }
    config.headers['Accept-Language'] = 'en-US';
    if (config.data instanceof FormData) {
      delete config.headers['Content-Type'];
    }
    return config;
  },
  (error) => {
    console.error('requesterror:', error);
    return Promise.reject(error);
  }
);

// responseshouldinterceptor
request.interceptors.response.use(
  async (response) => {
    const binary = response.config.responseType === 'blob';
    const jsonBlob = binary && response.data instanceof Blob && /json/i.test(response.data.type || response.headers?.['content-type'] || '');
    if (binary && !jsonBlob) {
      return response.data;
    }

    const res = jsonBlob ? JSON.parse(await response.data.text()) : response.data;

    // ifBack Statuscodenot Yes200, instructionsAPIhas question
    if (res.code && res.code !== 200) {
      ElMessage.error(res.msg || 'Request failed');

      // 401: not authorize, skipconvertto Sign Inpage
      if (res.code === 401 && isAuthSessionCurrent(response.config.authSession) && !window.location.pathname.startsWith('/login')) {
        clearAuthSession();
        window.location.href = '/login';
      }

      return Promise.reject(Object.assign(new Error(res.msg || 'Request failed'), { code: res.code }));
    }

    if (binary) throw new Error('The export did not return a file. Please try again.');
    return res;
  },
  async (error) => {
    console.error('responseshoulderror:', error);

    if (error.response?.data instanceof Blob && /json/i.test(error.response.data.type || '')) {
      try { error.response.data = JSON.parse(await error.response.data.text()); } catch {}
    }

    if (error.response) {
      switch (error.response.status) {
        case 401:
          if (isAuthSessionCurrent(error.config?.authSession) && !window.location.pathname.startsWith('/login')) {
          ElMessage.error('Your session has expired. Please sign in again.');
            clearAuthSession();
            window.location.href = '/login';
          }
          break;
        case 403:
          ElMessage.error('You do not have permission to perform this action.');
          break;
        case 404:
          ElMessage.error('The requested resource does not exist.');
          break;
        case 413:
          ElMessage.error('The upload is too large. Choose a smaller file or contact an administrator.');
          break;
        case 500:
          ElMessage.error('The service encountered an error. Please try again.');
          break;
        default:
          ElMessage.error(error.response.data?.msg || 'Request failed');
      }
    } else {
      ElMessage.error('The network connection is unavailable.');
    }

    return Promise.reject(error);
  }
);

export default request;
