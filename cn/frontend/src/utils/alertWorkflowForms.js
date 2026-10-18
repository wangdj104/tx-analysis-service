// Wall times stay unchanged. The offset is explicit user context, never guessed from a stored value.
export function measurementInstant(wall, offset, { localOnly = false } = {}) {
    const value = String(wall || '').replace(' ', 'T'), zone = String(offset || '').trim();
    if (!localOnly && (!/^[+-](?:0\d|1[0-4]):[0-5]\d$/.test(zone) || (zone.slice(1, 3) === '14' && zone.slice(4) !== '00')))
        throw new Error('OFFSET_REQUIRED');
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,6})?)?$/.test(value))
        throw new Error('TIME_REQUIRED');
    const normalized = value.length === 16 ? `${value}:00` : value, stamp = new Date(`${normalized.slice(0, 19)}Z`);
    if (!Number.isFinite(stamp.getTime()) || stamp.toISOString().slice(0, 19) !== normalized.slice(0, 19))
        throw new Error('TIME_REQUIRED');
    // 旧原表没有时区；仅验证新草稿的当地时间，不替它补时区。
    if (localOnly) return normalized;
    const instant = `${normalized}${zone}`;
    if (Date.parse(instant) > Date.now())
        throw new Error('FUTURE_OCCURRENCE');
    return instant;
}
export function localOffset() {
    const m = -new Date().getTimezoneOffset();
    return `${m < 0 ? '-' : '+'}${String(Math.floor(Math.abs(m) / 60)).padStart(2, '0')}:${String(Math.abs(m) % 60).padStart(2, '0')}`;
}
export function currentWall() {
    const d = new Date(), p = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
export function initialActionForm(detail = {}, action = '') {
    return { reason: '', note: '', wall: currentWall(), offset: localOffset(), assigneeId: action === 'ESCALATE' ? null : detail.responsibleUserId ?? null, deadlineMode: action === 'ESCALATE' ? 'clear' : 'preserve', dueAt: action === 'ESCALATE' ? '' : detail.dueAt || '', evidence: [], hasContact: false, relationship: '', contactOutcome: '', entryMode: detail.permissions?.canDeclareSelf ? 'SELF' : 'ASSISTED', outcome: 'HANDLED', targetState: 'IN_PROGRESS', sourceRevision: detail.source?.sourceRevision, evidenceId: null, resolution: 'WITHDRAW', replacementType: 'HEALTH_MEASUREMENT', replacementId: null };
}
const text = value => String(value ?? '').trim();
const id = value => {
    const n = Number(value);
    if (!Number.isSafeInteger(n) || n < 1)
        throw new Error('ID_REQUIRED');
    return n;
};
export function actionBody(action, form, detail, capabilities = {}) {
    const reason = text(form.reason), note = text(form.note);
    if (['CLOSE', 'CANCEL', 'REOPEN', 'REJECT_ASSIGNMENT'].includes(action))
        return { reason };
    if (action === 'ACCEPT')
        return { assigneeId: id(detail.responsibleUserId), dueAt: detail.dueAt ?? null };
    if (action === 'ASSIGN' || action === 'ESCALATE') {
        const body = { reason };
        if (action === 'ASSIGN' || form.assigneeId)
            body.assigneeId = id(form.assigneeId);
        // Hidden retained fields cannot carry authority through a conflict refresh.
        const canSetDeadline = action === 'ASSIGN' ? detail.permissions?.canSetDeadline === true : capabilities.canSetClinicalDeadline === true;
        if (canSetDeadline && form.deadlineMode === 'clear')
            body.dueAt = null;
        if (canSetDeadline && form.deadlineMode === 'set') {
            if (!/(?:Z|[+-]\d{2}:\d{2})$/.test(text(form.dueAt)) || !Number.isFinite(Date.parse(form.dueAt)))
                throw new Error('OFFSET_REQUIRED');
            body.dueAt = text(form.dueAt);
        }
        return body;
    }
    if (action === 'RETURN')
        return { reason, targetState: form.targetState };
    if (action === 'REVIEW_SOURCE_CHANGE')
        return { reason, sourceRevision: id(detail.source?.sourceRevision) };
    if (action === 'RESOLVE_REFERENCE_CHANGE') {
        const body = { reason, evidenceId: id(form.evidenceId), resolution: form.resolution };
        if (form.resolution === 'REPLACE')
            body.replacement = { sourceType: form.replacementType, sourceId: id(form.replacementId) };
        return body;
    }
    const occurredAt = form.occurredAt || measurementInstant(form.wall, form.offset);
    if (action === 'FOLLOW_UP')
        return { note, occurredAt };
    if (action === 'FEEDBACK')
        return { note, occurredAt, entryMode: form.entryMode, outcome: form.outcome, ...(form.outcome === 'DIFFICULTY' ? { reason } : {}) };
    if (action === 'SUBMIT') {
        const body = { note, occurredAt }, refs = (form.evidence || []).map(ref => ({ sourceType: ref.sourceType, sourceId: id(ref.sourceId) }));
        if (refs.length)
            body.evidence = refs;
        if (form.hasContact)
            body.contact = { relationship: text(form.relationship), outcome: text(form.contactOutcome) };
        if (!refs.length && !body.contact)
            throw new Error('EVIDENCE_REQUIRED');
        return body;
    }
    throw new Error('INVALID_ACTION');
}
/** Hidden fields are not a second reading. Explicit nulls also match native correction semantics. */
export function selfMonitorInput(form, requireNewContext = false) {
    if (requireNewContext) {
        measurementInstant(`${form.recordDate}T${form.recordTime}`, null, { localOnly: true });
        if (!['BP', 'BG', 'BP_BG'].includes(form.measureType)) throw new Error('TYPE_REQUIRED');
        if (form.measureType !== 'BP' && (!['mmol/L', 'mg/dL'].includes(form.bgUnit) || !['Fasting', 'After Meal2h', 'Random'].includes(form.measurePeriod))) throw new Error('CONTEXT_REQUIRED');
    }
    return { ...form, ...(form.measureType === 'BG' ? { systolicBp: null, diastolicBp: null } : {}), ...(form.measureType === 'BP' ? { bloodGlucose: null, bgUnit: null, measurePeriod: null } : {}) };
}

/** Identity display only: current authenticated account, authorized candidates or event actors. */
export function responsibleDisplayName(detail, candidates = [], events = []) {
    const responsible = detail?.responsibleUserId;
    if (!responsible) return null;
    const candidate = candidates.find(person => Number(person.userId) === Number(responsible));
    if (candidate?.displayName) return candidate.displayName;
    const storage = globalThis.localStorage;
    if (storage?.getItem('token') && String(responsible) === storage?.getItem('userId')) {
        const name = storage.getItem('realName')?.trim() || storage.getItem('username')?.trim();
        if (name) return name;
    }
    return events.find(event => Number(event.actorId) === Number(responsible) && event.actorName)?.actorName || null;
}
