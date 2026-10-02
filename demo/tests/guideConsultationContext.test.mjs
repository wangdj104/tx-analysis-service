import assert from 'node:assert/strict';
import test from 'node:test';
import { demo } from './helpers/runtime.mjs';

// Run shipped state, rendering, guide, and event handlers. Adapt only DOM
// geometry/selector lookup and the clock; target availability follows its HTML.
function guide(edition, role) {
  const app = demo(edition), targets = new Map(), scrolls = [];
  app.state.role = role;
  let hash = app.context.location.hash;
  Object.defineProperty(app.context.location, 'hash', {
    get: () => hash,
    set: value => { hash = value.startsWith('#') ? value : `#${value}`; }
  });
  app.context.innerWidth = 1200; app.context.innerHeight = 800;
  const target = selector => {
    if (!targets.has(selector)) {
      const element = selector === '#patient' ? app.node('patient') : app.node(selector);
      const classes = new Set();
      element.classList = { add: value => classes.add(value), remove: value => classes.delete(value), contains: value => classes.has(value) };
      element.getBoundingClientRect = () => ({ top: 80, left: 80, right: 240, bottom: 120, width: 160, height: 40 });
      element.scrollIntoView = options => scrolls.push({ selector, options });
      targets.set(selector, element);
    }
    return targets.get(selector);
  };
  app.context.document.querySelector = selector => {
    if (selector === '.guide-target') return [...targets.values()].find(element => element.classList.contains('guide-target')) || null;
    if (selector === '#patient') return target(selector);
    if (selector === '#chat-form textarea') return /id="chat-form"/.test(app.node('content').innerHTML) ? target(selector) : null;
    if (selector === '.columns .empty') return /class="columns"/.test(app.node('content').innerHTML) && /class="empty"/.test(app.node('content').innerHTML) ? target(selector) : null;
    if (selector === '.care-task-columns') return /class="care-task-columns"/.test(app.node('content').innerHTML) ? target(selector) : null;
    if (selector === '.language-link' || selector.startsWith('[data-action=') && app.node('content').innerHTML.includes(selector.slice(1, -1))) return target(selector);
    return null;
  };
  app.context.document.querySelectorAll = selector => {
    if (selector !== '[data-action]') return [];
    return [...app.node('content').innerHTML.matchAll(/<button\b[^>]*data-action="[^"]+"[^>]*>/g)].map(([html]) => {
      const dataset = Object.fromEntries([...html.matchAll(/data-([a-z]+)="([^"]*)"/g)].map(([, key, value]) => [key, value]));
      const control = app.node(`action:${dataset.action}:${dataset.id || ''}`);
      control.dataset = dataset;
      return control;
    });
  };
  app.node('content').classList = { add() {}, remove() {} };
  app.node('content').getBoundingClientRect = () => ({ top: 0, left: 0, right: 900, bottom: 700, width: 900, height: 700 });
  app.node('content').scrollIntoView = options => scrolls.push({ selector: '#content', options });
  const content = app.node('content'), contentHTML = Object.getOwnPropertyDescriptor(content, 'innerHTML');
  Object.defineProperty(content, 'innerHTML', {
    get: contentHTML.get,
    set(value) {
      // Replacing main content detaches its old highlighted nodes in a browser.
      for (const [selector, element] of targets) if (!['#patient', '.language-link'].includes(selector)) element.classList.remove('guide-target');
      contentHTML.set.call(this, value);
    }
  });
  app.node('guide-popover').style.setProperty = function(key, value) { this[key] = value; };
  for (const id of ['patient', 'guide-title', 'guide-start', 'content']) app.node(id).id = id;
  const pending = () => [...app.timers].filter(([, timer]) => timer.delay === 120);
  const flush = () => {
    for (const [id, timer] of pending()) { app.timers.delete(id); timer.callback(); }
  };
  const openChatStep = () => { app.node('guide-start').focus(); app.view.openGuide(); app.node('guide-next').handlers.click(); };
  return { app, target, scrolls, pending, flush, openChatStep };
}

