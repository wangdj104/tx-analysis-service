import { reactive } from 'vue';
import axios from 'axios';
import { applyPlatformBackground } from './healthAppearance.js';

export const DEFAULT_BRANDING = Object.freeze({
  platformName: '澄心健康', organizationName: '澄心健康', logo: `${import.meta.env?.BASE_URL || '/'}logo.svg`,
  pageBackground: '#f5f7fb', ownershipText: '© 2026 澄心健康 版权所有'
});

export const platformBranding = reactive({ ...DEFAULT_BRANDING, configuredLogo: '/logo.svg', loaded: false });

export function resolvePlatformLogo(logo) {
  // The backend's built-in logo is an edition asset; custom URLs stay exact.
  return logo === '/logo.svg' ? DEFAULT_BRANDING.logo : logo;
}

function localizeDefaultBranding(value = {}) {
  const result = { ...value };
  if (result.platformName === 'Chengxin Health') result.platformName = '澄心健康';
  if (result.organizationName === 'Chengxin Health') result.organizationName = '澄心健康';
  if (/^©\s*\d{4}\s+Chengxin Health\.?\s+All rights reserved\.?$/i.test(result.ownershipText || '')) {
    const year = String(result.ownershipText).match(/\d{4}/)?.[0] || new Date().getFullYear();
    result.ownershipText = `© ${year} 澄心健康 版权所有`;
  }
  return result;
}

export function applyPlatformBranding(value = {}) {
  // Keep the configured value portable when another branding field is saved.
  const configuredLogo = value.logo === undefined ? '/logo.svg' : value.logo;
  Object.assign(platformBranding, DEFAULT_BRANDING, localizeDefaultBranding(value), { configuredLogo, loaded: true });
  platformBranding.logo = resolvePlatformLogo(configuredLogo);
  applyPlatformBackground(platformBranding.pageBackground);
  document.title = platformBranding.platformName;
  let icon = document.querySelector("link[rel='icon']");
  if (!icon) { icon = document.createElement('link'); icon.rel = 'icon'; document.head.appendChild(icon); }
  icon.href = platformBranding.logo;
}

export async function loadPlatformBranding() {
  try {
    const response = await axios.get('/api/platform-branding/public', { timeout: 8000, headers: { 'Accept-Language': 'zh-CN' } });
    if (response.data?.code === 200) applyPlatformBranding(response.data.data);
    else applyPlatformBranding();
  } catch { applyPlatformBranding(); }
  return platformBranding;
}
