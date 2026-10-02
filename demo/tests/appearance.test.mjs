import assert from 'node:assert/strict';
import test from 'node:test';
import { demo } from './helpers/runtime.mjs';

const key = 'chengxin-health-appearance-v1';
const savedBrand = { platformName: 'Care Demo', organizationName: 'Care Team', ownershipText: 'Demo only', logo: 'assets/logo.svg', pageBackground: '#d4e3f2' };
function memoryStorage(values = {}) {
  const entries = new Map(Object.entries(values));
  return {
    getItem: name => entries.get(name) ?? null,
    setItem: (name, value) => entries.set(name, value),
    removeItem: name => entries.delete(name)
  };
}
function select(app, value) {
  assert.equal(typeof app.node('health-theme').handlers.change, 'function', 'theme control changes presentation');
  app.node('health-theme').handlers.change({ target: { value } });
}

for (const edition of ['en', 'zh']) {
  // Catch missing default initialization and accidental loss of saved platform branding.
  test(`${edition}: platform appearance retains the saved brand background`, () => {
    const storage = memoryStorage({ 'chengxin-demo-branding': JSON.stringify(savedBrand) });
    const app = demo(edition, { storage });
    assert.equal(app.context.document.documentElement.dataset.healthTheme, 'platform');
    assert.equal(app.node('health-theme').value, 'platform');
    assert.equal(app.context.document.body.style.background, '#d4e3f2');
    assert.equal(storage.getItem(key), null, 'merely loading the page does not overwrite preferences');
  });

  // Catch theme handlers that mutate care data, rerender drafts, or write unrelated storage.
  test(`${edition}: every preset applies immediately without replacing care content or branding`, () => {
    const storage = memoryStorage({ unrelated: 'keep', 'chengxin-demo-branding': JSON.stringify(savedBrand) });
    const app = demo(edition, { storage }), before = JSON.stringify(app.state), writes = app.node('content').writes;
    for (const theme of ['white', 'blue', 'mint', 'sand', 'dark']) {
      select(app, theme);
      assert.equal(app.context.document.documentElement.dataset.healthTheme, theme);
      assert.equal(app.node('health-theme').value, theme);
      assert.equal(app.context.document.body.style.background, 'var(--health-page)');
      assert.deepEqual(JSON.parse(storage.getItem(key)), { version: 1, theme });
      app.view.render();
      assert.equal(app.context.document.documentElement.dataset.healthTheme, theme, 'other screen renders retain appearance');
      assert.equal(JSON.stringify(app.state), before);
    }
    assert.equal(app.node('content').writes, writes + 5, 'only explicit page renders replace care content');
    assert.equal(storage.getItem('unrelated'), 'keep');
    assert.equal(JSON.parse(storage.getItem('chengxin-demo-branding')).pageBackground, '#d4e3f2');
    select(app, 'platform');
    assert.equal(app.context.document.body.style.background, '#d4e3f2');
  });

  // Catch malformed or future-version data applied as a DOM attribute or stylesheet value.
  test(`${edition}: corrupt and unknown saved appearances safely use platform default`, () => {
    for (const value of ['{', 'unknown', 'null', '[]', '"dark"', '{"theme":"dark"}', '{"version":2,"theme":"dark"}', '{"version":1,"theme":"unknown"}', '{"version":1,"theme":null}']) {
      const app = demo(edition, { storage: memoryStorage({ [key]: value }) });
      assert.equal(app.context.document.documentElement.dataset.healthTheme, 'platform', value);
    }
    const app = demo(edition);
    select(app, 'not-a-theme');
    assert.equal(app.context.document.documentElement.dataset.healthTheme, 'platform');
  });

  // Catch unavailable storage preventing first render or a selected live theme.
  test(`${edition}: denied storage still permits a live appearance change`, () => {
    const app = demo(edition, { storage: { getItem() { throw new Error('denied'); }, setItem() { throw new Error('quota'); } } });
    assert.equal(app.context.document.documentElement.dataset.healthTheme, 'platform');
    const before = JSON.stringify(app.state), writes = app.node('content').writes;
    select(app, 'dark');
    assert.equal(app.context.document.documentElement.dataset.healthTheme, 'dark');
    assert.equal(app.node('health-theme').value, 'dark');
    assert.equal(app.node('toast').hidden, false, 'a failed save is visible to the user');
    assert.match(app.node('toast').textContent, edition === 'en' ? /applied.*couldn.t.*save/i : /已.*应用.*未能保存/);
    assert.equal(app.node('content').writes, writes, 'storage feedback must not replace active input');
    assert.equal(JSON.stringify(app.state), before);
  });

  // Catch a preference being ignored on reload, including the bounded legacy format.
  test(`${edition}: saved and legacy valid themes restore on reload`, () => {
    for (const theme of ['platform', 'white', 'blue', 'mint', 'sand', 'dark']) {
      for (const raw of [theme, JSON.stringify({ version: 1, theme })]) {
        const app = demo(edition, { storage: memoryStorage({ [key]: raw }) });
        assert.equal(app.context.document.documentElement.dataset.healthTheme, theme);
        assert.equal(app.node('health-theme').value, theme);
      }
    }
  });
}

test('appearance follows the shared preference when the demo language changes', () => {
  const storage = memoryStorage(), en = demo('en', { storage });
  select(en, 'sand');
  const zh = demo('zh', { storage });
  assert.equal(zh.context.document.documentElement.dataset.healthTheme, 'sand');
  select(zh, 'dark');
  assert.equal(demo('en', { storage }).context.document.documentElement.dataset.healthTheme, 'dark');
});
