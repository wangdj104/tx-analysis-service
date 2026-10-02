import assert from 'node:assert/strict';
import test from 'node:test';
import { demo } from './helpers/runtime.mjs';

const guideKey = 'chengxin-demo-guide-complete';
const brandingKey = 'chengxin-demo-branding';
const appearanceKey = 'chengxin-health-appearance-v1';
const savedBrand = { platformName: 'Storage Test Clinic', organizationName: 'Fictional team', ownershipText: 'Fictional demo only', logo: 'assets/logo.svg', pageBackground: '#d4e3f2' };
const savedAppearance = JSON.stringify({ version: 1, theme: 'dark' });

// Faults affect only this in-memory adapter, never real browser storage.
function memoryStorage({ failGuide = false, failBranding = false, failBrandingSave = false } = {}) {
  const entries = new Map([[brandingKey, JSON.stringify(savedBrand)], [appearanceKey, savedAppearance], ['unrelated', 'keep']]);
  const writes = [];
  return {
    writes,
    getItem: key => entries.get(key) ?? null,
    setItem(key, value) {
      writes.push(['set', key, value]);
      if (failGuide && key === guideKey) throw new Error('Synthetic quota failure');
      if (failBrandingSave && key === brandingKey) throw new Error('Synthetic branding quota failure');
      entries.set(key, String(value));
    },
    removeItem(key) {
      writes.push(['remove', key]);
      if (failBranding && key === brandingKey) throw new Error('Synthetic unavailable storage');
      entries.delete(key);
    }
  };
}

// Run real app handlers and guide positioning; adapt only DOM lookup/geometry
// and the location hash behavior that the shared lightweight runtime omits.
function surface(edition, storage) {
  const app = demo(edition, { storage }), targets = new Map(), scrolls = [];
  let hash = app.context.location.hash;
  Object.defineProperty(app.context.location, 'hash', {
    get: () => hash,
    set: value => { hash = value.startsWith('#') ? value : `#${value}`; }
  });
  app.context.innerWidth = 1200; app.context.innerHeight = 800;
  for (const id of ['guide-start', 'guide-title', 'reset', 'content']) app.node(id).id = id;
  const target = selector => {
    if (!targets.has(selector)) {
      const element = app.node(selector), classes = new Set();
      element.classList = { add: name => classes.add(name), remove: name => classes.delete(name), contains: name => classes.has(name) };
      element.getBoundingClientRect = () => ({ top: 80, left: 80, right: 240, bottom: 120, width: 160, height: 40 });
      element.scrollIntoView = () => scrolls.push(selector);
      targets.set(selector, element);
    }
    return targets.get(selector);
  };
  app.context.document.querySelector = selector => {
    if (selector === '.guide-target') return [...targets.values()].find(element => element.classList.contains('guide-target')) || null;
    return app.view.currentGuide().some(step => step.selector === selector) ? target(selector) : null;
  };
  app.node('guide-popover').style.setProperty = function(key, value) { this[key] = value; };
  const pending = () => [...app.timers].filter(([, timer]) => timer.delay === 120);
  const flush = () => {
    for (const [id, timer] of pending()) { app.timers.delete(id); timer.callback(); }
  };
  return { app, scrolls, pending, flush };
}

function assertAppearance(app, storage) {
  assert.equal(app.context.document.documentElement.dataset.healthTheme, 'dark');
  assert.equal(app.node('health-theme').value, 'dark');
  assert.equal(storage.getItem(appearanceKey), savedAppearance);
  assert.equal(storage.getItem('unrelated'), 'keep');
}

function assertClosed(view, stale, opener) {
  const { app } = view;
  assert.equal(app.node('guide-tour').hidden, true);
  assert.equal(view.pending().length, 0, 'guide positioning timer is cancelled');
  assert.equal(app.context.document.querySelector('.guide-target'), null, 'highlight is removed');
  assert.equal(app.context.document.activeElement, opener, 'focus returns to the connected opener');
  const scrolls = view.scrolls.length;
  for (const callback of stale) callback();
  assert.equal(view.scrolls.length, scrolls, 'stale guide callbacks cannot scroll or restore a highlight');
  assert.equal(app.context.document.querySelector('.guide-target'), null);
  assert.equal(app.context.document.activeElement, opener);
}

function submitBranding(app, values) {
  const event = { target: { id: 'branding-form', values }, preventDefault() {} };
  for (const handler of app.listeners.submit || []) handler(event);
}

