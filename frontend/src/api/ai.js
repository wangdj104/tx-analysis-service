import request from '@/utils/request';

export const analyzeDialysis = (timeType, timeValue, patientId) =>
  request({ url: '/ai/analyze', method: 'get', params: { timeType, timeValue, patientId } });

export const saveAnalysis = (record) => request({ url: '/ai/save', method: 'post', data: record });

export const listHistory = (timeType, timeValue, patientId) =>
  request({ url: '/ai/history', method: 'get', params: { timeType, timeValue, patientId } });

export const deleteAnalysis = (id) => request({ url: `/ai/delete/${id}`, method: 'delete' });
