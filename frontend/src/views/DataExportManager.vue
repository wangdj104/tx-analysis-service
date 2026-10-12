<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>Data Export</h1>
                <p class="subtitle">Export dialysis, blood pressure, nutrition, and other records as CSV files for analysis and archiving</p>
              </div>
            </div>
          </div>
        </div>

        <div class="content-panel">
          <button class="execution-report-choice" type="button" :aria-expanded="showCareExecution" @click="showCareExecution=!showCareExecution">Care execution report · Current actions CSV / Period events CSV</button>
          <ExecutionReportPanel v-if="showCareExecution && currentPatientId" :patient-id="currentPatientId"/>
          <p v-else-if="showCareExecution" role="status">Select a patient first</p>
          <el-form v-if="!showCareExecution" :model="form" label-width="110px" ref="formRef" :rules="rules">
            <el-form-item label="Data type" prop="dataType">
              <el-select v-model="form.dataType" style="width: 100%">
                <el-option v-for="opt in dataTypeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-row :gutter="16">
              <el-col :xs="24" :sm="8">
                <el-form-item label="Time dimension" prop="timeType">
                  <el-select v-model="form.timeType" style="width: 100%">
                    <el-option label="Monthly" value="month" />
                    <el-option label="Weekly" value="week" />
                    <el-option label="Yearly" value="year" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="8">
                <el-form-item label="Time range" prop="timeValue">
                  <el-date-picker
                    v-model="form.timeValue"
                    :type="form.timeType === 'week' ? 'date' : form.timeType"
                    :value-format="form.timeType === 'year' ? 'YYYY' : form.timeType === 'month' ? 'YYYY-MM' : 'YYYY-MM-DD'"
                    placeholder="Select a time period"
                    style="width: 100%"
                    :clearable="false"
                  />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="Privacy">
              <el-checkbox v-model="form.masked">Mask phone numbers and identity numbers for portable export</el-checkbox>
            </el-form-item>
            <section class="export-scope" aria-live="polite">
              <strong>Export for：{{ currentPatientName || '—' }} · Record #{{ currentPatientId || '—' }}</strong>
              <p>{{ DATA_TYPE_LABELS[form.dataType] || '—' }} · Inclusive dates：{{ dateRange ? `${dateRange.from} – ${dateRange.to}` : 'Select a patient and date range first' }}</p>
              <p>{{ form.masked ? 'Phone and identity number masking is selected' : 'Masking is off: available original data will be included' }}</p>
              <p>Masking phone and identity numbers does not anonymize health data. The exported records may still identify a person.</p>
            </section>
            <p v-if="exportMessage" role="status" class="export-feedback">{{ exportMessage }}</p>
            <el-form-item>
              <el-button type="primary" @click="handleExport" :loading="exporting">
                <el-icon><Download /></el-icon>Export CSV
              </el-button>
            </el-form-item>
          </el-form>
        </div>
      </div>
    </el-main>
  </el-container>
</template>

<script setup>
import ExecutionReportPanel from '@/components/care-plan/ExecutionReportPanel.vue';
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Download } from '@element-plus/icons-vue';
import { exportDateRange } from '@/utils/exportPresentation';
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession';
import { exportCsv } from '@/api/dataExport.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { useMenuPermission } from '@/composables/useMenuPermission';

const { currentPatientId, currentPatientName } = useCurrentPatient();
const { hasMenu, hasMenuName } = useMenuPermission();
const showCareExecution = ref(false);
const exporting = ref(false);
const formRef = ref(null);

