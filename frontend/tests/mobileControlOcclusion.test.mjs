import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'
import { parse, compileStyle } from '@vue/compiler-sfc'
import { baseParse } from '@vue/compiler-dom'
import postcss from 'postcss'

const read = (edition, file) => readFileSync(new URL(`${edition === 'en' ? '../' : '../../cn/frontend/'}src/${file}`, import.meta.url), 'utf8')
function shell(edition, t) {
  const source = read(edition, 'App.vue'), calls = [], queued = [], scope = effectScope()
  const deps = { ref, computed, watch, nextTick: fn => queued.push(fn), searchQuery: ref('old'), searchVisible: ref(false), searchResults: ref([{ path: '/one' }]),
    currentPatientId: ref(11), appPatientList: ref([{ id: 22, patientName: 'Synthetic Long Name' }]), patientList: ref([]),
    window: new EventTarget(), Event, document: { getElementById: () => null, querySelector: () => ({ getBoundingClientRect: () => ({ bottom: 133 }) }) },
    route: { path: '/monitoring', fullPath: '/monitoring' }, router: { push() {}, replace() {} }, routerViewKey: ref('test'), getAuthSessionKey: () => 'test',
    parseReportRoute: () => false, isNarrowCareRoute: () => false,
    ElMessage: Object.fromEntries(['success', 'info'].map(type => [type, options => { const call = { type, options, closed: false }; calls.push(call); return { close() { call.closed = true } } }])) }
  const search = source.slice(source.indexOf('const activeSearchIndex'), source.indexOf('function onSearchShortcut'))
  const notices = source.slice(source.indexOf('// switchPatient'), source.indexOf('// Sign Out', source.indexOf('// switchPatient')))
  const view = scope.run(() => new Function(...Object.keys(deps), `${search}\n${notices}\nreturn {openSearch,switchPatient}`)(...Object.values(deps)))
  t.after(() => scope.stop())
  return { ...view, ...deps, calls, flush: () => { while (queued.length) queued.shift()() } }
}
for (const edition of ['en', 'cn']) {
  test(`${edition}: feature search dismisses the active patient notice without losing current record`, t => {
    const v = shell(edition, t); v.switchPatient(22); v.flush(); v.openSearch()
    assert.equal(v.calls[0].closed, true, 'the transient notice must not cover the search input or upper results')
    assert.equal(v.currentPatientId.value, 22); assert.equal(v.searchVisible.value, true); assert.equal(v.searchQuery.value, '')
  })
  test(`${edition}: opening search invalidates a queued patient notice even after search closes`, t => {
    const v = shell(edition, t); v.switchPatient(22); v.openSearch(); v.searchVisible.value = false; v.flush()
    assert.equal(v.calls.length, 0, 'the nextTick notice must not reappear after cancellation')
  })
  test(`${edition}: selection while search is already open keeps feedback in the persistent patient context`, t => {
    const v = shell(edition, t); v.openSearch(); v.switchPatient(22); v.flush()
    assert.equal(v.calls.length, 0); assert.equal(v.currentPatientId.value, 22)
    v.searchVisible.value = false; v.switchPatient(11); v.flush(); assert.equal(v.calls.length, 1, 'normal patient feedback resumes outside search')
  })

  test(`${edition}: mobile guide reserves its measured height and scrolls the target action clear of the card`, () => {
    const source = read(edition, 'components/OnboardingGuide.vue')
    const code = source.slice(source.indexOf('function positionGuide('), source.indexOf('function canReceiveFocus'))
    for (const [height, cardHeight, toolbarBottom] of [[900, 364, 133], [450, 225, 133], [320, 160, 133]]) {
      const action = { top: 507, bottom: 551, height: 44 }, mainStyle = new Map([['padding-bottom', '76px']]), shifts = []
      const main = { style: { getPropertyValue: key => mainStyle.get(key) || '', getPropertyPriority: () => '', setProperty: (key, value) => mainStyle.set(key, value), removeProperty: key => mainStyle.delete(key) } }
      const target = { getBoundingClientRect: () => ({ ...action, left: 14, width: 146 }), querySelector: () => null, closest: () => null }
      const card = { getBoundingClientRect: () => ({ height: cardHeight, top: height - cardHeight - 12, bottom: height - 12 }) }
      const deps = { viewport: reactive({ width: 320, height }), targetBox: reactive({}), guideCard: ref(card), mobileGuideAtTop: ref(false), mobileCardMaxHeight: ref(null), highlighted: target,
        document: { querySelector: selector => selector === '#workspace-content' ? main : selector === '.workspace-topbar' ? { getBoundingClientRect: () => ({ bottom: toolbarBottom }) } : null },
        window: { innerWidth: 320, innerHeight: height, scrollBy: options => { shifts.push(options); action.top -= options.top; action.bottom -= options.top } },
        getComputedStyle: () => ({ paddingBottom: '76px' }), guideReservation: null }
      const v = new Function(...Object.keys(deps), code + ';return {positionGuide,clearGuideReservation,reservation:()=>guideReservation}')(...Object.values(deps)); v.positionGuide({ scrollTarget: true })
      const cardTop = deps.mobileGuideAtTop.value ? 12 : height - cardHeight - 12, cardBottom = cardTop + cardHeight
      assert.ok(action.bottom <= cardTop - 12 || action.top >= cardBottom + 12, `${height}px: action and guide need a clear gap`)
      assert.ok(action.top >= toolbarBottom + 12 && action.bottom <= height - 12, `${height}px: action remains below the fixed toolbar and on screen`)
      assert.equal(mainStyle.get('padding-bottom'), `${76 + cardHeight + 24}px`, 'actual wrapped card height determines the scroll reservation')
      assert.ok(shifts.every(shift => shift.behavior === 'instant'), 'target position cannot remain under a card during smooth scrolling')
      v.positionGuide({ scrollTarget: true }); assert.equal(mainStyle.get('padding-bottom'), `${76 + cardHeight + 24}px`, 'repositioning must not accumulate spacing')
      v.clearGuideReservation(); assert.equal(mainStyle.get('padding-bottom'), '76px', 'pause and step cleanup restore existing inline spacing')
    }
  })

  test(`${edition}: discard choices keep explicit gaps and complete touch targets when wrapped`, () => {
    const { descriptor } = parse(read(edition, 'components/CareEntryDialog.vue'))
    const nodes = node => [node, ...(node.children || []).flatMap(nodes)]
    const actions = nodes(baseParse(descriptor.template.content)).find(node => node.props?.some(prop => prop.name === 'class' && prop.value?.content === 'draft-warning-actions'))
    assert.ok(actions, 'the action pair needs its own gap-aware layout container')
    const buttons = actions.children.filter(node => node.tag === 'el-button')
    assert.equal(buttons.length, 2); assert.deepEqual(buttons.map(node => node.props.find(prop => prop.name === 'on').exp.content), ['keepEditing', 'discardChanges'])
    const css = postcss.parse(descriptor.styles.map(style => style.content).join('\n')), declarations = selector => {
      const result = {}; css.walkRules(rule => { if (rule.selector === selector) rule.walkDecls(decl => { result[decl.prop] = decl.value }) }); return result
    }
    const layout = declarations('.draft-warning-actions'), button = declarations('.draft-warning-actions :deep(.el-button)')
    assert.equal(layout.display, 'flex'); assert.equal(layout['flex-wrap'], 'wrap'); assert.ok(Number.parseFloat(layout.gap) >= 8)
    assert.equal(button.margin, '0'); assert.ok(Number.parseFloat(button['min-height']) >= 44)
    assert.equal(button['white-space'], 'normal'); assert.equal(button['max-width'], '100%')
    assert.deepEqual(compileStyle({ source: descriptor.styles[0].content, id: 'data-v-occlusion', scoped: true }).errors, [])
  })
}
