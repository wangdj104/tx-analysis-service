<template>
  <div class="patient-switcher">
    <span class="patient-switcher__avatar"><el-icon :size="19"><User /></el-icon></span>
    <div class="patient-switcher__field">
      <div class="patient-switcher__context">
        <label :for="inputId">Current patient</label>
        <span v-if="patients.some(patient => patient.id === modelValue)" class="patient-switcher__record">{{ patientIdLabel(modelValue) }}</span>
      </div>
      <PatientSelect :patients="patients" :show-selected-id="false" :id="inputId" :model-value="patients.length ? modelValue : undefined" :disabled="!patients.length" filterable :placeholder="patients.length ? 'Select a patient' : 'No authorized patients'"
        popper-class="patient-switcher-popper" aria-label="Switch current patient" @change="$emit('update:modelValue', $event)">
        <el-option class="patient-switcher-option" v-if="patients.length && allowAll" label="All patients" :value="0" />
        <template #empty><span class="patient-switcher__empty">No patients</span></template>
      </PatientSelect>
    </div>
  </div>
</template>

<script setup>
import { User } from '@element-plus/icons-vue'
import PatientSelect from './PatientSelect.vue'
import { patientIdLabel } from '../utils/patientPresentation.js'
defineProps({ allowAll: { type: Boolean, default: true }, modelValue: { type: Number, default: 0 }, patients: { type: Array, default: () => [] }, inputId: { type: String, default: 'workspace-patient' } })
defineEmits(['update:modelValue'])
</script>

<style scoped>
.patient-switcher__context { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; padding-left: 8px; }
.patient-switcher__context label { padding-left: 0; }
.patient-switcher__record {
  flex-shrink: 0;
  white-space: nowrap;
  color: var(--ink-500);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.5;
}
/* The popup is teleported outside this component. Bound its own width before
   wrapping the name, otherwise the long option sets an intrinsic desktop width. */
:global(.el-popper.patient-switcher-popper) { width: min(38rem, calc(100vw - 24px)); }
:global(.el-select-dropdown.patient-switcher-popper) { width: 100%; min-width: 0 !important; }
.patient-switcher-option { height: auto; min-height: 44px; padding: 8px 12px; line-height: 1.5; white-space: normal; }
</style>
