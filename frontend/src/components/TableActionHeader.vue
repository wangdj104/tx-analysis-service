<template>
  <div class="table-action-header">
    <span class="table-action-header__label">操作</span>
    <ColumnSetting
      v-if="columns?.length"
      v-model="model"
      :columns="columns"
      icon-only
      @reset="$emit('reset')"
    />
  </div>
</template>

<script setup>
import ColumnSetting from '@/components/ColumnSetting.vue';

defineProps({
  columns: { type: Array, default: () => [] }
});
const model = defineModel({ type: Array, required: true });
defineEmits(['reset']);
</script>

<style scoped>
.table-action-header {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 100%;
}

.table-action-header__label {
  font-size: 13px;
  font-weight: 600;
  color: #475569;
}
</style>
