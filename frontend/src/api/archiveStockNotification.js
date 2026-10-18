import request from '@/utils/request'
import { captureAuthSession } from '@/utils/authSession'
const root = '/archive-stock-notifications'
const call = (config, options = {}) => request({ ...config, householdWorkflow: true, expectedAuth: options.expectedAuth ?? { ...captureAuthSession(), actorId: localStorage.getItem('userId') }, signal: options.signal })
export const current = (stockId, patientId, options) => call({ url: `${root}/${stockId}`, params: { patientId } }, options)
export const command = (envelope, options) => call({ url: `${root}/commands`, method: 'post', data: envelope }, options)
export const receipt = (stockId, patientId, key, options) => call({ url: `${root}/${stockId}/receipts/${encodeURIComponent(key)}`, params: { patientId } }, options)
