<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>数据导出</h1>
                <p class="subtitle">将透析、血压、营养等数据导出为 CSV 文件，便于外部分析与归档。</p>
              </div>
            </div>
          </div>
        </div>

        <div class="content-panel">
          <el-form :model="form" label-width="110px" ref="formRef" :rules="rules">
            <el-form-item label="数据类型" prop="dataType">
              <el-select v-model="form.dataType" style="width: 100%">
                <el-option v-for="opt in dataTypeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-row :gutter="16">
              <el-col :xs="24" :sm="8">
                <el-form-item label="时间维度" prop="timeType">
                  <el-select v-model="form.timeType" style="width: 100%">
                    <el-option label="按月" value="month" />
                    <el-option label="按周" value="week" />
                    <el-option label="按年" value="year" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="8">
                <el-form-item label="时间范围" prop="timeValue">
                  <el-date-picker
                    v-model="form.timeValue"
                    :type="form.timeType === 'week' ? 'date' : form.timeType"
                    :value-format="form.timeType === 'year' ? 'YYYY' : form.timeType === 'month' ? 'YYYY-MM' : 'YYYY-MM-DD'"
                    placeholder="选择时间"
                    style="width: 100%"
                    :clearable="false"
                  />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item>
              <el-button type="primary" @click="handleExport" :loading="exporting">
                <el-icon><Download /></el-icon>导出 CSV
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
    options.push({ label: '透析记录', value: 'dialysis' });
  }
  // Blood Pressure Pattern Analysis
  if (hasMenuName('血压模式分析')) {
    options.push({ label: '血压规律分析', value: 'bp_analysis' });
  }
  // Nutrition Diary
  if (hasMenuName('营养日记')) {
    options.push({ label: '营养日记', value: 'nutrition' });
  }
  // Complication Tracking
  if (hasMenuName('并发症跟踪')) {
    options.push({ label: '并发症记录', value: 'complication' });
  }
  // medicationrecord: needneedMedicationmanagementPermission
  if (hasMenu('/medication')) {
    options.push({ label: '用药记录', value: 'medication' });
  }
  // Blood GlucoseBlood Pressurerecord
  if (hasMenuName('血压血糖记录')) {
    options.push({ label: '血压血糖记录', value: 'bp_self_monitor' });
  }
  return options;
});

const DATA_TYPE_LABELS = {
  dialysis: '透析记录',
  bp_analysis: '血压规律分析',
  nutrition: '营养日记',
  complication: '并发症记录',
  medication: '用药记录',
  bp_self_monitor: '血压血糖记录'
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
  dataType: [{ required: true, message: '请选择数据类型', trigger: 'change' }],
  timeType: [{ required: true, message: '请选择时间维度', trigger: 'change' }],
  timeValue: [{ required: true, message: '请选择时间范围', trigger: 'change' }]
};

async function handleExport() {
  if (!currentPatientId.value) { ElMessage.warning('请先选择患者。'); return; }
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
    link.download = `${DATA_TYPE_LABELS[form.dataType] || form.dataType}_数据导出.csv`;
    link.click();
    window.URL.revokeObjectURL(url);
    ElMessage.success('数据已导出');
  } catch (e) {
    ElMessage.error('导出失败');
  } finally { exporting.value = false; }
}
</script>

<style scoped src="@/styles/module-layout.css"></style>
