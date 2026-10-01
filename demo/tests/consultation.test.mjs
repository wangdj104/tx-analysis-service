import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

// Run the shipped domain model, renderer, and event handlers. Only browser
// surfaces and the clock are adapted; consultation behavior is never mocked.
function demo(edition) {
  const base = new URL(edition === 'en' ? '../' : '../../cn/demo/', import.meta.url);
  const read = file => readFileSync(new URL(file, base), 'utf8');
  const nodes = new Map(), listeners = {}, timers = new Map(), classes = new Set();
  let nextTimer = 0;
  const node = id => {
    if (!nodes.has(id)) {
      let html = '';
      nodes.set(id, {
        textContent: '', style: {}, attributes: {}, handlers: {}, writes: 0,
        get innerHTML() { return html; },
        set innerHTML(value) { html = value; this.writes++; },
        addEventListener(type, callback) { this.handlers[type] = callback; },
        setAttribute(name, value) { this.attributes[name] = value; },
        removeAttribute(name) { delete this.attributes[name]; },
        showModal() {}, close() {}, focus() {}
      });
    }
    return nodes.get(id);
  };
  const document = {
    documentElement: { lang: edition === 'en' ? 'en' : 'zh-CN' },
    body: { style: {}, classList: { toggle(name) { const on = !classes.has(name); on ? classes.add(name) : classes.delete(name); return on; } } },
    getElementById: node, querySelectorAll: () => [], querySelector: () => null,
    addEventListener(type, callback) { (listeners[type] ||= []).push(callback); }
  };
  const context = vm.createContext({
    document, location: { hash: '#consultation' }, console,
    localStorage: { getItem() {}, removeItem() {}, setItem() {} },
    FormData: class { constructor(form) { this.values = form.values; } get(name) { return this.values[name] ?? null; } },
    setTimeout(callback, delay) { const id = ++nextTimer; timers.set(id, { callback, delay }); return id; },
    clearTimeout(id) { timers.delete(id); }
  });
  context.window = context;
  context.addEventListener = () => {};
  vm.runInContext(read('model.js'), context);
  vm.runInContext(read('app.js').replace(/\}\)\(\);\s*$/, 'globalThis.view = { consultation, render, get state() { return state; } };})();'), context);
  return {
    model: context.HealthDemo, node,
    get state() { return context.view.state; },
    consultation: () => context.view.consultation(),
    select(patientId) { node('patient').handlers.change({ target: { value: String(patientId) } }); },
    reset() { node('reset').handlers.click(); },
    submit(message) {
      const event = { target: { id: 'chat-form', values: { message } }, preventDefault() {} };
      for (const callback of listeners.submit || []) callback(event);
    },
    pendingReplies() { return [...timers.values()].filter(timer => timer.delay === 850).length; },
    runReplies() {
      for (const [id, timer] of [...timers]) {
        if (timer.delay === 850) { timers.delete(id); timer.callback(); }
      }
    }
  };
}

function secondSession(app) {
  const chat = JSON.parse(JSON.stringify(app.state.consultations[0]));
  chat.id = 702;
  chat.patientId = 2;
  chat.participants = [{ en: 'Mingyuan Li', zh: '李明远' }];
  chat.symptom = { en: 'Patient two follow-up', zh: '第二位患者随访' };
  chat.messages = [{ id: 20, sender: 'patient', name: chat.participants[0], text: { en: 'Patient two private message', zh: '第二位患者的独立消息' }, time: '10:00' }];
  app.state.consultations.push(chat);
  return chat;
}

function assertEmptyView(app, edition, patientId) {
  const html = app.consultation();
  const language = edition === 'en' ? 'en' : 'zh';
  assert.ok(html.includes(app.state.patients.find(patient => patient.id === patientId).name[language]), 'empty view identifies the selected patient');
  assert.match(html, /class="empty(?:\s|"|$)/, 'missing session has a visible empty state');
  assert.doesNotMatch(html, /id="chat-form"|data-action="chat-call"|class="chat-message(?:\s|")|class="participant-list"/);
  assert.ok(!html.includes(app.state.consultations[0]?.messages[0].text[language] ?? 'impossible-transcript-marker'), 'another patient transcript must not be visible');
}

