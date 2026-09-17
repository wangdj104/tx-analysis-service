<template>
  <el-popover trigger="click" placement="bottom-end" :width="220">
    <template #reference>
      <el-button size="small" class="col-setting-btn" :class="{ 'col-setting-btn--icon-only': iconOnly }">
        <el-icon><Setting /></el-icon>
        <span v-if="!iconOnly">columndisplay</span>
      </el-button>
    </template>
    <div class="col-setting-panel">
      <p class="col-setting-title">selectneeddisplay column</p>
      <el-checkbox-group v-model="model" class="col-setting-group">
        <el-checkbox v-for="col in columns" :key="col.key" :label="col.key" :value="col.key">
          {{ col.label }}
        </el-checkbox>
      </el-checkbox-group>
      <el-button size="small" text type="primary" @click="$emit('reset')">restoreDefault</el-button>
    </div>
  </el-popover>
</template>

<script setup>
defineProps({
  columns: { type: Array, required: true },
  iconOnly: { type: Boolean, default: false }
});
const model = defineModel({ type: Array, required: true });
defineEmits(['reset']);
</script>

<style scoped>
.col-setting-btn {
  margin-left: auto;
}

.col-setting-btn--icon-only {
  margin-left: 0;
  padding: 4px 6px;
}

.col-setting-title {
  margin: 0 0 8px;
  font-size: 12px;
  color: #64748b;
}

.col-setting-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 280px;
  overflow-y: auto;
}

.col-setting-group :deep(.el-checkbox) {
  margin-right: 0;
  height: auto;
}
</style>
