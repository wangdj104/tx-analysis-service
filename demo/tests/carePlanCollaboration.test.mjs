import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { demo } from './helpers/runtime.mjs';

const at = new Date('2026-10-03T12:00:00Z');
const dueAt = '2026-10-04T12:00:00+00:00';
function model() {
  const networkCalls = [];
  const context = vm.createContext({ fetch: (...args) => networkCalls.push(args), Date });
  vm.runInContext(readFileSync(new URL('../model.js', import.meta.url), 'utf8'), context);
  return { M: context.HealthDemo, state: context.HealthDemo.createState(at), networkCalls };
}
const body = () => ({ title: 'Fictional visit preparation', instructions: 'Demo coordination only.', planType: 'FOLLOW_UP', actions: [
  { ordinal: 1, instruction: 'Prepare fictional visit questions.', dueAt, assignedUserId: 2 },
  { ordinal: 2, instruction: 'Check the fictional document checklist.', dueAt, assignedUserId: 3 }
] });
function published() {
  const context = model(), plan = context.M.createCarePlanDraft(context.state, body(), at);
  context.M.publishCarePlan(context.state, plan.id, null, at);
  return { ...context, plan, actions: context.state.carePlanActions.filter(item => item.planId === plan.id) };
}
const receipt = (note = 'Fictional checklist prepared.') => ({ note, occurredAt: '2026-10-03T11:30:00+00:00', entryMode: 'SELF', evidence: [] });

test('demoShowsDraftPublishAssistReviewSequence', () => {
  const { M, state, networkCalls } = model();
  const plan = M.createCarePlanDraft(state, body(), at);
  assert.equal(plan.lifecycle, 'DRAFT');
  for (const role of ['patient', 'family', 'nurse', 'admin']) {
    state.role = role;
    assert.equal(M.visibleCarePlans(state, at).length, 0, `${role} must not see a draft`);
  }
  state.role = 'doctor'; M.publishCarePlan(state, plan.id, null, at);
  const [action, assisted] = state.carePlanActions;
  state.role = 'patient'; M.submitCarePlanAction(state, action.id, receipt(), at);
  assert.equal(action.status, 'SUBMITTED');
  assert.equal(action.firstSubmittedAt, at.toISOString());
  state.role = 'family'; M.submitCarePlanAction(state, assisted.id, receipt(), at);
  assert.equal(state.carePlanEvents.at(-1).entryMode, 'ASSISTED');
  state.role = 'nurse'; M.followUpCarePlanAction(state, action.id, { kind: 'DOCTOR_NOTIFIED', note: 'Fictional review request recorded.' }, at);
  assert.equal(action.status, 'SUBMITTED');
  state.role = 'doctor';
  assert.throws(() => M.closeCarePlan(state, plan.id, at), /unconfirmed/);
  M.reviewCarePlanAction(state, action.id, { decision: 'RETURN', note: 'Add fictional checklist detail.' }, at);
  assert.equal(action.status, 'OPEN');
  state.role = 'patient'; M.submitCarePlanAction(state, action.id, receipt('Fictional detail added.'), new Date(at.getTime() + 60000));
  assert.equal(action.firstSubmittedAt, at.toISOString());
  assert.equal(state.carePlanEvents.filter(event => event.actionId === action.id && event.eventType === 'SUBMITTED').length, 2);
  state.role = 'doctor';
  for (const item of [action, assisted]) M.reviewCarePlanAction(state, item.id, { decision: 'CONFIRM' }, at);
  assert.equal(action.status, 'CONFIRMED');
  M.closeCarePlan(state, plan.id, at);
  assert.equal(plan.lifecycle, 'COMPLETED');
  assert.equal(networkCalls.length, 0);
});

test('demoNurseCannotPublishOrConfirm', () => {
  const { M, state, plan, actions } = published();
  for (const role of ['family', 'nurse', 'patient', 'admin']) {
    state.role = role;
    assert.throws(() => M.publishCarePlan(state, plan.id, null, at), /care-plan-access/);
    assert.throws(() => M.createCarePlanRevision(state, plan.id, at), /care-plan-access/);
    assert.throws(() => M.reviewCarePlanAction(state, actions[0].id, { decision: 'CONFIRM' }, at), /care-plan-access/);
    assert.throws(() => M.closeCarePlan(state, plan.id, at), /care-plan-access/);
  }
  state.role = 'nurse';
  M.submitCarePlanAction(state, actions[0].id, receipt(), at);
  assert.equal(state.carePlanEvents.at(-1).entryMode, 'ASSISTED');
  assert.equal(actions[0].status, 'SUBMITTED');
});