for (const edition of ['en', 'zh']) {
  test(`${edition}: consultation lookup never falls back to another patient`, () => {
    const app = demo(edition);
    for (const patientId of [2, 3]) {
      app.state.patientId = patientId;
      assert.equal(app.model.currentConsultation(app.state), undefined);
    }
    app.state.patientId = 1;
    app.state.consultations = [];
    assert.equal(app.model.currentConsultation(app.state), undefined);
  });

  for (const operation of ['sendChatMessage', 'addDemoReply']) {
    test(`${edition}: ${operation} rejects a missing session without any mutation`, () => {
      for (const patientId of [2, 3]) {
        const app = demo(edition);
        app.state.patientId = patientId;
        const before = JSON.stringify(app.state);
        assert.throws(() => app.model[operation](app.state, operation === 'sendChatMessage' ? 'Do not append elsewhere' : edition), { message: 'no-consultation' });
        assert.equal(JSON.stringify(app.state), before);
      }
      const app = demo(edition);
      app.state.consultations = [];
      const before = JSON.stringify(app.state);
      assert.throws(() => app.model[operation](app.state, operation === 'sendChatMessage' ? 'No sessions' : edition), { message: 'no-consultation' });
      assert.equal(JSON.stringify(app.state), before);
    });
  }

  test(`${edition}: patients without consultations see their own empty state and no chat controls`, () => {
    const app = demo(edition);
    for (const patientId of [2, 3]) {
      app.select(patientId);
      assertEmptyView(app, edition, patientId);
    }
    app.state.consultations = [];
    app.select(1);
    assertEmptyView(app, edition, 1);
  });

  test(`${edition}: returning to the original patient preserves the legitimate conversation`, () => {
    const app = demo(edition), chat = app.model.currentConsultation(app.state);
    const before = JSON.stringify(chat);
    app.select(2);
    app.select(1);
    assert.equal(app.model.currentConsultation(app.state), chat);
    assert.equal(JSON.stringify(chat), before);
    assert.ok(app.consultation().includes(chat.messages[0].text[edition === 'en' ? 'en' : 'zh']));
  });

  test(`${edition}: each matching consultation receives only its own patient messages`, () => {
    const app = demo(edition), first = app.state.consultations[0], second = secondSession(app);
    const firstBefore = JSON.stringify(first);
    app.select(2);
    assert.equal(app.model.currentConsultation(app.state), second);
    app.model.sendChatMessage(app.state, 'Second patient only', 'patient');
    app.model.addDemoReply(app.state, edition);
    assert.equal(second.messages.length, 3);
    assert.equal(second.messages[1].name.en, 'Mingyuan Li');
    assert.equal(JSON.stringify(first), firstBefore);
    assert.ok(app.consultation().includes('Second patient only'));
    app.select(1);
    assert.equal(app.model.currentConsultation(app.state), first);
    assert.ok(!app.consultation().includes('Second patient only'));
  });

  test(`${edition}: a forged submit for a patient without a session reports a localized error without sending`, () => {
    const app = demo(edition);
    for (const patientId of [2, 3]) {
      app.select(patientId);
      const before = JSON.stringify(app.state), html = app.node('content').innerHTML, writes = app.node('content').writes;
      app.submit('A stale form must not send into another patient conversation');
      assert.equal(JSON.stringify(app.state), before, 'neither transcripts nor the audit trail may change');
      assert.equal(app.pendingReplies(), 0, 'rejected submission must not schedule a simulated reply');
      assert.equal(app.node('content').innerHTML, html);
      assert.equal(app.node('content').writes, writes);
      assert.match(app.node('toast').textContent, edition === 'en' ? /no consultation/i : /暂无.*问诊会话/);
    }
  });

  test(`${edition}: a legitimate delayed reply updates the still-selected consultation`, () => {
    const app = demo(edition), chat = app.state.consultations[0];
    const before = chat.messages.length, audits = app.state.audit.length;
    app.submit('A legitimate follow-up in the current conversation');
    assert.equal(chat.messages.length, before + 1);
    assert.equal(app.state.audit.length, audits + 1);
    assert.equal(app.pendingReplies(), 1);
    const writes = app.node('content').writes;
    app.runReplies();
    assert.equal(app.pendingReplies(), 0);
    assert.equal(chat.messages.length, before + 2);
    assert.equal(chat.messages.at(-1).sender, 'doctor');
    assert.equal(app.state.patientId, 1);
    assert.ok(app.node('content').writes > writes);
    assert.ok(app.node('content').innerHTML.includes(chat.messages.at(-1).text[edition === 'en' ? 'en' : 'zh']));
    assert.match(app.node('toast').textContent, edition === 'en' ? /simulated clinician reply/i : /模拟医生回复/);
  });

  test(`${edition}: a delayed reply stays with its origin after selecting a patient with no session`, () => {
    const app = demo(edition), first = app.state.consultations[0], before = first.messages.length;
    app.submit('Reply to the original conversation');
    assert.equal(first.messages.length, before + 1);
    assert.equal(app.pendingReplies(), 1);
    app.select(2);
    const emptyHtml = app.node('content').innerHTML, writes = app.node('content').writes;
    app.runReplies();
    assert.equal(first.messages.length, before + 2);
    assert.equal(app.state.patientId, 2);
    assert.equal(app.model.currentConsultation(app.state), undefined);
    assert.equal(app.node('content').innerHTML, emptyHtml);
    assert.equal(app.node('content').writes, writes, 'background reply must not replace the current empty view');
    assertEmptyView(app, edition, 2);
  });

  test(`${edition}: a delayed reply cannot be redirected into another matching session`, () => {
    const app = demo(edition), first = app.state.consultations[0], second = secondSession(app);
    const firstCount = first.messages.length, secondBefore = JSON.stringify(second);
    app.submit('Keep the reply with patient one');
    assert.equal(app.pendingReplies(), 1);
    app.select(2);
    app.runReplies();
    assert.equal(first.messages.length, firstCount + 2);
    assert.equal(JSON.stringify(second), secondBefore);
    assert.equal(app.state.patientId, 2);
  });

  test(`${edition}: resetting the demo prevents delayed replies from changing fresh state`, () => {
    const app = demo(edition), oldState = app.state;
    app.submit('This pending reply belongs to the old demo');
    assert.equal(app.pendingReplies(), 1);
    app.reset();
    assert.notEqual(app.state, oldState);
    const fresh = JSON.stringify(app.state), html = app.node('content').innerHTML, writes = app.node('content').writes;
    app.runReplies();
    assert.equal(JSON.stringify(app.state), fresh);
    assert.equal(app.node('content').innerHTML, html);
    assert.equal(app.node('content').writes, writes);
  });
}
