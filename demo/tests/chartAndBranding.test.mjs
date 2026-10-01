import assert from 'node:assert/strict';
import test from 'node:test';
import { demo } from './helpers/runtime.mjs';

for (const edition of ['en', 'zh']) {
  test(`${edition}: extreme valid readings stay within chart plotting bounds`, () => {
    const app = demo(edition);
    app.model.addVital(app.state, 260, 30);
    const chart = app.view.chart();
    const coordinates = [...chart.matchAll(/points="([^"]*)"/g)].flatMap(match => match[1].split(' ').map(pair => Number(pair.split(',')[1])));
    assert.ok(coordinates.length > 0);
    for (const y of coordinates) assert.ok(y >= 30 && y <= 215, `reading y=${y} leaves plot`);
  });
  test(`${edition}: seven days includes every reading in the date window`, () => {
    const app = demo(edition);
    app.context.Date = class extends Date { constructor(...args) { super(...(args.length ? args : ['2026-10-01T12:00:00'])); } };
    const rows = app.state.patients[0].records;
    rows.splice(0, rows.length,
      { date: '2026-09-24', time: '08:00', systolic: 120, diastolic: 80 },
      { date: '2026-09-25', time: '08:00', systolic: 121, diastolic: 81 },
      ...Array.from({length: 9}, (_, i) => ({ date: '2026-10-01', time: `10:0${i}`, systolic: 122, diastolic: 82 }))
    );
    const html = app.view.chart();
    assert.equal((html.match(/<circle /g) || []).length, 10);
    assert.ok(html.includes('2026-09-25'));
    assert.ok(!html.includes('2026-09-24'));
    rows.length = 0;
    assert.match(app.view.chart(), edition === 'en' ? /No readings in this period/ : /此时间段暂无读数/);
  });
  test(`${edition}: background picker and hex text remain synchronized before save`, () => {
    const app = demo(edition), picker = { name: 'pageBackground', value: '#123456' }, hex = { name: 'backgroundText', value: '#f7faf8' };
    const form = { id: 'branding-form', elements: { pageBackground: picker, backgroundText: hex } };
    for (const control of [picker, hex]) { control.form = form; control.closest = () => form; }
    for (const handler of app.listeners.input || []) handler({ target: picker });
    assert.equal(hex.value, '#123456');
    hex.value = '#abcdef';
    for (const handler of app.listeners.input || []) handler({ target: hex });
    assert.equal(picker.value, '#abcdef');
    hex.value = '#nope';
    for (const handler of app.listeners.input || []) handler({ target: hex });
    assert.equal(picker.value, '#abcdef', 'invalid partial hex must not change the picker');
  });
}
