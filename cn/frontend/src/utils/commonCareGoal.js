// 本域仅映射已有的有限 DTO，不把档案归属或账户接手解释成患者同意。
export const goalOperations = ['CREATE', 'REVISE', 'ACCEPT', 'DECLINE', 'STEP_BACK', 'ADD_PROGRESS', 'ADD_FEEDBACK', 'COMPLETE', 'CANCEL', 'REOPEN']
export const feedbackSources = ['ACCOUNT_STATEMENT', 'REPORTED_PATIENT', 'REPORTED_PARTICIPANT', 'NOT_OBTAINED']
export const goalId = value => /^[1-9][0-9]*$/.test(String(value)) && Number.isSafeInteger(Number(value)) ? Number(value) : null
export function parseGoalRoute(path) {
  if (typeof path !== 'string' || path.split(/[?#]/, 1)[0] !== '/care') return null
  const parts = path.split('?'), query = new URLSearchParams((parts[1] || '').split('#', 1)[0])
  if (!query.has('goalId') && !query.getAll('tab').includes('goals')) return null
  if (parts.length !== 2 || path.includes('#')) return { invalid: true }
  const keys = ['patientId', 'goalId', 'tab']
  if ([...query.keys()].some(key => !keys.includes(key) || query.getAll(key).length !== 1)) return { invalid: true }
  if (query.has('tab') && query.get('tab') !== 'goals') return { invalid: true }
  const patientId = goalId(query.get('patientId')), target = goalId(query.get('goalId'))
  if (!patientId || (query.has('goalId') && !target)) return { invalid: true }
  return target ? { patientId, goalId: target } : { patientId }
}
const validDate = value => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || Number(value.slice(0, 4)) < 1) return false
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value
}
export function validateGoalDraft(operation, draft) {
  const errors = {}
  const text = (key, max) => {
    const value = draft[key]
    if (typeof value !== 'string' || !value.trim()) errors[key] = 'required'
    else if ([...value].length > max) errors[key] = 'tooLong'
    else if (/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value)) errors[key] = 'invalidText'
  }
  if (!goalOperations.includes(operation)) errors.operation = 'required'
  if (['CREATE', 'REVISE'].includes(operation)) {
    for (const [key, max] of [['title',120], ['goalText',1000], ['progressCriterion',500], ['responsibility',500]]) text(key, max)
    if (draft.reviewDate && !validDate(draft.reviewDate)) errors.reviewDate = 'invalidDate'
  }
  if (['REVISE', 'DECLINE', 'STEP_BACK', 'CANCEL', 'REOPEN'].includes(operation)) text('reason', 1000)
  if (['ADD_PROGRESS', 'ADD_FEEDBACK'].includes(operation)) {
    text('note', 1000)
    if (draft.reportedDate && !validDate(draft.reportedDate)) errors.reportedDate = 'invalidDate'
  }
  if (operation === 'ADD_FEEDBACK' && !feedbackSources.includes(draft.feedbackSource)) errors.feedbackSource = 'required'
  if (operation === 'COMPLETE') text('result', 1000)
  if (['ACCEPT', 'COMPLETE'].includes(operation) && draft.confirmed !== true) errors.confirmed = 'required'
  return errors
}
export function goalBody(operation, draft, { actorId, owner, baseline, candidates = [] }) {
  const body = {}
  if (['CREATE', 'REVISE'].includes(operation)) {
    const old = baseline?.goal?.content?.nomineeId ?? null
    let nomineeId = null
    if (draft.nominationMode === 'keep' && operation === 'REVISE') nomineeId = old
    else if (draft.nominationMode === 'me') nomineeId = goalId(actorId)
    else if (draft.nominationMode === 'choose' && owner && candidates.some(item => item.userId === goalId(draft.selectedNomineeId))) nomineeId = goalId(draft.selectedNomineeId)
    else if (draft.nominationMode !== 'unassigned') throw new Error('COMMON_GOAL_NOMINEE_REQUIRED')
    if (!owner && old != null && old !== goalId(actorId) && nomineeId !== old) throw new Error('COMMON_GOAL_NOMINEE_REQUIRED')
    body.content = Object.fromEntries(['title', 'goalText', 'progressCriterion', 'responsibility'].map(key => [key, draft[key]]))
    body.content.reviewDate = draft.reviewDate || null
    body.content.nomineeId = nomineeId
  }
  if (['REVISE', 'DECLINE', 'STEP_BACK', 'CANCEL', 'REOPEN'].includes(operation)) body.reason = draft.reason
  if (['ADD_PROGRESS', 'ADD_FEEDBACK'].includes(operation)) { body.note = draft.note; body.reportedDate = draft.reportedDate || null }
  if (operation === 'ADD_FEEDBACK') body.feedbackSource = draft.feedbackSource
  if (operation === 'COMPLETE') body.result = draft.result
  return body
}
export function completionReady(detail) {
  const goal = detail?.goal, progress = detail?.latestProgress, feedback = detail?.latestFeedback
  return !!(detail?.completionReady && detail.recordOwner && goal?.state === 'ACTIVE' && goal.acceptedBy != null && detail.nomineeCurrentlyEligible &&
    progress?.cycle === goal.cycle && progress.operation === 'ADD_PROGRESS' && feedback?.cycle === goal.cycle &&
    feedback.operation === 'ADD_FEEDBACK' && feedbackSources.includes(feedback.feedbackSource) && feedback.feedbackSource !== 'NOT_OBTAINED')
}
export function validGoalReceipt(receipt, envelope) {
  if (!receipt || Object.keys(receipt).sort().join(',') !== 'cycle,goalId,operation,state,version') return false
  return !!(goalId(receipt.goalId) && goalId(receipt.version) && goalId(receipt.cycle) && receipt.cycle <= receipt.version &&
    ['PROPOSED','ACTIVE','COMPLETED','CANCELLED'].includes(receipt.state) && receipt.operation === envelope.operation &&
    receipt.version === envelope.expectedVersion + 1 && (envelope.target == null || receipt.goalId === envelope.target))
}
