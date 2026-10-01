import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { demo } from './helpers/runtime.mjs';

for (const edition of ['en', 'zh']) {
  test(`${edition}: incoming replies update the transcript without replacing the next draft or focus`, () => {
    const app = demo(edition);
    app.submit('Sent message');
    const writes = app.node('content').writes;
    const composer = app.node('draft-composer');
    composer.value = 'Still typing this next message';
    composer.selectionStart = 5; composer.selectionEnd = 9; composer.focus();
    app.runReplies();
    assert.equal(app.node('content').writes, writes, 'incoming reply must not replace the composer DOM');
    assert.equal(app.context.document.activeElement, composer);
    assert.equal(composer.value, 'Still typing this next message');
    assert.equal(composer.selectionStart, 5);
    assert.equal(composer.selectionEnd, 9);
    const chat = app.model.currentConsultation(app.state);
    assert.ok(app.node('chat-messages').innerHTML.includes(chat.messages.at(-1).text[edition === 'en' ? 'en' : 'zh']));
  });

  test(`${edition}: interactive tour is non-modal, receives focus and restores its opener on Escape`, () => {
    const app = demo(edition);
    const html = readFileSync(new URL(edition === 'en' ? '../index.html' : '../../cn/demo/index.html', import.meta.url), 'utf8');
    assert.match(html, /id="guide-tour"[^>]*aria-modal="false"/);
    assert.match(html, /id="guide-title"[^>]*tabindex="-1"/);
    const opener = app.node('guide-start'); opener.focus();
    app.view.openGuide();
    assert.equal(app.context.document.activeElement, app.node('guide-title'));
    let prevented = false;
    for (const handler of app.listeners.keydown || []) handler({ key: 'Escape', preventDefault() { prevented = true; } });
    assert.equal(app.node('guide-tour').hidden, true);
    assert.equal(app.context.document.activeElement, opener);
    assert.equal(prevented, true);
  });
}

for (const edition of ['en', 'zh']) {
  test(`${edition}: a rerender restores its corresponding keyboard action or the main region`, () => {
    const app = demo(edition), document = app.context.document;
    const old = { dataset: { action: 'range', days: '30' } };
    const replacement = { dataset: { action: 'range', days: '30' }, focus() { document.activeElement = this; } };
    document.activeElement = old;
    document.querySelectorAll = selector => selector === '[data-action]' ? [replacement] : [];
    app.view.render();
    assert.equal(document.activeElement, replacement);
    document.activeElement = { dataset: { action: 'complete', id: '2' } };
    document.querySelectorAll = () => [];
    app.view.render();
    assert.equal(document.activeElement, app.node('content'));
  });
}
