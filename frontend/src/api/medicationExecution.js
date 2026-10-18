import request from '@/utils/request';
import { captureAuthSession } from '@/utils/authSession';
const root = '/medication-executions';
const call = (config, options = {}) => request({ ...config, householdWorkflow: true, expectedAuth: options.expectedAuth ?? { ...captureAuthSession(), actorId: localStorage.getItem('userId') }, signal: options.signal });
export const capabilities = (patientId, options) => call({ url: `${root}/capabilities`, params: { patientId } }, options);
export const list = (params, options) => call({ url: root, params }, options);
export const detail = (id, options) => call({ url: `${root}/${id}`, params: options?.patientId ? { patientId: options.patientId } : {} }, options);
export const medications = (params, options) => call({ url: `${root}/medications`, params }, options);
export const medication = (id, options) => call({ url: `${root}/medications/${id}`, params: { patientId: options.patientId } }, options);
export const clinicians = (params, options) => call({ url: `${root}/clinicians`, params }, options);
export const sources = (params, options) => call({ url: `${root}/sources`, params }, options);
export const replacementPreview = (id, options) => call({ url: `${root}/${id}/replacement-preview` }, options);
export const events = (id, params, options) => call({ url: `${root}/${id}/events`, params }, options);
export const deliveryStatus = (id, params, options) => call({ url: `${root}/${id}/delivery-status`, params }, options);
export const receipt = (patientId, key, options) => call({ url: `${root}/command-receipts/${encodeURIComponent(key)}`, params: { patientId } }, options);
export function command(envelope, options) {
  const { operation, target, commandKey, expectedVersion, patientId, body } = envelope;
  if (!['CREATE_RECONCILIATION', 'NEW_RECONCILIATION', 'REVISE_RECONCILIATION', 'VERIFY_RECONCILIATION', 'RETURN_RECONCILIATION', 'CANCEL_RECONCILIATION', 'SCHEDULE_REPLACEMENT', 'CANCEL_SCHEDULE','RECORD_TAKEN','RECORD_SKIPPED','SNOOZE','CORRECT_INTAKE','RECORD_CONTACT','END_INTAKE_UNRESOLVED','REOPEN_INTAKE_FOLLOW_UP','PERSONAL_STOCK_FACT'].includes(operation)) throw new TypeError('INVALID_ACTION');
  const action = operation.toLowerCase().replaceAll('_', '-');
  const url = operation==='PERSONAL_STOCK_FACT' && target===null ? `${root}/personal-stock-fact` : operation === 'CREATE_RECONCILIATION' && target === null ? `${root}/${action}` : `${root}/${target}/${action}`;
  return call({ url, method: 'post', data: { commandKey, expectedVersion, patientId, body } }, options);
}
export const retryDelivery = (id, body, options) => call({ url: `${root}/notifications/${id}/retry`, method: 'post', data: body }, options);

export const intakes = (id,params,options) => call({url:`${root}/${id}/intakes`,params},options);

export const personalIntakes=(params,options)=>call({url:`${root}/personal-intakes`,params},options);
