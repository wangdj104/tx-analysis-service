<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>Patient Management</h1>
                <p class="subtitle">managementPatientinformation, convenientineachmoduleQuickselect</p>
              </div>
            </div>
          </div>
        </div>
        <div class="content-panel">
          <div class="toolbar">
            <el-button type="primary" @click="showAddDialog">
              <el-icon><Plus /></el-icon>AddPatient
            </el-button>
            <el-button @click="showSensitive = !showSensitive">{{ showSensitive ? 'hidesensitiveinformation' : 'displaysensitiveinformation' }}</el-button>
          </div>
          <el-table :data="displayPatients" stripe class="app-data-table">
            <el-table-column v-if="patientColVisible('name')" prop="name" label="Name" min-width="96" />
            <el-table-column v-if="patientColVisible('gender')" prop="gender" label="Sex" width="72" align="center">
              <template #default="{ row }">
                <span v-if="row.gender === 'MALE'">Male</span>
                <span v-else-if="row.gender === 'FEMALE'">Female</span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column v-if="patientColVisible('birthDate')" prop="birthDate" label="Date of Birth" width="108" />
            <el-table-column v-if="patientColVisible('phone')" prop="phone" label="phone" min-width="112" />
            <el-table-column v-if="patientColVisible('idCard')" prop="idCard" label="bodycopycertificate" min-width="148" show-overflow-tooltip />
            <el-table-column v-if="patientColVisible('address')" prop="address" label="address" min-width="140" show-overflow-tooltip />
            <el-table-column v-if="patientColVisible('status')" prop="status" label="Status" width="80" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.status === 1" type="success" size="small">Normal</el-tag>
                <el-tag v-else type="danger" size="small">Disabled</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="Actions" width="148" align="center" fixed="right">
              <template #header>
                <TableActionHeader v-model="patientVisibleCols" :columns="PATIENT_COLUMN_DEFS" @reset="resetPatientColumns" />
              </template>
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="showEditDialog(row)">Edit</el-button>
                <el-button link type="success" size="small" @click="showClinicalDialog(row)">clinical</el-button>
                <el-popconfirm title="Confirm deletion?" @confirm="handleDelete(row.id)">
                  <template #reference>
                    <el-button link type="danger" size="small">Delete</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-main>

    <!-- clinicalinformationdialog -->
    <el-dialog v-model="clinicalDialogVisible" title="Patientclinicalinformation" width="640px" destroy-on-close>
      <el-form :model="clinicalForm" label-width="120px" ref="clinicalFormRef">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Dialysistype">
              <el-select v-model="clinicalForm.dialysisType" placeholder="SelectDialysistype" style="width: 100%">
                <el-option label="bloodDialysis(HD)" value="HD" />
                <el-option label="peritonealDialysis(PD)" value="PD" />
                <el-option label="CRRT" value="CRRT" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="startDialysis Date">
              <el-date-picker v-model="clinicalForm.dialysisStartDate" type="date" placeholder="selectDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="vascular access">
              <el-select v-model="clinicalForm.vascularAccess" placeholder="Select" style="width: 100%">
                <el-option label="arteriovenous fistula(AVF)" value="AVF" />
                <el-option label="personworkbloodmanagewithinfistula(AVG)" value="AVG" />
                <el-option label="central venous catheter(CATH)" value="CATH" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="targetDry Weight(kg)">
              <el-input-number v-model="clinicalForm.targetDryWeight" :precision="2" :min="20" :max="200" :step="0.1" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="originalonset/primary diagnosis">
          <el-input v-model="clinicalForm.primaryDiagnosis" placeholder="for example : slowlypropertykidneysmallglobulekidneyinflammation, glucoseurinediseasekidneydiseaseetc." />
        </el-form-item>
        <el-form-item label="Medicationallergy history">
          <el-input v-model="clinicalForm.allergyDrugs" type="textarea" :rows="2" placeholder="allergyMedicationlist" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Dayfluidup limit(ml)">
              <el-input-number v-model="clinicalForm.fluidLimitMl" :min="0" :max="5000" :step="100" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="Notes">
          <el-input v-model="clinicalForm.remark" type="textarea" :rows="2" placeholder="Notesinformation" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="clinicalDialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="handleSaveClinical">Save</el-button>
      </template>
    </el-dialog>

    <!-- Add/EditPatient -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? 'EditPatient' : 'AddPatient'" width="600px" destroy-on-close>
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Name" prop="name">
              <el-input v-model="form.name" placeholder="Enter Name" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Sex">
              <el-select v-model="form.gender" placeholder="Select" style="width: 100%">
                <el-option label="Male" value="MALE" />
                <el-option label="Female" value="FEMALE" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Date of Birth">
              <el-date-picker v-model="form.birthDate" type="date" placeholder="selectDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Phone Number">
              <el-input v-model="form.phone" placeholder="Enter Phone Number" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="ID Number">
          <el-input v-model="form.idCard" placeholder="Enter ID Number" />
        </el-form-item>
        <el-form-item label="address">
          <el-input v-model="form.address" placeholder="Enter address" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Urgentcontact">
              <el-input v-model="form.emergencyContact" placeholder="UrgentcontactName" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Urgentphone">
              <el-input v-model="form.emergencyPhone" placeholder="Urgentcontactphone" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="medical history">
          <el-input v-model="form.medicalHistory" type="textarea" :rows="2" placeholder="medical historysimpleneedrecord" />
        </el-form-item>
        <el-form-item label="Notes">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="Notesinformation" />
        </el-form-item>
        <el-form-item label="Specialty roles">
          <el-select v-model="form.specialtyRoleIds" multiple filterable clearable :loading="specialtyLoading" placeholder="Select specialties; empty means general patient" style="width:100%">
            <el-option v-for="role in specialtyRoles" :key="role.id" :label="role.roleName" :value="role.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="Status">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">Normal</el-radio>
            <el-radio :value="0">Disabled</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">Cancel</el-button>
        <el-button type="primary" :disabled="specialtyLoading || !specialtyReady" @click="handleSave">Save</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { getPatientList, savePatient, updatePatient, deletePatient, getSpecialtyRoles, getPatientSpecialtyRoles } from '@/api/patient.js';
