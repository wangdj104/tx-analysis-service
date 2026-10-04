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

// Logs contain fixed event names and numeric status only, never Axios config/body/headers.
function logRequestError(event, error) {
  const status = Number(error?.response?.status || error?.code);
  console.error(event, { status: Number.isInteger(status) && status >= 100 && status <= 599 ? status : 0 });
}

// Report parsing can await Blob.text after axios has delivered the response. Recheck
// the captured context before any message, redirect, or return at that boundary.
function requireCurrentReport(config) {
  if (config?.executionReport && (config.signal?.aborted || !isAuthSessionCurrent(config.expectedAuth) ||
      config.expectedAuth?.actorId !== localStorage.getItem('userId'))) {
    throw new axios.CanceledError('REPORT_CONTEXT_CHANGED', config);
  }
}

// requestinterceptor
request.interceptors.request.use(
  (config) => {
    const expected = config.expectedAuth;
    config.authSession = expected || captureAuthSession();
    if (expected) {
      // Keep the captured account in a closure. Recheck after all interceptors/transforms,
      // immediately before the actual adapter can transmit headers or clinical data.
      const snapshot = { token: expected.token, revision: expected.revision, actorId: expected.actorId };
      const adapter = config.adapter || request.defaults.adapter;
      config.adapter = dispatchConfig => {
        const authorization = dispatchConfig.headers?.get?.('Authorization') ?? dispatchConfig.headers?.Authorization;
        if (dispatchConfig.signal?.aborted || !isAuthSessionCurrent(snapshot) || snapshot.actorId !== localStorage.getItem('userId') ||
            authorization !== `Bearer ${snapshot.token}`) {
          throw Object.assign(new axios.CanceledError('EXPECTED_AUTH_CHANGED', dispatchConfig), { notDispatched: true });
        }
        return axios.getAdapter(adapter, dispatchConfig)(dispatchConfig);
      };
    }
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
    logRequestError('request_failed', error);
    return Promise.reject(error);
  }
);

// responseshouldinterceptor
request.interceptors.response.use(
  async (response) => {
    requireCurrentReport(response.config);
    const binary = response.config.responseType === 'blob';
    const jsonBlob = binary && response.data instanceof Blob && /json/i.test(response.data.type || response.headers?.['content-type'] || '');
    if (binary && !jsonBlob) {
      if (response.config.returnExportResponse) {
        return { blob: response.data, headers: {
          'content-disposition': response.headers?.['content-disposition'],
          'content-type': response.headers?.['content-type']
        } };
      }
      return response.data;
    }

    const raw = jsonBlob ? JSON.parse(await response.data.text()) : response.data;
    const res = response.config.executionReport ? raw : localizePayload(raw);

    requireCurrentReport(response.config);

    // ifBack Statuscodenot Yes200, instructionsAPIhas question
    if (res.code && res.code !== 200) {
      ElMessage.error(localizeServerText(res.msg) || '请求失败');

      // 401: not authorize, skipconvertto Sign Inpage
      if (res.code === 401 && isAuthSessionCurrent(response.config.authSession) && !window.location.pathname.endsWith('/login')) {
        clearAuthSession();
        window.location.href = '/cn/login';
      }

      return Promise.reject(Object.assign(new Error(res.msg || '请求失败'), { code: res.code, ...(response.config.executionReport ? { data: res.data } : {}) }));
    }

    if (binary) throw new Error('导出未返回有效文件，请重试。');
    return res;
  },
  async (error) => {
    // Opt-in obsolete/cancelled care requests are silent; legacy error behavior is unchanged.
    if (error.config?.expectedAuth && axios.isCancel(error)) return Promise.reject(error);
    requireCurrentReport(error.config);

    if (error.response?.data instanceof Blob && /json/i.test(error.response.data.type || error.response.headers?.['content-type'] || '')) {
      try { error.response.data = JSON.parse(await error.response.data.text()); } catch {}
    }

    requireCurrentReport(error.config);
    logRequestError('response_failed', error);

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
