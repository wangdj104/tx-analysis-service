import request from '@/utils/request';
export const getAuditPage = params => request({ url: '/audit-log/page', method: 'get', params });
