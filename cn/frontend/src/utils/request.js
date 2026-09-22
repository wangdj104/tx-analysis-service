import axios from 'axios';
import { ElMessage } from 'element-plus';
import { captureAuthSession, isAuthSessionCurrent, clearAuthSession } from '@/utils/authSession';
import { localizePayload, localizeServerText } from '@/utils/serverText';

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
    config.headers['Accept-Language'] = 'zh-CN';
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

    const res = localizePayload(jsonBlob ? JSON.parse(await response.data.text()) : response.data);

    // ifBack Statuscodenot Yes200, instructionsAPIhas question
    if (res.code && res.code !== 200) {
      ElMessage.error(localizeServerText(res.msg) || '请求失败');

      // 401: not authorize, skipconvertto Sign Inpage
      if (res.code === 401 && isAuthSessionCurrent(response.config.authSession) && !window.location.pathname.endsWith('/login')) {
        clearAuthSession();
        window.location.href = '/cn/login';
      }

      return Promise.reject(Object.assign(new Error(res.msg || '请求失败'), { code: res.code }));
    }

    if (binary) throw new Error('导出未返回有效文件，请重试。');
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
          if (isAuthSessionCurrent(error.config?.authSession) && !window.location.pathname.endsWith('/login')) {
          ElMessage.error('登录状态已过期，请重新登录。');
            clearAuthSession();
            window.location.href = '/cn/login';
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
          ElMessage.error(localizeServerText(error.response.data?.msg) || '请求失败');
      }
    } else {
      ElMessage.error('网络连接不可用。');
    }

    return Promise.reject(error);
  }
);

export default request;
