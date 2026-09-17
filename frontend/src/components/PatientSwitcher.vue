<template>
  <div class="patient-switcher">
    <span class="patient-switcher__avatar"><el-icon :size="19"><User /></el-icon></span>
    <div class="patient-switcher__field">
      <label :for="inputId">currentPatient</label>
      <el-select :id="inputId" :model-value="modelValue" filterable placeholder="selectPatient"
        aria-label="switchcurrentPatient" @change="$emit('update:modelValue', $event)">
        <el-option v-for="patient in patients" :key="patient.id" :value="patient.id" :label="patient.patientName || patient.name" />
        <el-option label="AllPatient" :value="0" />
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
