import { appointmentActual, appointmentWall } from './appointmentFollowUpForms';
const positive = value => { if (!Number.isSafeInteger(value) || value < 1) throw Error('INVALID_ACTION'); return value; };
const text = value => String(value || '').trim() || null;
/** 新摘要必须由用户明确输入真实时间，不能取预约排程或本机现在。 */
export function visitBody(operation, draft, origin) {
  if (!draft.confirm) throw Error('CONFIRM_REQUIRED');
  if (operation === 'CREATE_VISIT') {
    const actual = appointmentActual({ ...draft, timeKind: 'KNOWN' });
    const diagnosisSummary = text(draft.diagnosisSummary), treatmentSummary = text(draft.treatmentSummary), followUpAdvice = text(draft.followUpAdvice);
    if (!diagnosisSummary && !treatmentSummary) throw Error('SUMMARY_REQUIRED');
    if (new TextEncoder().encode([diagnosisSummary, treatmentSummary, followUpAdvice].map(v => v || '').join('')).length > 65536) throw Error('SOURCE_TOO_LARGE');
    let originAppointment = null;
    if (draft.originId) {
      if (!origin || !origin.managed || origin.patientId !== draft.patientId || origin.sourceKind !== draft.originKind || String(origin.sourceId) !== String(draft.originId) || !draft.confirmOrigin) throw Error('ORIGIN_CONFIRM_REQUIRED');
      originAppointment = { sourceKind: origin.sourceKind, sourceId: positive(origin.sourceId), expectedVersion: positive(origin.version), expectedSourceFingerprint: origin.sourceFingerprint };
    }
    return { actual, originAppointment, diagnosisSummary, treatmentSummary, followUpAdvice, followUpAt: draft.followUpAt ? appointmentWall(draft.followUpAt) : null };
  }
  if (operation === 'PUBLISH_VISIT') return {};
  if (!['SET_DISPOSITION', 'REVISE_DISPOSITION'].includes(operation) || !['NO_FOLLOW_UP', 'ACTIONS_REQUIRED'].includes(draft.disposition)) throw Error('DECISION_REQUIRED');
  const reason = text(draft.reason), required = operation === 'REVISE_DISPOSITION' || draft.disposition === 'NO_FOLLOW_UP';
  if (required && !reason || reason && [...reason].length > 1000) throw Error('REASON_REQUIRED');
  const links = draft.disposition === 'ACTIONS_REQUIRED' ? (draft.links || []).map(link => {
    if (!['CHECK', 'REVISIT', 'REFERRAL'].includes(link.kind)) throw Error('LINK_KIND_REQUIRED');
    return { planId: positive(link.planId), revisionId: positive(link.revisionId), actionId: positive(link.actionId), kind: link.kind };
  }) : [];
  if (draft.disposition === 'ACTIONS_REQUIRED' && (!links.length || links.length > 10 || new Set(links.map(link => link.actionId)).size !== links.length)) throw Error('LINKS_REQUIRED');
  return { disposition: draft.disposition, reason, links };
}
