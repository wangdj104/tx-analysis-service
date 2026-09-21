import request from '@/utils/request';
export function savePlatformBranding(data) { return request.post('/platform-branding', data); }
