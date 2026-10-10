import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { demo } from './runtime.mjs';
import { assertDemoLanguage } from './browserLanguage.mjs';

const visible = html => html.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
const protocolLabels = /\b(?:CARE_PLAN|DRAFT_SAVED|REVISION_DRAFTED|DRAFT|ACTIVE|COMPLETED|CANCELLED|OPEN|NEEDS_HELP|SUBMITTED|CONFIRMED|SUPERSEDED|PUBLISHED|REVISED|FOLLOW_UP|RETURNED|CLOSED|SELF|ASSISTED)\b/;
function click(app, action, data = {}) {
  const element = { dataset: { action, ...data } };
  for (const handler of app.listeners.click || []) handler({ target: { closest: selector => selector === '[data-action]' ? element : null } });
}
function seed(app, edition) {
  const zh = edition === 'zh';
  const body = { title: zh ? '虚构复诊准备' : 'Fictional visit preparation', instructions: zh ? '准备虚构复诊清单。' : 'Prepare the fictional visit checklist.', planType: 'FOLLOW_UP', actions: [
    { ordinal: 1, instruction: zh ? '准备虚构问题。' : 'Prepare fictional questions.', dueAt: new Date(Date.now()+86400000).toISOString(), assignedUserId: 2 },
    { ordinal: 2, instruction: zh ? '核对虚构材料。' : 'Check fictional documents.', dueAt: new Date(Date.now()+86400000).toISOString(), assignedUserId: 3 }
  ] };
  const plan = app.model.createCarePlanDraft(app.state, body);
  app.model.publishCarePlan(app.state, plan.id, null);
  return { plan, actions: app.state.carePlanActions, body };
}

