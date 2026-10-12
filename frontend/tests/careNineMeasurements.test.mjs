import assert from 'node:assert/strict'
import test from 'node:test'
import { contrastRatio, assertTaskHierarchy, assertTaskDensity, assertMedicationSemantics, assertReceiptHierarchy, assertSubmittingState } from './careNineMeasurements.mjs'

// These are tests of the acceptance guards, never browser matrix evidence.
const box = { width: 120, height: 44, left: 12, right: 132, top: 10, bottom: 54, visible: true }
const action = () => ({ id: 'plan-action-702', titleOccurrences: 1, statusOccurrences: 1, borderedAncestorDepth: 2, bounds: box })
const status = (name, color, background, icon) => ({ status: name, label: name, color, background, icon, bounds: box, semanticTokenColor: color, contrast: contrastRatio(color, background) })
const medication = () => [status('PENDING', 'rgb(69, 100, 119)', 'rgb(237, 243, 247)', 'clock'), status('MISSED', 'rgb(173, 60, 54)', 'rgb(255, 240, 239)', 'warning'), status('SNOOZED', 'rgb(140, 87, 23)', 'rgb(255, 246, 231)', 'bell')]
const control = (id, role, background = 'rgb(255, 255, 255)') => ({ testId: id, role, bounds: box, background, color: 'rgb(0, 0, 0)', border: '1px solid rgb(23, 107, 89)', disabled: false, opacity: '1', text: id })
const receipt = () => ({ surface: 'rgb(255, 255, 255)', controls: [control('submit-receipt', 'primary', 'rgb(23, 107, 89)'), control('close-receipt', 'secondary'), control('', 'remove', 'rgba(0, 0, 0, 0)')], referenceControls: [{ describedBy: 'ref-701', referenceId: 'ref-701', referenceText: 'Synthetic 120/80 mmHg', sameRow: true, bounds: box }] })

test('contrast is computed from actual sRGB foreground and opaque surface, without rounding a failing 4.49 up', () => {
  assert.equal(contrastRatio('rgb(0, 0, 0)', 'rgb(255, 255, 255)'), 21)
  assert.ok(contrastRatio('rgb(119, 119, 119)', 'rgb(255, 255, 255)') < 4.5)
  assert.throws(() => contrastRatio('rgba(0, 0, 0, 0.5)', 'rgb(255, 255, 255)'), /opaque/)
})
test('task guard rejects duplicate visible title/status and nested third border', () => {
  assertTaskHierarchy([action()], 320)
  for (const bad of [{ titleOccurrences: 2 }, { statusOccurrences: 2 }, { borderedAncestorDepth: 3 }, { bounds: { ...box, right: 330 } }]) assert.throws(() => assertTaskHierarchy([{ ...action(), ...bad }], 320))
})
test('density guard requires actual matched-width baseline and at least30percent height reduction', () => {
  const density = { baselineHeights: [200, 100], currentHeights: [140, 70], baselineContainerWidth: 300, currentContainerWidth: 300, baselineSourceSha256: 'a'.repeat(64), baselineCommit: 'c'.repeat(40) }
  assert.ok(Math.abs(assertTaskDensity(density) - 0.3) < 1e-9)
  for (const bad of [{ currentHeights: [190, 100] }, { baselineHeights: [] }, { baselineContainerWidth: 320 }, { baselineSourceSha256: '' }]) assert.throws(() => assertTaskDensity({ ...density, ...bad }))
})
test('medication gate rejects same icons/colors, missing text and insufficient actual contrast', () => {
  assertMedicationSemantics(medication())
  for (const mutate of [rows => { rows[1].icon = rows[0].icon }, rows => { rows[1].color = rows[0].color }, rows => { rows[1].label = '' }, rows => { rows[0].contrast = 4.49 }, rows => { rows[1].semanticTokenColor = 'rgb(1, 2, 3)' }]) { const rows = medication(); mutate(rows); assert.throws(() => assertMedicationSemantics(rows)) }
})
test('receipt gate requires one filled primary, secondary close and local lightweight44px removals', () => {
  assertReceiptHierarchy(receipt())
  for (const mutate of [row => { row.controls[1].background = row.controls[0].background }, row => { row.controls[0].background = row.surface }, row => { row.referenceControls[0].sameRow = false }, row => { row.referenceControls[0].describedBy = '' }, row => { row.controls[2].bounds = { ...box, height: 43 } }]) { const row = receipt(); mutate(row); assert.throws(() => assertReceiptHierarchy(row)) }
})
test('submitting guard requires disabled busy state plus changed visible wording and appearance', () => {
  const enabled = { ...control('submit-receipt', 'primary'), ariaBusy: 'false' }
  const pending = { ...enabled, disabled: true, ariaBusy: 'true', text: 'Submitting…', opacity: '0.5' }
  assertSubmittingState(enabled, pending)
  for (const bad of [{ disabled: false }, { ariaBusy: 'false' }, { text: enabled.text }, { opacity: '1' }]) assert.throws(() => assertSubmittingState(enabled, { ...pending, ...bad }))
})

test('real measured task targets cannot be compressed below44px to achieve density', () => {
  assert.throws(() => assertTaskHierarchy([{ ...action(), touchTargets: [{ ...box, height: 43 }] }], 320), /44px/)
})
test('a remove action must sit within its own measured row and not cover reference text', () => {
  const measurement = receipt()
  measurement.referenceControls[0].textBounds = { ...box }
  measurement.referenceControls[0].rowBounds = { ...box }
  assert.throws(() => assertReceiptHierarchy(measurement), /overlap|row/)
})
