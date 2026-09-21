import request from '@/utils/request';

export const listRecords = (timeType, timeValue, patientId) => {
  const params = {};
  if (timeType) params.timeType = timeType;
  if (timeValue) params.timeValue = timeValue;
  if (patientId) params.patientId = patientId;
  return request({ url: '/dialysis/list', method: 'get', params });
};

export const getRecord = (id) => request({ url: `/dialysis/${id}`, method: 'get' });

export const saveRecord = (data) => request({ url: '/dialysis/save', method: 'post', data });

export const updateRecord = (data) => request({ url: '/dialysis/update', method: 'put', data });

export const deleteRecord = (id) => request({ url: `/dialysis/delete/${id}`, method: 'delete' });

export const getStats = (timeType, timeValue, patientId) => {
  const params = { timeType, timeValue };
  if (patientId) params.patientId = patientId;
  return request({ url: '/dialysis/stats', method: 'get', params });
};

export const getChartData = (timeType, timeValue, patientId) => {
  const params = { timeType, timeValue };
  if (patientId) params.patientId = patientId;
  return request({ url: '/dialysis/chart', method: 'get', params });
};
