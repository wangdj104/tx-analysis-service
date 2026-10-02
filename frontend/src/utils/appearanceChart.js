// Presentation-only ECharts adaptation. Clinical data, formatter functions and
// threshold coordinates stay in their original options; no JSON cloning.
const LIGHT = Object.freeze({ surface: '#ffffff', text: '#273c47', muted: '#526775', border: '#cbd7df', grid: '#e4edf1', tooltip: '#ffffff' });
const DARK = Object.freeze({ surface: '#18262e', text: '#edf6f2', muted: '#aec2b9', border: '#455b67', grid: '#304551', tooltip: '#20323e' });

function hexRgb(color) {
  if (typeof color !== 'string' || !/^#(?:[a-f\d]{3}|[a-f\d]{6})$/i.test(color)) return null;
  const hex = color.length === 4 ? [...color.slice(1)].map(value => value + value).join('') : color.slice(1);
  return [0, 2, 4].map(index => parseInt(hex.slice(index, index + 2), 16));
}

function luminance(rgb) {
  const values = rgb.map(value => {
    const channel = value / 255;
    return channel <= .04045 ? channel / 12.92 : ((channel + .055) / 1.055) ** 2.4;
  });
  return values[0] * .2126 + values[1] * .7152 + values[2] * .0722;
}

function readableColor(color, surface, minimum, dark) {
  const rgb = hexRgb(color), background = hexRgb(surface);
  // Keep ECharts gradients, callbacks and other color formats untouched.
  if (!rgb || !background) return color;
  const backdrop = luminance(background);
  const contrast = value => (Math.max(value, backdrop) + .05) / (Math.min(value, backdrop) + .05);
  if (contrast(luminance(rgb)) >= minimum) return color;
  // Mixing white/black keeps the original hue rather than changing the clinical palette.
  for (let step = 1; step <= 100; step++) {
    const amount = step / 100;
    const adjusted = rgb.map(value => Math.round(value + ((dark ? 255 : 0) - value) * amount));
    if (contrast(luminance(adjusted)) >= minimum) return '#' + adjusted.map(value => value.toString(16).padStart(2, '0')).join('');
  }
  return dark ? '#ffffff' : '#000000';
}

function mapComponent(value, adapt) {
  return Array.isArray(value) ? value.map(adapt) : adapt(value);
}

export function adaptChartOption(option, theme = 'platform') {
  if (!option || typeof option !== 'object') return option;
  const dark = theme === 'dark', palette = dark ? DARK : LIGHT;
  const semanticColor = color => dark ? readableColor(color, palette.surface, 3, true) : color;
  const text = (style, color = palette.muted) => {
    const result = { ...style, color: typeof style?.color === 'function' ? style.color : color };
    if (style?.rich) result.rich = Object.fromEntries(Object.entries(style.rich).map(([key, value]) => [key, text(value, color)]));
    return result;
  };
  const label = style => text(style, style?.color && hexRgb(style.color)
    ? readableColor(style.color, palette.surface, 4.5, dark) : palette.text);
  const stroke = style => ({ ...style, color: palette.border });
  const semanticStyle = style => style ? { ...style, ...(style.color ? { color: semanticColor(style.color) } : {}) } : style;
  const axisPointer = pointer => ({ ...pointer,
    lineStyle: stroke(pointer?.lineStyle),
    label: { ...text(pointer?.label, palette.text), backgroundColor: palette.tooltip }
  });
  const axis = value => ({ ...value,
    axisLabel: text(value?.axisLabel), nameTextStyle: text(value?.nameTextStyle),
    axisLine: { ...value?.axisLine, lineStyle: stroke(value?.axisLine?.lineStyle) },
    axisTick: { ...value?.axisTick, lineStyle: stroke(value?.axisTick?.lineStyle) },
    splitLine: { ...value?.splitLine, lineStyle: { ...value?.splitLine?.lineStyle, color: palette.grid } },
    ...(value?.axisPointer ? { axisPointer: axisPointer(value.axisPointer) } : {})
  });
  const markerPoint = point => {
    if (Array.isArray(point)) return point.map(markerPoint);
    if (!point || typeof point !== 'object') return point;
    return { ...point,
      ...(point.label ? { label: label(point.label) } : {}),
      ...(point.lineStyle ? { lineStyle: semanticStyle(point.lineStyle) } : {}),
      ...(point.itemStyle ? { itemStyle: semanticStyle(point.itemStyle) } : {})
    };
  };
  const marker = value => ({ ...value, label: label(value.label),
    ...(value.lineStyle ? { lineStyle: semanticStyle(value.lineStyle) } : {}),
    ...(value.itemStyle ? { itemStyle: semanticStyle(value.itemStyle) } : {}),
    ...(Array.isArray(value.data) ? { data: value.data.map(markerPoint) } : {})
  });
  const series = value => {
    const result = { ...value, label: label(value?.label) };
    for (const key of ['itemStyle', 'lineStyle']) if (value?.[key]) result[key] = semanticStyle(value[key]);
    for (const key of ['markLine', 'markPoint', 'markArea']) if (value?.[key]) result[key] = marker(value[key]);
    for (const key of ['emphasis', 'blur', 'select']) {
      if (value?.[key]?.label) result[key] = { ...value[key], label: label(value[key].label) };
    }
    return result;
  };
  const result = { ...option, textStyle: text(option.textStyle, palette.text) };
  for (const key of ['xAxis', 'yAxis', 'radiusAxis', 'angleAxis']) {
    if (option[key]) result[key] = mapComponent(option[key], axis);
  }
  if (option.axisPointer) result.axisPointer = axisPointer(option.axisPointer);
  if (option.legend) result.legend = mapComponent(option.legend, value => ({ ...value, textStyle: text(value.textStyle), inactiveColor: palette.muted }));
  if (option.title) result.title = mapComponent(option.title, value => ({ ...value, textStyle: text(value.textStyle, palette.text), subtextStyle: text(value.subtextStyle) }));
  if (option.grid) result.grid = mapComponent(option.grid, value => ({ ...value, borderColor: palette.border }));
  if (option.tooltip) result.tooltip = mapComponent(option.tooltip, value => ({ ...value,
    backgroundColor: palette.tooltip, borderColor: palette.border, textStyle: text(value.textStyle, palette.text),
    ...(value.axisPointer ? { axisPointer: axisPointer(value.axisPointer) } : {})
  }));
  if (option.series) result.series = mapComponent(option.series, series);
  if (Array.isArray(option.color)) result.color = option.color.map(semanticColor);
  return result;
}
