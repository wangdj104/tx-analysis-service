<template>
  <VChart ref="chartRef" v-bind="$attrs" :option="themedOption" />
</template>

<script setup>
import { computed, shallowRef, watch } from 'vue';
import VChart from 'vue-echarts';
import { appearanceTheme } from '@/utils/healthAppearance';
import { adaptChartOption } from '@/utils/appearanceChart';
import { useReducedMotion } from '@/composables/useReducedMotion';

defineOptions({ inheritAttrs: false });
const props = defineProps({ option: Object });
const chartRef = shallowRef();
// Updating the option lets vue-echarts update its existing instance. Switching
// its `theme` prop instead would dispose it and lose the user's chart state.
const reducedMotion = useReducedMotion();
// Keep preference changes out of the computed option dependency graph:
// vue-echarts replaces a fresh option with notMerge=true, losing zoom/legend state.
let motionReduced = reducedMotion.value;
const seriesList = option => option?.series ? (Array.isArray(option.series) ? option.series : [option.series]) : [];
const identity = item => ({
  ...(item.id != null ? { id: item.id } : {}),
  ...(item.name != null ? { name: item.name } : {})
});
function animationSettings(option) {
  return {
    animation: option?.animation ?? 'auto',
    series: seriesList(option).map(item => item ? { ...identity(item), animation: item.animation ?? null } : null)
  };
}
let authoredMotion = animationSettings(props.option);
function currentAnimationSettings() {
  const settings = animationSettings(chartRef.value.getOption());
  const model = chartRef.value.chart?.getModel();
  // getOption omits generated ids, but callers may use the actual model id in
  // replaceMerge. Keep metadata aligned to the existing component indexes.
  settings.series = settings.series.map((item, index) => item ? {
    ...item, id: model?.getSeriesByIndex(index)?.id ?? item.id
  } : null);
  return settings;
}
function rememberSeriesIdentities() {
  authoredMotion.series = currentAnimationSettings().series.map((item, index) => item ? {
    ...identity(item), animation: authoredMotion.series[index]?.animation ?? null
  } : null);
}

function motionOption(option) {
  if (!motionReduced || !option) return option;
  const disable = series => series ? { ...series, animation: false } : null;
  return { ...option, animation: false,
    ...(option.series ? { series: Array.isArray(option.series) ? option.series.map(disable) : disable(option.series) } : {})
  };
}
const themedOption = computed(() => {
  authoredMotion = animationSettings(props.option);
  return motionOption(adaptChartOption(props.option, appearanceTheme.value));
});
watch(reducedMotion, value => {
  if (value && chartRef.value) authoredMotion = currentAnimationSettings();
  motionReduced = value;
  if (!chartRef.value) return;
  // Explicit reset values matter: omitted properties would leave forced false
  // values behind after ECharts merges the next option. null restores inheritance.
  chartRef.value.setOption({
    animation: value ? false : authoredMotion.animation,
    series: authoredMotion.series.map(item => item ? { ...item, animation: value ? false : item.animation } : null)
  }, { notMerge: false });
}, { flush: 'sync' });

// Track only animation metadata for the public imperative API. ECharts itself
// still owns all option/series merging, removals, data and interaction state.
function matchingSeries(items, actual, byIdOnly = false) {
  const result = [], remaining = new Set(items.filter(Boolean));
  const match = predicate => actual.forEach((target, index) => {
    if (!target || result[index]) return;
    const item = [...remaining].find(candidate => predicate(candidate, target));
    if (item) { result[index] = item; remaining.delete(item); }
  });
  // Resolve explicit identities before positional entries. Consume matches so
  // duplicate names still retain each series' separate animation choice.
  match((item, target) => item.id != null && target.id != null && String(item.id) === String(target.id));
  if (!byIdOnly) {
    match((item, target) => item.id == null && item.name != null && item.name === target.name);
    match(item => item.id == null);
  }
  return result;
}
function rememberImperativeMotion(option, updateOptions, actualOption) {
  const replace = updateOptions === true || updateOptions?.notMerge;
  const previous = replace ? animationSettings() : authoredMotion;
  const actual = seriesList(actualOption);
  const replaceSeries = [updateOptions?.replaceMerge].flat().includes('series');
  const before = matchingSeries(previous.series, actual, replaceSeries);
  const updates = matchingSeries(seriesList(option), actual);
  authoredMotion = {
    animation: Object.hasOwn(option, 'animation') ? option.animation ?? 'auto' : previous.animation,
    // Preserve ECharts' null slots after replaceMerge; compacting the array can
    // change a surviving series' index or bring a removed series back.
    series: actual.map((item, index) => item ? {
      ...identity(item),
      animation: updates[index] && Object.hasOwn(updates[index], 'animation') ? updates[index].animation ?? null : before[index]?.animation ?? null
    } : null)
  };
}

// Keep the same public ref API as vue-echarts (including image export).
const publicMethods = {};
for (const name of ['getWidth', 'getHeight', 'getDom', 'getOption', 'resize', 'dispatchAction',
  'convertToPixel', 'convertFromPixel', 'containPixel', 'getDataURL', 'getConnectedDataURL',
  'appendData', 'clear', 'isDisposed', 'dispose']) {
  publicMethods[name] = (...args) => {
    if (!chartRef.value) throw new Error('ECharts is not initialized yet.');
    const result = chartRef.value[name](...args);
    if (name === 'clear') authoredMotion = animationSettings();
    return result;
  };
}
function setOption(option, updateOptions) {
  if (!chartRef.value) throw new Error('ECharts is not initialized yet.');
  if (motionReduced) rememberSeriesIdentities();
  const result = chartRef.value.setOption(motionOption(adaptChartOption(option, appearanceTheme.value)), updateOptions);
  if (motionReduced && option) rememberImperativeMotion(option, updateOptions, currentAnimationSettings());
  return result;
}
defineExpose({ ...publicMethods, setOption,
  chart: computed(() => chartRef.value?.chart), root: computed(() => chartRef.value?.root)
});
</script>
