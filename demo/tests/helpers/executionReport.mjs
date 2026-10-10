import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { demo } from './runtime.mjs';

const at = new Date('2026-10-03T12:00:00Z');
const later = new Date('2026-10-20T12:00:00Z');
const body = (title = 'PUBLISHED REPORT PLAN') => ({ title, instructions: 'Published fictional instructions', planType: 'FOLLOW_UP', actions: [
  { ordinal: 1, instruction: 'Prepare fictional visit questions', dueAt: '2026-10-04T12:00:00Z', assignedUserId: 2 },
  { ordinal: 2, instruction: 'Check fictional documents', dueAt: '2026-10-04T12:00:00Z', assignedUserId: 3 }
] });
const receipt = note => ({ note, occurredAt: '2026-10-03T11:00:00Z', entryMode: 'SELF', evidence: [] });
function seed(app, when = at) {
  const M = app.model, state = app.state;
  const plan = M.createCarePlanDraft(state, body(), when);
  M.publishCarePlan(state, plan.id, null, when);
  return { M, state, plan, actions: state.carePlanActions.filter(item => item.planId === plan.id) };
}
function click(app, action, data = {}) {
  const el = { dataset: { action, ...data } };
  for (const handler of app.listeners.click || []) handler({ target: { closest: selector => selector === '[data-action]' ? el : null } });
}
function role(app, value) {
  const el = { dataset: { role: value } };
  for (const handler of app.listeners.click || []) handler({ target: { closest: selector => selector === '[data-role]' ? el : null } });
}

