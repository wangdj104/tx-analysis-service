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
      ElMessage.error(res.msg || '请求失败');

      // 401: not authorize, skipconvertto Sign Inpage
      if (res.code === 401 && isAuthSessionCurrent(response.config.authSession) && !window.location.pathname.startsWith('/login')) {
        clearAuthSession();
        window.location.href = '/login';
      }

      return Promise.reject(new Error(res.msg || '请求失败'));
    }

    return res;
  },
  (error) => {
    console.error('responseshoulderror:', error);

    if (error.response) {
      switch (error.response.status) {
        case 401:
          if (isAuthSessionCurrent(error.config?.authSession) && !window.location.pathname.startsWith('/login')) {
          ElMessage.error('登录状态已过期，请重新登录。');
            clearAuthSession();
            window.location.href = '/login';
          }
          break;
        case 403:
          ElMessage.error('你没有执行此操作的权限。');
          break;
        case 404:
          ElMessage.error('请求的资源不存在。');
          break;
        case 413:
          ElMessage.error('上传文件过大，请选择较小的文件或联系管理员。');
          break;
        case 500:
          ElMessage.error('服务发生错误，请稍后重试。');
          break;
        default:
          ElMessage.error(error.response.data?.msg || '请求失败');
      }
    } else {
      ElMessage.error('网络连接不可用。');
    }

    return Promise.reject(error);
  }
);

export default request;
