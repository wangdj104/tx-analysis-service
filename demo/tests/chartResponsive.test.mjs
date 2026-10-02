import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { demo } from './helpers/runtime.mjs';

const points = html => [...html.matchAll(/points="([^"]+)"/g)].map(match => match[1].split(' ').map(pair => pair.split(',').map(Number)));
const titles = html => [...html.matchAll(/<title>([^<]+)<\/title>/g)].map(match => match[1]);
function clickRange(app, days) {
  const target = { dataset: { action: 'range', days: String(days) }, closest: selector => selector === '[data-action]' ? target : null };
  for (const listener of app.listeners.click) listener({ target });
}
function mount(edition, { observer = true, width = 421, page = 'monitoring' } = {}) {
  const app = demo(edition), observers = [], resizeListeners = new Set();
  let currentHost;
  app.context.ResizeObserver = observer ? class {
    constructor(callback) { this.callback = callback; this.disconnected = false; observers.push(this); }
    observe(host) { this.host = host; }
    disconnect() { this.disconnected = true; }
  } : undefined;
  app.context.addEventListener = (type, listener) => { if (type === 'resize') resizeListeners.add(listener); };
  app.context.removeEventListener = (type, listener) => { if (type === 'resize') resizeListeners.delete(listener); };
  app.context.document.querySelector = selector => selector === '.chart-host' ? currentHost : null;
  const host = () => {
    if (currentHost) currentHost.isConnected = false;
    currentHost = app.node(`chart-host-${observers.length}-${app.node('content').writes}`);
    currentHost.width = width;
    currentHost.innerHTML = app.view.chart();
    const ownHost = currentHost;
    ownHost.getBoundingClientRect = () => ({ width: ownHost.width });
    return ownHost;
  };
  if (page === 'vitals') app.state.role = 'patient';
  app.context.location.hash = `#${page}`;
  const firstHost = host();
  app.view.render();
  return { app, observers, resizeListeners, host, firstHost };
}

