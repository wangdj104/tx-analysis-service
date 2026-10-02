import { ref } from 'vue';

export const APPEARANCE_STORAGE_KEY = 'chengxin-health-appearance-v1';
export const APPEARANCE_THEME_IDS = Object.freeze(['platform', 'white', 'blue', 'mint', 'sand', 'dark']);
export const appearanceTheme = ref('platform');
let stopStorageListener;

function validTheme(value) {
  return APPEARANCE_THEME_IDS.includes(value) ? value : 'platform';
}

function savedTheme(raw) {
  // Earlier browser-local choices may have been stored as a plain preset ID.
  if (APPEARANCE_THEME_IDS.includes(raw)) return raw;
  try {
    const value = JSON.parse(raw);
    return value?.version === 1 ? validTheme(value.theme) : 'platform';
  } catch { return 'platform'; }
}

function applyTheme(value) {
  appearanceTheme.value = validTheme(value);
  document.documentElement.dataset.healthTheme = appearanceTheme.value;
}

export function initializeAppearance() {
  stopStorageListener?.();
  let saved = null;
  try { saved = localStorage.getItem(APPEARANCE_STORAGE_KEY); } catch { /* Browser storage can be disabled. */ }
  applyTheme(savedTheme(saved));
  const target = window;
  const onStorage = event => {
    if (event.key === APPEARANCE_STORAGE_KEY || event.key === null) applyTheme(savedTheme(event.newValue));
  };
  target.addEventListener('storage', onStorage);
  const cleanup = () => target.removeEventListener('storage', onStorage);
  stopStorageListener = cleanup;
  return cleanup;
}

export function setAppearanceTheme(value) {
  applyTheme(value);
  try {
    localStorage.setItem(APPEARANCE_STORAGE_KEY, JSON.stringify({ version: 1, theme: appearanceTheme.value }));
    return true;
  } catch { return false; }
}

export function applyPlatformBackground(value) {
  const color = /^#[0-9a-f]{6}$/i.test(value || '') ? value.toLowerCase() : '#f5f7fb';
  document.documentElement.style.setProperty('--platform-page-bg', color);
  // An older branding runtime wrote an inline value that bypassed theme tokens.
  document.documentElement.style.removeProperty('--app-page-bg');
}
