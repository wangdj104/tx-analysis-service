import assert from 'node:assert/strict'
import test from 'node:test'
import { demo } from './helpers/runtime.mjs'

function guide(edition, matches) {
  const app = demo(edition), scrolls = [], media = { matches }
  app.context.matchMedia = () => media
  app.context.innerWidth = 1200; app.context.innerHeight = 800
  const target = app.node('synthetic-guide-target')
  target.classList = { add() {}, remove() {} }
  target.getBoundingClientRect = () => ({ top: 80, left: 80, right: 240, bottom: 120, width: 160, height: 40 })
  target.scrollIntoView = options => scrolls.push(options)
  app.context.document.querySelector = selector => selector === '.guide-target' ? null : target
  app.node('guide-popover').style.setProperty = function(key, value) { this[key] = value }
  const flush = () => {
    for (const [id, timer] of [...app.timers]) {
      if (timer.delay === 120) { app.timers.delete(id); timer.callback() }
    }
  }
  return { app, media, scrolls, flush }
}

for (const edition of ['en', 'zh']) {
  test(`${edition}: demo guide uses instant scrolling for reduced motion`, () => {
    const view = guide(edition, true); view.app.view.openGuide(); view.flush()
    assert.ok(view.scrolls.length > 0)
    assert.ok(view.scrolls.every(options => options.behavior === 'auto'))
  })
  test(`${edition}: normal-motion guide retains smooth scrolling`, () => {
    const view = guide(edition, false); view.app.view.openGuide(); view.flush()
    assert.ok(view.scrolls.length > 0)
    assert.ok(view.scrolls.every(options => options.behavior === 'smooth'))
  })
  test(`${edition}: queued guide scroll reads an updated media preference`, () => {
    const view = guide(edition, false); view.app.view.openGuide(); view.media.matches = true; view.flush()
    assert.ok(view.scrolls.every(options => options.behavior === 'auto'))
  })
  test(`${edition}: browsers without matchMedia keep a working guide`, () => {
    const view = guide(edition, false); delete view.app.context.matchMedia
    view.app.view.openGuide(); view.flush()
    assert.ok(view.scrolls.length > 0)
    assert.ok(view.scrolls.every(options => options.behavior === 'smooth'))
  })
}
