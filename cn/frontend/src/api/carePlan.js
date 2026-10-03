import request from '@/utils/request'
import { captureAuthSession } from '@/utils/authSession'

const root = '/care-plans'
// Capture at API invocation, not in axios's later interceptor microtask.
const careRequest = (config, { expectedAuth, signal } = {}) => request({
  ...config, expectedAuth: expectedAuth ?? { ...captureAuthSession(), actorId: localStorage.getItem('userId') },
  ...(signal ? { signal } : {})
})
export const getCarePlanCapabilities = (options) => careRequest({ url: `${root}/capabilities`, method: 'get' }, options)
export const listPlans = (params, options) => careRequest({ url: root, method: 'get', params }, options)
export const getPlan = (id, options) => careRequest({ url: `${root}/${id}`, method: 'get' }, options)
export const listPlanRevisions = (id, params, options) => careRequest({ url: `${root}/${id}/revisions`, method: 'get', params }, options)
export const getPlanRevision = (id, revisionId, options) => careRequest({ url: `${root}/${id}/revisions/${revisionId}`, method: 'get' }, options)
export const createDraft = (data, options) => careRequest({ url: root, method: 'post', data }, options)
export const saveDraft = (id, revisionId, data, options) => careRequest({ url: `${root}/${id}/revisions/${revisionId}/save`, method: 'post', data }, options)
export const publishPlan = (id, revisionId, data, options) => careRequest({ url: `${root}/${id}/revisions/${revisionId}/publish`, method: 'post', data }, options)
export const revisePlan = (id, data, options) => careRequest({ url: `${root}/${id}/revisions`, method: 'post', data }, options)
export function transitionPlan(id, action, data, options) {
  if (!['CANCEL', 'CLOSE'].includes(action)) throw new TypeError('操作必须为 CANCEL 或 CLOSE。')
  return careRequest({ url: `${root}/${id}/${action.toLowerCase()}`, method: 'post', data }, options)
}
export const submitReceipt = (id, data, options) => careRequest({ url: `${root}/actions/${id}/receipts`, method: 'post', data }, options)
export const requestHelp = (id, data, options) => careRequest({ url: `${root}/actions/${id}/help`, method: 'post', data }, options)
export const followUp = (id, data, options) => careRequest({ url: `${root}/actions/${id}/follow-ups`, method: 'post', data }, options)
export const reviewReceipt = (id, data, options) => careRequest({ url: `${root}/actions/${id}/reviews`, method: 'post', data }, options)
export const listPlanEvents = (id, options) => {
  const params = options?.params
  const paging = params ? Object.fromEntries(['cursor', 'limit'].filter(key => params[key] !== undefined).map(key => [key, params[key]])) : null
  return careRequest({ url: `${root}/${id}/events`, method: 'get', ...(paging ? { params: paging } : {}) }, options)
}
export const listAssignees = (patientId, options) => careRequest({ url: `${root}/assignees`, method: 'get', params: { patientId } }, options)
export const listNurseAssignments = (patientId, options) => careRequest({ url: '/care-nurse-assignments', method: 'get', params: { patientId } }, options)
export const assignNurse = (data, options) => careRequest({ url: '/care-nurse-assignments', method: 'post', data }, options)
export const revokeNurseAssignment = (id, options) => careRequest({ url: `/care-nurse-assignments/${id}/revoke`, method: 'post' }, options)