export function languageConsistencyTests(edition) {
  const zh = edition === 'zh';
  const translated = (en, cn) => zh ? cn : en;

  test(`${edition}: every role route, navigation and guide uses localized display text`, async () => {
    const app = demo(edition);
    for (const [role, settings] of Object.entries(app.view.copy)) {
      app.state.role = role;
      for (const route of Object.keys(settings.nav)) {
        app.navigate(`#${route}`);
        const content = app.node('content').innerHTML;
        const text = [visible(content), visible(app.node('nav').innerHTML), ...[...content.matchAll(/(?:aria-label|placeholder|title)="([^"]*)"/g)].map(match => match[1])].join(' ');
        assert.doesNotMatch(text, /\bCARE_PLAN\b/, `${role}/${route}: permission codes are not display labels`);
        if (zh) assert.doesNotMatch(text, /\bLogo\b/);
        else assert.doesNotMatch(text, /[\p{Script=Han}]/u, `${role}/${route}: English chrome contains Chinese`);
        // Exercise the browser assertion against the shipped render output as well.
        const decoded = text.replace(/&(?:amp|lt|gt|quot|#39);/g, entity => ({'&amp;':'&','&lt;':'<','&gt;':'>','&quot;':'"','&#39;':"'"}[entity]));
        await assertDemoLanguage({locator:() => ({evaluateAll:async () => decoded})}, edition);
      }
      for (const step of app.view.currentGuide()) {
        const text = [step.title, step.description, step.action, step.gesture, step.result].join(' ');
        assert.doesNotMatch(text, /\bCARE_PLAN\b/);
        if (zh) assert.doesNotMatch(text, /\bLogo\b/);
        else assert.doesNotMatch(text, /[\p{Script=Han}]/u);
      }
    }
  });

  test(`${edition}: care cards localize every lifecycle and action status without changing model enums`, () => {
    const app = demo(edition), { plan, actions } = seed(app, edition);
    app.navigate('#plans');
    for (const lifecycle of ['DRAFT','ACTIVE','COMPLETED','CANCELLED']) {
      plan.lifecycle = lifecycle;
      for (const status of ['OPEN','NEEDS_HELP','SUBMITTED','CONFIRMED','SUPERSEDED','CANCELLED']) {
        actions[0].status = status;
        const before = JSON.stringify(app.state);
        app.view.render();
        assert.doesNotMatch(visible(app.node('content').innerHTML), protocolLabels);
        assert.equal(JSON.stringify(app.state), before);
        assert.ok(app.node('content').innerHTML.includes(`data-care-status="${status}"`), 'machine-readable state remains unchanged');
      }
    }
  });

  test(`${edition}: history renders all event and entry-declaration labels in the page language`, () => {
    const app = demo(edition);
    const labels = {
      DRAFT_SAVED: ['Draft saved','草稿已保存'], REVISION_DRAFTED: ['Revision draft created','已创建修订草稿'],
      PUBLISHED: ['Plan published','计划已发布'], REVISED: ['Revision published','修订已发布'],
      SUBMITTED: ['Receipt submitted','回执已提交'], NEEDS_HELP: ['Difficulty reported','已报告困难'],
      FOLLOW_UP: ['Follow-up recorded','已记录跟进'], CONFIRMED: ['Doctor review confirmed','医生已确认复核'],
      RETURNED: ['Returned for detail','已退回补充'], CLOSED: ['Plan closed','计划已关闭'], CANCELLED: ['Plan cancelled','计划已取消']
    };
    const events = Object.keys(labels).map((eventType,index) => ({ eventType, actorName: {en:'Nurse Lin',zh:'林护士'}, actorRole:'nurse', recordedAt:'2026-10-03T08:00:00Z', entryMode:index%2?'SELF':'ASSISTED', evidence:[] }));
    const before = JSON.stringify(events), text = visible(app.view.careEvents(events));
    for (const [en,cn] of Object.values(labels)) assert.ok(text.includes(translated(en,cn)), `${en}: missing localized event label`);
    assert.ok(text.includes(translated('Self-declared record','本人声明记录')));
    assert.ok(text.includes(translated('Assisted record','协助记录')));
    assert.doesNotMatch(text, protocolLabels);
    assert.equal(JSON.stringify(events), before);
  });

  test(`${edition}: receipt and publication dialogs do not expose internal state codes`, () => {
    const app = demo(edition), { plan, actions } = seed(app, edition);
    for (const role of ['patient','family','nurse']) {
      app.state.role = role;
      click(app, 'cp-submit', {id:String(actions[0].id)});
      assert.doesNotMatch(visible(app.node('action-fields').innerHTML), protocolLabels, role);
      assert.match(app.node('action-title').textContent, zh ? /提交执行回执/ : /Submit execution receipt/);
    }
    app.state.role = 'doctor';
    app.model.createCarePlanRevision(app.state, plan.id);
    click(app, 'cp-publish', {id:String(plan.id)});
    assert.doesNotMatch(visible(app.node('action-fields').innerHTML), protocolLabels);
    assert.ok(visible(app.node('action-fields').innerHTML).includes(translated('Replaced by a newer revision','已被新版本替代')));
    assert.ok(visible(app.node('action-fields').innerHTML).includes(translated('To do','待执行')));
  });

  test(`${edition}: report preview keeps patient-entered language and medical terms while localizing system history`, () => {
    const app = demo(edition), { actions } = seed(app, edition);
    const note = 'Original patient text 原始记录: metformin 500 mg';
    app.state.role = 'family';
    app.model.submitCarePlanAction(app.state, actions[0].id, {note, occurredAt:new Date(Date.now()-60000).toISOString(), entryMode:'SELF', evidence:[]});
    const before = JSON.stringify(app.state);
    click(app, 'care-report-open');
    const text = visible(app.node('record-detail').innerHTML);
    assert.doesNotMatch(text, protocolLabels);
    assert.ok(text.includes(note));
    assert.ok(text.includes(translated('Wei Zhang','张伟')));
    assert.ok(text.includes(translated('Receipt submitted','回执已提交')));
    assert.ok(text.includes(translated('v1','版本 1')));
    assert.equal(JSON.stringify(app.state), before);
  });

  test(`${edition}: audit actions use translated feature and care-event labels without rewriting audit data`, () => {
    const app = demo(edition);
    seed(app, edition);
    for (const key of ['clinical-import','menu-system','role-nurse','automation-daily']) app.model.runFeature(app.state,key);
    app.state.role = 'admin';
    const before = JSON.stringify(app.state);
    app.navigate('#audit');
    const text = visible(app.node('content').innerHTML);
    for (const key of ['clinical-import','menu-system','role-nurse','automation-daily']) assert.ok(!text.includes(key), `${key}: internal feature key leaked`);
    for (const [en,cn] of [['Batch import &amp; OCR','批量导入与 OCR'],['System administration','系统管理'],['Daily monitoring summary','每日监测摘要'],['Plan published','计划已发布']]) assert.ok(text.includes(translated(en,cn)), en);
    assert.doesNotMatch(text, protocolLabels);
    assert.equal(JSON.stringify(app.state), before);
  });

  test(`${edition}: timestamps use an explicit page locale and retain their UTC offset`, () => {
    const app = demo(edition), instant = '2026-10-03T08:00:00Z';
    const html = app.view.careEvents([{actorRole:'nurse',actorName:{en:'Nurse Lin',zh:'林护士'},eventType:'PUBLISHED',recordedAt:instant,evidence:[]}]);
    assert.ok(visible(html).includes(new Date(instant).toLocaleString(zh?'zh-CN':'en-GB')));
    assert.match(visible(html), /UTC[+-]\d{2}:\d{2}/);
  });

  test(`${edition}: common dialogs, report details, validation and input hints use the selected language`, () => {
    const app = demo(edition);
    for (const [kind,en,cn] of [['vital','Record blood pressure','记录血压'],['plan','Create care plan','新建照护计划'],['handover','Add family handover','新增家庭交接']]) {
      click(app,kind);
      assert.equal(app.node('action-title').textContent, translated(en,cn));
      app.node('action-form').handlers.submit({preventDefault(){},target:{values:{}}});
      assert.ok(app.node('form-error').textContent.includes(translated('Check the fields','请检查输入')));
    }
    click(app,'detail',{id:'0'});
    assert.equal(app.node('detail-title').textContent, translated('Complete blood count','血常规报告'));
    assert.ok(visible(app.node('record-detail').innerHTML).includes(translated('Clinician reviewed','医生已复核')));
    assert.ok(app.consultation().includes(translated('Describe symptoms or reply to the care team…','描述症状或回复照护团队……')));
    const index = readFileSync(new URL(zh?'../../../cn/demo/index.html':'../../index.html',import.meta.url),'utf8');
    assert.ok(index.includes(`lang="${zh?'zh-CN':'en'}"`));
    assert.ok(index.includes(`aria-label="${translated('Close','关闭')}"`));
  });
}
