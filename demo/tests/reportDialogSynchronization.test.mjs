import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('browser report dismissal waits for queued cleanup after Escape, button close, and reset', () => {
  const source = readFileSync(new URL('./executionReport.browser.mjs', import.meta.url), 'utf8');
  const helper = source.match(/const waitForReportClosed = async \(\) => \{([\s\S]*?)\n    \};/)?.[1];
  assert.ok(helper, 'Share the dismissal postconditions instead of sampling text immediately after hidden');
  assert.match(helper, /await expect\(page\.locator\('#detail-dialog'\)\)\.toBeHidden\(\{ timeout: 5000 \}\)/);
  for (const id of ['record-detail', 'detail-title']) {
    assert.ok(helper.includes(`await expect(page.locator('#${id}')).toHaveText('', { timeout: 5000 })`), `${id} must actually clear within a bounded assertion`);
  }
  assert.ok(helper.includes("await expect(page.locator('#record-detail')).toHaveJSProperty('innerHTML', '', { timeout: 5000 })"), 'Hidden report markup must also be removed');
  assert.match(source, /const close = async.*click\(\); await waitForReportClosed\(\);/);
  assert.match(source, /await page\.keyboard\.press\('Escape'\); await waitForReportClosed\(\);/);
  assert.match(source, /document\.getElementById\('reset'\)\.click\(\)\);\s*await waitForReportClosed\(\);/);
  assert.doesNotMatch(source, /waitForTimeout|setTimeout/, 'Wait for state, not an arbitrary sleep');
});
