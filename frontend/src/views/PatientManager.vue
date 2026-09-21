<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>患者管理</h1>
                <p class="subtitle">维护患者基本信息，方便在各功能模块中快速选择。</p>
              </div>
            </div>
          </div>
        </div>
        <div class="content-panel">
          <div class="toolbar">
            <el-button type="primary" @click="showAddDialog">
              <el-icon><Plus /></el-icon>新增患者
            </el-button>
            <el-button @click="showSensitive = !showSensitive">{{ showSensitive ? '隐藏敏感信息' : '显示敏感信息' }}</el-button>
          </div>
          <el-table :data="displayPatients" stripe class="app-data-table">
            <el-table-column v-if="patientColVisible('name')" prop="name" label="姓名" min-width="96" />
            <el-table-column v-if="patientColVisible('gender')" prop="gender" label="性别" width="72" align="center">
              <template #default="{ row }">
                <span v-if="row.gender === 'MALE'">男</span>
                <span v-else-if="row.gender === 'FEMALE'">女</span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column v-if="patientColVisible('birthDate')" prop="birthDate" label="出生日期" width="108" />
            <el-table-column v-if="patientColVisible('phone')" prop="phone" label="电话" min-width="112" />
            <el-table-column v-if="patientColVisible('idCard')" prop="idCard" label="身份证号" min-width="148" show-overflow-tooltip />
            <el-table-column v-if="patientColVisible('address')" prop="address" label="地址" min-width="140" show-overflow-tooltip />
            <el-table-column v-if="patientColVisible('status')" prop="status" label="状态" width="80" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.status === 1" type="success" size="small">正常</el-tag>
                <el-tag v-else type="danger" size="small">停用</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="148" align="center" fixed="right">
              <template #header>
                <TableActionHeader v-model="patientVisibleCols" :columns="PATIENT_COLUMN_DEFS" @reset="resetPatientColumns" />
              </template>
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="showEditDialog(row)">编辑</el-button>
                <el-button link type="success" size="small" @click="showClinicalDialog(row)">临床信息</el-button>
                <el-popconfirm title="确认删除吗？" @confirm="handleDelete(row.id)">
                  <template #reference>
                    <el-button link type="danger" size="small">删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-main>

    <!-- clinicalinformationdialog -->
    <el-dialog v-model="clinicalDialogVisible" title="患者临床信息" width="640px" destroy-on-close>
      <el-form :model="clinicalForm" label-width="120px" ref="clinicalFormRef">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="透析类型">
              <el-select v-model="clinicalForm.dialysisType" placeholder="选择透析类型" style="width: 100%">
                <el-option label="血液透析（HD）" value="HD" />
                <el-option label="腹膜透析（PD）" value="PD" />
                <el-option label="CRRT" value="CRRT" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="开始透析日期">
              <el-date-picker v-model="clinicalForm.dialysisStartDate" type="date" placeholder="选择日期" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="血管通路">
              <el-select v-model="clinicalForm.vascularAccess" placeholder="请选择" style="width: 100%">
                <el-option label="动静脉内瘘（AVF）" value="AVF" />
                <el-option label="人工血管内瘘（AVG）" value="AVG" />
                <el-option label="中心静脉导管（CATH）" value="CATH" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="目标干体重（kg）">
              <el-input-number v-model="clinicalForm.targetDryWeight" :precision="2" :min="20" :max="200" :step="0.1" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="原发病 / 主要诊断">
          <el-input v-model="clinicalForm.primaryDiagnosis" placeholder="例如：慢性肾小球肾炎、糖尿病肾病等" />
        </el-form-item>
        <el-form-item label="药物过敏史">
          <el-input v-model="clinicalForm.allergyDrugs" type="textarea" :rows="2" placeholder="过敏药物清单" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="每日液体上限（mL）">
              <el-input-number v-model="clinicalForm.fluidLimitMl" :min="0" :max="5000" :step="100" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注">
          <el-input v-model="clinicalForm.remark" type="textarea" :rows="2" placeholder="备注信息" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="clinicalDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSaveClinical">保存</el-button>
      </template>
    </el-dialog>

    <!-- Add/EditPatient -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑患者' : '新增患者'" width="600px" destroy-on-close>
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="姓名" prop="name">
              <el-input v-model="form.name" placeholder="请输入姓名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="性别">
              <el-select v-model="form.gender" placeholder="请选择" style="width: 100%">
                <el-option label="男" value="MALE" />
                <el-option label="女" value="FEMALE" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="出生日期">
              <el-date-picker v-model="form.birthDate" type="date" placeholder="选择日期" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机号码">
              <el-input v-model="form.phone" placeholder="请输入手机号码" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="身份证号">
          <el-input v-model="form.idCard" placeholder="请输入身份证号" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" placeholder="请输入地址" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="紧急联系人">
              <el-input v-model="form.emergencyContact" placeholder="紧急联系人姓名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="紧急联系电话">
              <el-input v-model="form.emergencyPhone" placeholder="紧急联系人电话" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="既往病史">
          <el-input v-model="form.medicalHistory" type="textarea" :rows="2" placeholder="简要记录既往病史" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="备注信息" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">正常</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { getPatientList, savePatient, updatePatient, deletePatient } from '@/api/patient.js';
