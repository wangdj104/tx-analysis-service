import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { reactive, ref, watch, effectScope, nextTick } from 'vue'
function setup(t, row) {
  const source = fs.readFileSync(new URL('../src/components/CareEntryDialog.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import.*$/gm, '')
  const props = reactive({ modelValue: false, kind: 'ORDER', row }), scope = effectScope()
  const deps = { reactive, ref, watch, defineProps: () => props, defineEmits: () => () => {}, localDateKey: () => '2026-10-10' }
  const view = scope.run(() => new Function(...Object.keys(deps), script + '\nreturn {form}')(...Object.values(deps)))
  t.after(() => scope.stop()); return { ...view, props }
}
test('new Chinese medication orders default to a Chinese tablet unit', async t => {
  const view = setup(t); view.props.modelValue = true; await nextTick()
  assert.equal(view.form.details.unit, '片')
})
test('existing medication units are preserved exactly when reopening', async t => {
  const view = setup(t, { kind: 'ORDER', details: { unit: 'tablet', doses: [{ time: '08:00', quantity: 1 }] } })
  view.props.modelValue = true; await nextTick()
  assert.equal(view.form.details.unit, 'tablet')
})
