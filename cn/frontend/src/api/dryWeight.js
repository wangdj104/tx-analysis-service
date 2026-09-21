import request from '@/utils/request';

export const listDryWeights = (patientId) =>
  request({ url: '/dry-weight/list', method: 'get', params: { patientId } });

export const getDryWeightByMonth = (yearMonth, patientId) =>
  request({ url: '/dry-weight/get', method: 'get', params: { yearMonth, patientId } });

export const saveDryWeight = (data) => request({ url: '/dry-weight/save', method: 'post', data });

export const deleteDryWeight = (id) => request({ url: `/dry-weight/delete/${id}`, method: 'delete' });
