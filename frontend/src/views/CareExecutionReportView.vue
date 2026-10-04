<template><main class="execution-report-page"><ExecutionReportPanel v-if="scope" :key="`${scope.patientId}:${scope.planId}`" :patient-id="scope.patientId" :plan-id="scope.planId"/><p v-else role="alert">Invalid report scope. Open the report from an authorized patient or plan.</p></main></template>
<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { parseReportRoute } from '@/utils/workspaceAccess'
import ExecutionReportPanel from '@/components/care-plan/ExecutionReportPanel.vue'
const route = useRoute(), scope = computed(() => parseReportRoute(route.fullPath))
</script>
<style scoped>.execution-report-page{max-width:1100px;margin:auto;padding:24px;min-width:0}@media(max-width:600px){.execution-report-page{padding:14px}}</style>
