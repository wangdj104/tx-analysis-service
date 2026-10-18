import request from '@/utils/request';
import { captureAuthSession } from '@/utils/authSession';
const root = '/emergency-assistance';
const call = (config, options = {
}
) => request({
    ...config, householdWorkflow: true, expectedAuth: options.expectedAuth ?? {
        ...captureAuthSession(), actorId: localStorage.getItem('userId') }
    , signal: options.signal }
);
export const capabilities = (patientId, options) => call({
    url: '/care-journey/emergency-capabilities', params: {
        patientId }
}
, options);
export const list = (params, options) => call({
    url: root, params }
, options);
export const detail = (id, options) => call({
    url: `${root}/${id}`, params: options?.patientId ? {
        patientId: options.patientId }
    : {
    }
}
, options);
export const events = (id, params, options) => call({
    url: `${root}/${id}/events`, params }
, options);
export const deliveryStatus = (id, params, options) => call({
    url: `${root}/${id}/delivery-status`, params }
, options);
export const receipt = (patientId, key, options) => call({
    url: `${root}/command-receipts/${encodeURIComponent(key)}`, params: {
        patientId }
}
, options);
export function command(envelope, options) {
    const {
        operation, target, commandKey, expectedVersion, patientId, body }
    = envelope;
    if (!['CREATE_REQUEST','ACCEPT','PROGRESS','HANDOFF','RESOLVE','CANCEL_MISTAKE'].includes(operation)) throw new TypeError('INVALID_ACTION');
    const action = operation.toLowerCase().replaceAll('_', '-');
    if (operation !== 'CREATE_REQUEST' && (!Number.isSafeInteger(target) || target < 1)) throw new TypeError('INVALID_EMERGENCY');
    const url = operation === 'CREATE_REQUEST' ? root : `${root}/${target}/${action}`;
    return call({
        url, method: 'post', data: {
            commandKey, expectedVersion, patientId, body }
    }
    , options);
}
export const retryDelivery = (id, body, options) => call({
    url: `${root}/notifications/${id}/retry`, method: 'post', data: body }
, options);
export const legacyList=(patientId,options)=>call({
    url:'/care-journey/emergencies',params:{
        patientId}
}
,options);
export const legacyDetail=(id,options)=>call({
    url:`/care-journey/emergencies/${id}`}
,options);
export const legacyCreate=(body,options)=>call({
    url:'/care-journey/emergencies',method:'post',data:body}
,options);
