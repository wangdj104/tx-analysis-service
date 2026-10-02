import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync, existsSync } from 'node:fs';

function contrast(foreground, background) {
  const luminance = color => color.slice(1).match(/../g).map(value => parseInt(value, 16) / 255).map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4).reduce((total, value, index) => total + value * [.2126, .7152, .0722][index], 0);
  const [a, b] = [luminance(foreground), luminance(background)];
  return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
}

// These tests catch low-contrast palette edits without freezing individual colors.
for (const directory of ['../', '../../cn/demo/']) {
  test(`${directory}: appearance palettes keep content, controls, charts and alerts readable`, () => {
    const path = new URL(directory + 'appearance.css', import.meta.url);
    assert.ok(existsSync(path), 'the demo must ship its coordinated appearance stylesheet');
    const css = readFileSync(path, 'utf8');
    const tokens = selector => Object.fromEntries([...css.match(selector)[1].matchAll(/(--[\w-]+):\s*(#[\da-f]{6})\s*;/gi)].map(([, key, value]) => [key, value]));
    const base = tokens(/:root\s*\{([^}]+)\}/);
    const footer = css.match(/(?:^|\n)\.footer\s*\{([^}]+)\}/)?.[1] || '';
    const footerSurface = footer.match(/background(?:-color)?:\s*var\((--[\w-]+)\)/)?.[1];
    for (const name of ['platform', 'white', 'blue', 'mint', 'sand', 'dark']) {
      const palette = name === 'platform' ? base : { ...base, ...tokens(new RegExp(`:root\\[data-health-theme="${name}"\\]\\s*\\{([^}]+)\\}`)) };
      // The footer must supply its own surface, rather than inheriting an arbitrary brand background.
      for (const brandBackground of ['#000000', '#ffffff', '#ff0000']) {
        const footerBackground = palette[footerSurface] || brandBackground;
        assert.ok(contrast(palette['--muted'], footerBackground) >= 4.5, `${name}: footer readable on brand ${brandBackground}`);
      }
      for (const [foreground, background, minimum] of [
        ['--ink', '--health-surface', 7], ['--muted', '--health-page', 4.5],
        ['--muted', '--health-surface', 4.5], ['--green', '--health-surface', 4.5],
        ['--health-on-accent', '--green', 4.5], ['--health-chart-primary', '--health-surface', 3],
        ['--health-chart-secondary', '--health-surface', 3], ['--health-warning', '--health-warning-bg', 4.5],
        ['--health-danger', '--health-danger-bg', 4.5], ['--health-success', '--health-success-bg', 4.5]
      ]) {
        assert.ok(palette[foreground] && palette[background], `${name}: ${foreground}/${background} defined`);
        const ratio = contrast(palette[foreground], palette[background]);
        assert.ok(ratio >= minimum, `${name}: ${foreground} on ${background} contrast ${ratio.toFixed(2)} < ${minimum}`);
      }
    }
  });
}