export function executionReportTests(edition) {
  test(`${edition}: report is a read-only published projection, with no clinician draft content or draft activity`, () => {
    const app = demo(edition), { M, state, plan } = seed(app);
    assert.equal(typeof M.careExecutionSummary, 'function', 'read-only execution report projection is missing');
    M.createCarePlanDraft(state, body('PRIVATE NEW DRAFT'), at);
    M.createCarePlanRevision(state, plan.id, at);
    M.saveCarePlanDraft(state, plan.id, body('PRIVATE REVISION DRAFT'), at);
    const before = JSON.stringify(state), report = M.careExecutionSummary(state, { days: 0 }, later);
    assert.equal(report.authorized, true);
    assert.equal(report.patient.id, 1);
    assert.equal(report.plans.length, 1);
    assert.equal(report.plans[0].revisionNo, 1);
    assert.equal(report.plans[0].title.en, 'PUBLISHED REPORT PLAN');
    assert.equal(report.generatedAt, later.toISOString());
    assert.doesNotMatch(JSON.stringify(report), /PRIVATE|DRAFT_SAVED|REVISION_DRAFTED|draftRevisionId/);
    assert.equal(JSON.stringify(state), before, 'opening a report must not add audit events or mutate state');
    report.plans[0].title.en = 'MUTATION';
    assert.equal(M.careExecutionSummary(state, { days: 0 }, later).plans[0].title.en, 'PUBLISHED REPORT PLAN');
  });

  test(`${edition}: outstanding help, detail and clinician review survive the activity filter and update on transitions`, () => {
    const app = demo(edition), { M, state, actions } = seed(app);
    state.role = 'family';
    M.helpCarePlanAction(state, actions[0].id, 'HELP OUTSIDE WINDOW', at);
    M.submitCarePlanAction(state, actions[1].id, receipt('PENDING OUTSIDE WINDOW'), at);
    let report = M.careExecutionSummary(state, { days: 7 }, later);
    assert.equal(report.activity.length, 0);
    assert.deepEqual(Array.from(report.outstanding, item => item.attention), ['NEEDS_HELP', 'AWAITING_REVIEW']);
    assert.equal(report.outstanding[0].note, 'HELP OUTSIDE WINDOW');
    assert.equal(report.outstanding[1].reviewWaitingSince, at.toISOString());
    state.role = 'doctor';
    M.reviewCarePlanAction(state, actions[1].id, { decision: 'RETURN', note: 'DETAIL OUTSIDE WINDOW' }, at);
    report = M.careExecutionSummary(state, { days: 7 }, later);
    assert.equal(report.outstanding.find(item => item.id === actions[1].id).attention, 'NEEDS_DETAIL');
    assert.equal(report.outstanding.find(item => item.id === actions[1].id).note, 'DETAIL OUTSIDE WINDOW');
    state.role = 'patient'; M.submitCarePlanAction(state, actions[1].id, receipt('NEW DETAIL'), later);
    report = M.careExecutionSummary(state, { days: 7 }, later);
    assert.equal(report.outstanding.find(item => item.id === actions[1].id).attention, 'AWAITING_REVIEW');
    state.role = 'doctor'; M.reviewCarePlanAction(state, actions[1].id, { decision: 'CONFIRM' }, later);
    assert.equal(M.careExecutionSummary(state, { days: 7 }, later).outstanding.length, 1);
  });

  test(`${edition}: public activity uses recording time and retains accountable identity plus revision and action`, () => {
    const app = demo(edition), { M, state, plan, actions } = seed(app);
    state.role = 'family'; M.submitCarePlanAction(state, actions[0].id, receipt('ASSISTED RECEIPT'), later);
    const report = M.careExecutionSummary(state, { days: 7 }, later), event = report.activity[0];
    assert.equal(report.activity.length, 1, 'old occurrence inside a recent submission is still recorded activity');
    assert.equal(event.actorRole, 'family');
    assert.equal(event.actorId, 3);
    assert.equal(event.entryMode, 'ASSISTED');
    assert.equal(event.recordedAt, later.toISOString());
    assert.equal(event.occurredAt, '2026-10-03T11:00:00.000Z');
    assert.equal(event.planId, plan.id);
    assert.equal(event.actionId, actions[0].id);
    assert.equal(event.revisionNo, 1);
    assert.equal(report.activityFrom, '2026-10-13T12:00:00.000Z');
    assert.throws(() => M.careExecutionSummary(state, { days: -1 }, later), /invalid-report-range/);
  });

  test(`${edition}: new revisions replace current actions without removing public history; terminal work is not outstanding`, () => {
    const app = demo(edition), { M, state, plan, actions } = seed(app);
    state.role = 'patient'; M.submitCarePlanAction(state, actions[0].id, receipt('OLD RECEIPT'), at);
    state.role = 'doctor';
    M.createCarePlanRevision(state, plan.id, at);
    M.saveCarePlanDraft(state, plan.id, body('CURRENT REVISION'), at);
    M.publishCarePlan(state, plan.id, { currentRevisionId: plan.currentRevisionId, supersededActionIds: actions.map(item => item.id) }, later);
    let report = M.careExecutionSummary(state, { days: 0 }, later);
    assert.equal(report.plans[0].revisionNo, 2);
    assert.ok(report.plans[0].actions.every(item => item.revisionId === plan.currentRevisionId && item.status === 'OPEN'));
    assert.ok(report.outstanding.every(item => !actions.some(old => old.id === item.id)));
    assert.ok(report.activity.some(event => event.note === 'OLD RECEIPT' && event.revisionNo === 1));
    assert.doesNotMatch(JSON.stringify(report), /DRAFT_SAVED|REVISION_DRAFTED/);
    M.cancelCarePlan(state, plan.id, 'Demo cancellation', later);
    report = M.careExecutionSummary(state, { days: 0 }, later);
    assert.equal(report.outstanding.length, 0);
    assert.equal(report.plans[0].lifecycle, 'CANCELLED');
  });

  test(`${edition}: report access reuses current role, patient, active account and independent nurse permissions`, () => {
    const app = demo(edition), { M, state } = seed(app);
    for (const value of ['doctor', 'patient', 'family', 'nurse']) {
      state.role = value;
      assert.equal(M.careExecutionSummary(state, { days: 0 }).plans.length, 1, value);
    }
    state.role = 'admin';
    let report = M.careExecutionSummary(state, { days: 0 });
    assert.equal(report.authorized, false);
    assert.equal(report.patient, null);
    assert.doesNotMatch(JSON.stringify(report), /PUBLISHED REPORT PLAN|Aihua|张爱华/);
    state.role = 'nurse'; state.nurseAssignments[0].status = 'REVOKED';
    assert.equal(M.careExecutionSummary(state, { days: 0 }).authorized, false);
    state.nurseAssignments[0].status = 'ACTIVE';
    state.carePlanGrants.find(item => item.role === 'nurse').status = 'REVOKED';
    assert.equal(M.careExecutionSummary(state, { days: 0 }).authorized, false);
    state.role = 'family'; state.patientId = 2;
    assert.equal(M.careExecutionSummary(state, { days: 0 }).authorized, false);
    state.role = 'doctor';
    assert.equal(M.careExecutionSummary(state, { days: 0 }).plans.length, 0);
    state.patientId = 1; state.users[0].active = false;
    assert.equal(M.careExecutionSummary(state, { days: 0 }).authorized, false);
  });

  test(`${edition}: report entry renders read-only content and filters only activity without changing state`, () => {
    const app = demo(edition), { M, state, actions } = seed(app);
    state.role = 'family'; M.helpCarePlanAction(state, actions[0].id, '<img src=x onerror=alert(1)>', at);
    state.role = 'doctor'; app.navigate('#plans');
    assert.match(app.node('content').innerHTML, /data-action="care-report-open"/);
    const before = JSON.stringify(state);
    click(app, 'care-report-open');
    assert.equal(app.node('detail-dialog').open, true);
    const html = app.node('record-detail').innerHTML;
    assert.match(html, /data-report-section="current"/);
    assert.match(html, /data-report-section="outstanding"/);
    assert.match(html, /data-report-section="activity"/);
    assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/);
    assert.doesNotMatch(html, /<img src=x|data-action="cp-|data-action="export"|download=/);
    assert.match(html, /HTML.*PDF.*CSV/);
    assert.match(html, /\/care-plans\/reports/);
    assert.match(html, edition === 'en' ? /Synthetic.*read-only/ : /虚构.*只读/);
    click(app, 'care-report-range', { days: '0' });
    assert.match(app.node('record-detail').innerHTML, /aria-pressed="true"[^>]*data-days="0"|data-days="0"[^>]*aria-pressed="true"/);
    assert.equal(JSON.stringify(state), before);
  });

  test(`${edition}: existing health-report and visit-summary cards open the actual summary rather than a run counter`, () => {
    const app = demo(edition);
    for (const [value, hash] of [['doctor', '#analytics'], ['family', '#careplan']]) {
      role(app, value); app.navigate(hash);
      assert.match(app.node('content').innerHTML, /data-action="care-report-open"/);
      assert.doesNotMatch(app.node('content').innerHTML, /data-action="module-action"[^>]*data-key="(?:health-report|visit-summary)"/);
    }
  });

  test(`${edition}: closing, navigation, patient/role switching, reset and reload discard report content and range`, () => {
    const app = demo(edition); seed(app);
    const open = () => { click(app, 'care-report-open'); assert.match(app.node('record-detail').innerHTML, /PUBLISHED REPORT PLAN/); };
    open(); click(app, 'care-report-range', { days: '0' });
    app.node('detail-dialog').close(); app.node('detail-dialog').handlers.close();
    assert.equal(app.node('record-detail').innerHTML, '');
    open(); assert.match(app.node('record-detail').innerHTML, /data-days="7"[^>]*aria-pressed="true"/);
    app.select(2);
    assert.equal(app.node('detail-dialog').open, false); assert.equal(app.node('record-detail').innerHTML, '');
    click(app, 'care-report-open'); assert.doesNotMatch(app.node('record-detail').innerHTML, /PUBLISHED REPORT PLAN/);
    app.select(1); open(); role(app, 'admin');
    assert.equal(app.node('detail-dialog').open, false); assert.equal(app.node('record-detail').innerHTML, '');
    click(app, 'care-report-open'); assert.doesNotMatch(app.node('record-detail').innerHTML, /PUBLISHED REPORT PLAN|Aihua|张爱华/);
    role(app, 'doctor'); open(); app.navigate('#analytics');
    assert.equal(app.node('record-detail').innerHTML, '');
    open(); app.reset();
    assert.equal(app.node('detail-dialog').open, false); assert.equal(app.node('record-detail').innerHTML, '');
    click(app, 'care-report-open'); assert.doesNotMatch(app.node('record-detail').innerHTML, /PUBLISHED REPORT PLAN/);
    const reloaded = demo(edition); click(reloaded, 'care-report-open');
    assert.doesNotMatch(reloaded.node('record-detail').innerHTML, /PUBLISHED REPORT PLAN/);
  });

  test(`${edition}: report recomputes after help/review and rejects late filter events after access or dialog changes`, () => {
    const app = demo(edition), { M, state, actions } = seed(app);
    click(app, 'care-report-open');
    state.role = 'patient'; M.submitCarePlanAction(state, actions[0].id, receipt('CURRENT PENDING'), at); state.role = 'doctor';
    app.view.render();
    assert.match(app.node('record-detail').innerHTML, /AWAITING_REVIEW/);
    click(app, 'care-report-range', { days: '0' });
    assert.match(app.node('record-detail').innerHTML, /AWAITING_REVIEW/);
    M.reviewCarePlanAction(state, actions[0].id, { decision: 'RETURN', note: 'CURRENT DETAIL' }, at);
    app.view.render();
    assert.match(app.node('record-detail').innerHTML, /CURRENT DETAIL/);
    click(app, 'care-report-range', { days: '7' });
    assert.match(app.node('record-detail').innerHTML, /CURRENT DETAIL/);
    state.users[0].active = false;
    click(app, 'care-report-range', { days: '0' });
    assert.doesNotMatch(app.node('record-detail').innerHTML, /PUBLISHED REPORT PLAN|CURRENT DETAIL/);
    app.node('detail-dialog').close(); app.node('detail-dialog').handlers.close();
    click(app, 'care-report-range', { days: '0' });
    assert.equal(app.node('record-detail').innerHTML, '');
  });

  test(`${edition}: report preserves mobile wrapping and does not add network or persistent report storage`, () => {
    const base = new URL(edition === 'en' ? '../../' : '../../../cn/demo/', import.meta.url);
    const source = readFileSync(new URL('app.js', base), 'utf8'), css = readFileSync(new URL('style.css', base), 'utf8');
    assert.match(css, /\.cp-report[^{]*\{[^}]*overflow-wrap:\s*anywhere/s);
    assert.match(css, /\.cp-report-filters[^}]*flex-wrap:\s*wrap/s);
    assert.doesNotMatch(source, /fetch\s*\(|XMLHttpRequest|WebSocket|sendBeacon/);
    assert.doesNotMatch(source, /setItem\([^\n]*(?:careExecutionSummary|reportContext|reportDays)/);
  });
}
