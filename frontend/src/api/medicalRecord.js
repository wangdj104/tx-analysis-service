import request from '@/utils/request';
import { prepareUploadFiles } from '@/utils/imageCompress';

export const uploadAndRecognize = async (files, patientId, recordType) => {
  const prepared = await prepareUploadFiles(files);
  const formData = new FormData();
  prepared.forEach(file => formData.append('files', file));
  if (patientId) formData.append('patientId', patientId);
  if (recordType) formData.append('recordType', recordType);
  return request({ url: '/medical-record/upload', method: 'post', data: formData, timeout: 300000 });
};

export const recognizeBase64 = (base64Image, recordType) =>
  request({ url: '/medical-record/recognize-base64', method: 'post', data: { base64Image, recordType } });

export const listRecords = (params) => request({ url: '/medical-record/list', method: 'get', params });

export const getRecord = (id) => request({ url: `/medical-record/${id}`, method: 'get' });

export const saveRecord = (record, items, attachments) =>
  request({ url: '/medical-record/save', method: 'post', data: { record, items, attachments } });

export const saveRecordsBatch = (records, itemsList, attachments) =>
  request({ url: '/medical-record/save-batch', method: 'post', data: { records, itemsList, attachments } });

export const updateRecord = (record, items, attachments) =>
  request({ url: '/medical-record/update', method: 'put', data: { record, items, attachments } });

export const deleteRecord = (id) => request({ url: `/medical-record/delete/${id}`, method: 'delete' });

export const getItemTrend = (patientId, patientName, itemName) => {
  const params = { itemName };
  if (patientId) params.patientId = patientId;
  if (patientName) params.patientName = patientName;
  return request({ url: '/medical-record/trend', method: 'get', params });
};

export const getAllItemNames = (patientId) => {
  const params = {};
  if (patientId) params.patientId = patientId;
  return request({ url: '/medical-record/items', method: 'get', params });
};
