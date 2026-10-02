import assert from 'node:assert/strict';
import test from 'node:test';
import { demo } from './helpers/runtime.mjs';

function growingTranscript(app) {
  const transcript = app.node('chat-messages');
  const baselineWrites = transcript.writes;
  transcript.clientHeight = 300;
  Object.defineProperty(transcript, 'scrollHeight', { get: () => transcript.writes > baselineWrites ? 1400 : 1000 });
  return transcript;
}

for (const edition of ['en', 'zh']) {
  test(`${edition}: a new reply follows the latest message when already near the bottom`, () => {
    const app = demo(edition);
    app.submit('Synthetic sent message');
    const transcript = growingTranscript(app);
    transcript.scrollTop = 650;
    const draft = app.node('chat-message');
    draft.value = 'Unsent next draft'; draft.selectionStart = 3; draft.selectionEnd = 8; draft.focus();
    app.runReplies();
    assert.equal(transcript.scrollTop, 1400);
    assert.equal(draft.value, 'Unsent next draft');
    assert.equal(draft.selectionStart, 3); assert.equal(draft.selectionEnd, 8);
    assert.equal(app.context.document.activeElement, draft);
  });
  test(`${edition}: a reader looking at earlier messages keeps their scroll position`, () => {
    const app = demo(edition);
    app.submit('Synthetic sent message');
    const transcript = growingTranscript(app);
    transcript.scrollTop = 100;
    app.runReplies();
    assert.equal(transcript.scrollTop, 100);
    assert.equal(transcript.scrollHeight, 1400);
    assert.equal(app.node('toast').hidden, false);
  });
  test(`${edition}: opening the room and sending a message scroll to the latest transcript`, () => {
    const app = demo(edition), transcript = app.node('chat-messages');
    transcript.scrollHeight = 1000; transcript.scrollTop = 0;
    app.view.render();
    assert.equal(transcript.scrollTop, 1000);
    transcript.scrollTop = 0;
    app.submit('Synthetic sent message');
    assert.equal(transcript.scrollTop, 1000);
  });
}
