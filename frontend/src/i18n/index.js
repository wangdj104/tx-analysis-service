import { inject } from 'vue';

export const APP_LOCALE = 'en-US';
export const I18N_KEY = Symbol('family-health-i18n');

const messages = {
  appName: 'Chengxin Health',
  appTagline: 'Thoughtful tracking for healthier days',
  common: {
    save: 'Save',
    cancel: 'Cancel',
    close: 'Close',
    confirm: 'Confirm',
    delete: 'Delete',
    edit: 'Edit',
    search: 'Search',
    reset: 'Reset',
    loading: 'Loading…',
    noData: 'No data',
    success: 'Operation completed',
    failed: 'Operation failed'
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
