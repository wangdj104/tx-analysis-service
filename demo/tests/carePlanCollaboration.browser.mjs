// Optional actual-browser QA; uses installed Playwright/Chromium, no backend or real data.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import { assertDemoLanguage, auditDemoRoutes } from './helpers/browserLanguage.mjs';
const root = new URL('../../', import.meta.url);
const require = createRequire(new URL('frontend/package.json', root));
const { chromium } = require('playwright');
const output = process.env.DEMO_QA_OUTPUT || '/tmp/care-plan-demo-qa';
mkdirSync(output, { recursive: true });
const browser = await chromium.launch({ ...(process.env.CHROMIUM_PATH ? {executablePath:process.env.CHROMIUM_PATH} : {}), headless: true, args: ['--no-sandbox'] });
try {
  for (const edition of ['en','zh']) {
    for (const width of [1440,390]) {
      const page = await browser.newPage({ viewport:{ width, height:900 } }), errors=[], networkCalls=[];
      page.on('pageerror', error => errors.push(error.message));
      page.on('request', request => { if (['fetch','xhr','websocket'].includes(request.resourceType()) || /^https?:/.test(request.url())) networkCalls.push(request.url()); });
      const file = new URL(edition==='en' ? 'demo/index.html' : 'cn/demo/index.html', root);
      await page.goto(file.href+'#plans');
      await auditDemoRoutes(page, edition);
      const click = async (action,id) => page.locator(`[data-action="${action}"]${id?`[data-id="${id}"]`:''}`).first().click();
      const submit = async () => { await page.locator('#action-submit').click(); await page.locator('#action-dialog').waitFor({state:'hidden'}); };
      const switchRole = async role => { await page.locator(`[data-role="${role}"]`).click(); };
      const planPage = async () => { await page.locator(`[data-page="${await page.locator('[data-role="doctor"]').getAttribute('aria-pressed')==='true'?'plans':'careplan'}"]`).click(); };
      const noOverflow = async () => assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth), `${edition} ${width}px has horizontal overflow`);
      await noOverflow(); await click('cp-create'); await assertDemoLanguage(page,edition);
      await page.locator('[name="title"]').fill(edition==='en'?'Fictional browser QA plan':'虚构浏览器验收计划');
      await submit();
      const planId=await page.locator('[data-plan-id]').first().getAttribute('data-plan-id');
      await switchRole('family');await planPage();
      assert.equal(await page.locator('[data-plan-id]').count(),0,'Family must not see draft');
      await switchRole('doctor');await planPage();await click('cp-publish',planId);await page.locator('[name="confirm"]').check();await submit();
      const ids=await page.locator('.cp-plan > .cp-action [data-action="cp-followup"]').evaluateAll(items=>items.map(item=>item.dataset.id));
      assert.equal(ids.length,2);
      const originalNote='Original patient note 原始记录: metformin 500 mg';
      await switchRole('patient');await planPage();await click('cp-submit',ids[0]);await assertDemoLanguage(page,edition);await page.locator('[name="note"]').fill(originalNote);await page.locator('[name="entryMode"]').selectOption('SELF');await submit();
      assert.equal(await page.locator(`.cp-plan > .cp-action[data-care-status="SUBMITTED"]`).count(),1);
      await switchRole('family');await planPage();await click('cp-submit',ids[1]);await submit();
      await switchRole('nurse');assert.equal(await page.locator('#nav a').count(),1);assert.equal(await page.locator('[data-action="cp-confirm"]').count(),0);
      await click('cp-followup',ids[0]);await page.locator('[name="kind"]').selectOption('DOCTOR_NOTIFIED');await submit();
      await assertDemoLanguage(page,edition,[originalNote]);
      assert.ok((await page.locator('.cp-plan').textContent()).includes(originalNote),'Receipt source text must remain verbatim');
      await page.screenshot({path:`${output}/${edition}-${width}-nursing.png`,fullPage:true});await noOverflow();
      await switchRole('doctor');await planPage();await click('cp-return',ids[0]);await submit();
      await switchRole('patient');await planPage();await click('cp-submit',ids[0]);await submit();
      await switchRole('doctor');await planPage();
      for(const id of ids){await click('cp-confirm',id);await page.locator('[name="confirm"]').check();await submit();}
      await click('cp-revise',planId);await click('cp-edit',planId);await page.locator('[name="instruction1"]').fill(edition==='en'?'Fictional revised checklist':'虚构修订清单');await submit();
      await click('cp-publish',planId);assert.ok((await page.locator('#action-fields').textContent()).includes(edition==='en'?'Replaced by a newer revision':'已被新版本替代'));await assertDemoLanguage(page,edition,[originalNote]);await page.locator('[name="confirm"]').check();await submit();
      const newIds=await page.locator('.cp-plan > .cp-action [data-action="cp-followup"]').evaluateAll(items=>items.map(item=>item.dataset.id));
      assert.equal(newIds.length,2);assert.ok(newIds.every(id=>!ids.includes(id)));
      for(const id of newIds){await switchRole('family');await planPage();await click('cp-submit',id);await submit();await switchRole('doctor');await planPage();await click('cp-confirm',id);await page.locator('[name="confirm"]').check();await submit();}
      await click('cp-close',planId);await page.locator('[name="confirm"]').check();await submit();
      assert.ok((await page.locator('.cp-plan').textContent()).includes(edition==='en'?'Closed by doctor':'医生已关闭'));
      await assertDemoLanguage(page,edition,[originalNote]);
      await page.locator('.cp-history > summary').click();await noOverflow();
      await page.screenshot({path:`${output}/${edition}-${width}-completed.png`,fullPage:true});
      assert.equal(networkCalls.length,0,'No external/API network calls');assert.deepEqual(errors,[]);
      await page.locator('#reset').click();assert.equal(await page.locator('[data-plan-id]').count(),0);assert.equal(await page.locator('#action-fields').textContent(),'');
      console.log(`${edition} ${width}px: draft privacy → publish → personal/family receipt → nurse follow-up → return/resubmit → confirm → revision/history → close → reset; no page errors, overflow or network calls`);
      await page.close();
    }
  }
} finally { await browser.close(); }