for (const edition of ['en', 'zh']) {
  for (const failGuide of [false, true]) {
    // Without isolating setItem, Finish throws before close/focus/timer cleanup.
    test(`${edition}: Finish ${failGuide ? 'recovers from unavailable storage' : 'saves normally'} without changing demo data`, () => {
      const storage = memoryStorage({ failGuide }), view = surface(edition, storage), { app } = view;
      const before = JSON.stringify(app.state), opener = app.node('guide-start');
      opener.focus(); app.view.openGuide();
      const stale = [];
      for (let index = 1; index < app.view.currentGuide().length; index++) {
        stale.push(...view.pending().map(([, timer]) => timer.callback));
        view.flush(); app.node('guide-next').handlers.click();
      }
      assert.equal(app.node('guide-count').textContent, '5 / 5');
      stale.push(...view.pending().map(([, timer]) => timer.callback));
      view.flush();
      assert.ok(app.context.document.querySelector('.guide-target'));
      // A resize-like refresh leaves a pending callback at the final step.
      app.node('guide-previous').handlers.click(); app.node('guide-next').handlers.click();
      stale.push(...view.pending().map(([, timer]) => timer.callback));
      assert.doesNotThrow(() => app.node('guide-next').handlers.click());
      assertClosed(view, stale, opener);
      assert.equal(JSON.stringify(app.state), before);
      assert.equal(storage.getItem(guideKey), failGuide ? null : '1');
      assert.equal(app.node('toast').hidden, false);
      if (failGuide) {
        assert.match(app.node('toast').textContent, edition === 'en' ? /Guide completed.*couldn.t save.*completion/i : /指引已完成.*未能保存.*完成状态/);
        assert.notEqual(app.node('toast').textContent, edition === 'en' ? 'Guide completed. You can restart it at any time.' : '指引已完成，可随时重新开始。');
      } else {
        assert.equal(app.node('toast').textContent, edition === 'en' ? 'Guide completed. You can restart it at any time.' : '指引已完成，可随时重新开始。');
      }
      const writes = storage.writes.length, warning = app.node('toast').textContent;
      for (let repeat = 0; repeat < 3; repeat++) app.node('guide-next').handlers.click();
      assert.equal(storage.writes.length, writes, 'repeated Finish after closing is inert');
      assert.equal(app.node('toast').textContent, warning);
      opener.focus(); app.view.openGuide();
      assert.equal(app.node('guide-count').textContent, '1 / 5');
      const title = app.node('guide-title').textContent, pending = view.pending().length;
      for (const callback of stale) callback();
      assert.equal(app.node('guide-title').textContent, title);
      assert.equal(view.pending().length, pending, 'old callbacks do not affect the restarted guide');
      for (let index = 0; index < app.view.currentGuide().length; index++) app.node('guide-next').handlers.click();
      assertClosed(view, stale, opener);
      assert.equal(storage.writes.length, writes + 1, 'an explicit restarted completion can try again');
      assert.equal(JSON.stringify(app.state), before);
      assert.equal(storage.getItem(brandingKey), JSON.stringify(savedBrand));
      assertAppearance(app, storage);
    });
  }

  for (const failBranding of [false, true]) {
    // Without isolating removeItem, Reset swaps the state but leaves stale UI/hash.
    test(`${edition}: Reset ${failBranding ? 'recovers from unavailable storage with an honest warning' : 'clears saved branding normally'} and fully restores the session`, () => {
      const storage = memoryStorage({ failBranding }), view = surface(edition, storage), { app } = view;
      storage.setItem(guideKey, '1');
      app.submit('Fictional pre-reset draft');
      const previousState = app.state;
      app.model.complete(app.state, 2);
      app.model.review(app.state, 101, 'approved');
      app.state.role = 'family'; app.context.location.hash = '#care'; app.select(2);
      const range = { dataset: { action: 'range', days: '30' } };
      for (const handler of app.listeners.click || []) handler({ target: { closest: selector => selector === '[data-action]' ? range : null } });
      assert.equal((app.view.chart().match(/<circle /g) || []).length, 30);
      const opener = app.node('guide-start'); opener.focus(); app.view.openGuide();
      const stale = view.pending().map(([, timer]) => timer.callback);
      view.flush();
      assert.ok(app.context.document.querySelector('.guide-target'));
      app.select(3);
      stale.push(...view.pending().map(([, timer]) => timer.callback));
      const oldContent = app.node('content').innerHTML, oldWrites = app.node('content').writes;
      assert.doesNotThrow(() => app.reset());
      assertClosed(view, stale, opener);
      assert.notEqual(app.state, previousState);
      assert.equal(app.state.role, 'doctor'); assert.equal(app.state.patientId, 1);
      assert.equal(app.context.location.hash, '#overview');
      assert.equal(app.node('content').writes, oldWrites + 1);
      assert.notEqual(app.node('content').innerHTML, oldContent);
      assert.match(app.node('content').innerHTML, edition === 'en' ? /<h1>Start with what needs clinical attention<\/h1>/ : /<h1>先处理真正需要临床关注的事项<\/h1>/);
      assert.match(app.node('role-switch').innerHTML, /data-role="doctor" aria-pressed="true"/);
      assert.match(app.node('patient').innerHTML, /<option value="1" selected>/);
      assert.equal(app.node('breadcrumb').textContent, app.view.copy.doctor.nav.overview);
      assert.equal(app.node('workspace-label').textContent, app.view.copy.doctor.workspace);
      assert.equal(app.node('brand-name').textContent, edition === 'en' ? 'Chengxin Health' : '澄心健康');
      assert.equal((app.view.chart().match(/<circle /g) || []).length, 7, 'chart range resets with the session');
      const fresh = app.model.createState();
      // Initial audit time is allowed to advance while the test crosses a minute.
      const withoutAuditTime = state => JSON.stringify(state, (key, value) => key === 'time' ? undefined : value);
      assert.equal(withoutAuditTime(app.state), withoutAuditTime(fresh));
      const cleanState = JSON.stringify(app.state), cleanContent = app.node('content').innerHTML;
      app.runReplies();
      assert.equal(JSON.stringify(app.state), cleanState, 'old simulated replies cannot write into the reset session');
      assert.equal(app.node('content').innerHTML, cleanContent);
      assert.equal(app.node('toast').hidden, false);
      if (failBranding) {
        assert.match(app.node('toast').textContent, edition === 'en' ? /reset.*this visit.*couldn.t clear.*saved branding.*reload.*restore/i : /本次访问.*重置.*未能清除.*已保存.*品牌.*刷新.*恢复/);
        assert.notEqual(app.node('toast').textContent, edition === 'en' ? 'Demo reset.' : '演示已重置。');
      } else {
        assert.equal(app.node('toast').textContent, edition === 'en' ? 'Demo reset.' : '演示已重置。');
      }
      const message = app.node('toast').textContent;
      for (let repeat = 0; repeat < 2; repeat++) {
        assert.doesNotThrow(() => app.reset());
        assert.equal(app.node('toast').textContent, message);
        assert.equal(withoutAuditTime(app.state), withoutAuditTime(fresh));
        assert.equal(app.context.location.hash, '#overview');
      }
      assert.equal(storage.getItem(brandingKey), failBranding ? JSON.stringify(savedBrand) : null);
      assert.equal(storage.getItem(guideKey), '1', 'reset keeps the existing guide completion preference');
      assertAppearance(app, storage);
      opener.focus(); app.view.openGuide();
      assert.equal(app.node('guide-count').textContent, '1 / 5');
      for (const callback of stale) callback();
      assert.equal(app.context.document.activeElement, app.node('guide-title'));
      // A new runtime reading the same synthetic storage represents page reload.
      const reloaded = demo(edition, { storage });
      assert.equal(reloaded.node('brand-name').textContent, failBranding ? savedBrand.platformName : edition === 'en' ? 'Chengxin Health' : '澄心健康');
      assertAppearance(reloaded, storage);
    });
  }

  for (const failBrandingSave of [false, true]) {
    // A valid update must render even when its separate persistence attempt fails.
    test(`${edition}: valid branding ${failBrandingSave ? 'applies for this visit when saving fails' : 'saves and applies normally'}`, () => {
      for (const theme of ['platform', 'dark']) {
        const storage = memoryStorage({ failBrandingSave });
        const appearance = JSON.stringify({ version: 1, theme });
        storage.setItem(appearanceKey, appearance);
        const app = demo(edition, { storage });
        app.state.role = 'admin'; app.context.location.hash = '#branding'; app.view.render();
        const withoutBranding = state => JSON.stringify({ ...state, branding: undefined });
        const before = withoutBranding(app.state), writes = app.node('content').writes;
        const values = { platformName: 'New Fictional Clinic', organizationName: 'New Fictional Team', backgroundText: '#eef6f2', ownershipText: 'New fictional ownership' };
        const expected = { platformName: values.platformName, organizationName: values.organizationName, pageBackground: values.backgroundText, ownershipText: values.ownershipText, logo: savedBrand.logo };
        assert.doesNotThrow(() => submitBranding(app, values));
        assert.equal(app.node('content').writes, writes + 1, 'valid branding is rendered regardless of persistence');
        assert.equal(app.state.branding.platformName.en, values.platformName);
        assert.equal(app.state.branding.platformName.zh, values.platformName);
        assert.equal(app.node('brand-name').textContent, values.platformName);
        assert.equal(app.node('brand-organization').textContent, values.organizationName);
        assert.equal(app.node('ownership').textContent, values.ownershipText);
        assert.equal(app.node('brand-logo').src, savedBrand.logo);
        assert.equal(app.context.document.title, `${app.view.copy.admin.nav.branding} · ${values.platformName}`);
        assert.match(app.node('content').innerHTML, /name="platformName"[^>]*value="New Fictional Clinic"/);
        assert.match(app.node('content').innerHTML, /name="backgroundText"[^>]*value="#eef6f2"/);
        assert.equal(app.context.document.body.style.background, theme === 'platform' ? values.backgroundText : 'var(--health-page)');
        assert.equal(app.context.document.documentElement.dataset.healthTheme, theme);
        assert.equal(app.node('health-theme').value, theme);
        assert.equal(storage.getItem(appearanceKey), appearance);
        assert.equal(storage.getItem('unrelated'), 'keep');
        assert.equal(withoutBranding(app.state), before, 'branding changes no care or account data');
        assert.equal(app.node('toast').hidden, false);
        if (failBrandingSave) {
          assert.match(app.node('toast').textContent, edition === 'en' ? /branding.*applied.*this visit.*couldn.t save.*reload.*restore/i : /品牌.*本次访问.*应用.*未能保存.*刷新.*恢复/);
          assert.doesNotMatch(app.node('toast').textContent, edition === 'en' ? /Check the branding fields|saved and applied/ : /请检查品牌配置|已保存并应用/);
        } else {
          assert.equal(app.node('toast').textContent, edition === 'en' ? 'Platform branding saved and applied.' : '平台品牌已保存并应用。');
        }
        const message = app.node('toast').textContent;
        submitBranding(app, values);
        assert.equal(app.node('toast').textContent, message, 'a repeated valid submission stays honest');
        assert.deepEqual(JSON.parse(storage.getItem(brandingKey)), failBrandingSave ? savedBrand : expected);
        const reloaded = demo(edition, { storage });
        assert.equal(reloaded.node('brand-name').textContent, failBrandingSave ? savedBrand.platformName : values.platformName);
        assert.equal(reloaded.context.document.documentElement.dataset.healthTheme, theme);
        assert.equal(storage.getItem(appearanceKey), appearance);
      }
    });

    // Splitting the persistence catch must not turn validation failures into saves.
    test(`${edition}: invalid branding stays unchanged with ${failBrandingSave ? 'unavailable' : 'normal'} storage`, () => {
      const storage = memoryStorage({ failBrandingSave }), app = demo(edition, { storage });
      app.state.role = 'admin'; app.context.location.hash = '#branding'; app.view.render();
      const before = JSON.stringify(app.state), html = app.node('content').innerHTML;
      const writes = app.node('content').writes, storageWrites = storage.writes.length;
      const valid = { platformName: 'Fictional Clinic', organizationName: 'Fictional Team', backgroundText: '#eef6f2', ownershipText: 'Fictional ownership' };
      for (const invalid of [{ platformName: ' ' }, { platformName: 'x'.repeat(81) }, { organizationName: 'x'.repeat(121) }, { backgroundText: 'red' }, { ownershipText: ' ' }, { ownershipText: 'x'.repeat(241) }]) {
        submitBranding(app, { ...valid, ...invalid });
        assert.equal(app.node('toast').textContent, edition === 'en' ? 'Check the branding fields and try again.' : '请检查品牌配置后重试。');
        assert.equal(JSON.stringify(app.state), before);
        assert.equal(app.node('content').innerHTML, html);
        assert.equal(app.node('content').writes, writes);
        assert.equal(storage.writes.length, storageWrites, 'invalid values never reach persistence');
        assert.equal(storage.getItem(brandingKey), JSON.stringify(savedBrand));
        assertAppearance(app, storage);
      }
    });
  }
}
