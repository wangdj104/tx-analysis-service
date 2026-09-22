<template>
  <div class="patient-switcher">
    <span class="patient-switcher__avatar"><el-icon :size="19"><User /></el-icon></span>
    <div class="patient-switcher__field">
      <label :for="inputId">Current patient</label>
      <el-select :id="inputId" :model-value="patients.length ? modelValue : undefined" :disabled="!patients.length" filterable :placeholder="patients.length ? 'Select a patient' : 'No authorized patients'"
        aria-label="Switch current patient" @change="$emit('update:modelValue', $event)">
        <el-option v-for="patient in patients" :key="patient.id" :value="patient.id" :label="patient.patientName || patient.name" />
        <el-option v-if="patients.length" label="All patients" :value="0" />
        <template #empty><span class="patient-switcher__empty">No patients</span></template>
      </el-select>
    </div>
  </div>
</template>

<script setup>
import { User } from '@element-plus/icons-vue'
defineProps({ modelValue: { type: Number, default: 0 }, patients: { type: Array, default: () => [] }, inputId: { type: String, default: 'workspace-patient' } })
defineEmits(['update:modelValue'])
</script>
