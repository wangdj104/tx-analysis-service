import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

for (const edition of ['en', 'zh']) {
  const base = new URL(edition === 'en' ? '../' : '../../cn/demo/', import.meta.url);
  const css = readFileSync(new URL('style.css', base), 'utf8');
  const branding = readFileSync(new URL('branding.css', base), 'utf8');

  test(`${edition}: consultation long text wraps inside its bubble`, () => {
    assert.match(css, /\.chat-message p\s*\{[^}]*overflow-wrap:\s*anywhere/s);
  });

  test(`${edition}: large-text mode also enlarges consultation text`, () => {
    assert.match(css, /body\.large-text[^{}]*\.chat-message p[^{}]*\{[^}]*font-size:\s*18px/s);
    assert.match(css, /body\.large-text[^{}]*\.participant-list span[^{}]*\{[^}]*font-size:\s*18px/s);
  });

  test(`${edition}: guide stays above its highlighted target and scrolls on short screens`, () => {
    assert.match(branding, /\.guide-tour\s*\{[^}]*z-index:\s*102/s);
    assert.match(branding, /@media \(max-width: 720px\)\s*\{\s*\.guide-popover\s*\{[^}]*overflow-y:\s*auto/s);
  });

  test(`${edition}: phone layout retains the existing guide entry`, () => {
    const mobile = css.slice(css.lastIndexOf('@media (max-width: 720px)'));
    assert.match(mobile, /\.sidebar-bottom\s*\{[^}]*display:\s*block/s);
    assert.match(mobile, /\.sidebar-bottom\s*>\s*p,\s*\.sidebar-bottom\s+\.demo-account\s*\{[^}]*display:\s*none/s);
  });
}

test('both demo editions retain identical responsive styling', () => {
  assert.equal(readFileSync(new URL('../style.css', import.meta.url), 'utf8'), readFileSync(new URL('../../cn/demo/style.css', import.meta.url), 'utf8'));
});
