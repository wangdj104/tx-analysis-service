// Optional real-browser QA. Requires installed frontend dependencies and an allowed Chromium process.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import { assertDemoLanguage } from './helpers/browserLanguage.mjs';
const root = new URL('../../', import.meta.url);
const require = createRequire(new URL('frontend/package.json', root));
const { chromium } = require('playwright');
const { expect } = require('@playwright/test');
const output = process.env.DEMO_QA_OUTPUT || '/tmp/care-execution-demo-qa';
mkdirSync(output, { recursive: true });
const browser = await chromium.launch({ ...(process.env.CHROMIUM_PATH ? {executablePath:process.env.CHROMIUM_PATH} : {}), headless: true, args: ['--no-sandbox'] });
try {
  for (const edition of ['en', 'zh']) for (const width of [1440, 390]) {
    const page = await browser.newPage({ viewport: { width, height: 900 } }), errors = [], external = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => { if (/^https?:/.test(request.url()) || ['fetch','xhr','websocket'].includes(request.resourceType())) external.push(request.url()); });
    await page.goto(new URL(edition === 'en' ? 'demo/index.html#plans' : 'cn/demo/index.html#plans', root).href);
    const click = action => page.locator(`[data-action="${action}"]`).first().click();
    const submit = async () => { await page.locator('#action-submit').click(); await page.locator('#action-dialog').waitFor({ state:'hidden' }); };
    const role = async value => { await page.locator(`[data-role="${value}"]`).click(); if (value !== 'nurse' && value !== 'admin') await page.locator(`[data-page="${value === 'doctor' ? 'plans' : 'careplan'}"]`).click(); };
    const waitForReportClosed = async () => {
      // Native dialog hiding precedes its queued close event. Await the cleanup itself.
      await expect(page.locator('#detail-dialog')).toBeHidden({ timeout: 5000 });
      await expect(page.locator('#record-detail')).toHaveText('', { timeout: 5000 });
      await expect(page.locator('#record-detail')).toHaveJSProperty('innerHTML', '', { timeout: 5000 });
      await expect(page.locator('#detail-title')).toHaveText('', { timeout: 5000 });
    };
    const close = async () => { await page.locator('#detail-dialog [data-close]').last().click(); await waitForReportClosed(); };
    const noOverflow = async () => assert.ok(await page.evaluate(() => {
      const dialog = document.getElementById('detail-dialog');
      return document.documentElement.scrollWidth <= innerWidth && dialog.scrollWidth <= dialog.clientWidth;
    }), `${edition} ${width}px overflow`);
    await click('cp-create');
    await page.locator('[name="title"]').fill('FICTIONAL REPORT BROWSER PLAN');
    await submit(); await click('cp-publish'); await page.locator('[name="confirm"]').check(); await submit();
    const originalNote='Original patient note 原始记录: metformin 500 mg';
    await role('family'); await click('cp-submit'); await page.locator('[name="note"]').fill(originalNote); await submit();
    await role('doctor'); await click('cp-return'); await page.locator('[name="note"]').fill('FICTIONAL DETAIL REQUEST'); await submit();
    await click('cp-revise'); await click('cp-edit'); await page.locator('[name="title"]').fill('PRIVATE REVISION DRAFT'); await submit();
    await click('care-report-open');
    assert.equal(await page.locator('.cp-report').count(), 1);
    assert.ok((await page.locator('[data-report-section="outstanding"]').textContent()).includes('FICTIONAL DETAIL REQUEST'));
    assert.ok(!(await page.locator('.cp-report').textContent()).includes('PRIVATE REVISION DRAFT'));
    await page.locator('[data-action="care-report-range"][data-days="0"]').click();
    assert.ok((await page.locator('[data-report-section="activity"]').textContent()).includes(edition==='en'?'Assisted record':'协助记录'));
    assert.ok((await page.locator('[data-report-section="activity"]').textContent()).includes(originalNote),'Original receipt language must be preserved');
    await assertDemoLanguage(page,edition,['FICTIONAL REPORT BROWSER PLAN','FICTIONAL DETAIL REQUEST','PRIVATE REVISION DRAFT',originalNote]);
    assert.equal(await page.locator('.cp-report [data-action^="cp-"]').count(), 0);
    await noOverflow(); await page.screenshot({ path:`${output}/${edition}-${width}-report.png`, fullPage:true });
    await page.keyboard.press('Escape'); await waitForReportClosed();
    await click('care-report-open'); assert.equal(await page.locator('[data-days="7"]').getAttribute('aria-pressed'), 'true'); await close();
    for (const value of ['patient','family','nurse']) {
      await role(value); await click('care-report-open'); await noOverflow();
      assert.ok((await page.locator('.cp-report').textContent()).includes('FICTIONAL REPORT BROWSER PLAN'));
      assert.ok(!(await page.locator('.cp-report').textContent()).includes('PRIVATE REVISION DRAFT'));
      await assertDemoLanguage(page,edition,['FICTIONAL REPORT BROWSER PLAN','FICTIONAL DETAIL REQUEST',originalNote]);
      await close();
    }
    await role('admin'); assert.equal(await page.locator('[data-action="care-report-open"]').count(), 0);
    await role('doctor'); await page.locator('#patient').selectOption('2'); await click('care-report-open');
    assert.ok(!(await page.locator('#record-detail').textContent()).includes('FICTIONAL REPORT BROWSER PLAN')); await close();
    await page.locator('#patient').selectOption('1'); await click('care-report-open');
    await page.evaluate(() => document.getElementById('reset').click());
    await waitForReportClosed();
    await page.reload(); await role('doctor'); await click('care-report-open');
    assert.ok(!(await page.locator('#record-detail').textContent()).includes('FICTIONAL REPORT BROWSER PLAN'));
    assert.deepEqual(errors, []); assert.deepEqual(external, []);
    console.log(`${edition} ${width}px: report identity/history, private draft exclusion, review state, five roles, date controls, Escape/reopen, patient switch, reset/reload, no overflow or external calls`);
    await page.close();
  }
} finally { await browser.close(); }