function clickAction(view, action, id) {
  const { app } = view;
  const control = app.context.document.querySelectorAll('[data-action]').find(element => element.dataset.action === action && element.dataset.id === String(id));
  assert.ok(control, 'the action must exist in rendered HTML before it can be clicked');
  control.focus();
  for (const handler of app.listeners.click || []) handler({ target: { closest: selector => selector === '[data-action]' ? control : null } });
}

function assertUnavailableAction(view, edition, role) {
  const { app } = view, doctor = role === 'doctor';
  assert.equal(app.node('guide-count').textContent, '1 / 5');
  assert.equal(app.node('guide-title').textContent, doctor
    ? edition === 'en' ? 'No clinical items awaiting review' : '暂无待复核项目'
    : edition === 'en' ? 'No one-click care task is pending' : '暂无可直接完成的照护任务');
  assert.match(app.node('guide-action').textContent, edition === 'en' ? /continue the guide/ : /继续指引/);
  const selector = doctor ? '.columns .empty' : '.care-task-columns';
  assert.equal(app.view.currentGuide()[0].selector, selector);
  assert.doesNotMatch(app.node('content').innerHTML, doctor ? /data-action="review-(approve|reject)"/ : /data-action="complete"/);
  view.flush();
  assert.equal(view.scrolls.at(-1).selector, selector);
  assert.equal(view.target(selector).classList.contains('guide-target'), true);
}

