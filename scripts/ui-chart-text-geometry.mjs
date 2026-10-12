// Shared by the real browser harness, evidence collector and offline ECharts SSR
// regression. Measurements are the renderer's visible text bounds, not DOM boxes
// around the canvas or guesses based on configured grid/legend positions.
export function measureVitalsChartText(chart) {
  const model = chart.getModel()
  const axis = model.getComponent('xAxis')
  const roots = {
    ticks: chart.getViewOfComponentModel(axis).group,
    legend: chart.getViewOfComponentModel(model.getComponent('legend')).group,
  }
  const visible = { ticks: new Map(), legend: new Map() }
  for (const item of chart.getZr().storage.getDisplayList(true)) {
    if (item.type !== 'tspan' || item.invisible || item.style?.opacity === 0) continue
    let text, ancestor = item
    while (ancestor) {
      if (ancestor.type === 'text') text = ancestor
      for (const [kind, root] of Object.entries(roots)) if (ancestor === root && text) {
        if (!visible[kind].has(text)) visible[kind].set(text, [])
        visible[kind].get(text).push(item)
      }
      ancestor = ancestor.parent || ancestor.__hostTarget
    }
  }
  const bounds = text => {
    const rect = text.getBoundingRect().clone()
    rect.applyTransform(text.getComputedTransform())
    return { text: String(text.style.text), left: rect.x, top: rect.y, right: rect.x + rect.width, bottom: rect.y + rect.height, width: rect.width, height: rect.height }
  }
  const categories = axis.getCategories().map(value => String(typeof value === 'object' ? value.value : value))
  return {
    width: chart.getWidth(), height: chart.getHeight(),
    seriesNames: model.getSeries().map(series => series.name),
    endpoints: [...new Set([categories[0], categories.at(-1)])],
    ...Object.fromEntries(Object.entries(visible).map(([kind, entries]) => [kind, [...entries].map(([text, rendered]) => {
      const spans = rendered.map(bounds)
      return { ...bounds(text), sourceText: String(text.style.text), text: textFromSpans(spans), spans }
    })])),
  }
}

function textFromSpans(spans) {
  const lines = []
  for (const span of [...spans].sort((a, b) => a.top - b.top || a.left - b.left)) {
    let line = lines.find(line => Math.abs(line.top - span.top) <= .5)
    if (!line) { line = { top: span.top, text: '' }; lines.push(line) }
    line.text += span.text
  }
  return lines.map(line => line.text).join('\n')
}

export function assertVitalsChartText(measurement) {
  const fail = message => { throw new Error(`Vital chart text: ${message}`) }
  if (!measurement || !Number.isFinite(measurement.width) || !Number.isFinite(measurement.height) || measurement.width <= 0 || measurement.height <= 0) fail('missing chart size')
  const { width, height, ticks, legend, seriesNames, endpoints } = measurement
  if (!Array.isArray(ticks) || !ticks.length || !Array.isArray(legend) || !legend.length) fail('missing rendered tick or legend text')
  const normalize = value => String(value).replace(/\s+/g, ' ').trim()
  const validateBox = item => {
    if (!item || typeof item.text !== 'string' || !item.text.trim() || !['left', 'top', 'right', 'bottom', 'width', 'height'].every(key => Number.isFinite(item[key]))) fail('invalid rendered text bounds')
    if (item.width <= 0 || item.height <= 0 || Math.abs(item.right - item.left - item.width) > .01 || Math.abs(item.bottom - item.top - item.height) > .01) fail('inconsistent rendered text bounds')
    if (item.left < -.5 || item.top < -.5 || item.right > width + .5 || item.bottom > height + .5) fail(`clipped text: ${item.text}`)
  }
  for (const item of [...ticks, ...legend]) {
    validateBox(item)
    if (!Array.isArray(item.spans) || !item.spans.length) fail('missing visible glyph spans')
    for (const span of item.spans) {
      validateBox(span)
      if (span.left < item.left - .5 || span.right > item.right + .5 || span.top < item.top - .5 || span.bottom > item.bottom + .5) fail('glyph spans exceed text bounds')
    }
    if (textFromSpans(item.spans) !== item.text) fail('reported text differs from actual visible glyph spans')
  }
  if (!Array.isArray(seriesNames) || !seriesNames.length || !seriesNames.every(name => legend.some(item => item.text === name))) fail('a full series legend is missing')
  if (!Array.isArray(endpoints) || !endpoints.length || endpoints.some(value => !value || !ticks.some(item => normalize(item.text) === normalize(value)))) fail('a full endpoint timestamp is missing')
  const intersects = (a, b) => Math.min(a.right, b.right) - Math.max(a.left, b.left) > .5 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > .5
  const all = [...ticks, ...legend]
  for (let i = 0; i < all.length; i++) for (let j = i + 1; j < all.length; j++) if (intersects(all[i], all[j])) fail(`overlapping text: ${all[i].text} / ${all[j].text}`)
  if (Math.min(...legend.map(item => item.top)) - Math.max(...ticks.map(item => item.bottom)) < 8) fail('tick and legend rows need at least 8px separation')
}
