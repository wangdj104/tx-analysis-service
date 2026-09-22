import test from 'node:test'
import assert from 'node:assert/strict'
import { dedupeRecognizedItems } from '../src/utils/medicalRecordItems.js'

test('different lab tests are preserved even when values, units and ranges match', () => {
  const items = ['Serum calcium', 'Serum phosphorus'].map(itemName => ({ itemName, resultValue: '1.20', unit: 'mmol/L' }))
  assert.equal(dedupeRecognizedItems(items).length, 2)
})

test('only complete duplicate results are merged; conflicting values and abnormal flags remain', () => {
  const original = { itemName: 'Creatinine', resultValue: '120', unit: 'umol/L', isAbnormal: 0 }
  const items = [original, { ...original, itemName: ' creatinine ' }, { ...original, resultValue: '121' }, { ...original, isAbnormal: 1 }]
  assert.equal(dedupeRecognizedItems(items).length, 3)
  assert.notEqual(dedupeRecognizedItems(items)[0], original)
})

test('incomplete or unnamed OCR items are not silently discarded', () => {
  assert.equal(dedupeRecognizedItems([{ resultValue: '1' }, { resultValue: '1' }, { itemName: 'Unknown' }, { itemName: 'Unknown' }]).length, 4)
})