for (const edition of ['en', 'zh']) {
  test(`${edition}: a cleared review queue gets honest guidance without creating a decision`, () => {
    const view = guide(edition, 'doctor'), { app } = view;
    const originalSteps = JSON.parse(JSON.stringify(app.view.currentGuide()));
    for (const item of app.state.reviews) app.model.review(app.state, item.id, 'approved');
    const before = JSON.stringify(app.state);
    app.node('guide-start').focus(); app.view.openGuide();
    assertUnavailableAction(view, edition, 'doctor');
    assert.equal(JSON.stringify(app.state), before);
    assert.deepEqual(JSON.parse(JSON.stringify(app.view.currentGuide().slice(1))), originalSteps.slice(1));
    app.node('guide-next').handlers.click();
    assert.equal(app.node('guide-count').textContent, '2 / 5');
    app.node('guide-previous').handlers.click();
    assertUnavailableAction(view, edition, 'doctor');
  });

  for (const decision of ['approve', 'reject']) {
    test(`${edition}: ${decision} of the last review refreshes step one without focus or step advance`, () => {
      const view = guide(edition, 'doctor'), { app } = view;
      for (const item of app.state.reviews.slice(1)) app.model.review(app.state, item.id, 'approved');
      app.node('guide-start').focus(); app.view.openGuide();
      const stale = view.pending().map(([, timer]) => timer.callback), beforeAudit = app.state.audit.length;
      clickAction(view, `review-${decision}`, app.state.reviews[0].id);
      assert.equal(app.state.audit.length, beforeAudit + 1, 'only the explicit review action writes an audit');
      assert.equal(app.state.reviews[0].status, decision === 'approve' ? 'approved' : 'rejected');
      assert.equal(app.context.document.activeElement, app.node('content'), 'a removed action returns to main content, not the guide');
      const scrolls = view.scrolls.length;
      for (const callback of stale) callback();
      assert.equal(view.scrolls.length, scrolls);
      assertUnavailableAction(view, edition, 'doctor');
      assert.equal(app.context.document.activeElement, app.node('content'));
    });
  }

  test(`${edition}: family guidance distinguishes unavailable completion buttons from unfinished measurements`, () => {
    for (const measurementsDone of [false, true]) {
      const view = guide(edition, 'family'), { app } = view;
      const originalSteps = JSON.parse(JSON.stringify(app.view.currentGuide()));
      for (const task of app.model.current(app.state).tasks.filter(task => !task.done && task.type !== 'vital')) app.model.complete(app.state, task.id);
      if (measurementsDone) app.model.addVital(app.state, 126, 78);
      const before = JSON.stringify(app.state);
      app.node('guide-start').focus(); app.view.openGuide();
      assertUnavailableAction(view, edition, 'family');
      assert.match(app.node('guide-description').textContent, edition === 'en' ? /Measurements may still need to be recorded/ : /测量仍可能需要记录/);
      assert.doesNotMatch(app.node('guide-description').textContent, edition === 'en' ? /all.*(?:tasks|care).*complete/i : /全部.*完成|照护已完成/);
      assert.match(app.node('content').innerHTML, /data-action="vital"/);
      assert.equal(JSON.stringify(app.state), before);
      assert.deepEqual(JSON.parse(JSON.stringify(app.view.currentGuide().slice(1))), originalSteps.slice(1));
    }
  });

  test(`${edition}: completing the final one-click family task refreshes the open step and preserves remaining measurements`, () => {
    const view = guide(edition, 'family'), { app } = view;
    app.node('guide-start').focus(); app.view.openGuide(); view.flush();
    for (const task of app.model.current(app.state).tasks.filter(task => !task.done && task.type !== 'vital')) clickAction(view, 'complete', task.id);
    assertUnavailableAction(view, edition, 'family');
    assert.equal(app.model.current(app.state).tasks.find(task => task.type === 'vital').done, false);
    assert.equal(app.context.document.activeElement, app.node('content'));
    const state = JSON.stringify(app.state);
    app.node('guide-next').handlers.click(); view.flush();
    assert.equal(app.node('guide-count').textContent, '2 / 5');
    assert.equal(view.scrolls.at(-1).selector, '[data-action="handover"]');
    app.node('guide-previous').handlers.click();
    assertUnavailableAction(view, edition, 'family');
    assert.equal(JSON.stringify(app.state), state);
  });

  test(`${edition}: switching family patients restores available controls without changing guide focus or other tasks`, () => {
    const view = guide(edition, 'family'), { app } = view;
    const original = JSON.parse(JSON.stringify(app.view.currentGuide()[0]));
    for (const task of app.model.current(app.state).tasks.filter(task => !task.done && task.type !== 'vital')) app.model.complete(app.state, task.id);
    app.node('guide-start').focus(); app.view.openGuide();
    assertUnavailableAction(view, edition, 'family');
    const originalPatientTasks = JSON.stringify(app.model.current(app.state).tasks);
    app.node('patient').focus(); app.select(2); view.flush();
    assert.equal(app.node('guide-count').textContent, '1 / 5');
    assert.equal(app.node('guide-action').textContent, original.action);
    assert.equal(view.scrolls.at(-1).selector, '[data-action="complete"]');
    assert.equal(app.context.document.activeElement, app.node('patient'));
    app.select(1);
    assertUnavailableAction(view, edition, 'family');
    assert.equal(JSON.stringify(app.model.current(app.state).tasks), originalPatientTasks);
    assert.equal(app.context.document.activeElement, app.node('patient'));
  });

  test(`${edition}: saving a remaining measurement reanchors family guidance without advancing or stealing dialog-return focus`, () => {
    const view = guide(edition, 'family'), { app } = view;
    for (const task of app.model.current(app.state).tasks.filter(task => !task.done && task.type !== 'vital')) app.model.complete(app.state, task.id);
    app.node('guide-start').focus(); app.view.openGuide();
    assertUnavailableAction(view, edition, 'family');
    const control = app.context.document.querySelectorAll('[data-action]').find(element => element.dataset.action === 'vital');
    control.focus();
    for (const handler of app.listeners.click || []) handler({ target: { closest: selector => selector === '[data-action]' ? control : null } });
    app.node('measurement-input').focus();
    const audit = app.state.audit.length;
    app.node('action-form').handlers.submit({ target: { values: { systolic: '126', diastolic: '78' } }, preventDefault() {} });
    app.node('action-dialog').handlers.close();
    assertUnavailableAction(view, edition, 'family');
    assert.equal(app.context.document.activeElement.dataset.action, 'vital');
    assert.notEqual(app.context.document.activeElement, app.node('guide-title'));
    assert.equal(app.state.audit.length, audit + 1);
    assert.equal(app.model.current(app.state).tasks.find(task => task.type === 'vital').done, true);
    app.view.closeGuide();
    assert.equal(app.context.document.activeElement, app.node('guide-start'));
  });

  for (const role of ['doctor', 'family']) {
    for (const close of ['pause', 'escape']) {
      test(`${edition} ${role}: empty-action ${close} and restart retain caller focus and reject queued positions`, () => {
        const view = guide(edition, role), { app } = view;
        if (role === 'doctor') for (const item of app.state.reviews) app.model.review(app.state, item.id, 'approved');
        else for (const task of app.model.current(app.state).tasks.filter(task => !task.done && task.type !== 'vital')) app.model.complete(app.state, task.id);
        app.node('guide-start').focus(); app.view.openGuide();
        const stale = view.pending().map(([, timer]) => timer.callback);
        if (close === 'pause') app.node('guide-pause').handlers.click();
        else for (const handler of app.listeners.keydown || []) handler({ key: 'Escape', preventDefault() {} });
        assert.equal(app.node('guide-tour').hidden, true);
        assert.equal(app.context.document.activeElement, app.node('guide-start'));
        for (const callback of stale) callback();
        assert.equal(view.scrolls.length, 0);
        assert.equal(app.context.document.querySelector('.guide-target'), null);
        app.view.openGuide();
        for (const callback of stale) callback();
        assert.equal(view.scrolls.length, 0);
        assertUnavailableAction(view, edition, role);
        assert.equal(app.context.document.activeElement, app.node('guide-title'));
        app.view.closeGuide();
        assert.equal(app.context.document.activeElement, app.node('guide-start'));
      });
    }
  }
}