import { getClinicalByPatient, saveClinical } from '@/api/patientClinical.js';
import TableActionHeader from '@/components/TableActionHeader.vue';
import { useTableColumns } from '@/composables/useTableColumns';

const PATIENT_COLUMN_DEFS = [
  { key: 'name', label: 'Name' },
  { key: 'gender', label: 'Sex' },
  { key: 'birthDate', label: 'Date of Birth', default: false },
  { key: 'phone', label: 'phone' },
  { key: 'idCard', label: 'bodycopycertificate', default: false },
  { key: 'address', label: 'address', default: false },
  { key: 'status', label: 'Status' }
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
const specialtyRoles = ref([]), specialtyLoading = ref(false), specialtyReady = ref(false);

async function loadSpecialtyRoles() {
  specialtyLoading.value = true;
  try { const res = await getSpecialtyRoles(); if (res.code !== 200 || !Array.isArray(res.data)) throw new Error('Specialty roles unavailable'); specialtyRoles.value = res.data; specialtyReady.value = true; }
  catch { specialtyReady.value = false; ElMessage.error('Unable to load specialty roles. Please retry.'); }
  finally { specialtyLoading.value = false; }
}

const form = reactive({
  id: null, name: '', gender: '', birthDate: '', phone: '', idCard: '',
  address: '', emergencyContact: '', emergencyPhone: '', medicalHistory: '',
  remark: '', status: 1, specialtyRoleIds: []
});

const rules = {
  name: [{ required: true, message: 'Enter Name', trigger: 'blur' }]
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
      ElMessage.success('Clinical information saved successfully');
      clinicalDialogVisible.value = false;
    } else {
      ElMessage.error(res.msg || 'Failed to save');
    }
  } catch (e) {
    ElMessage.error('Failed to save');
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
    remark: '', status: 1, specialtyRoleIds: []
  });
  dialogVisible.value = true;
  if (!specialtyReady.value) loadSpecialtyRoles();
}

async function showEditDialog(row) {
  isEdit.value = true;
  const source = patients.value.find(item => item.id === row.id) ?? row;
  Object.assign(form, { ...source, specialtyRoleIds: [] });
  dialogVisible.value = true;
  specialtyLoading.value = true;
  specialtyReady.value = false;
  try {
    const [roles, selected] = await Promise.all([getSpecialtyRoles(), getPatientSpecialtyRoles(row.id)]);
    if (form.id !== row.id || !dialogVisible.value) return;
    if (roles.code !== 200 || selected.code !== 200 || !Array.isArray(roles.data) || !Array.isArray(selected.data)) throw new Error('Specialty roles unavailable');
    specialtyRoles.value = roles.data;
    form.specialtyRoleIds = selected.data;
    specialtyReady.value = true;
  } catch { ElMessage.error('Unable to load specialty roles. Reopen this patient.'); }
  finally { specialtyLoading.value = false; }
}

async function handleSave() {
  if (!specialtyReady.value || specialtyLoading.value) return;
  try {
    await formRef.value.validate();
    const res = isEdit.value ? await updatePatient(form) : await savePatient(form);
    if (res.code === 200) {
      ElMessage.success(res.data || 'Saved successfully');
      dialogVisible.value = false;
      loadPatients();
      window.dispatchEvent(new Event('patient-specialty-changed'));
    } else {
      ElMessage.error(res.msg || 'Failed to save');
    }
  } catch (e) {
    console.error(e);
  }
}

async function handleDelete(id) {
  const res = await deletePatient(id);
  if (res.code === 200) {
    ElMessage.success('Deleted successfully');
    loadPatients();
  } else {
    ElMessage.error(res.msg || 'Failed to delete');
  }
}

onMounted(() => {
  loadPatients();
  loadSpecialtyRoles();
});
</script>

<style scoped src="@/styles/module-layout.css"></style>