test('demoRevisionKeepsHistory', () => {
  const { M, state, plan, actions } = published();
  state.role = 'patient'; M.submitCarePlanAction(state, actions[0].id, receipt(), at);
  state.role = 'doctor'; M.reviewCarePlanAction(state, actions[0].id, { decision: 'CONFIRM' }, at);
  const oldRevisionId = plan.currentRevisionId;
  M.createCarePlanRevision(state, plan.id, at);
  const updated = body(); updated.title = 'Fictional revised visit preparation';
  M.saveCarePlanDraft(state, plan.id, updated, at);
  state.role = 'family';
  const view = M.visibleCarePlans(state, at)[0];
  assert.equal(view.title.en, 'Fictional visit preparation');
  assert.equal(view.draftRevisionId, undefined);
  assert.equal(view.revisions.length, 1);
  state.role = 'doctor';
  assert.throws(() => M.publishCarePlan(state, plan.id, null, at), /revision-confirmation/);
  M.publishCarePlan(state, plan.id, { currentRevisionId: oldRevisionId, supersededActionIds: [actions[1].id] }, at);
  assert.equal(actions[0].status, 'CONFIRMED');
  assert.equal(actions[1].status, 'SUPERSEDED');
  assert.equal(state.carePlanActions.filter(item => item.revisionId === plan.currentRevisionId).length, 2);
  assert.ok(state.carePlanActions.filter(item => item.revisionId === plan.currentRevisionId).every(item => item.status === 'OPEN'));
  assert.equal(state.carePlanEvents.filter(event => event.eventType === 'SUBMITTED').length, 1);
  assert.equal(M.visibleCarePlans(state, at)[0].revisions.length, 2);
});

test('demo scope requires nursing assignment plus independent module grant, including history', () => {
  const { M, state, plan } = published();
  state.role = 'nurse'; assert.equal(M.visibleCarePlans(state, at).length, 1);
  state.nurseAssignments[0].status = 'REVOKED';
  assert.equal(M.visibleCarePlans(state, at).length, 0);
  state.nurseAssignments[0].status = 'ACTIVE'; state.carePlanGrants.find(item => item.role === 'nurse').status = 'REVOKED';
  assert.equal(M.visibleCarePlans(state, at).length, 0);
  assert.throws(() => M.followUpCarePlanAction(state, state.carePlanActions[0].id, { kind: 'CONTACTED', note: 'Fictional contact.' }, at), /care-plan-access/);
  state.role = 'admin'; assert.equal(M.visibleCarePlans(state, at).length, 0);
  state.role = 'doctor'; state.patientId = 2; assert.equal(M.visibleCarePlans(state, at).length, 0);
  assert.throws(() => M.publishCarePlan(state, plan.id, null, at), /care-plan-access/);
});

test('demo validates one-time inputs and overdue is derived without blaming submitted work', () => {
  const { M, state } = model();
  for (const actions of [[], Array(51).fill(body().actions[0])]) assert.throws(() => M.createCarePlanDraft(state, { ...body(), actions }, at), /invalid-care-plan/);
  assert.throws(() => M.createCarePlanDraft(state, { ...body(), actions: [{ ...body().actions[0], dueAt: '2026-10-04' }] }, at), /invalid-care-plan/);
  const plan = M.createCarePlanDraft(state, body(), at); M.publishCarePlan(state, plan.id, null, at);
  const action = state.carePlanActions[0]; state.role = 'patient';
  assert.throws(() => M.submitCarePlanAction(state, action.id, { ...receipt(), note: ' ' }, at), /invalid-receipt/);
  assert.throws(() => M.submitCarePlanAction(state, action.id, { ...receipt(), occurredAt: '2099-01-01T12:00:00Z' }, at), /invalid-receipt/);
  M.helpCarePlanAction(state, action.id, 'Fictional missing document.', at);
  assert.equal(action.status, 'NEEDS_HELP');
  M.submitCarePlanAction(state, action.id, receipt(), at);
  const later = new Date('2026-10-05T12:00:00Z');
  assert.equal(M.visibleCarePlans(state, later)[0].actions[0].overdue, false);
  state.role = 'doctor'; M.reviewCarePlanAction(state, action.id, { decision: 'RETURN', note: 'Fictional clarification.' }, later);
  assert.equal(M.visibleCarePlans(state, later)[0].actions[0].overdue, true);
  assert.equal(action.firstSubmittedAt, at.toISOString());
});

