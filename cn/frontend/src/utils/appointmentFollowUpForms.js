const id = value => { const parsed = Number(value); if (!/^[1-9]\d*$/.test(String(value)) || !Number.isSafeInteger(parsed)) throw Error('INVALID_ACCOUNT'); return parsed; };
const reason = value => { const result = String(value || '').trim(); if (!result || [...result].length > 500) throw Error('REASON_REQUIRED'); return result; };
export const appointmentWall = value => {
  const text = String(value || '').replace(' ', 'T').replace(/\.0{1,6}$/, '');
  const result = /^\d{4}-\d\d-\d\dT\d\d:\d\d$/.test(text) ? `${text}:00` : text;
  if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d$/.test(result)) throw Error('WALL_TIME_REQUIRED'); return result;
};
export function appointmentActual(draft) {
  if (draft.timeKind === 'UNKNOWN') return { timeKind: 'UNKNOWN', occurredAt: null };
  if (draft.timeKind !== 'KNOWN') throw Error('ACTUAL_CHOICE_REQUIRED');
  const wall = String(draft.actualWall || '').trim(), offset = String(draft.actualOffset || '').trim();
  if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?$/.test(wall) || !/^(Z|[+-](?:0\d|1[0-7]):[0-5]\d|[+-]18:00)$/.test(offset)) throw Error('ACTUAL_OFFSET_REQUIRED');
  return { timeKind: 'KNOWN', occurredAt: wall + offset };
}
export function appointmentBody(operation, draft, source, target) {
  const responsibility = () => { let value=String(draft.dueAt || '').trim(); if (/^\d{4}-\d\d-\d\dT\d\d:\d\d$/.test(value)) value+=':00'; if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?$/.test(value)) throw Error('WALL_TIME_REQUIRED'); return { responsibleUserId: id(draft.responsibleUserId), dueAt: value+'Z' }; };
  const schedule = () => source.sourceKind === 'CARE_ITEM_APPOINTMENT' ? { eventAt: appointmentWall(draft.startAt), notifyAt: draft.notifyAt ? appointmentWall(draft.notifyAt) : null } : { doctorUserId: id(draft.doctorUserId), scheduleId: draft.scheduleId ? id(draft.scheduleId) : null, startAt: appointmentWall(draft.startAt), endAt: appointmentWall(draft.endAt) };
  const successor = () => { if (!target || target.patientId !== source.patientId || target.sourceKind !== source.sourceKind || target.sourceId === source.sourceId || !draft.confirmTarget) throw Error('TARGET_CONFIRM_REQUIRED'); return { sourceKind: target.sourceKind, sourceId: target.sourceId, expectedVersion: target.version, expectedSourceFingerprint: target.sourceFingerprint, track: true }; };
  switch (operation) {
    case 'TRACK_APPOINTMENT': if (!draft.confirmTrack) throw Error('TRACK_CONFIRM_REQUIRED'); return responsibility();
    case 'RESCHEDULE': return { schedule: schedule(), reason: reason(draft.reason) };
    case 'CANCEL': if ((source.successor || source.nextDecision === 'SCHEDULED') && !['KEEP_LINK','UNLINK'].includes(draft.successorDisposition)) throw Error('SUCCESSOR_DISPOSITION_REQUIRED'); return { reason: reason(draft.reason), successorDisposition: source.successor || source.nextDecision === 'SCHEDULED' ? draft.successorDisposition : null };
    case 'COMPLETE_SELECTED_APPOINTMENT': return { actual: appointmentActual(draft) };
    case 'RECORD_ATTENDANCE': case 'CORRECT_ATTENDANCE':
      if (!['ATTENDED','NO_SHOW', ...(operation === 'CORRECT_ATTENDANCE' ? ['UNVERIFIED'] : [])].includes(draft.attendance)) throw Error('ATTENDANCE_REQUIRED');
      return { attendance: draft.attendance, actual: appointmentActual(draft), reason: reason(draft.reason), ...(operation === 'CORRECT_ATTENDANCE' ? { eventId: id(source.fact?.id) } : {}) };
    case 'SET_NEXT_DECISION': if (!['NEEDS_DECISION','NOT_ARRANGED','CLOSED_UNRESOLVED'].includes(draft.nextDecision)) throw Error('DECISION_REQUIRED'); return { ...responsibility(), nextDecision: draft.nextDecision, reason: reason(draft.reason) };
    case 'CREATE_NEXT': case 'LINK_NEXT': case 'REPLACE_NEXT':
      return { ...responsibility(), reason: reason(draft.reason), ...(operation === 'LINK_NEXT' || operation === 'REPLACE_NEXT' && draft.nextMode === 'LINK' ? { successor: successor() } : { schedule: schedule() }), ...(operation === 'REPLACE_NEXT' ? { previousSuccessorDisposition: 'KEEP_APPOINTMENT' } : {}) };
    default: throw Error('INVALID_ACTION');
  }
}
