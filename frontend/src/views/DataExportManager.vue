<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>Data Export</h1>
                <p class="subtitle">will Dialysis, Blood Pressure, Nutritionetc.Data Exportfor CSVfile, convenientinexternalanalysis and archive</p>
              </div>
            </div>
          </div>
        </div>

        <div class="content-panel">
          <el-form :model="form" label-width="110px" ref="formRef" :rules="rules">
            <el-form-item label="datatype" prop="dataType">
              <el-select v-model="form.dataType" style="width: 100%">
                <el-option v-for="opt in dataTypeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-row :gutter="16">
              <el-col :xs="24" :sm="8">
                <el-form-item label="Time dimension" prop="timeType">
                  <el-select v-model="form.timeType" style="width: 100%">
                    <el-option label="by Month" value="month" />
                    <el-option label="by week" value="week" />
                    <el-option label="by Year" value="year" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="8">
                <el-form-item label="Time range" prop="timeValue">
                  <el-date-picker
                    v-model="form.timeValue"
                    :type="form.timeType === 'week' ? 'date' : form.timeType"
                    :value-format="form.timeType === 'year' ? 'YYYY' : form.timeType === 'month' ? 'YYYY-MM' : 'YYYY-MM-DD'"
                    placeholder="selectTime"
                    style="width: 100%"
                    :clearable="false"
                  />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item>
              <el-button type="primary" @click="handleExport" :loading="exporting">
                <el-icon><Download /></el-icon>ExportCSV
              </el-button>
            </el-form-item>
          </el-form>
        </div>
      </div>
    </el-main>
  </el-container>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { Download } from '@element-plus/icons-vue';
import { exportCsv } from '@/api/dataExport.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { useMenuPermission } from '@/composables/useMenuPermission';

const { currentPatientId } = useCurrentPatient();
const { hasMenu, hasMenuName } = useMenuPermission();
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
    options.push({ label: 'complicationrecord', value: 'complication' });
  }
  // medicationrecord: needneedMedicationmanagementPermission
  if (hasMenu('/medication')) {
    options.push({ label: 'medicationrecord', value: 'medication' });
  }
  // Blood GlucoseBlood Pressurerecord
  if (hasMenuName('Blood GlucoseBlood Pressurerecord')) {
    options.push({ label: 'Blood GlucoseBlood Pressurerecord', value: 'bp_self_monitor' });
  }
  return options;
});

const DATA_TYPE_LABELS = {
  dialysis: 'Dialysis Records',
  bp_analysis: 'Blood Pressure Pattern Analysis',
  nutrition: 'Nutrition Diary',
  complication: 'complicationrecord',
  medication: 'medicationrecord',
  bp_self_monitor: 'Blood GlucoseBlood Pressurerecord'
};

const form = reactive({
  dataType: '',
  timeType: 'month',
  timeValue: ''
});

// initializetimebased onOptionaltypeDefaultselectinNo. one
onMounted(() => {
  if (dataTypeOptions.value.length > 0) {
    form.dataType = dataTypeOptions.value[0].value;
  }
});

const rules = {
  dataType: [{ required: true, message: 'Selectdatatype', trigger: 'change' }],
  timeType: [{ required: true, message: 'Select a time dimension', trigger: 'change' }],
  timeValue: [{ required: true, message: 'Select a time range', trigger: 'change' }]
};

async function handleExport() {
  if (!currentPatientId.value) { ElMessage.warning('Select a patient first.'); return; }
  try { await formRef.value.validate(); } catch { return; }
  exporting.value = true;
  try {
    const blob = await exportCsv({
      patientId: currentPatientId.value,
      ...form
    });
    // blobalready throughYesBlobtoimage, directlyCreateDownloadlink
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${DATA_TYPE_LABELS[form.dataType] || form.dataType}_Data Export.csv`;
    link.click();
    window.URL.revokeObjectURL(url);
    ElMessage.success('dataalready Export');
  } catch (e) {
    ElMessage.error('Exportfailed');
  } finally { exporting.value = false; }
}
</script>

<style scoped src="@/styles/module-layout.css"></style>
