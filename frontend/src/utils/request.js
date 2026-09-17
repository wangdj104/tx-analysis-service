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
  (response) => {
    // blobtyperequestdirectlyBackoriginaldata, not doJSONparse
    if (response.config.responseType === 'blob') {
      return response.data;
    }

    const res = response.data;

    // ifBack Statuscodenot Yes200, instructionsAPIhas question
    if (res.code && res.code !== 200) {
      ElMessage.error(res.msg || 'Request failed');

      // 401: not authorize, skipconvertto Sign Inpage
      if (res.code === 401 && isAuthSessionCurrent(response.config.authSession) && !window.location.pathname.startsWith('/login')) {
        clearAuthSession();
        window.location.href = '/login';
      }

      return Promise.reject(new Error(res.msg || 'Request failed'));
    }

    return res;
  },
  (error) => {
    console.error('responseshoulderror:', error);

    if (error.response) {
      switch (error.response.status) {
        case 401:
          if (isAuthSessionCurrent(error.config?.authSession) && !window.location.pathname.startsWith('/login')) {
            ElMessage.error('not authorize, Please sign in again');
            clearAuthSession();
            window.location.href = '/login';
          }
          break;
        case 403:
          ElMessage.error('rejectaccess');
          break;
        case 404:
          ElMessage.error('requestresourcedoes not exist');
          break;
        case 413:
          ElMessage.error('Uploadfiletoo large (413) : Please changemoresmallimage or contactmanagementmemberadjustlarge Nginx client_max_body_size');
          break;
        case 500:
          ElMessage.error('servicedeviceerror');
          break;
        default:
          ElMessage.error(error.response.data?.msg || 'Request failed');
      }
    } else {
      ElMessage.error('networkconnectionAbnormal');
    }

    return Promise.reject(error);
  }
);

export default request;
