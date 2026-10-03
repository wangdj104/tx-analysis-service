import assert from 'node:assert/strict';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import { readFileSync } from 'node:fs';
import { computed, reactive, ref } from 'vue';

function setupManager(branding, savePlatformBranding = async () => { throw new Error('Unexpected save'); }) {
  // Exercise the existing SFC setup, supplying only UI/request imports.
  const source = readFileSync(new URL('../src/views/PlatformBrandingManager.vue', import.meta.url), 'utf8')
    .match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '');
  const bindings = { computed, reactive, ref, ...branding, ElMessage: { success() {} }, savePlatformBranding };
  return new Function(...Object.keys(bindings), source + '\nreturn { form, safeLogo, save };')(...Object.values(bindings));
}

for (const base of ['/', '/cn/']) {
  test(`built-in logo respects Vite base ${base} without changing custom branding`, async t => {
    // Load the actual utility through Vite so its build-time BASE_URL is real.
    const server = await createServer({
      configFile: false, envFile: false,
      root: fileURLToPath(new URL('..', import.meta.url)), base,
      server: { middlewareMode: true, watch: null, ws: false }, appType: 'custom',
      optimizeDeps: { noDiscovery: true, include: [] },
    });
    t.after(() => server.close());
    const branding = await server.ssrLoadModule('/src/utils/platformBranding.js');
    const icon = { href: '' };
    globalThis.document = {
      title: '', querySelector: () => icon,
      documentElement: { style: { setProperty() {}, removeProperty() {} } },
    };
    const expected = `${base}logo.svg`;

    await t.test('initial and fallback branding defaults use the mounted asset', () => {
      assert.equal(branding.DEFAULT_BRANDING.logo, expected);
      assert.equal(branding.platformBranding.logo, expected);
      branding.applyPlatformBranding();
      assert.equal(branding.platformBranding.logo, expected);
      assert.equal(icon.href, expected);
    });

    await t.test('the legacy backend default is resolved before display and favicon', () => {
      branding.applyPlatformBranding({ logo: '/logo.svg', platformName: 'Example Clinic' });
      assert.equal(branding.platformBranding.logo, expected);
      assert.equal(icon.href, expected);
      assert.equal(document.title, 'Example Clinic');
    });

    await t.test('custom root paths, subpaths, HTTPS and uploaded images stay exact', () => {
      for (const logo of ['/clinic/logo.svg', '/cn/logo.svg', '/logo.png',
        'https://clinic.example/logo.svg?revision=2', 'data:image/png;base64,aGVsbG8=']) {
        branding.applyPlatformBranding({ logo });
        assert.equal(branding.platformBranding.logo, logo);
        assert.equal(icon.href, logo);
      }
    });

    await t.test('branding preview resolves the built-in logo and invalid-input fallback', () => {
      const view = setupManager(branding);
      for (const logo of ['', 'http://unsafe.example/logo.svg', '/logo.svg']) {
        view.form.logo = logo;
        assert.equal(view.safeLogo.value, expected);
        assert.equal(view.form.logo, logo, 'preview must not replace entered or saved branding');
      }
      for (const logo of ['/clinic/logo.svg', '/cn/logo.svg', 'https://clinic.example/logo.svg', 'data:image/png;base64,aGVsbG8=']) {
        view.form.logo = logo;
        assert.equal(view.safeLogo.value, logo);
      }
    });

    await t.test('editing another field saves the canonical built-in logo across editions', async () => {
      branding.applyPlatformBranding({ logo: '/logo.svg' });
      const requests = [];
      const view = setupManager(branding, async form => {
        const saved = { ...form }; requests.push(saved);
        return { data: saved };
      });
      view.form.organizationName = 'Updated Organization';
      await view.save();
      assert.equal(requests[0].organizationName, 'Updated Organization');
      assert.equal(requests[0].logo, '/logo.svg');
      assert.equal(view.form.logo, '/logo.svg');
      assert.equal(branding.platformBranding.logo, expected);
      view.form.pageBackground = '#123456';
      await view.save();
      assert.equal(requests[1].logo, '/logo.svg');
      assert.equal(requests[1].pageBackground, '#123456');
    });

    await t.test('deliberate custom paths and uploaded logos are saved exactly', async () => {
      for (const configured of ['/logo.svg', '/cn/logo.svg', '/clinic/logo.svg', '/logo.svg?tenant=1',
        'https://clinic.example/logo.svg?revision=2', 'data:image/png;base64,aGVsbG8=']) {
        branding.applyPlatformBranding({ logo: configured });
        let saved;
        const view = setupManager(branding, async form => {
          saved = { ...form }; return { data: saved };
        });
        view.form.organizationName = 'Custom Organization';
        if (configured === '/logo.svg') view.form.logo = '/cn/logo.svg';
        await view.save();
        const expectedCustom = configured === '/logo.svg' ? '/cn/logo.svg' : configured;
        assert.equal(saved.logo, expectedCustom);
        assert.equal(view.form.logo, expectedCustom);
        assert.equal(branding.platformBranding.logo, expectedCustom);
      }
    });
  });
}
