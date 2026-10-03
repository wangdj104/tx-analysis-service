import test from 'node:test'
import assert from 'node:assert/strict'
import { fixture, findNode, flush, deferred } from './helpers/carePlanDoctorHarness.mjs'

const action = { id: 31, planId: 17, patientId: 1, instruction: 'Synthetic action', allowedActions: ['SUBMIT_RECEIPT'], allowedEntryModes: ['ASSISTED'] }
const walk = node => [node, ...(node.children || []).flatMap(walk)]
const ancestors = node => node ? [node, ...ancestors(node.parent)] : []
const has = (node, name) => node.props[name] != null && node.props[name] !== false
const disabled = node => ['button', 'input', 'select', 'textarea', 'fieldset'].includes(node.tag) &&
  (has(node, 'disabled') || ancestors(node.parent).some(parent => parent.tag === 'fieldset' && has(parent, 'disabled')))
const rendered = node => !ancestors(node).some(parent => has(parent, 'hidden') || parent.style.display === 'none') && !(node.tag === 'input' && node.props.type === 'hidden')
const visibility = node => ancestors(node).find(parent => parent.style.visibility)?.style.visibility || 'visible'

// The existing component renderer has no browser DOM. Supply host focus/selector
// behavior here; the actual SFC, Vue updates, command state and key handler run.
// Native Tab traversal/showModal/Escape are separately checked by the unchanged E2E.
function host(node) {
  for (const child of walk(node)) {
    if (child.focus) continue
    Object.defineProperty(child, 'tabIndex', { get() {
      if (has(this, 'tabindex')) return Number(this.props.tabindex)
      return ['button', 'input', 'select', 'textarea'].includes(this.tag) || (this.tag === 'a' && has(this, 'href')) ? 0 : -1
    } })
    child.matches = selector => selector.split(',').some(part => {
      part = part.trim()
      if (part === ':disabled') return disabled(child)
      const attribute = part.match(/^\[([^\]]+)\]$/)
      if (attribute) return has(child, attribute[1])
      const tag = part.match(/^(\w+)(?:\[([^\]]+)\])?$/)
      return !!tag && child.tag === tag[1] && (!tag[2] || has(child, tag[2]))
    })
    child.closest = selector => ancestors(child).find(parent => parent.matches?.(selector)) || null
    child.getClientRects = () => rendered(child) ? [{}] : []
    child.querySelectorAll = selector => walk(child).slice(1).filter(item => item.matches(selector))
    child.focus = () => {
      if (!disabled(child) && rendered(child) && !child.closest('[inert]') && visibility(child) === 'visible') document.activeElement = child
    }
  }
}

async function receipt(t, options = {}) {
  const previous = { document: globalThis.document, getComputedStyle: globalThis.getComputedStyle }
  const opener = { focus() { document.activeElement = opener } }
  globalThis.document = { activeElement: opener }
  globalThis.getComputedStyle = node => ({ visibility: visibility(node) })
  const view = await fixture(t, 'ReceiptDialog', { action: { ...action, ...options.action }, planVersion: 4 }, options.transport || (async () => ({ data: { id: 17, version: 5, actions: [action] } })))
  t.after(() => { globalThis.document = previous.document; globalThis.getComputedStyle = previous.getComputedStyle })
  host(view.root)
  window.getComputedStyle = globalThis.getComputedStyle
  const dialog = walk(view.root).find(node => node.tag === 'dialog')
  dialog.open = true
  view.vm.form.note = 'Synthetic focus test note'
  view.vm.form.occurredAt = new Date(Date.now() - 60000).toISOString()
  const control = id => { host(view.root); const node = findNode(view.root, id); assert.ok(node, `${id} rendered`); return node }
  const key = (key = 'Tab', options = {}) => {
    host(view.root)
    const event = new Event('keydown', { cancelable: true })
    Object.assign(event, { key, shiftKey: false, altKey: false, ctrlKey: false, metaKey: false }, options)
    dialog.props.onKeydown?.(event)
    return event
  }
  return { ...view, dialog, opener, control, key, focus(id) { control(id).focus() } }
}

test('mounted receipt wraps Close→note and Shift+Tab note→Close without intercepting interior Tab or Escape', async t => {
  const view = await receipt(t)
  view.focus('close-receipt')
  assert.equal(view.key().defaultPrevented, true)
  assert.equal(document.activeElement, view.control('receipt-note'))
  assert.equal(view.key('Tab', { shiftKey: true }).defaultPrevented, true)
  assert.equal(document.activeElement, view.control('close-receipt'))
  view.focus('receipt-note')
  assert.equal(view.key().defaultPrevented, false, 'native interior traversal remains available')
  assert.equal(view.key('Escape').defaultPrevented, false)
  assert.equal(view.key('Tab', { ctrlKey: true }).defaultPrevented, false)
  view.dialog.open = false
  assert.equal(view.key('Tab', { shiftKey: true }).defaultPrevented, false, 'closed dialogs do not capture keys')
})

