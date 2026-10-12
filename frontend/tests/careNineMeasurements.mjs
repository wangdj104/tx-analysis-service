import assert from 'node:assert/strict'

// Guard tests use fabricated measurements, but browser reports only call these
// guards with actual DOM reads. No unit-test fixture can mark a browser row passed.
function rgb(value) {
  if (/^#[\da-f]{6}$/i.test(value)) return [1, 3, 5].map(i => parseInt(value.slice(i, i + 2), 16))
  const match = String(value).match(/^rgba?\(([^)]+)\)$/)
  assert.ok(match, `Unsupported measured color: ${value}`)
  const channels = match[1].split(/[,\s/]+/).filter(Boolean).map(Number)
  assert.ok(channels.length === 3 || channels[3] === 1, 'Contrast requires an actual opaque composited surface')
  return channels.slice(0, 3)
}
export function contrastRatio(foreground, background) {
  const luminance = color => rgb(color).map(channel => channel / 255).map(channel => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4).reduce((total, channel, i) => total + channel * [0.2126, 0.7152, 0.0722][i], 0)
  const a = luminance(foreground), b = luminance(background)
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
}
function touch(bounds, label) { assert.ok(bounds?.visible && bounds.width >= 44 && bounds.height >= 44, `${label} has an actual 44px target`) }
export function assertTaskHierarchy(actions, viewport) {
  assert.ok(actions.length, 'At least one real task is measured')
  for (const action of actions) {
    assert.equal(action.titleOccurrences, 1, `${action.id}: task title appears exactly once in rendered text`)
    assert.equal(action.statusOccurrences, 1, `${action.id}: status appears exactly once in rendered text`)
    assert.ok(action.borderedAncestorDepth <= 2, `${action.id}: no more than two nested bordered containers`)
    for (const target of action.touchTargets || []) touch(target, `${action.id} control`)
    assert.ok(action.bounds?.visible && action.bounds.height > 0 && action.bounds.left >= -1 && action.bounds.right <= viewport + 1, `${action.id}: task fits the real viewport`)
  }
}
export function assertTaskDensity(density) {
  assert.match(density.baselineSourceSha256, /^[\da-f]{64}$/i, 'Baseline provenance is required')
  assert.match(density.baselineCommit, /^[\da-f]{40}$/i, 'Frozen baseline commit is required')
  assert.ok(Math.abs(density.baselineContainerWidth - density.currentContainerWidth) <= 1, 'Baseline and current rendered container widths match')
  assert.ok(density.baselineHeights.length > 0 && density.currentHeights.length === density.baselineHeights.length, 'Same task fixture is measured in both layouts')
  assert.ok([...density.baselineHeights, ...density.currentHeights].every(value => Number.isFinite(value) && value > 0), 'Density uses positive actual DOM heights')
  const before = density.baselineHeights.reduce((a, b) => a + b, 0), after = density.currentHeights.reduce((a, b) => a + b, 0)
  const reduction = 1 - after / before
  assert.ok(reduction >= 0.30 - 1e-9, `Task height reduction ${(reduction * 100).toFixed(2)}% must reach 30% without changing fixture or width`)
  return reduction
}
export function assertMedicationSemantics(statuses) {
  assert.deepEqual(statuses.map(row => row.status).sort(), ['MISSED', 'PENDING', 'SNOOZED'])
  assert.equal(new Set(statuses.map(row => row.icon)).size, 3, 'Pending/missed/snoozed use three distinct rendered icons')
  assert.equal(new Set(statuses.map(row => row.color)).size, 3, 'Pending/missed/snoozed use three distinct semantic colors')
  for (const row of statuses) {
    assert.ok(row.label?.trim() && row.icon?.trim() && row.bounds?.visible, `${row.status} retains visible text and icon`)
    assert.deepEqual(rgb(row.color), rgb(row.semanticTokenColor), `${row.status} uses its stable semantic color token`)
    assert.ok(Number.isFinite(row.contrast) && row.contrast >= 4.5, `${row.status} text contrast ${row.contrast} reaches WCAG AA 4.5:1`)
    assert.ok(Math.abs(row.contrast - contrastRatio(row.color, row.background)) < 1e-9, 'Contrast is calculated from this rendered foreground/background pair')
  }
}
function unfilled(color, surface) { return color === surface || color === 'rgba(0, 0, 0, 0)' || color === 'transparent' }
export function assertReceiptHierarchy(measurement) {
  const primary = measurement.controls.filter(row => !row.disabled && !unfilled(row.background, measurement.surface))
  assert.equal(primary.length, 1, 'Exactly one action has a filled background')
  assert.equal(primary[0].testId, 'submit-receipt', 'Only Submit is a filled primary action')
  const close = measurement.controls.find(row => row.testId === 'close-receipt')
  assert.ok(close && unfilled(close.background, measurement.surface), 'Close is a secondary control')
  for (const row of measurement.controls) touch(row.bounds, row.testId || row.role)
  assert.ok(measurement.referenceControls.length > 0, 'Actual selected references are measured')
  const removals = measurement.controls.filter(row => row.role === 'remove')
  assert.equal(removals.length, measurement.referenceControls.length, 'Each reference has one local remove action')
  assert.ok(removals.every(row => unfilled(row.background, measurement.surface)), 'Remove actions remain lightweight')
  for (const row of measurement.referenceControls) {
    assert.ok(row.referenceText?.trim() && row.referenceId && row.describedBy?.split(/\s+/).includes(row.referenceId) && row.sameRow, 'Each remove control is associated with its own reference description')
    touch(row.bounds, 'Remove reference')
    if (row.rowBounds && row.textBounds) {
      const box = row.bounds, text = row.textBounds, parent = row.rowBounds
      for (const child of [box, text]) assert.ok(child.left >= parent.left - 1 && child.right <= parent.right + 1 && child.top >= parent.top - 1 && child.bottom <= parent.bottom + 1, 'Reference text and remove control stay within the same visual row')
      assert.ok(box.right <= text.left + 1 || text.right <= box.left + 1 || box.bottom <= text.top + 1 || text.bottom <= box.top + 1, 'Remove control must not overlap reference text')
    }
  }
}
export function assertSubmittingState(enabled, pending) {
  assert.equal(enabled.disabled, false, 'Ready submit is enabled')
  assert.equal(pending.disabled, true, 'Submitting action is disabled')
  assert.equal(pending.ariaBusy, 'true', 'Submitting exposes a busy state')
  assert.notEqual(pending.text, enabled.text, 'Submitting has distinct visible text')
  assert.ok(pending.opacity !== enabled.opacity || pending.background !== enabled.background || pending.color !== enabled.color, 'Disabled submitting action is visually distinguishable')
  touch(pending.bounds, 'Submitting action')
}

