import assert from 'node:assert/strict'

// Only consume measured browser geometry. Unit fixtures validate these guards,
// but never become browser acceptance evidence or count as a passed matrix cell.
export function assertReadableBox(box, viewport, label = box?.text || 'Rendered text') {
  assert.ok(box?.visible && box.width > 0 && box.height > 0, `${label} is actually rendered`)
  assert.ok(box.left >= -1 && box.right <= viewport + 1, `${label} stays inside the viewport`)
  assert.ok(box.text?.trim() && box.textBoxes?.length, `${label} contains visible text`)
  for (const text of box.textBoxes) {
    assert.ok(text.left >= box.left - 1 && text.right <= box.right + 1, `${label} text stays inside its own layout box`)
    assert.ok(text.top >= box.top - 1 && text.bottom <= box.bottom + 1, `${label} text stays inside its own vertical layout box`)
    assert.ok(text.left >= -1 && text.right <= viewport + 1, `${label} text is not cut off by the viewport`)
    for (const clip of box.clips || []) {
      if (clip.x) assert.ok(text.left >= clip.left - 1 && text.right <= clip.right + 1, `${label} text is not horizontally clipped`)
      if (clip.y) assert.ok(text.top >= clip.top - 1 && text.bottom <= clip.bottom + 1, `${label} text is not vertically clipped`)
    }
  }
}
export function assertDisjointBoxes(a, b, label = 'Rendered regions') {
  assert.ok(a.right <= b.left + 1 || b.right <= a.left + 1 || a.bottom <= b.top + 1 || b.bottom <= a.top + 1, `${label} must not intersect`)
}
export function assertTouchTarget(box, label = 'Action') {
  assert.ok(box?.visible && box.width >= 44 && box.height >= 44, `${label} has a real 44px minimum target`)
}

// Passed directly to locator.evaluateAll: standalone, observation-only DOM reads.
export function measureReadableElements(nodes) {
  const rect = element => {
    const box = element.getBoundingClientRect()
    return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height }
  }
  return nodes.map(element => {
    const style = getComputedStyle(element), bounds = rect(element)
    const textBoxes = [], walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
    for (let text = walker.nextNode(); text; text = walker.nextNode()) {
      if (!text.textContent.trim()) continue
      const range = document.createRange(); range.selectNodeContents(text)
      for (const box of range.getClientRects()) if (box.width && box.height) textBoxes.push({ left: box.left, right: box.right, top: box.top, bottom: box.bottom })
    }
    const clips = []
    for (let ancestor = element; ancestor; ancestor = ancestor.parentElement) {
      const css = getComputedStyle(ancestor), x = /hidden|clip|auto|scroll/.test(css.overflowX), y = ancestor !== document.documentElement && ancestor !== document.body && /hidden|clip|auto|scroll/.test(css.overflowY)
      if (x || y) {
        const box = ancestor.getBoundingClientRect(), left = box.left + ancestor.clientLeft, top = box.top + ancestor.clientTop
        clips.push({ tag: ancestor.tagName, className: ancestor.className, left, top, right: left + ancestor.clientWidth, bottom: top + ancestor.clientHeight, x, y })
      }
    }
    return { ...bounds, text: element.textContent.trim(), visible: bounds.width > 0 && bounds.height > 0 && style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity) > 0,
      textBoxes, clips, fontSize: style.fontSize, lineHeight: style.lineHeight, color: style.color, background: style.backgroundColor, textOverflow: style.textOverflow, whiteSpace: style.whiteSpace }
  })
}
