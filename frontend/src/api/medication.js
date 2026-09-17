import request from '@/utils/request';

export const listMedications = (patientId) => {
  const params = {};
  if (patientId) params.patientId = patientId;
  return request({ url: '/medication/list', method: 'get', params });
};

export const listActiveMedications = (patientId) => {
  const params = {};
  if (patientId) params.patientId = patientId;
  return request({ url: '/medication/list-active', method: 'get', params });
};

export const saveMedication = (data) => request({ url: '/medication/save', method: 'post', data });

export const saveMedicationsBatch = (data) => request({ url: '/medication/save-batch', method: 'post', data });

export const updateMedication = (data) => request({ url: '/medication/update', method: 'put', data });

export const deleteMedication = (id) => request({ url: `/medication/delete/${id}`, method: 'delete' });

export const uploadAndRecognize = (files, patientId) => {
  const formData = new FormData();
  files.forEach(file => formData.append('files', file));
  if (patientId) formData.append('patientId', patientId);
  return request({ url: '/medication/upload-recognize', method: 'post', data: formData, timeout: 300000 });
};

export const recognizeBase64 = (base64Image) =>
  request({ url: '/medication/recognize-base64', method: 'post', data: { base64Image } });

export const listLogs = (params) => request({ url: '/medication/log/list', method: 'get', params });

export const saveLog = (data) => request({ url: '/medication/log/save', method: 'post', data });

export const updateLog = (data) => request({ url: '/medication/log/update', method: 'put', data });

export const deleteLog = (id) => request({ url: `/medication/log/delete/${id}`, method: 'delete' });