// Standalone observation functions passed directly into Playwright evaluate.
export function measureTaskGroups(nodes) {
  const visible = node => { const css = getComputedStyle(node), box = node.getBoundingClientRect(); return box.width > 0 && box.height > 0 && css.visibility !== 'hidden' && css.display !== 'none' }
  return nodes.map(node => {
    const bounds = node.getBoundingClientRect(), title = node.querySelector('.action-summary__title')?.innerText.trim(), status = node.querySelector('.action-summary__status')?.innerText.trim()
    const text = node.innerText, borders = []
    for (let parent = node.querySelector('.action-card') || node; parent && !parent.classList.contains('plan-tasks'); parent = parent.parentElement) {
      const css = getComputedStyle(parent), sides = ['Top', 'Right', 'Bottom', 'Left'].filter(side => parseFloat(css[`border${side}Width`]) > 0 && !['none', 'hidden'].includes(css[`border${side}Style`]) && css[`border${side}Color`] !== 'rgba(0, 0, 0, 0)')
      if (visible(parent) && sides.length >= 3) borders.push({ className: parent.className, sides, border: css.border })
    }
    const touchTargets = [...node.querySelectorAll(':scope > summary, .action-event-history > summary, .controls button')].filter(visible).map(target => { const box = target.getBoundingClientRect(); return { width: box.width, height: box.height, visible: true } })
    return { id: node.dataset.testid, title, status, text, touchTargets, titleOccurrences: title ? text.split(title).length - 1 : 0, statusOccurrences: status ? text.split(status).length - 1 : 0, borderedAncestorDepth: borders.length, borders, bounds: { left: bounds.left, right: bounds.right, top: bounds.top, bottom: bounds.bottom, width: bounds.width, height: bounds.height, visible: visible(node) } }
  })
}
export function measureMedicationStatuses(nodes) {
  const token = { PENDING: '--info', MISSED: '--danger', SNOOZED: '--warning' }
  return nodes.map(node => {
    const css = getComputedStyle(node), bounds = node.getBoundingClientRect(), svg = node.querySelector('svg'), iconBounds = svg?.getBoundingClientRect()
    let surface = node, background = css.backgroundColor
    while (surface.parentElement && (background === 'rgba(0, 0, 0, 0)' || background === 'transparent')) { surface = surface.parentElement; background = getComputedStyle(surface).backgroundColor }
    return { status: node.dataset.medicationStatus, label: node.innerText.trim(), icon: iconBounds?.width > 0 && iconBounds?.height > 0 ? svg.innerHTML : '', color: css.color, background, semanticTokenColor: getComputedStyle(document.documentElement).getPropertyValue(token[node.dataset.medicationStatus]).trim(), bounds: { left: bounds.left, right: bounds.right, width: bounds.width, height: bounds.height, visible: bounds.width > 0 && bounds.height > 0 && css.visibility !== 'hidden' } }
  })
}
export function measureReceiptControls(dialog) {
  const rect = node => { const box = node.getBoundingClientRect(); return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height } }
  const measure = node => {
    const css = getComputedStyle(node), box = node.getBoundingClientRect()
    return { testId: node.dataset.testid || '', role: node.matches('.remove-reference') ? 'remove' : node.type === 'submit' ? 'primary' : 'secondary', text: node.innerText.trim(), bounds: { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height, visible: box.width > 0 && box.height > 0 && css.visibility !== 'hidden' }, background: css.backgroundColor, color: css.color, border: css.border, disabled: node.matches(':disabled'), opacity: css.opacity, ariaBusy: node.getAttribute('aria-busy') }
  }
  const controls = [...dialog.querySelectorAll('button')].filter(node => node.getClientRects().length).map(measure)
  const referenceControls = [...dialog.querySelectorAll('.evidence-row')].map(row => {
    const button = row.querySelector('.remove-reference'), text = row.querySelector('p'), describedBy = button?.getAttribute('aria-describedby') || ''
    return { textBounds: text ? rect(text) : null, rowBounds: rect(row), referenceId: text?.id, referenceText: text?.innerText, describedBy, sameRow: Boolean(text?.id && describedBy.split(/\s+/).map(id => document.getElementById(id)).includes(text) && row.contains(button)), bounds: button ? measure(button).bounds : null }
  })
  return { surface: getComputedStyle(dialog).backgroundColor, controls, referenceControls }
}
