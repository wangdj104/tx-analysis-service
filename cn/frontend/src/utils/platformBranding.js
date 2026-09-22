import { reactive } from 'vue';
import axios from 'axios';

export const DEFAULT_BRANDING = Object.freeze({
  platformName: '澄心健康', organizationName: '澄心健康', logo: '/logo.svg',
  pageBackground: '#f5f7fb', ownershipText: '© 2026 澄心健康 版权所有'
});

export const platformBranding = reactive({ ...DEFAULT_BRANDING, loaded: false });

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
  Object.assign(platformBranding, DEFAULT_BRANDING, localizeDefaultBranding(value), { loaded: true });
  document.documentElement.style.setProperty('--app-page-bg', platformBranding.pageBackground);
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
