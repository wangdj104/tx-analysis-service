<template>
  <VChart ref="chartRef" v-bind="$attrs" :option="themedOption" />
</template>

<script setup>
import { computed, shallowRef } from 'vue';
import VChart from 'vue-echarts';
import { appearanceTheme } from '@/utils/healthAppearance';
import { adaptChartOption } from '@/utils/appearanceChart';

defineOptions({ inheritAttrs: false });
const props = defineProps({ option: Object });
const chartRef = shallowRef();
// Updating the option lets vue-echarts update its existing instance. Switching
// its `theme` prop instead would dispose it and lose the user's chart state.
const themedOption = computed(() => adaptChartOption(props.option, appearanceTheme.value));

// Keep the same public ref API as vue-echarts (including image export).
const publicMethods = {};
for (const name of ['getWidth', 'getHeight', 'getDom', 'getOption', 'resize', 'dispatchAction',
  'convertToPixel', 'convertFromPixel', 'containPixel', 'getDataURL', 'getConnectedDataURL',
  'appendData', 'clear', 'isDisposed', 'dispose']) {
  publicMethods[name] = (...args) => {
    if (!chartRef.value) throw new Error('ECharts is not initialized yet.');
    return chartRef.value[name](...args);
  };
}
function setOption(option, updateOptions) {
  if (!chartRef.value) throw new Error('ECharts is not initialized yet.');
  return chartRef.value.setOption(adaptChartOption(option, appearanceTheme.value), updateOptions);
}
defineExpose({ ...publicMethods, setOption,
  chart: computed(() => chartRef.value?.chart), root: computed(() => chartRef.value?.root)
});
</script>
