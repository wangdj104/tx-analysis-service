<template>
  <div class="time-scope-filter" :class="{ 'time-scope-filter--stack': stack }">
    <div class="time-scope-filter__dims" role="tablist" aria-label="statisticsdimension">
      <button
        v-for="opt in dimensions"
        :key="opt.value"
        type="button"
        class="time-scope-filter__tab"
        :class="{ 'is-active': timeType === opt.value }"
        role="tab"
        :aria-selected="timeType === opt.value"
        @click="selectDimension(opt.value)"
      >
        <el-icon :size="15"><component :is="opt.icon" /></el-icon>
        <span>{{ opt.label }}</span>
      </button>
    </div>
    <div class="time-scope-filter__picker">
      <span class="time-scope-filter__picker-label">{{ pickerLabel }}</span>
      <el-date-picker
        :model-value="timeValue"
        :type="pickerType"
        :value-format="valueFormat"
        :placeholder="pickerPlaceholder"
        size="default"
        class="time-scope-filter__date-input"
        :clearable="false"
        @update:model-value="onDateUpdate"
      />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { Calendar as MonthIcon, Timer, Histogram } from '@element-plus/icons-vue';

const props = defineProps({
  timeType: { type: String, required: true },
  timeValue: { type: String, default: '' },
  stack: { type: Boolean, default: false }
});

const emit = defineEmits(['update:timeType', 'update:timeValue', 'dimension-change', 'date-change']);

const dimensions = [
  { value: 'month', label: 'by Month', icon: MonthIcon },
  { value: 'week', label: 'by week', icon: Timer },
  { value: 'year', label: 'by Year', icon: Histogram }
];

const pickerType = computed(() => (props.timeType === 'week' ? 'date' : props.timeType));

const valueFormat = computed(() => {
  if (props.timeType === 'year') return 'YYYY';
  if (props.timeType === 'month') return 'YYYY-MM';
  return 'YYYY-MM-DD';
});

const pickerLabel = computed(() => {
  if (props.timeType === 'year') return 'Yearcopy';
  if (props.timeType === 'week') return 'weekstartstart';
  return 'Month';
});

const pickerPlaceholder = computed(() => {
  if (props.timeType === 'year') return 'selectYearcopy';
  if (props.timeType === 'week') return 'selectDate';
  return 'selectMonth';
});

function selectDimension(value) {
  if (value === props.timeType) return;
  emit('update:timeType', value);
  emit('update:timeValue', ''); // switchdimensiontimeclearemptyDatevalue, avoidformatnot match
  emit('dimension-change', value);
}

function onDateUpdate(val) {
  emit('update:timeValue', val);
  emit('date-change', val);
}
</script>

<style scoped>
.time-scope-filter {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 6px 8px 6px 6px;
  background: linear-gradient(180deg, var(--surface-subtle) 0%, var(--surface-subtle) 100%);
  border: 1px solid var(--line);
  border-radius: 12px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%);
}

.time-scope-filter--stack {
  flex-direction: column;
  align-items: stretch;
  width: 100%;
}

.time-scope-filter__dims {
  display: inline-flex;
  padding: 3px;
  background: var(--paper);
  border-radius: 9px;
  border: 1px solid var(--line);
  gap: 2px;
}

.time-scope-filter__tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--ink-500);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease, box-shadow 0.18s ease;
  white-space: nowrap;
}

.time-scope-filter__tab:hover {
  color: var(--care-700);
  background: rgb(79 106 246 / 6%);
}

.time-scope-filter__tab.is-active {
  color: var(--care-700);
  background: linear-gradient(135deg, rgb(79 106 246 / 14%) 0%, rgb(99 102 241 / 10%) 100%);
  box-shadow: 0 1px 3px rgb(79 106 246 / 12%);
  font-weight: 600;
}

.time-scope-filter__picker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 4px 0 2px;
}

.time-scope-filter__picker-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-500);
  white-space: nowrap;
}

.time-scope-filter__date-input {
  width: 156px;
}

.time-scope-filter__date-input :deep(.el-input__wrapper) {
  border-radius: 8px;
  box-shadow: 0 0 0 1px var(--line) inset;
  background: var(--paper);
}

.time-scope-filter--stack .time-scope-filter__dims {
  width: 100%;
  justify-content: stretch;
}

.time-scope-filter--stack .time-scope-filter__tab {
  flex: 1;
  justify-content: center;
}

.time-scope-filter--stack .time-scope-filter__picker {
  width: 100%;
  padding: 2px 4px 4px;
}

.time-scope-filter--stack .time-scope-filter__date-input {
  flex: 1;
  width: auto;
  min-width: 0;
}

@media (max-width: 768px) {
  .time-scope-filter:not(.time-scope-filter--stack) {
    flex-direction: column;
    align-items: stretch;
    width: 100%;
  }

  .time-scope-filter:not(.time-scope-filter--stack) .time-scope-filter__dims {
    width: 100%;
  }

  .time-scope-filter:not(.time-scope-filter--stack) .time-scope-filter__tab {
    flex: 1;
    justify-content: center;
  }

  .time-scope-filter:not(.time-scope-filter--stack) .time-scope-filter__picker {
    width: 100%;
  }

  .time-scope-filter:not(.time-scope-filter--stack) .time-scope-filter__date-input {
    flex: 1;
    width: auto;
  }
}
</style>
