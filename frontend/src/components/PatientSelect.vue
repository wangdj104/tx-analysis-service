<template>
  <el-select v-bind="$attrs" :model-value="modelValue" :popper-class="popperClass"
    @update:model-value="$emit('update:modelValue', $event)">
    <template #label="{ label }">
      <span v-if="selectedPatient" class="patient-selection" :aria-label="formatPatientIdentity(selectedPatient)">
        <span class="patient-selection__name">{{ patientName(selectedPatient) }}</span>
        <span v-if="showSelectedId" class="patient-selection__id">{{ patientIdLabel(selectedPatient.id) }}</span>
      </span>
      <span v-else>{{ label }}</span>
    </template>
    <el-option v-for="patient in patients" :key="patient.id" :value="patient.id" :label="formatPatientIdentity(patient)" class="patient-identity-option">
      <span class="patient-option" :aria-label="formatPatientIdentity(patient)">
        <span class="patient-option__name">{{ patientName(patient) }}</span>
        <span class="patient-option__id">{{ patientIdLabel(patient.id) }}</span>
      </span>
    </el-option>
    <slot />
    <template #empty><slot name="empty"><span class="patient-select-empty">No patients</span></slot></template>
  </el-select>
</template>

<script setup>
import { computed, useAttrs } from 'vue';
import { patientName, patientIdLabel, formatPatientIdentity } from '../utils/patientPresentation.js';
defineOptions({ inheritAttrs: false });
const props = defineProps({
  modelValue: { type: [Number, String], default: null },
  patients: { type: Array, default: () => [] },
  showSelectedId: { type: Boolean, default: true }
});
defineEmits(['update:modelValue']);
const attrs = useAttrs();
const selectedPatient = computed(() => props.modelValue == null ? null : props.patients.find(patient => String(patient.id) === String(props.modelValue)));
const popperClass = computed(() => ['patient-identity-popper', attrs.popperClass || attrs['popper-class']].filter(Boolean).join(' '));
</script>

<style scoped>
.patient-selection { display: flex; align-items: center; gap: 8px; width: 100%; min-width: 0; }
.patient-selection__name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.patient-selection__id, .patient-option__id { flex-shrink: 0; white-space: nowrap; font-size: 12px; font-weight: 400; color: var(--ink-500); }
:global(.el-popper.patient-identity-popper) { max-width: calc(100vw - 24px); }
:global(.el-select-dropdown.patient-identity-popper) { min-width: 0 !important; }
.patient-identity-option { height: auto; min-height: 44px; padding: 8px 12px; line-height: 1.5; white-space: normal; }
.patient-option { display: flex; align-items: flex-start; flex-wrap: wrap; gap: 4px 12px; min-width: 0; max-width: 100%; }
.patient-option__name { flex: 1; min-width: 0; white-space: normal; overflow-wrap: anywhere; }
.patient-select-empty { display: block; padding: 16px; text-align: center; color: var(--ink-500); }
</style>
