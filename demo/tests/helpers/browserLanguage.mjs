import assert from 'node:assert/strict';

const protocolLabels = /\b(?:CARE_PLAN|DRAFT_SAVED|REVISION_DRAFTED|DRAFT|ACTIVE|COMPLETED|CANCELLED|OPEN|NEEDS_HELP|SUBMITTED|CONFIRMED|SUPERSEDED|PUBLISHED|REVISED|FOLLOW_UP|RETURNED|CLOSED|SELF|ASSISTED)\b/;
const technicalWords = new Set(['AI','OCR','PDF','HTML','CSV','PHQ','GAD','WHO','ISO','UTC','Unicode','mmHg','kg','ml','g','L','MB','A','B']);

export async function assertDemoLanguage(page, edition, sourceText = []) {
  let text = await page.locator('#content, #nav, #role-switch, dialog[open]').evaluateAll(nodes => nodes.map(node => [
    node.textContent,
    ...[...node.querySelectorAll('[aria-label], [placeholder], [title]')].flatMap(element => ['aria-label','placeholder','title'].map(attribute => element.getAttribute(attribute) || ''))
  ].join(' ')).join(' '));
  // Patient-entered text is literal source data, regardless of the surrounding UI locale.
  for (const value of sourceText) text = text.split(value).join('');
  assert.doesNotMatch(text.replaceAll('AI-ASSISTED', 'AI-assisted'), protocolLabels, `${edition}: raw protocol code in rendered UI`);
  if (edition === 'en') assert.doesNotMatch(text, /[\p{Script=Han}]/u, 'English UI contains Chinese system text');
  else {
    text = text.replaceAll('/care-plans/reports', '');
    const unexpected = [...new Set(text.match(/[A-Za-z_]+/g) || [])].filter(word => !technicalWords.has(word));
    assert.deepEqual(unexpected, [], `Chinese UI contains untranslated system text: ${unexpected.join(', ')}`);
  }
}

export async function auditDemoRoutes(page, edition) {
  for (const role of ['doctor','patient','family','nurse','admin']) {
    await page.locator(`[data-role="${role}"]`).click();
    const routes = await page.locator('#nav [data-page]').evaluateAll(nodes => nodes.map(node => node.dataset.page));
    for (const route of routes) {
      await page.locator(`[data-page="${route}"]`).click();
      await assertDemoLanguage(page, edition);
    }
  }
  await page.locator('[data-role="doctor"]').click();
  await page.locator('[data-page="plans"]').click();
}
