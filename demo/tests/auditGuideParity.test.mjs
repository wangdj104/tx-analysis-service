import assert from 'node:assert/strict';
import test from 'node:test';
import { demo } from './helpers/runtime.mjs';

for (const edition of ['en', 'zh']) {
  test(`${edition}: every role guide remains inside its available navigation`, () => {
    const app = demo(edition);
    for (const role of ['doctor','patient','family','admin']) {
      app.state.role = role;
      for (const step of app.view.currentGuide()) if (step.path) assert.ok(Object.hasOwn(app.view.copy[role].nav,step.path), `${role} cannot visit ${step.path}`);
    }
  });
  test(`${edition}: successful care actions create patient-scoped audit events once`, () => {
    const app = demo(edition), state = app.state, model = app.model;
    const actions = [
      () => model.complete(state,2),
      () => model.addVital(state,120,80),
      () => model.addPlan(state,'Fictional follow-up','2099-01-01'),
      () => model.addHandover(state,'Fictional handover')
    ];
    for (const operation of actions) {
      const before=state.audit.length; operation();
      assert.equal(state.audit.length,before+1);
      assert.equal(state.audit[0].patientId,1);
      assert.ok(state.audit[0].actor.en && state.audit[0].actor.zh);
      assert.equal(state.audit[0].result,'success');
    }
    const before=state.audit.length;
    assert.equal(model.complete(state,2),false);
    assert.throws(()=>model.addVital(state,60,90));
    assert.equal(state.audit.length,before);
  });
  test(`${edition}: clinical review audit identifies the reviewed patient rather than header selection`, () => {
    const app=demo(edition), review=app.state.reviews[0];
    app.state.patientId = review.patientId===1?2:1;
    const before=app.state.audit.length;
    assert.equal(app.model.review(app.state,review.id,'approved'),true);
    assert.equal(app.state.audit.length,before+1);
    assert.equal(app.state.audit[0].patientId,review.patientId);
    assert.equal(app.state.audit[0].targetId,review.id);
    assert.equal(app.model.review(app.state,review.id,'rejected'),false);
    assert.equal(app.state.audit.length,before+1);
  });
}

for (const edition of ['en', 'zh']) {
  test(`${edition}: changing role and resetting safely dismiss an in-progress guide`, () => {
    const app = demo(edition);
    app.view.openGuide();
    for (let i=0; i<4; i++) app.node('guide-next').handlers.click();
    const role = { dataset: { role: 'admin' } };
    const target = { closest: selector => selector === '[data-role]' ? role : null };
    for (const handler of app.listeners.click || []) handler({ target });
    assert.equal(app.node('guide-tour').hidden,true);
    assert.doesNotThrow(()=>app.node('guide-next').handlers.click());
    app.view.openGuide();
    app.reset();
    assert.equal(app.node('guide-tour').hidden,true);
    assert.doesNotThrow(()=>app.view.openGuide());
    assert.match(app.node('guide-count').textContent,/^1 \/ /);
  });
}

test('CI and Pages verify the separate Chinese demo tests as well as shared behavior', async () => {
  const {readFileSync}=await import('node:fs');
  for (const workflow of ['ci.yml','demo-pages.yml']) {
    const source=readFileSync(new URL('../../.github/workflows/'+workflow,import.meta.url),'utf8');
    assert.match(source,/node --test cn\/demo\/tests\/\*\.test\.mjs/);
  }
});