// based onRolePermissiondynamicbuilddatatypeselectitem
const dataTypeOptions = computed(() => {
  const options = [];

  // Dialysis Records: needneedDialysisMenuPermission
  if (hasMenu('/dialysis')) {
    options.push({ label: 'Dialysis Records', value: 'dialysis' });
  }
  // Blood Pressure Pattern Analysis
  if (hasMenuName('Blood Pressure Pattern Analysis')) {
    options.push({ label: 'Blood Pressure Pattern Analysis', value: 'bp_analysis' });
  }
  // Nutrition Diary
  if (hasMenuName('Nutrition Diary')) {
    options.push({ label: 'Nutrition Diary', value: 'nutrition' });
  }
  // Complication Tracking
  if (hasMenuName('Complication Tracking')) {
    options.push({ label: 'Complication records', value: 'complication' });
  }
  // Medication records: needneedMedicationmanagementPermission
  if (hasMenu('/medication')) {
    options.push({ label: 'Medication records', value: 'medication' });
  }
  // Blood GlucoseBlood Pressurerecord
  if (hasMenuName('Blood GlucoseBlood Pressurerecord')) {
    options.push({ label: 'Blood pressure and glucose records', value: 'bp_self_monitor' });
  }
  return options;
});

const DATA_TYPE_LABELS = {
  dialysis: 'Dialysis Records',
  bp_analysis: 'Blood Pressure Pattern Analysis',
  nutrition: 'Nutrition Diary',
  complication: 'Complication records',
  medication: 'Medication records',
  bp_self_monitor: 'Blood pressure and glucose records'
};

const form = reactive({
  dataType: '',
  timeType: 'month',
  timeValue: '',
  masked: true
});

// initializetimebased onOptionaltypeDefaultselectinNo. one
onMounted(() => {
  if (dataTypeOptions.value.length > 0) {
    form.dataType = dataTypeOptions.value[0].value;
  }
});

const dateRange = computed(() => exportDateRange(form.timeType, form.timeValue));
const exportMessage = ref('');
let exportEpoch = 0, disposed = false;
watch(() => [currentPatientId.value, form.dataType, form.timeType, form.timeValue, form.masked], () => {
  exportEpoch++; exporting.value = false; exportMessage.value = '';
}, { flush: 'sync' });
onUnmounted(() => { disposed = true; exportEpoch++; });

const rules = {
  dataType: [{ required: true, message: 'Select a data type', trigger: 'change' }],
  timeType: [{ required: true, message: 'Select a time dimension', trigger: 'change' }],
  timeValue: [{ required: true, message: 'Select a time range', trigger: 'change' }]
};

async function handleExport() {
  if (exporting.value) return;
  if (!currentPatientId.value) { ElMessage.warning('Select a patient first.'); return; }
  const epoch = ++exportEpoch, auth = captureAuthSession();
  const snapshot = { patientId: currentPatientId.value, ...form }, range = exportDateRange(snapshot.timeType, snapshot.timeValue);
  const current = () => !disposed && epoch === exportEpoch && snapshot.patientId === currentPatientId.value && isAuthSessionCurrent(auth);
  exporting.value = true; exportMessage.value = '';
  try {
    try { await formRef.value.validate(); } catch { return; }
    if (!current()) return;
    if (!range) { exportMessage.value = 'Select a valid date range.'; return; }
    const blob = await exportCsv(snapshot);
    if (!current()) return;
    const text = await blob.text();
    if (!current()) return;
    if (text.replace(/^\uFEFF/, '').trim().split(/\r?\n/).length < 2) { exportMessage.value = 'No records for this patient in the selected date range.'; return; }
    const url = window.URL.createObjectURL(blob);
    try {
      const link = document.createElement('a');
      link.href = url;
      link.download = `${snapshot.dataType}_${range.from}_${range.to}.csv`;
      link.click();
    } finally { window.URL.revokeObjectURL(url); }
    exportMessage.value = 'Data exported.';
  } catch (e) {
    if (current()) exportMessage.value = 'Export failed. Your selections are kept; please retry.';
  } finally { if (current()) exporting.value = false; }
}
</script>

<style scoped src="@/styles/module-layout.css"></style>

<style scoped>.execution-report-choice{min-height:44px;padding:10px 16px;margin-bottom:16px;max-width:100%;font:inherit;white-space:normal}.execution-report-choice:focus-visible{outline:3px solid var(--care-600)}</style>

<style scoped>.export-scope{padding:16px;background:var(--care-50);border:1px solid var(--line);border-radius:10px;margin-bottom:16px;overflow-wrap:anywhere}.export-scope p{line-height:1.6;margin:8px 0}.export-feedback{padding:12px;border:1px solid var(--line)}</style>