test('demoResetDoesNotRetainPrivateRealInput', () => {
  const { M, state } = published();
  state.role = 'patient'; M.submitCarePlanAction(state, state.carePlanActions[0].id, receipt('PRIVATE INPUT PROBE'), at);
  assert.ok(JSON.stringify(state).includes('PRIVATE INPUT PROBE'));
  assert.ok(!JSON.stringify(M.createState(at)).includes('PRIVATE INPUT PROBE'));
});

function click(app, action, data = {}) {
  const el = { dataset: { action, ...data } };
  const target = { closest: selector => selector === '[data-action]' ? el : null };
  for (const handler of app.listeners.click || []) handler({ target });
}
function role(app, value) {
  const el = { dataset: { role: value } };
  for (const handler of app.listeners.click || []) handler({ target: { closest: selector => selector === '[data-role]' ? el : null } });
}
function submitAction(app, values) { app.node('action-form').handlers.submit({ target: { values }, preventDefault() {} }); }

for (const edition of ['en', 'zh']) {
  test(`${edition}: shipped UI has a clickable collaboration sequence and nurse-only navigation`, () => {
    const app = demo(edition);
    app.context.location.hash = '#plans'; app.view.render();
    assert.match(app.node('content').innerHTML, /data-action="cp-create"/);
    click(app, 'cp-create');
    submitAction(app, { title: 'UI fictional plan', instructions: 'UI fictional instructions', instruction1: 'UI fictional personal action', instruction2: 'UI fictional family action', dueAt1: dueAt, dueAt2: dueAt });
    const plan = app.state.carePlans[0];
    assert.equal(plan.lifecycle, 'DRAFT');
    role(app, 'family'); app.context.location.hash = '#careplan'; app.view.render();
    assert.ok(!app.node('content').innerHTML.includes('UI fictional plan'));
    role(app, 'doctor'); app.context.location.hash = '#plans'; app.view.render();
    click(app, 'cp-publish', { id: String(plan.id) }); submitAction(app, { confirm: 'yes' });
    const action = app.state.carePlanActions[0];
    role(app, 'patient'); app.context.location.hash = '#careplan'; app.view.render();
    assert.match(app.node('content').innerHTML, /data-action="cp-submit"/);
    click(app, 'cp-submit', { id: String(action.id) }); submitAction(app, { ...receipt(), occurredAt: new Date(Date.now()-60000).toISOString() });
    assert.equal(action.status, 'SUBMITTED');
    assert.match(app.node('content').innerHTML, /SUBMITTED/);
    assert.doesNotMatch(app.node('content').innerHTML, /data-action="cp-confirm"/);
    role(app, 'nurse');
    assert.match(app.node('nav').innerHTML, /nursing/);
    assert.doesNotMatch(app.node('nav').innerHTML, /consultation|clinical|medications|records|journey/);
    assert.match(app.node('content').innerHTML, /data-action="cp-followup"/);
    assert.doesNotThrow(() => app.view.currentGuide());
    role(app, 'doctor'); app.context.location.hash = '#plans'; app.view.render();
    click(app, 'cp-return', { id: String(action.id) }); submitAction(app, { note: 'UI fictional clarification' });
    assert.equal(action.status, 'OPEN');
    role(app, 'family'); app.context.location.hash = '#careplan'; app.view.render();
    click(app, 'cp-submit', { id: String(action.id) }); submitAction(app, { ...receipt(), occurredAt: new Date(Date.now()-60000).toISOString() });
    assert.equal(app.state.carePlanEvents.at(-1).entryMode, 'ASSISTED');
    app.reset();
    assert.equal(app.state.carePlans.length, 0);
    assert.equal(app.node('action-fields').innerHTML, '');
  });

  test(`${edition}: demo boundaries and CSP remain explicit and drafts are not persisted`, () => {
    const root = new URL(edition === 'en' ? '../' : '../../cn/demo/', import.meta.url);
    const html = readFileSync(new URL('index.html', root), 'utf8');
    assert.match(html, /connect-src 'none'/);
    assert.match(html, /No message|不会.*通知|无.*通知/);
    const appSource = readFileSync(new URL('app.js', root), 'utf8');
    assert.doesNotMatch(appSource, /fetch\s*\(|XMLHttpRequest|WebSocket|sendBeacon/);
    assert.doesNotMatch(appSource, /setItem\([^\n]*(?:carePlans|carePlanEvents|carePlanActions)/);
  });
}

test('demo rejects invalid dates, future assignments, disabled nursing accounts and read-only receipts', () => {
  const { M, state, actions } = published();
  state.role = 'nurse';
  state.nurseAssignments[0].startsAt = '2099-01-01T00:00:00Z';
  assert.equal(M.visibleCarePlans(state, at).length, 0);
  state.nurseAssignments[0].startsAt = '2026-10-01T00:00:00Z';
  const nurse = state.users.find(item => item.id === 4);
  assert.ok(nurse);
  nurse.active = false;
  assert.equal(M.visibleCarePlans(state, at).length, 0);
  nurse.active = true;
  state.role = 'family'; state.carePlanGrants.find(item => item.role === 'family').permission = 'READ';
  assert.equal(M.visibleCarePlans(state, at).length, 1);
  assert.throws(() => M.submitCarePlanAction(state, actions[0].id, receipt(), at), /care-plan-access/);
  state.role = 'doctor';
  for (const dueAt of ['2026-02-30T12:00:00Z', '2026-10-03T24:00:00Z', '9999-12-31T23:59:59-01:00']) {
    assert.throws(() => M.createCarePlanDraft(state, { ...body(), actions: [{ ...body().actions[0], dueAt }] }, at), /invalid-care-plan/);
  }
});

test('demo cancellation preserves receipt history and terminal plans cannot reopen', () => {
  const { M, state, plan, actions } = published();
  state.role = 'family'; M.submitCarePlanAction(state, actions[0].id, receipt(), at);
  state.role = 'doctor'; M.cancelCarePlan(state, plan.id, 'Fictional cancellation reason.', at);
  assert.equal(plan.lifecycle, 'CANCELLED');
  assert.equal(actions[0].status, 'CANCELLED');
  assert.equal(M.visibleCarePlans(state, at)[0].actions[0].events[0].note, receipt().note);
  assert.throws(() => M.createCarePlanRevision(state, plan.id, at), /care-plan-state/);
  state.role = 'patient'; assert.throws(() => M.submitCarePlanAction(state, actions[0].id, receipt(), at), /care-plan-state/);
});

for (const edition of ['en','zh']) {
  test(`${edition}: role, patient changes and dialog close discard private draft inputs`, () => {
    const app = demo(edition);
    click(app, 'cp-create');
    app.node('action-fields').innerHTML += 'PRIVATE INPUT PROBE';
    role(app, 'family');
    assert.equal(app.node('action-fields').innerHTML, '');
    role(app, 'doctor');click(app, 'cp-create');app.node('action-fields').innerHTML += 'PRIVATE INPUT PROBE';
    app.select(2);assert.equal(app.node('action-fields').innerHTML, '');
    click(app, 'cp-create');app.node('action-fields').innerHTML += 'PRIVATE INPUT PROBE';
    app.node('action-dialog').close();app.node('action-dialog').handlers.close();assert.equal(app.node('action-fields').innerHTML, '');
  });
}

for (const edition of ['en','zh']) {
  test(`${edition}: an unpublished draft never claims a publisher and note text stays literal`, () => {
    const app=demo(edition);
    const plan=app.model.createCarePlanDraft(app.state, { ...body(), title:'Fictional <b data-probe="care">draft</b>' });
    app.context.location.hash='#plans';app.view.render();
    const html=app.node('content').innerHTML;
    assert.ok(html.includes('&lt;b data-probe=&quot;care&quot;&gt;draft&lt;/b&gt;'));
    const card=html.slice(html.indexOf('data-plan-id="'+plan.id+'"'),html.indexOf('<section class="panel"><h2>'));
    assert.ok(!card.includes(edition==='en'?'Published by':'发布医生'));
  });
}

test('demo never silently saves unsupported real evidence references', () => {
  const {M,state}=model();
  assert.throws(()=>M.createCarePlanDraft(state,{...body(),actions:[{...body().actions[0],evidence:[{sourceType:'MEDICAL_RECORD',sourceId:1}]}]},at),/invalid-care-plan/);
  assert.equal(state.carePlans.length,0);
});

test('demo nursing seed is already active across positive and negative timezone offsets', async () => {
  const { spawnSync } = await import('node:child_process');
  const modelUrl = new URL('../model.js', import.meta.url).href;
  const source = `import ${JSON.stringify(modelUrl)}; const now = new Date('2026-10-03T18:00:00Z'); const state=globalThis.HealthDemo.createState(now); state.role='nurse'; console.log(JSON.stringify({startsAt:state.nurseAssignments[0].startsAt,canRead:globalThis.HealthDemo.careAccess(state,1,false,now)}));`;
  for (const timezone of ['Asia/Shanghai','America/Los_Angeles','Pacific/Kiritimati','Etc/UTC']) {
    const result=spawnSync(process.execPath,['--input-type=module','-e',source],{encoding:'utf8',env:{...process.env,TZ:timezone}});
    assert.equal(result.status,0,result.stderr);
    const state=JSON.parse(result.stdout);
    assert.ok(new Date(state.startsAt)<=new Date('2026-10-03T18:00:00Z'),`${timezone}: seeded assignment must not begin in the future`);
    assert.equal(state.canRead,true,`${timezone}: seeded nurse flow must remain available`);
  }
});

for (const edition of ['en','zh']) {
  test(`${edition}: cancelled unpublished drafts retain history without edit or publish controls`, () => {
    const app=demo(edition), plan=app.model.createCarePlanDraft(app.state,body());
    click(app,'cp-cancel',{id:String(plan.id)});
    submitAction(app,{note:'Fictional draft cancelled.'});
    assert.equal(plan.lifecycle,'CANCELLED');
    app.context.location.hash='#plans';app.view.render();
    const html=app.node('content').innerHTML;
    assert.match(html,/CANCELLED/);
    assert.ok(html.includes('Fictional visit preparation'));
    for(const action of ['cp-edit','cp-publish','cp-revise','cp-cancel','cp-close']) assert.ok(!html.includes(`data-action="${action}"`),`${action} must not be presented for a terminal draft`);
    assert.throws(()=>app.model.saveCarePlanDraft(app.state,plan.id,body()),/care-plan-state/);
    assert.throws(()=>app.model.publishCarePlan(app.state,plan.id,null),/care-plan-state/);
  });
}

const supplementary = '🧪';
test('demo care model counts non-BMP text at every declared code-point boundary', () => {
  for (const [field,max] of [['title',160],['instructions',4000],['instruction',2000]]) {
    const {M,state}=model();
    const input=body();
    if(field==='instruction')input.actions[0].instruction=supplementary.repeat(max);else input[field]=supplementary.repeat(max);
    const plan=M.createCarePlanDraft(state,input,at);
    assert.equal(state.carePlans.length,1);
    const bad=body();if(field==='instruction')bad.actions[0].instruction=supplementary.repeat(max+1);else bad[field]=supplementary.repeat(max+1);
    assert.throws(()=>M.saveCarePlanDraft(state,plan.id,bad,at),/invalid-care-plan/);
    const revision=state.carePlanRevisions[0];
    assert.equal([...(field==='instruction'?revision.actions[0].instruction.en:revision[field].en)].length,max);
  }
  const {M,state,plan,actions}=published();state.role='patient';
  const action=actions[0];
  assert.throws(()=>M.submitCarePlanAction(state,action.id,receipt(supplementary.repeat(2001)),at),/invalid-receipt/);
  assert.equal(action.status,'OPEN');
  M.submitCarePlanAction(state,action.id,receipt(supplementary.repeat(2000)),at);
  assert.equal([...state.carePlanEvents.at(-1).note].length,2000);
  state.role='nurse';
  assert.throws(()=>M.followUpCarePlanAction(state,action.id,{kind:'CONTACTED',note:supplementary.repeat(1001)},at),/invalid-care-note/);
  M.followUpCarePlanAction(state,action.id,{kind:'CONTACTED',note:supplementary.repeat(1000)},at);
  state.role='doctor';
  assert.throws(()=>M.reviewCarePlanAction(state,action.id,{decision:'RETURN',note:supplementary.repeat(1001)},at),/invalid-care-note/);
  M.reviewCarePlanAction(state,action.id,{decision:'RETURN',note:supplementary.repeat(1000)},at);
  state.role='family';
  assert.throws(()=>M.helpCarePlanAction(state,action.id,supplementary.repeat(1001),at),/invalid-care-note/);
  M.helpCarePlanAction(state,action.id,supplementary.repeat(1000),at);
  state.role='doctor';
  assert.throws(()=>M.cancelCarePlan(state,plan.id,supplementary.repeat(1001),at),/invalid-care-note/);
  M.cancelCarePlan(state,plan.id,supplementary.repeat(1000),at);
  assert.equal(plan.lifecycle,'CANCELLED');
});

for(const edition of ['en','zh']) {
  test(`${edition}: care editor uses code-point limits and preserves over-limit input on correction`,()=>{
    const app=demo(edition);click(app,'cp-create');
    const html=app.node('action-fields').innerHTML;
    assert.doesNotMatch(html,/maxlength=/);
    for(const [name,max] of [['title',160],['instructions',4000],['instruction1',2000],['instruction2',2000]]) {
      assert.match(html,new RegExp(`name="${name}"[^>]*data-codepoint-max="${max}"`));
      assert.ok(html.includes(`id="cp-count-${name}"`));
    }
    const values={title:supplementary.repeat(161),instructions:supplementary.repeat(4000),instruction1:supplementary.repeat(2000),instruction2:supplementary.repeat(2000),dueAt1:dueAt,dueAt2:dueAt};
    const before=app.node('action-fields').innerHTML;
    submitAction(app,values);
    assert.equal(app.state.carePlans.length,0);
    assert.equal(app.node('action-fields').innerHTML,before);
    assert.equal(values.title,supplementary.repeat(161));
    assert.match(app.node('form-error').textContent,edition==='en'?/161.*160.*preserved/:/161.*160.*保留/);
    values.title=supplementary.repeat(160);submitAction(app,values);
    assert.equal(app.state.carePlans.length,1);
    assert.equal([...app.state.carePlanRevisions[0].title.en].length,160);
  });

  test(`${edition}: receipt and management notes share code-point-aware counters`,()=>{
    const app=demo(edition),plan=app.model.createCarePlanDraft(app.state,body());app.model.publishCarePlan(app.state,plan.id,null);
    const action=app.state.carePlanActions[0];
    for(const [roleName,kind,max] of [['patient','cp-submit',2000],['patient','cp-help',1000],['nurse','cp-followup',1000],['doctor','cp-return',1000],['doctor','cp-cancel',1000]]) {
      role(app,roleName);click(app,kind,{id:String(kind==='cp-cancel'?plan.id:action.id)});
      const fields=app.node('action-fields').innerHTML;
      assert.doesNotMatch(fields,/maxlength=/);
      assert.match(fields,new RegExp(`name="note"[^>]*data-codepoint-max="${max}"`));
      const control=app.node('cp-note-input');Object.assign(control,{name:'note',value:supplementary.repeat(max),dataset:{codepointMax:String(max)},form:{id:'action-form'}});
      for(const handler of app.listeners.input||[])handler({target:control});
      assert.equal(control.value,supplementary.repeat(max));
      assert.match(app.node('cp-count-note').textContent,new RegExp(`${max} / ${max}`));
      control.value+=supplementary;
      for(const handler of app.listeners.input||[])handler({target:control});
      assert.match(app.node('cp-count-note').textContent,new RegExp(`${max+1} / ${max}`));
      assert.equal(String(control.attributes['aria-invalid']),'true');
    }
  });
}

for(const edition of ['en','zh']) {
  test(`${edition}: non-BMP receipt input is never truncated and remains correctable at the limit`,()=>{
    const app=demo(edition),plan=app.model.createCarePlanDraft(app.state,body());app.model.publishCarePlan(app.state,plan.id,null);
    const action=app.state.carePlanActions[0];role(app,'patient');click(app,'cp-submit',{id:String(action.id)});
    const fields=app.node('action-fields').innerHTML;
    const input={...receipt(supplementary.repeat(2001)),occurredAt:new Date(Date.now()-60000).toISOString()};
    submitAction(app,input);
    assert.equal(action.status,'OPEN');assert.equal(app.node('action-fields').innerHTML,fields);
    assert.equal([...input.note].length,2001);
    assert.match(app.node('form-error').textContent,edition==='en'?/2001.*2000.*preserved/:/2001.*2000.*保留/);
    input.note=supplementary.repeat(2000);submitAction(app,input);
    assert.equal(action.status,'SUBMITTED');assert.equal([...app.state.carePlanEvents.at(-1).note].length,2000);
  });
  test(`${edition}: cancelling with a revision draft also removes terminal mutation controls`,()=>{
    const app=demo(edition),plan=app.model.createCarePlanDraft(app.state,body());
    app.model.publishCarePlan(app.state,plan.id,null);app.model.createCarePlanRevision(app.state,plan.id);
    click(app,'cp-cancel',{id:String(plan.id)});submitAction(app,{note:'Fictional revision draft cancelled.'});
    app.context.location.hash='#plans';app.view.render();
    const html=app.node('content').innerHTML;
    assert.match(html,/CANCELLED/);assert.match(html,/v2/);
    for(const action of ['cp-edit','cp-publish','cp-revise','cp-cancel','cp-close'])assert.ok(!html.includes(`data-action="${action}"`));
    assert.equal(app.state.carePlanRevisions.length,2);
  });
}

for(const edition of ['en','zh']) {
  test(`${edition}: actual route hash events discard care modal input across Back and Forward`,()=>{
    const app=demo(edition);
    app.navigate('#overview');app.navigate('#plans');click(app,'cp-create');
    const sentinel=edition==='en'?'UNSAVED_BACK_FORWARD_SENTINEL':'往返导航未保存标记';
    app.node('action-fields').innerHTML+=sentinel;
    assert.equal(app.node('action-dialog').open,true);
    app.view.render();
    assert.ok(app.node('action-fields').innerHTML.includes(sentinel),'ordinary rendering must not discard an open form');
    app.navigate('#overview');
    assert.equal(app.node('action-dialog').open,false,'Back route event must dismiss care dialog');
    assert.equal(app.node('action-fields').innerHTML,'');
    assert.match(app.node('content').innerHTML,edition==='en'?/Start with what needs clinical attention/:/先处理真正需要临床关注的事项/);
    app.navigate('#plans');
    assert.equal(app.node('action-dialog').open,false,'Forward must not reopen stale care dialog');
    assert.equal(app.node('action-fields').innerHTML,'');
    assert.equal(app.state.carePlans.length,0);
    click(app,'cp-create');
    assert.equal(app.node('action-dialog').open,true);
    assert.ok(!app.node('action-fields').innerHTML.includes(sentinel),'reopening starts with a fresh fictional draft');
  });

  test(`${edition}: care history localizes role display while keeping snapshot enums unchanged`,()=>{
    const app=demo(edition),roles=['patient','doctor','family','nurse','admin'];
    const labels=edition==='en'?['Patient','Doctor','Family','Nurse','Administrator']:['患者','医生','家属','护理','管理员'];
    const events=roles.map((actorRole,index)=>({id:index+1,actorRole,actorName:{en:`Actor ${index}`,zh:`演示成员 ${index}`},eventType:'SUBMITTED',recordedAt:'2026-10-03T08:00:00Z',entryMode:'ASSISTED',evidence:[]}));
    const before=JSON.stringify(events),html=app.view.careEvents(events);
    for(const label of labels)assert.ok(html.includes(` · ${label} · SUBMITTED`),`${label} must be the rendered human-readable role`);
    for(const rawRole of roles)assert.ok(!html.includes(` · ${rawRole} · `),`${rawRole} is a protocol role, not a display label`);
    assert.equal(JSON.stringify(events),before);
    assert.equal(events[0].eventType,'SUBMITTED');assert.equal(events[0].entryMode,'ASSISTED');
  });
}

test('subsequent care app deployment changes the cache key without renaming existing resource aliases',()=>{
  const html=readFileSync(new URL('../index.html',import.meta.url),'utf8');
  assert.match(html,/<script src="app\.js\?v=20261002-storage1&amp;careplan=20261003-2&amp;report=20261010-1"/);
  assert.match(html,/<script src="model\.js\?v=20261001-r9&amp;careplan=20261003-1&amp;report=20261010-1"/);
  assert.match(html,/style\.css\?v=20261001-1&amp;careplan=20261003-1/);
});
