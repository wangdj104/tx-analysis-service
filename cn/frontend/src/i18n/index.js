import { inject } from 'vue';

export const APP_LOCALE = 'zh-CN';
export const I18N_KEY = Symbol('family-health-i18n');

const messages = {
  appName: '澄心健康',
  appTagline: '用心记录，守护每一天的健康',
  common: {
    save: '保存',
    cancel: '取消',
    close: '关闭',
    confirm: '确认',
    delete: '删除',
    edit: '编辑',
    search: '查询',
    reset: '重置',
    loading: '加载中…',
    noData: '暂无数据',
    success: '操作成功',
    failed: '操作失败'
  }
};

function resolveMessage(key) {
  return key.split('.').reduce((value, part) => value?.[part], messages);
}

export function t(key, params = {}) {
  const template = resolveMessage(key) || key;
  return Object.entries(params).reduce(
    (value, [name, replacement]) => value.replaceAll(`{${name}}`, String(replacement)),
    template
  );
}

export function formatDate(value, options = {}) {
  return new Intl.DateTimeFormat(APP_LOCALE, options).format(new Date(value));
}

export function formatNumber(value, options = {}) {
  return new Intl.NumberFormat(APP_LOCALE, options).format(value);
}

export function useI18n() {
  return inject(I18N_KEY, { locale: APP_LOCALE, t, formatDate, formatNumber });
}

export default {
  install(app) {
    const i18n = { locale: APP_LOCALE, t, formatDate, formatNumber };
    app.config.globalProperties.$t = t;
    app.provide(I18N_KEY, i18n);
  }
};