import { getClinicalByPatient, saveClinical } from '@/api/patientClinical.js';
import TableActionHeader from '@/components/TableActionHeader.vue';
import { useTableColumns } from '@/composables/useTableColumns';

const PATIENT_COLUMN_DEFS = [
  { key: 'name', label: '姓名' },
  { key: 'gender', label: '性别' },
  { key: 'birthDate', label: '出生日期', default: false },
  { key: 'phone', label: '电话' },
  { key: 'idCard', label: '身份证号', default: false },
  { key: 'address', label: '地址', default: false },
  { key: 'status', label: '状态' }
];
const { visibleKeys: patientVisibleCols, isVisible: patientColVisible, resetColumns: resetPatientColumns } =
  useTableColumns('patient-list', PATIENT_COLUMN_DEFS);

const patients = ref([]);
const showSensitive = ref(false);
const displayPatients = computed(() => showSensitive.value ? patients.value : patients.value.map(item => ({
  ...item,
  phone: item.phone && item.phone.length >= 7 ? `${item.phone.slice(0,3)}****${item.phone.slice(-4)}` : item.phone,
  idCard: item.idCard && item.idCard.length >= 8 ? `${item.idCard.slice(0,3)}***********${item.idCard.slice(-4)}` : item.idCard,
  address: item.address && item.address.length > 6 ? `${item.address.slice(0,6)}***` : item.address
})));
const dialogVisible = ref(false);
const isEdit = ref(false);
const formRef = ref(null);

const form = reactive({
  id: null, name: '', gender: '', birthDate: '', phone: '', idCard: '',
  address: '', emergencyContact: '', emergencyPhone: '', medicalHistory: '',
  remark: '', status: 1
});

const rules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }]
};

// ---- clinicalinformation ----
const clinicalDialogVisible = ref(false);
const clinicalFormRef = ref(null);
const clinicalPatientId = ref(null);
const clinicalForm = reactive({
  id: null, patientId: null, dialysisType: '', dialysisStartDate: '', vascularAccess: '',
  primaryDiagnosis: '', allergyDrugs: '', targetDryWeight: null, fluidLimitMl: null, remark: ''
});

async function showClinicalDialog(row) {
  clinicalPatientId.value = row.id;
  Object.assign(clinicalForm, {
    id: null, patientId: row.id, dialysisType: '', dialysisStartDate: '', vascularAccess: '',
    primaryDiagnosis: '', allergyDrugs: '', targetDryWeight: null, fluidLimitMl: null, remark: ''
  });
  try {
    const res = await getClinicalByPatient(row.id);
    if (res.code === 200 && res.data) {
      Object.assign(clinicalForm, res.data);
    }
  } catch (e) { console.error(e); }
  clinicalDialogVisible.value = true;
}

async function handleSaveClinical() {
  try {
    const res = await saveClinical(clinicalForm);
    if (res.code === 200) {
      ElMessage.success('临床信息保存成功');
      clinicalDialogVisible.value = false;
    } else {
      ElMessage.error(res.msg || '保存失败');
    }
  } catch (e) {
    ElMessage.error('保存失败');
  }
}

async function loadPatients() {
  try {
    const res = await getPatientList();
    if (res.code === 200) patients.value = res.data || [];
  } catch (e) {
    console.error(e);
  }
}

function showAddDialog() {
  isEdit.value = false;
  Object.assign(form, {
    id: null, name: '', gender: '', birthDate: '', phone: '', idCard: '',
    address: '', emergencyContact: '', emergencyPhone: '', medicalHistory: '',
    remark: '', status: 1
  });
  dialogVisible.value = true;
}

function showEditDialog(row) {
  isEdit.value = true;
  const source = patients.value.find(item => item.id === row.id) ?? row;
  Object.assign(form, { ...source });
  dialogVisible.value = true;
}

async function handleSave() {
  try {
    await formRef.value.validate();
    const res = isEdit.value ? await updatePatient(form) : await savePatient(form);
    if (res.code === 200) {
      ElMessage.success(res.data || '保存成功');
      dialogVisible.value = false;
      loadPatients();
    } else {
      ElMessage.error(res.msg || '保存失败');
    }
  } catch (e) {
    console.error(e);
  }
}

async function handleDelete(id) {
  const res = await deletePatient(id);
  if (res.code === 200) {
    ElMessage.success('删除成功');
    loadPatients();
  } else {
    ElMessage.error(res.msg || '删除失败');
  }
}

onMounted(() => {
  loadPatients();
});
</script>

<style scoped src="@/styles/module-layout.css"></style>
