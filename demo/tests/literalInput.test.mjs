import assert from 'node:assert/strict';
import test from 'node:test';
import { demo } from './helpers/runtime.mjs';

for (const edition of ['en', 'zh']) {
  test(`${edition}: care-plan titles render as literal text, preserving markup-like notes`, () => {
    const app = demo(edition), text = 'Review <b data-demo="probe">result</b> & "notes"';
    app.model.addPlan(app.state, text, '2026-10-10');
    app.context.location.hash = '#plans'; app.view.render();
    const html = app.node('content').innerHTML;
    assert.ok(html.includes('Review &lt;b data-demo=&quot;probe&quot;&gt;result&lt;/b&gt; &amp; &quot;notes&quot;'));
    assert.ok(!html.includes('<b data-demo="probe">'));
    assert.equal(app.state.plans[0].title.en, text);
  });
  test(`${edition}: family handovers cannot inject local markup`, () => {
    const app = demo(edition), text = 'Hand over <img data-demo="probe"> & follow-up';
    app.state.role = 'family'; app.model.addHandover(app.state, text);
    app.context.location.hash = '#handover'; app.view.render();
    const html = app.node('content').innerHTML;
    assert.ok(html.includes('Hand over &lt;img data-demo=&quot;probe&quot;&gt; &amp; follow-up'));
    assert.ok(!html.includes('<img data-demo="probe">'));
    assert.equal(app.state.handovers[0].text.en, text);
  });
}
