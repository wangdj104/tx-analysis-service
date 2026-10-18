import request from '@/utils/request';
import { captureAuthSession } from '@/utils/authSession';
const root = '/visit-followup';
const call = (config, options = {}) => request({ ...config, householdWorkflow: true, expectedAuth: options.expectedAuth ?? { ...captureAuthSession(), actorId: localStorage.getItem('userId') }, signal: options.signal });
const locator = (kind, id) => {
  if (!['CARE_ITEM_APPOINTMENT', 'CARE_APPOINTMENT', 'VISIT_RECORD'].includes(kind) || !Number.isSafeInteger(Number(id)) || Number(id) < 1) throw new TypeError('INVALID_APPOINTMENT');
  return `${root}/sources/${kind}/${Number(id)}`;
};
export const capabilities = (patientId, options) => call({ url: `${root}/capabilities`, params: { patientId } }, options);
export const appointments = (params, options) => call({ url: `${root}/appointments`, params }, options);
export const source = (patientId, kind, id, options) => call({ url: locator(kind, id), params: { patientId } }, options);
export const events = (patientId, kind, id, params, options) => call({ url: `${locator(kind, id)}/events`, params: { ...params, patientId } }, options);
export const command = (envelope, options) => call({ url: `${root}/commands`, method: 'post', data: envelope }, options);

export const visits = (params, options) => call({ url: `${root}/visits`, params }, options);
export const visitActions = (params, options) => call({ url: `${root}/visit-actions`, params }, options);

export const owners = (params, options) => call({ url: `${root}/owners`, params }, options);
export const doctors = (params, options) => call({ url: `${root}/doctors`, params }, options);
export const successors = (params, options) => call({ url: `${root}/successors`, params }, options);
export const nextAppointments = (patientId, options) => call({ url: `${root}/next-appointments`, params: { patientId } }, options);