function assertEmptyStep(view, edition) {
  const { app } = view;
  assert.equal(app.node('guide-count').textContent, '2 / 5');
  assert.equal(app.node('guide-title').textContent, edition === 'en' ? 'No consultation for this patient' : '当前患者暂无问诊会话');
  assert.match(app.node('guide-action').textContent, edition === 'en' ? /verify.*patient.*continue/i : /核对.*患者.*继续/);
  assert.match(app.node('guide-result').textContent, edition === 'en' ? /existing conversation/i : /已有会话/);
  assert.equal(app.view.currentGuide()[1].selector, '#patient');
  assert.match(app.node('content').innerHTML, /id="consultation-empty-title"/);
  assert.doesNotMatch(app.node('content').innerHTML, /id="chat-form"/);
  view.flush();
  assert.equal(view.scrolls.at(-1).selector, '#patient');
  assert.equal(view.target('#patient').classList.contains('guide-target'), true);
}

for (const edition of ['en', 'zh']) {
  for (const role of ['doctor', 'patient']) {
    test(`${edition} ${role}: missing consultation replaces only the chat guide step without changing patient data`, () => {
      for (const patientId of [2, 3]) {
        const view = guide(edition, role), { app } = view;
        const originalSteps = JSON.parse(JSON.stringify(app.view.currentGuide()));
        app.select(patientId);
        const before = JSON.stringify(app.state);
        view.openChatStep();
        assertEmptyStep(view, edition);
        assert.equal(JSON.stringify(app.state), before);
        const steps = JSON.parse(JSON.stringify(app.view.currentGuide()));
        assert.equal(steps.length, originalSteps.length);
        assert.deepEqual(steps.filter((_, index) => index !== 1), originalSteps.filter((_, index) => index !== 1));
        app.node('guide-next').handlers.click();
        view.flush();
        assert.equal(app.node('guide-count').textContent, '3 / 5');
        assert.equal(app.state.patientId, patientId);
        app.node('guide-previous').handlers.click();
        assertEmptyStep(view, edition);
      }
    });

    test(`${edition} ${role}: patient changes refresh an open chat step and round trips restore its original instructions`, () => {
      const view = guide(edition, role), { app } = view;
      const original = JSON.parse(JSON.stringify(app.view.currentGuide()[1]));
      const conversations = JSON.stringify(app.state.consultations), audits = JSON.stringify(app.state.audit);
      view.openChatStep(); view.flush();
      assert.equal(view.scrolls.at(-1).selector, '#chat-form textarea');
      app.node('patient').focus();
      app.select(3);
      assertEmptyStep(view, edition);
      assert.equal(app.context.document.activeElement, app.node('patient'));
      app.select(1); view.flush();
      assert.equal(app.node('guide-count').textContent, '2 / 5');
      assert.equal(app.node('guide-action').textContent, original.action);
      assert.equal(view.scrolls.at(-1).selector, '#chat-form textarea');
      assert.deepEqual(JSON.parse(JSON.stringify(app.view.currentGuide()[1])), original);
      app.select(1); view.flush();
      assert.equal(view.scrolls.at(-1).selector, '#chat-form textarea');
      assert.equal(app.context.document.activeElement, app.node('patient'));
      assert.equal(JSON.stringify(app.state.consultations), conversations);
      assert.equal(JSON.stringify(app.state.audit), audits);
      app.view.closeGuide();
      assert.equal(app.context.document.activeElement, app.node('guide-start'));
    });

    test(`${edition} ${role}: queued positioning cannot outlive newer patient context or a close and restart`, () => {
      const view = guide(edition, role), { app } = view;
      view.openChatStep();
      const stale = view.pending().map(([, timer]) => timer.callback);
      app.select(3);
      assert.equal(view.target('#chat-form textarea').classList.contains('guide-target'), false);
      const before = view.scrolls.length;
      for (const callback of stale) callback();
      assert.equal(view.scrolls.length, before, 'obsolete callbacks cannot highlight the old target or #content');
      assertEmptyStep(view, edition);
      app.select(1);
      const closing = view.pending().map(([, timer]) => timer.callback);
      app.view.closeGuide();
      for (const callback of closing) callback();
      assert.equal(app.context.document.querySelector('.guide-target'), null);
      assert.equal(app.context.document.activeElement, app.node('guide-start'));
      app.view.openGuide();
      const restarted = view.scrolls.length;
      for (const callback of closing) callback();
      assert.equal(view.scrolls.length, restarted, 'old callbacks remain invalid after reopening');
      view.flush();
      assert.equal(app.node('guide-count').textContent, '1 / 5');
      assert.equal(view.scrolls.at(-1).selector, role === 'doctor' ? '[data-action="review-approve"]' : '[data-action="vital"]');
      app.view.closeGuide();
      assert.equal(app.context.document.activeElement, app.node('guide-start'));
    });
  }

  test(`${edition}: card-based patient selection also refreshes the chat guide`, () => {
    const view = guide(edition, 'doctor'), { app } = view;
    view.openChatStep(); view.flush();
    const control = { dataset: { action: 'select-patient', id: '3' } };
    for (const handler of app.listeners.click || []) handler({ target: { closest: selector => selector === '[data-action]' ? control : null } });
    assertEmptyStep(view, edition);
  });

  test(`${edition}: guide availability uses matching conversations rather than fixed patient IDs`, () => {
    const view = guide(edition, 'patient'), { app } = view;
    app.state.consultations[0].patientId = 3;
    app.select(3); view.openChatStep(); view.flush();
    assert.equal(view.scrolls.at(-1).selector, '#chat-form textarea');
    app.select(1);
    assertEmptyStep(view, edition);
  });
}
