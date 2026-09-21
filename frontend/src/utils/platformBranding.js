import { reactive } from 'vue';
import axios from 'axios';

export const DEFAULT_BRANDING = Object.freeze({
  platformName: 'Chengxin Health', organizationName: 'Chengxin Health', logo: '/logo.svg',
  pageBackground: '#f5f7fb', ownershipText: '© 2026 Chengxin Health. All rights reserved.'
});

export const platformBranding = reactive({ ...DEFAULT_BRANDING, loaded: false });

export function applyPlatformBranding(value = {}) {
  Object.assign(platformBranding, DEFAULT_BRANDING, value, { loaded: true });
  document.documentElement.style.setProperty('--app-page-bg', platformBranding.pageBackground);
  document.title = platformBranding.platformName;
  let icon = document.querySelector("link[rel='icon']");
  if (!icon) { icon = document.createElement('link'); icon.rel = 'icon'; document.head.appendChild(icon); }
  icon.href = platformBranding.logo;
}

export async function loadPlatformBranding() {
  try {
    const response = await axios.get('/api/platform-branding/public', { timeout: 8000 });
    if (response.data?.code === 200) applyPlatformBranding(response.data.data);
    else applyPlatformBranding();
  } catch { applyPlatformBranding(); }
  return platformBranding;
}
