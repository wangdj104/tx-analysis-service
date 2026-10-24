import axios from 'axios';
import { ElMessage } from 'element-plus';
import { captureAuthSession, isAuthSessionCurrent, clearAuthSession } from '@/utils/authSession';
import { captureAssistanceLoginRedirect, ownsAssistanceLoginRedirect } from '@/utils/assistanceLogin';
import { captureRequestContext, isRequestContextCurrent, isProtectedAccessFailure } from '@/utils/requestContext';

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
  if (config?.signal?.aborted || !isRequestContextCurrent(config?.privacyContext)) {
    throw new axios.CanceledError('REQUEST_CONTEXT_CHANGED', config);
  }
  if ((config?.executionReport || config?.alertWorkflow || config?.householdWorkflow || config?.visitQuestion) && (config.signal?.aborted || !isAuthSessionCurrent(config.expectedAuth) ||
      config.expectedAuth?.actorId !== localStorage.getItem('userId'))) {
    throw new axios.CanceledError('REPORT_CONTEXT_CHANGED', config);
  }
}

// 仅该概览 GET 的暂时失败交给原页面内联提示；失权、其他请求及错误拒绝保持原样。
function monitoringErrorIsInline(config, status) {
  return config?.monitoringInlineErrors === true && config.url === '/monitoring/snapshot-bounded' && config.method === 'get' &&
    (status === null || (Number.isInteger(status) && status >= 500 && status <= 599));
}

// These readers already render a localized disabled state. Preserve the rejection
// without turning this expected configuration state into a global error toast.
function measurementReadDisabledIsInline(config, status, payload) {
  return config?.measurementReadModel === true && config.method === 'get' && status === 503 &&
    /^\/alert-workflows\/read-model\/(?:measurement-trend|measurement-items(?:\/[1-9]\d*\/events)?)$/.test(config.url || '') &&
    payload?.data?.errorCode === 'FEATURE_DISABLED';
}

function browserFullPath() { return window.location.pathname + window.location.search + window.location.hash; }

// 旧页面或旧账号的401不能清掉新交接入口；普通页面沿用原失效处理。
function requireCurrentAssistanceLogin(config) {
  const current = captureAssistanceLoginRedirect(browserFullPath(), '/');
  if (!config?.assistanceLogin?.protected && !current.protected) return;
  const auth = captureAuthSession();
  if (!ownsAssistanceLoginRedirect(config?.assistanceLogin, browserFullPath()) ||
      config?.authSession?.token !== auth.token || config?.authSession?.revision !== auth.revision) {
    throw new axios.CanceledError('ASSISTANCE_LOGIN_CONTEXT_CHANGED', config);
  }
}

function redirectExpiredSession(config) {
  const current = captureAssistanceLoginRedirect(browserFullPath(), '/');
  if (!isAuthSessionCurrent(config?.authSession) || current.dedicatedLogin || window.location.pathname.startsWith('/login')) return;
  clearAuthSession();
  window.location.href = current.loginHref || '/login';
}

