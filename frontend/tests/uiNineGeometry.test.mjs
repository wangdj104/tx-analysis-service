import assert from 'node:assert/strict'
import test from 'node:test'
import { assertReadableBox, assertDisjointBoxes, assertTouchTarget } from './uiNineGeometry.mjs'

const box = (overrides = {}) => ({ left: 12, right: 112, top: 12, bottom: 56, width: 100, height: 44, visible: true, text: 'Readable reference 140 mmHg', textBoxes: [{ left: 14, right: 108, top: 16, bottom: 32 }], clips: [], ...overrides })
test('readable geometry accepts measured visible wrapped content inside its viewport and clipping ancestors', () => {
  assertReadableBox(box({ textBoxes: [{ left: 14, right: 108, top: 16, bottom: 32 }, { left: 14, right: 78, top: 34, bottom: 50 }], clips: [{ left: 12, right: 112, top: 12, bottom: 56, x: true, y: true }] }), 320)
})
test('readable geometry rejects invisible, overflowed, clipped and empty text', () => {
  for (const invalid of [box({ visible: false }), box({ right: 330 }), box({ text: '' }), box({ textBoxes: [{ left: 14, right: 200, top: 16, bottom: 32 }], clips: [{ left: 12, right: 112, top: 12, bottom: 56, x: true, y: true }] }), box({ textBoxes: [{ left: 14, right: 108, top: 58, bottom: 74 }], clips: [{ left: 12, right: 112, top: 12, bottom: 56, x: true, y: true }] })]) assert.throws(() => assertReadableBox(invalid, 320))
})
test('readable geometry rejects text spilling into an adjacent control without a clipping ancestor', () => {
  assert.throws(() => assertReadableBox(box({ textBoxes: [{ left: 14, right: 130, top: 16, bottom: 32 }] }), 320))
})
test('patient notifications reject intersection with patient context, including partial overlap', () => {
  assertDisjointBoxes(box(), box({ top: 60, bottom: 104 }))
  assert.throws(() => assertDisjointBoxes(box(), box({ top: 54, bottom: 98 })))
})
test('touch controls require a real 44 by 44 pixel target', () => {
  assertTouchTarget(box())
  assert.throws(() => assertTouchTarget(box({ width: 43 })))
  assert.throws(() => assertTouchTarget(box({ height: 43 })))
})
