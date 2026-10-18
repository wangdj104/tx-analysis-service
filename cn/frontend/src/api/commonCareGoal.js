import request from '@/utils/request'
import { goalOperations, goalId } from '@/utils/commonCareGoal'

const root = '/common-care-goals'
// 使用共享的发送前身份复验；错误只在本面板显示，不叠加全站 toast。
const call = (config, options = {}) => request({ ...config, householdWorkflow: true, commonGoalInlineErrors: true, expectedAuth: options.expectedAuth, signal: options.signal })
export const capabilities = (patientId, options) => call({ url: `${root}/capabilities`, method: 'get', params: { patientId } }, options)
export const list = (patientId, filters, options) => call({ url: root, method: 'get', params: { ...filters, patientId } }, options)
export const current = (patientId, goalId, options) => call({ url: `${root}/${goalId}/current`, method: 'get', params: { patientId } }, options)
export const history = (patientId, goalId, filters, options) => call({ url: `${root}/${goalId}/history`, method: 'get', params: { ...filters, patientId } }, options)
export const historyDetail = (patientId, goalId, sequence, options) => call({ url: `${root}/${goalId}/history/${sequence}`, method: 'get', params: { patientId } }, options)
export const candidates = (patientId, name, options) => call({ url: `${root}/candidates`, method: 'get', params: { patientId, ...(name ? { name } : {}) } }, options)
export const receipt = (patientId, key, options) => call({ url: `${root}/command-receipts/${encodeURIComponent(key)}`, method: 'get', params: { patientId } }, options)
export function command(envelope, options) {
  const { operation, target, commandKey, expectedVersion, patientId, body } = envelope
  if (!goalOperations.includes(operation) || !goalId(patientId) || (operation !== 'CREATE' && !goalId(target))) throw new TypeError('COMMON_GOAL_INVALID_REQUEST')
  return call({ url: `${root}/commands`, method: 'post', data: { commandKey, patientId, goalId: target, expectedVersion, operation, ...body } }, options)
}