// requestinterceptor
request.interceptors.request.use(
  (config) => {
    config.privacyContext ||= captureRequestContext(config);
    if (!isRequestContextCurrent(config.privacyContext)) {
      throw Object.assign(new axios.CanceledError('REQUEST_CONTEXT_CHANGED', config), { notDispatched: true });
    }
    const privacyAdapter = config.adapter || request.defaults.adapter;
    config.adapter = dispatchConfig => {
      if (dispatchConfig.signal?.aborted || !isRequestContextCurrent(config.privacyContext)) {
        throw Object.assign(new axios.CanceledError('REQUEST_CONTEXT_CHANGED', dispatchConfig), { notDispatched: true });
      }
      return axios.getAdapter(privacyAdapter, dispatchConfig)(dispatchConfig);
    };
    const expected = config.expectedAuth;
    config.authSession = expected || captureAuthSession();
    config.assistanceLogin = captureAssistanceLoginRedirect(browserFullPath(), '/');
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
    config.headers['Accept-Language'] = 'en-US';
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

// 共同目标在面板内展示错误，旧请求的消息与登录失效流程保持不变。
function showRequestError(config, message) {
  if (config?.privacyInlineErrors && config.url === '/privacy/access-context') return;
  const goalInline = config?.commonGoalInlineErrors === true && /^\/common-care-goals(?:\/|$)/.test(config.url || '');
  // 登录错误只由仍属于当前页面和账号的 Login 展示，旧请求不能先弹共享提示。
  const loginInline = config?.method === 'post' && config.url === '/auth/login';
  if (!goalInline && !loginInline) ElMessage.error(message);
}

function retireDeniedAccess(config) {
  if (isProtectedAccessFailure(config)) window.dispatchEvent(new Event('protected-access-denied'));
}

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

    const res = jsonBlob ? JSON.parse(await response.data.text()) : response.data;

    requireCurrentReport(response.config);

    // ifBack Statuscodenot Yes200, instructionsAPIhas question
    if (res.code && res.code !== 200) {
      if (Number(res.code) === 403) retireDeniedAccess(response.config);
      if (res.code === 401) requireCurrentAssistanceLogin(response.config);
      if (!monitoringErrorIsInline(response.config, Number(res.code)) && !measurementReadDisabledIsInline(response.config, Number(res.code), res)) showRequestError(response.config, res.msg || 'Request failed');

      // 401: not authorize, skipconvertto Sign Inpage
      if (res.code === 401) redirectExpiredSession(response.config);

      return Promise.reject(Object.assign(new Error(res.msg || 'Request failed'), { code: res.code, ...((response.config.executionReport || response.config.alertWorkflow || response.config.householdWorkflow || response.config.visitQuestion) ? { data: res.data } : {}) }));
    }

    if (binary) throw new Error('The export did not return a file. Please try again.');
    return res;
  },
  async (error) => {
    if (axios.isCancel(error)) return Promise.reject(error);
    // Opt-in obsolete/cancelled care requests are silent; legacy error behavior is unchanged.
    if (error.config?.expectedAuth && axios.isCancel(error)) return Promise.reject(error);
    requireCurrentReport(error.config);

    if (error.response?.data instanceof Blob && /json/i.test(error.response.data.type || error.response.headers?.['content-type'] || '')) {
      try { error.response.data = JSON.parse(await error.response.data.text()); } catch {}
    }

    requireCurrentReport(error.config);
    if (error.response?.status === 401) requireCurrentAssistanceLogin(error.config);
    if (error.response?.status === 403) retireDeniedAccess(error.config);
    if (measurementReadDisabledIsInline(error.config, Number(error.response?.status), error.response?.data)) return Promise.reject(error);
    logRequestError('response_failed', error);

    if (error.response) {
      switch (error.response.status) {
        case 401:
          if (isAuthSessionCurrent(error.config?.authSession) && !captureAssistanceLoginRedirect(browserFullPath(), '/').dedicatedLogin && !window.location.pathname.startsWith('/login')) {
          showRequestError(error.config, 'Your session has expired. Please sign in again.');
            redirectExpiredSession(error.config);
          }
          break;
        case 403:
          showRequestError(error.config, 'You do not have permission to perform this action.');
          break;
        case 404:
          showRequestError(error.config, 'The requested resource does not exist.');
          break;
        case 413:
          showRequestError(error.config, 'The upload is too large. Choose a smaller file or contact an administrator.');
          break;
        case 500:
          if (!monitoringErrorIsInline(error.config, Number(error.response.status))) showRequestError(error.config, 'The service encountered an error. Please try again.');
          break;
        default:
          if (!monitoringErrorIsInline(error.config, Number(error.response.status))) showRequestError(error.config, error.response.data?.msg || 'Request failed');
      }
    } else {
      if (!monitoringErrorIsInline(error.config, null)) showRequestError(error.config, 'The network connection is unavailable.');
    }

    return Promise.reject(error);
  }
);

// Capture synchronously at the call site, before Axios schedules its interceptor microtask.
const guardedRequest = new Proxy(request, {
  get(target, property, receiver) {
    if (['get', 'delete', 'head', 'options', 'post', 'put', 'patch'].includes(property)) {
      return (url, dataOrConfig, options) => {
        const writes = ['post', 'put', 'patch'].includes(property);
        const config = { ...(writes ? options : dataOrConfig), url, method: property, ...(writes ? { data: dataOrConfig } : {}) };
        config.privacyContext ||= captureRequestContext(config);
        return Reflect.apply(target, receiver, [config]);
      };
    }
    return Reflect.get(target, property, receiver);
  },
  apply(target, thisArg, args) {
    const config = typeof args[0] === 'string' ? { ...args[1], url: args[0] } : { ...args[0] };
    config.privacyContext ||= captureRequestContext(config);
    return Reflect.apply(target, thisArg, [config]);
  }
});
export default guardedRequest;