for (const edition of ['en', 'zh']) {
  test(`${edition}: chart uses the rendered width without changing readings or vertical scale`, () => {
    const app = demo(edition);
    app.model.addVital(app.state, 260, 30);
    const baseline = app.view.chart(700), originalPoints = points(baseline), originalState = JSON.stringify(app.state);
    for (const width of [239, 421, 700, 1100]) {
      const html = app.view.chart(width), series = points(html);
      assert.match(html, new RegExp(`viewBox="0 0 ${width} 240"`));
      assert.deepEqual(titles(html), titles(baseline));
      for (let s = 0; s < series.length; s++) {
        assert.deepEqual(series[s].map(point => point[1]), originalPoints[s].map(point => point[1]));
        assert.equal(series[s][0][0], 48);
        assert.equal(series[s].at(-1)[0], width - 42);
        series[s].forEach(([x, y], index) => {
          assert.ok(Number.isFinite(x) && x >= 48 && x <= width - 42);
          assert.ok(Number.isFinite(y) && y >= 30 && y <= 215);
          if (index) assert.ok(x >= series[s][index - 1][0]);
        });
      }
      assert.match(html, new RegExp(`<text x="${width - 42}" y="235" text-anchor="end">`));
    }
    assert.equal(JSON.stringify(app.state), originalState);
  });

  test(`${edition}: responsive chart preserves range boundaries, chronology, empty and single states`, () => {
    const app = demo(edition);
    app.context.Date = class extends Date { constructor(...args) { super(...(args.length ? args : ['2026-10-01T12:00:00'])); } };
    const rows = app.state.patients[0].records;
    rows.splice(0, rows.length,
      { date: '2026-10-01', time: '19:00', systolic: 126, diastolic: 76 },
      { date: '2026-09-01', time: '08:00', systolic: 121, diastolic: 71 },
      { date: '2026-09-02', time: '08:00', systolic: 122, diastolic: 72 },
      { date: '2026-09-25', time: '08:00', systolic: 123, diastolic: 73 },
      { date: '2026-10-01', time: '08:00', systolic: 124, diastolic: 74 });
    assert.deepEqual(titles(app.view.chart(239)), ['2026-09-25 08:00: 123/73', '2026-10-01 08:00: 124/74', '2026-10-01 19:00: 126/76']);
    clickRange(app, 30);
    assert.deepEqual(titles(app.view.chart(239)), ['2026-09-02 08:00: 122/72', '2026-09-25 08:00: 123/73', '2026-10-01 08:00: 124/74', '2026-10-01 19:00: 126/76']);
    rows.splice(1);
    for (const series of points(app.view.chart(239))) assert.equal(series[0][0], 48);
    assert.equal(titles(app.view.chart(239)).length, 1);
    rows.length = 0;
    assert.match(app.view.chart(239), edition === 'en' ? /No readings in this period/ : /此时间段暂无读数/);
  });

  test(`${edition}: axis labels keep explicit normal and large sizes on a fixed-height canvas`, () => {
    const css = readFileSync(new URL(edition === 'en' ? '../appearance.css' : '../../cn/demo/appearance.css', import.meta.url), 'utf8');
    assert.match(css, /\.chart\s*\{[^}]*height:\s*240px;[^}]*max-height:\s*none/s);
    assert.match(css, /\.grid-lines text,\s*\.axis-label\s*\{[^}]*font-size:\s*14px/s);
    assert.match(css, /body\.large-text[^{}]*\.grid-lines text[^{}]*\{[^}]*font-size:\s*18px/s);
  });

  test(`${edition}: chart resizes only its host and rejects unchanged, hidden and disposed callbacks`, () => {
    const { app, observers, firstHost } = mount(edition);
    assert.match(firstHost.innerHTML, /viewBox="0 0 421 240"/);
    assert.equal(observers.length, 1);
    const observer = observers[0], contentWrites = app.node('content').writes, state = JSON.stringify(app.state);
    const focus = app.node('text-size'); focus.focus();
    const firstWrites = firstHost.writes;
    observer.callback();
    assert.equal(firstHost.writes, firstWrites);
    for (const width of [0, NaN, Infinity]) { firstHost.width = width; observer.callback(); }
    assert.equal(firstHost.writes, firstWrites);
    firstHost.width = 600; observer.callback();
    assert.match(firstHost.innerHTML, /viewBox="0 0 600 240"/);
    assert.equal(app.node('content').writes, contentWrites);
    assert.equal(app.context.document.activeElement, focus);
    assert.equal(JSON.stringify(app.state), state);
    app.node('text-size').handlers.click();
    assert.equal(app.node('content').writes, contentWrites, 'large text is a presentation change');
    app.context.location.hash = '#overview'; app.view.render();
    assert.equal(observer.disconnected, true);
    const retiredWrites = firstHost.writes;
    firstHost.width = 800; observer.callback();
    assert.equal(firstHost.writes, retiredWrites, 'queued callback cannot redraw a disposed host');
  });

  test(`${edition}: patient, range and reset renders replace observers without stale data`, () => {
    const { app, observers, host, firstHost } = mount(edition);
    const secondHost = host(); app.select(2);
    assert.equal(observers.length, 2);
    assert.equal(observers[0].disconnected, true);
    assert.deepEqual(titles(secondHost.innerHTML), titles(app.view.chart(421)));
    assert.notDeepEqual(titles(secondHost.innerHTML), titles(firstHost.innerHTML));
    const thirdHost = host(); clickRange(app, 30);
    assert.equal(observers[1].disconnected, true);
    assert.equal(observers.length, 3);
    assert.equal(titles(thirdHost.innerHTML).length, 30);
    const writes = firstHost.writes; firstHost.width = 800; observers[0].callback();
    assert.equal(firstHost.writes, writes);
    app.reset();
    assert.ok(observers.every(observer => observer.disconnected));
  });

  test(`${edition}: window resize fallback is removed when the chart leaves the page`, () => {
    const { app, firstHost, resizeListeners } = mount(edition, { observer: false });
    assert.match(firstHost.innerHTML, /viewBox="0 0 421 240"/);
    assert.equal(resizeListeners.size, 1);
    const redraw = [...resizeListeners][0]; firstHost.width = 500; redraw();
    assert.match(firstHost.innerHTML, /viewBox="0 0 500 240"/);
    app.context.location.hash = '#overview'; app.view.render();
    assert.equal(resizeListeners.size, 0);
    const writes = firstHost.writes; firstHost.width = 600; redraw();
    assert.equal(firstHost.writes, writes);
  });

  test(`${edition}: large-text fallback redraws the current width without resetting controls or data`, () => {
    const { app, host, resizeListeners } = mount(edition, { observer: false });
    host(); app.select(2);
    const currentHost = host(); clickRange(app, 30);
    const contentWrites = app.node('content').writes, content = app.node('content').innerHTML;
    const state = JSON.stringify(app.state), readings = titles(currentHost.innerHTML);
    const resizeListener = [...resizeListeners][0], focus = app.node('text-size'); focus.focus();
    for (const [width, pressed] of [[800, 'true'], [421, 'false']]) {
      currentHost.width = width;
      focus.handlers.click();
      assert.match(currentHost.innerHTML, new RegExp(`viewBox="0 0 ${width} 240"`));
      assert.equal(focus.attributes['aria-pressed'], pressed);
      assert.deepEqual(titles(currentHost.innerHTML), readings);
      assert.equal(app.node('content').writes, contentWrites);
      assert.equal(app.node('content').innerHTML, content);
      assert.equal(app.context.document.activeElement, focus);
      assert.equal(JSON.stringify(app.state), state);
      assert.deepEqual([...resizeListeners], [resizeListener], 'toggle reuses the existing listener');
    }
  });

  test(`${edition}: large-text hook becomes inert after navigation and targets only a fresh chart`, () => {
    const { app, host, firstHost, resizeListeners } = mount(edition, { observer: false });
    const obsoleteResize = [...resizeListeners][0];
    app.context.location.hash = '#overview'; app.view.render();
    const retiredWrites = firstHost.writes;
    firstHost.width = 800;
    app.node('text-size').handlers.click(); obsoleteResize();
    assert.equal(firstHost.writes, retiredWrites);
    assert.equal(resizeListeners.size, 0);
    app.context.location.hash = '#monitoring';
    const freshHost = host(); app.view.render();
    const freshResize = [...resizeListeners][0];
    assert.notEqual(freshResize, obsoleteResize);
    freshHost.width = 600;
    app.node('text-size').handlers.click(); obsoleteResize();
    assert.match(freshHost.innerHTML, /viewBox="0 0 600 240"/);
    assert.equal(firstHost.writes, retiredWrites);
    assert.deepEqual([...resizeListeners], [freshResize]);
  });

  test(`${edition}: unmeasured vitals chart retains its fallback and recovers when visible`, () => {
    const { app, firstHost, observers } = mount(edition, { width: 0, page: 'vitals' });
    assert.match(app.node('content').innerHTML, /class="chart-host"/);
    assert.match(firstHost.innerHTML, /viewBox="0 0 700 240"/);
    assert.equal(observers.length, 1);
    firstHost.width = 239; observers[0].callback();
    assert.match(firstHost.innerHTML, /viewBox="0 0 239 240"/);
    firstHost.isConnected = false;
    const writes = firstHost.writes; firstHost.width = 421; observers[0].callback();
    assert.equal(firstHost.writes, writes, 'detached host callbacks are inert');
  });
}

test('chart behavior and styles remain byte-identical across demo editions', () => {
  for (const file of ['app.js', 'appearance.css']) assert.equal(readFileSync(new URL(`../${file}`, import.meta.url), 'utf8'), readFileSync(new URL(`../../cn/demo/${file}`, import.meta.url), 'utf8'));
});
