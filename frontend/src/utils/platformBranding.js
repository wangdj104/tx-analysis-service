import { reactive } from 'vue';
import axios from 'axios';
import { applyPlatformBackground } from './healthAppearance.js';

export const DEFAULT_BRANDING = Object.freeze({
  platformName: 'Chengxin Health', organizationName: 'Chengxin Health', logo: `${import.meta.env?.BASE_URL || '/'}logo.svg`,
  pageBackground: '#f5f7fb', ownershipText: '© 2026 Chengxin Health. All rights reserved.'
});

export const platformBranding = reactive({ ...DEFAULT_BRANDING, configuredLogo: '/logo.svg', loaded: false });

export function resolvePlatformLogo(logo) {
  // The backend's built-in logo is an edition asset; custom URLs stay exact.
  return logo === '/logo.svg' ? DEFAULT_BRANDING.logo : logo;
}

export function applyPlatformBranding(value = {}) {
  // Keep the configured value portable when another branding field is saved.
  const configuredLogo = value.logo === undefined ? '/logo.svg' : value.logo;
  Object.assign(platformBranding, DEFAULT_BRANDING, value, { configuredLogo, loaded: true });
  platformBranding.logo = resolvePlatformLogo(configuredLogo);
  applyPlatformBackground(platformBranding.pageBackground);
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
