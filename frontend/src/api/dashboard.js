import request from '@/utils/request';

export const getDashboardSummary = (patientId) => {
  const params = {};
  if (patientId) params.patientId = patientId;
  return request({ url: '/dashboard/summary', method: 'get', params });
};