test('pending command excludes disabled fieldset descendants and discovers newly rendered leave controls', async t => {
  const pending = deferred(), view = await receipt(t, { transport: () => pending.promise })
  view.vm.form.evidence.push({ sourceType: 'MEASUREMENT', sourceId: 8 })
  await flush()
  const command = view.vm.submit()
  await flush()
  const fieldset = walk(view.root).find(node => node.tag === 'fieldset')
  assert.equal(fieldset.props.disabled, true)
  assert.ok(walk(fieldset).some(node => node.tag === 'button' && !node.props.disabled), 'inherited disabled control is present')
  view.focus('close-receipt')
  assert.equal(view.key().defaultPrevented, true)
  assert.equal(document.activeElement, view.control('close-receipt'), 'Close is the only enabled control')
  await view.click('close-receipt')
  await flush()
  view.focus('keep-editing')
  assert.equal(view.key().defaultPrevented, true)
  assert.equal(document.activeElement, view.control('close-receipt'))
  assert.equal(view.key('Tab', { shiftKey: true }).defaultPrevented, true)
  assert.equal(document.activeElement, view.control('keep-editing'))
  assert.equal(view.control('confirm-abandon').props.disabled, true)
  pending.resolve({ data: { planId: 17, version: 5 } })
  await command
})

test('UNKNOWN retry and leave controls become the current boundary and disappear when editing resumes', async t => {
  const view = await receipt(t, { transport: async () => { throw new Error('Synthetic lost response') } })
  await view.vm.submit(); await flush()
  assert.equal(view.vm.state.commandPhase, 'unknown')
  view.focus('retry-original')
  assert.equal(view.key().defaultPrevented, true)
  assert.equal(document.activeElement, view.control('receipt-note'))
  assert.equal(view.key('Tab', { shiftKey: true }).defaultPrevented, true)
  assert.equal(document.activeElement, view.control('retry-original'))
  view.focus('close-receipt')
  assert.equal(view.key().defaultPrevented, false, 'Close is no longer the last control')
  await view.click('close-receipt')
  view.focus('receipt-note')
  assert.equal(view.key('Tab', { shiftKey: true }).defaultPrevented, true)
  assert.equal(document.activeElement, view.control('keep-editing'))
  await view.click('keep-editing')
  view.focus('receipt-note')
  assert.equal(view.key('Tab', { shiftKey: true }).defaultPrevented, true)
  assert.equal(document.activeElement, view.control('retry-original'))
  assert.equal(view.calls.filter(call => call.method === 'post').length, 1, 'keyboard handling sends no command')
})

test('conflict reload is included after Close and revoked state wraps its sole remaining Close', async t => {
  const view = await receipt(t, { transport: async () => { throw Object.assign(new Error('Synthetic conflict'), { response: { status: 409 } }) } })
  await view.vm.submit(); await flush()
  view.focus('reload-receipt')
  assert.equal(view.key().defaultPrevented, true)
  assert.equal(document.activeElement, view.control('receipt-note'))
  view.auth.clearAuthSession(); await flush()
  assert.equal(findNode(view.root, 'receipt-note'), undefined)
  view.focus('close-receipt')
  for (const shiftKey of [false, true]) {
    assert.equal(view.key('Tab', { shiftKey }).defaultPrevented, true)
    assert.equal(document.activeElement, view.control('close-receipt'))
  }
})

test('visible enabled boundaries skip hidden, inert, visibility-hidden and negative-tabindex controls', async t => {
  const view = await receipt(t)
  const note = view.control('receipt-note'), input = walk(view.root).find(node => node.tag === 'input'), select = walk(view.root).find(node => node.tag === 'select'), fieldset = walk(view.root).find(node => node.tag === 'fieldset')
  note.parent.props.hidden = true
  input.props.tabindex = -1
  select.style.visibility = 'hidden'
  fieldset.props.inert = true
  view.focus('close-receipt')
  assert.equal(view.key().defaultPrevented, true)
  assert.equal(document.activeElement, view.control('submit-receipt'))
  assert.equal(view.key('Tab', { shiftKey: true }).defaultPrevented, true)
  assert.equal(document.activeElement, view.control('close-receipt'))
})

test('focus on a disabled or removed control recovers inside the dialog; empty control set focuses the dialog', async t => {
  const view = await receipt(t)
  view.focus('receipt-note')
  view.control('receipt-note').props.disabled = true
  assert.equal(view.key().defaultPrevented, true)
  assert.equal(document.activeElement.tag, 'input')
  document.activeElement = { tag: 'removed' }
  assert.equal(view.key('Tab', { shiftKey: true }).defaultPrevented, true)
  assert.equal(document.activeElement, view.control('close-receipt'))
  for (const node of walk(view.dialog).filter(node => ['button', 'input', 'select', 'textarea'].includes(node.tag))) node.props.disabled = true
  assert.equal(view.key().defaultPrevented, true)
  assert.equal(document.activeElement, view.dialog)
  assert.equal(view.key('Tab', { shiftKey: true }).defaultPrevented, true)
  assert.equal(document.activeElement, view.dialog)
})

test('cancel uses the existing close action and unmount restores the opener', async t => {
  const view = await receipt(t)
  view.focus('close-receipt')
  view.key()
  const cancel = new Event('cancel', { cancelable: true })
  view.dialog.props.onCancel(cancel)
  assert.equal(cancel.defaultPrevented, true)
  assert.deepEqual(view.events, [['closed']])
  await view.unmount()
  assert.equal(document.activeElement, view.opener)
  await view.remount()
  const reopened = walk(view.root).find(node => node.tag === 'dialog')
  host(view.root); reopened.open = true
  view.control('close-receipt').focus()
  const event = new Event('keydown', { cancelable: true }); Object.assign(event, { key: 'Tab' })
  reopened.props.onKeydown?.(event)
  assert.equal(event.defaultPrevented, true)
  assert.equal(document.activeElement, view.control('receipt-note'))
})
