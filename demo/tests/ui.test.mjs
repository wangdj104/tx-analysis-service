import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

// Execute the actual demo renderers and handlers with a minimal DOM adapter.
// The adapter supplies browser surfaces only; care data and actions are real.
function demo(edition) {
  const root = new URL(edition === 'en' ? '../' : '../../cn/demo/', import.meta.url);
  const read = file => readFileSync(new URL(file, root), 'utf8');
  const nodes = new Map(), listeners = {}, classes = new Set();
  const node = id => {
    if (!nodes.has(id)) nodes.set(id, { innerHTML: '', textContent: '', style: {}, attributes: {}, handlers: {},
      addEventListener(type, callback) { this.handlers[type] = callback; },
      setAttribute(name, value) { this.attributes[name] = value; },
      removeAttribute(name) { delete this.attributes[name]; },
      showModal() {}, close() {}, focus() {} });
    return nodes.get(id);
  };
  const document = { documentElement: { lang: edition === 'en' ? 'en' : 'zh-CN' },
    body: { style: {}, classList: { toggle(name, force) { const on = force ?? !classes.has(name); on ? classes.add(name) : classes.delete(name); return on; } } },
    getElementById: node, querySelectorAll: () => [],
    addEventListener(type, callback) { (listeners[type] ||= []).push(callback); } };
  const context = vm.createContext({ document, location: { hash: '' }, localStorage: { getItem() {}, removeItem() {} },
    setTimeout() {}, clearTimeout() {}, console });
  context.window = context; context.addEventListener = () => {};
  vm.runInContext(read('model.js'), context);
  const source = read('app.js').replace(/\}\)\(\);\s*$/, 'globalThis.view = { today, care, tasks, render, get state() { return state; } };})();');
  vm.runInContext(source, context);
  return { ...context.view, context, node, classes, read };
}

for (const edition of ['en', 'zh']) {
  test(`${edition}: care homes put record actions and task groups before summary statistics`, () => {
    const app = demo(edition);
    for (const render of [app.today, app.care]) {
      const html = render();
      assert.match(html, /aria-labelledby="care-heading"/);
      assert.ok(html.indexOf('class="care-actions"') > 0);
      assert.ok(html.indexOf('class="care-actions"') < html.indexOf('class="care-task-columns"'));
      assert.ok(html.indexOf('class="care-task-columns"') < html.indexOf('class="stats"'));
      assert.match(html, /id="medication-heading"/);
      assert.match(html, /id="care-tasks-heading"/);
      assert.match(html, edition === 'en' ? /Current record.*Aihua Zhang/ : /当前档案.*张爱华/);
      assert.doesNotMatch(html, /<select/);
    }
    assert.match(app.care(), /data-action="handover"/);
  });

  test(`${edition}: tasks prioritize unfinished care without mutating data and counts update`, () => {
    const app = demo(edition), state = app.state;
    const before = JSON.stringify(state.patients[0].tasks);
    const html = app.today();
    assert.match(html, /class="care-count">1 (?:pending|项待完成)/);
    const medication = app.tasks('medication');
    assert.ok(medication.indexOf('data-id="2"') < medication.indexOf('task done'));
    assert.equal(JSON.stringify(state.patients[0].tasks), before);
    app.context.HealthDemo.complete(state, 2);
    assert.match(app.today(), /class="care-count">0 (?:pending|项待完成)/);
    state.patientId = 2;
    assert.match(app.care(), edition === 'en' ? /Current record.*Mingyuan Li/ : /当前档案.*李明远/);
  });

  test(`${edition}: large-text control is accessible and only changes presentation`, () => {
    const app = demo(edition), before = JSON.stringify(app.state);
    assert.match(app.read('index.html'), /id="text-size"[^>]*aria-pressed="false"/);
    assert.equal(typeof app.node('text-size').handlers.click, 'function');
    app.node('text-size').handlers.click();
    assert.ok(app.classes.has('large-text'));
    assert.equal(String(app.node('text-size').attributes['aria-pressed']), 'true');
    app.node('text-size').handlers.click();
    assert.ok(!app.classes.has('large-text'));
    assert.equal(JSON.stringify(app.state), before);
  });
}

test('both editions ship the same UI behavior, styles and versioned assets', () => {
  const en = demo('en'), zh = demo('zh');
  for (const file of ['app.js', 'model.js', 'style.css']) assert.equal(en.read(file), zh.read(file));
  for (const app of [en, zh]) {
    assert.match(app.read('index.html'), /app\.js\?v=20261002-safety1/);
    assert.match(app.read('index.html'), /model\.js\?v=20261001-r9/);
    assert.match(app.read('index.html'), /style\.css\?v=20261001-1/);
    assert.match(app.read('index.html'), /branding\.css\?v=20261001-1/);
    assert.match(app.read('app.js'), /setAttribute\('aria-current',\s*'page'\)/);
    assert.match(app.read('style.css'), /body\.large-text/);
    assert.match(app.read('style.css'), /@media\s*\(max-width:\s*720px\)/);
    assert.match(app.read('style.css'), /animation:\s*none\s*!important/);
  }
});

test('general panel spacing preserves the flush consultation split panes', () => {
  for (const edition of ['en', 'zh']) {
    assert.match(demo(edition).read('style.css'), /\.chat-shell\.panel\s*\{\s*padding:\s*0;\s*\}/);
  }
});
