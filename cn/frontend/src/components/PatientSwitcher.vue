<template>
  <div class="patient-switcher">
    <span class="patient-switcher__avatar"><el-icon :size="19"><User /></el-icon></span>
    <div class="patient-switcher__field">
      <div class="patient-switcher__context">
        <label :for="inputId">当前患者</label>
        <span v-if="patients.some(patient => patient.id === modelValue)" class="patient-switcher__record" :aria-label="`档案 #${modelValue}`">#{{ modelValue }}</span>
      </div>
      <el-select :id="inputId" :model-value="patients.length ? modelValue : undefined" :disabled="!patients.length" filterable :placeholder="patients.length ? '选择患者' : '暂无授权患者'"
        aria-label="切换当前患者" @change="$emit('update:modelValue', $event)">
        <el-option v-for="patient in patients" :key="patient.id" :value="patient.id" :label="`${patient.patientName || patient.name} · 档案 #${patient.id}`">
          <span class="patient-option"><span class="patient-option__name">{{ patient.patientName || patient.name }}</span><span class="patient-option__record"> · 档案 #{{ patient.id }}</span></span>
        </el-option>
        <el-option v-if="patients.length && allowAll" label="全部患者" :value="0" />
        <template #empty><span class="patient-switcher__empty">暂无患者</span></template>
      </el-select>
    </div>
  </div>
</template>

<script setup>
import { User } from '@element-plus/icons-vue'
defineProps({ allowAll: { type: Boolean, default: true }, modelValue: { type: Number, default: 0 }, patients: { type: Array, default: () => [] }, inputId: { type: String, default: 'workspace-patient' } })
defineEmits(['update:modelValue'])
</script>

<style scoped>
.patient-switcher__context { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; padding-left: 8px; }
.patient-switcher__context label { padding-left: 0; }
.patient-switcher__record {
  flex-shrink: 0;
  white-space: nowrap;
  color: var(--care-700);
  font-size: 12px;
  font-weight: 700;
  line-height: 1.5;
}
.patient-option { display: flex; align-items: center; gap: 4px; min-width: 0; max-width: 100%; }
.patient-option__name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.patient-option__record { flex-shrink: 0; white-space: nowrap; font-weight: 600; }
</style>
