import request from '@/utils/request';
import { captureAuthSession } from '@/utils/authSession';
const root = '/family-assistance';
const call = (config, options = {}) => request({ ...config, householdWorkflow: true, expectedAuth: options.expectedAuth ?? { ...captureAuthSession(), actorId: localStorage.getItem('userId') }, signal: options.signal });
export const capabilities = (patientId, options) => call({ url: `${root}/capabilities`, params: { patientId } }, options);
export const list = (params, options) => call({ url: root, params }, options);
export const detail = (id, options) => call({ url: `${root}/${id}`, params: options?.patientId ? { patientId: options.patientId } : {} }, options);
export const events = (id, params, options) => call({ url: `${root}/${id}/events`, params }, options);
export const deliveryStatus = (id, params, options) => call({ url: `${root}/${id}/delivery-status`, params }, options);
export const receipt = (patientId, key, options) => call({ url: `${root}/command-receipts/${encodeURIComponent(key)}`, params: { patientId } }, options);
export function command(envelope, options) {
  const { operation, target, commandKey, expectedVersion, patientId, body } = envelope;
  if (!['CREATE','ASSIGN','ACCEPT','REJECT','HANDLE','CONFIRM','RETURN','HANDOFF','CANCEL','CLOSE_UNRESOLVED','REOPEN','RECONFIRM_APPOINTMENT'].includes(operation)) throw new TypeError('INVALID_ACTION');
  const action = operation.toLowerCase().replaceAll('_', '-');
  if (operation !== 'CREATE' && (!Number.isSafeInteger(target) || target < 1)) throw new TypeError('INVALID_ASSISTANCE');
  const url = operation === 'CREATE' ? root : `${root}/${target}/${action}`;
  return call({ url, method: 'post', data: { commandKey, expectedVersion, patientId, body } }, options);
}
export const retryDelivery = (id, body, options) => call({ url: `${root}/notifications/${id}/retry`, method: 'post', data: body }, options);
export const handoverSource = (id, options) => call({ url: `${root}/handover-sources/${id}`, params: { patientId: options?.patientId } }, options);
export const handoverContent = (id, options) => call({ url: `${root}/${id}/handover-content`, params: { patientId: options?.patientId } }, options);
export const handoverWork = (params, options) => call({ url: `${root}/handover-work`, params }, options);
export const handoverCandidates = (params, options) => call({ url: `${root}/handover-candidates`, params }, options);
