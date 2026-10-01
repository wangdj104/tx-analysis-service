import { readFileSync } from 'node:fs';
import vm from 'node:vm';

// Execute shipped code; browser surfaces and timers are the only adapters.
export function demo(edition) {
  const base = new URL(edition === 'en' ? '../../' : '../../../cn/demo/', import.meta.url);
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
        showModal() {}, close() {}, focus() { document.activeElement = this; }, isConnected: true
      });
    }
    return nodes.get(id);
  };
  node('guide-tour').hidden = true;
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
  vm.runInContext(read('app.js').replace(/\}\)\(\);\s*$/, 'globalThis.view = { consultation, stats, doctorOverview, appointments, chart, currentGuide, copy, render, openGuide, closeGuide, get state() { return state; } };})();'), context);
  return {
    model: context.HealthDemo, node, context, listeners, timers,
    view: context.view,
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
