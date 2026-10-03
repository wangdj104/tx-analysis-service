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
                <el-button v-if="nurseAssignmentEnabled" class="nurse-assignment-entry" link type="primary" size="small" @click="nurseAssignmentPatient=row">Assign nursing team</el-button>
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

    <el-dialog :model-value="!!nurseAssignmentPatient" @update:model-value="value=>{if(!value)nurseAssignmentPatient=null}" title="Nursing team assignments" width="min(640px,94vw)" destroy-on-close><NurseAssignments v-if="nurseAssignmentPatient" :patient-id="nurseAssignmentPatient.id" /></el-dialog>
    <!-- clinicalinformationdialog -->
    <el-dialog v-model="clinicalDialogVisible" title="Patientclinicalinformation" width="min(640px, 94vw)" destroy-on-close>
      <p class="clinical-patient-context">Patient: {{ clinicalPatientName || clinicalPatientId }}</p>
      <el-alert v-if="clinicalError" :title="clinicalError" type="error" :closable="false" show-icon />
      <el-form v-loading="clinicalLoading" :disabled="clinicalLoading || !clinicalReady || clinicalFormSaving" :model="clinicalForm" label-width="120px" ref="clinicalFormRef">
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
        <el-button v-if="clinicalError" @click="showClinicalDialog({ id: clinicalPatientId, name: clinicalPatientName })">Retry</el-button>
        <el-button type="primary" :loading="clinicalSaving" :disabled="clinicalLoading || !clinicalReady" @click="handleSaveClinical">Save</el-button>
      </template>
    </el-dialog>

    <!-- Add/EditPatient -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? 'EditPatient' : 'AddPatient'" width="600px" destroy-on-close>
      <el-form :disabled="profileSaving" :model="form" label-width="100px" :rules="rules" ref="formRef">
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
          <el-select v-model="form.specialtyRoleIds" multiple filterable clearable :loading="specialtyLoading" :disabled="profileSaving || specialtyLoading || !specialtyReady" placeholder="Select specialties; empty means general patient" style="width:100%">
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
        <el-button type="primary" :loading="profileSaving" :disabled="profileSaving || specialtyLoading || !specialtyReady" @click="handleSave">Save</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { getPatientList, savePatient, updatePatient, deletePatient, getSpecialtyRoles, getPatientSpecialtyRoles } from '@/api/patient.js';
import { getClinicalByPatient, saveClinical } from '@/api/patientClinical.js';
import TableActionHeader from '@/components/TableActionHeader.vue';
import { useTableColumns } from '@/composables/useTableColumns';
import NurseAssignments from '@/components/care-plan/NurseAssignments.vue';
import { getCarePlanCapabilities } from '@/api/carePlan';
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession';
import { localizeSpecialtyRole } from '@/utils/specialtyRoleLabels';

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

const nurseAssignmentPatient=ref(null),nurseAssignmentEnabled=ref(false);
onMounted(async()=>{const auth=captureAuthSession(),actor=localStorage.getItem('userId');try{const roles=JSON.parse(localStorage.getItem('userRoleCodes')||'[]');if(!roles.includes('admin'))return;const result=await getCarePlanCapabilities({expectedAuth:{...auth,actorId:actor}});if(isAuthSessionCurrent(auth)&&actor===localStorage.getItem('userId'))nurseAssignmentEnabled.value=result.data?.enabled===true}catch{nurseAssignmentEnabled.value=false}});
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
const specialtyRoles = ref([]);
const catalogLoading = ref(false), catalogReady = ref(false);
const selectedRolesLoading = ref(false), selectedRolesReady = ref(false);
const specialtyLoading = computed(() => catalogLoading.value || selectedRolesLoading.value);
const specialtyReady = computed(() => catalogReady.value && selectedRolesReady.value);
const profileSaving = ref(false);
let profileEpoch = 0, catalogRequest = 0, profileUnmounted = false;

function invalidateProfileDialog() {
  profileEpoch++;
  catalogRequest++;
  catalogLoading.value = false;
  selectedRolesLoading.value = false;
  selectedRolesReady.value = false;
  profileSaving.value = false;
}

function isCurrentProfile(epoch) {
  return !profileUnmounted && dialogVisible.value && epoch === profileEpoch;
}

async function loadSpecialtyRoles(epoch = profileEpoch) {
  const request = ++catalogRequest;
  const isCurrent = () => !profileUnmounted && epoch === profileEpoch && request === catalogRequest;
  catalogLoading.value = true;
  catalogReady.value = false;
  try {
    const res = await getSpecialtyRoles();
    if (!isCurrent()) return;
    if (res.code !== 200 || !Array.isArray(res.data)) throw new Error('Specialty roles unavailable');
    specialtyRoles.value = res.data.map(localizeSpecialtyRole);
    catalogReady.value = true;
  } catch {
    if (isCurrent()) ElMessage.error('Unable to load specialty roles. Please retry.');
  } finally {
    if (isCurrent()) catalogLoading.value = false;
  }
}

async function loadPatientSpecialties(epoch, patientId) {
  selectedRolesLoading.value = true;
  selectedRolesReady.value = false;
  try {
    const res = await getPatientSpecialtyRoles(patientId);
    if (!isCurrentProfile(epoch)) return;
    if (res.code !== 200 || !Array.isArray(res.data)) throw new Error('Patient specialty roles unavailable');
    form.specialtyRoleIds = [...res.data];
    selectedRolesReady.value = true;
  } catch {
    if (isCurrentProfile(epoch)) ElMessage.error('Unable to load specialty roles. Reopen this patient.');
  } finally {
    if (isCurrentProfile(epoch)) selectedRolesLoading.value = false;
  }
}

watch(dialogVisible, visible => { if (!visible) invalidateProfileDialog(); }, { flush: 'sync' });
onUnmounted(() => { profileUnmounted = true; invalidateProfileDialog(); });

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
const clinicalPatientName = ref('');
const clinicalLoading = ref(false), clinicalReady = ref(false), clinicalSaving = ref(false), clinicalFormSaving = ref(false);
const clinicalError = ref('');
let clinicalEpoch = 0;
const clinicalForm = reactive({
  id: null, patientId: null, dialysisType: '', dialysisStartDate: '', vascularAccess: '',
  primaryDiagnosis: '', allergyDrugs: '', targetDryWeight: null, fluidLimitMl: null, remark: ''
});

function invalidateClinicalDialog() {
  clinicalEpoch++;
  clinicalLoading.value = false;
  clinicalReady.value = false;
  clinicalFormSaving.value = false;
}

function isCurrentClinical(epoch, patientId) {
  return clinicalDialogVisible.value && epoch === clinicalEpoch && patientId === clinicalPatientId.value;
}

async function showClinicalDialog(row) {
  if (!row?.id) return;
  invalidateClinicalDialog();
  const epoch = clinicalEpoch, patientId = row.id;
  clinicalPatientId.value = patientId;
  clinicalPatientName.value = row.name || row.patientName || '';
  clinicalError.value = '';
  Object.assign(clinicalForm, {
    id: null, patientId, dialysisType: '', dialysisStartDate: '', vascularAccess: '',
    primaryDiagnosis: '', allergyDrugs: '', targetDryWeight: null, fluidLimitMl: null, remark: ''
  });
  clinicalDialogVisible.value = true;
  clinicalLoading.value = true;
  try {
    const res = await getClinicalByPatient(patientId);
    if (!isCurrentClinical(epoch, patientId)) return;
    if (res.code !== 200 || (res.data && Number(res.data.patientId) !== Number(patientId))) throw new Error('Invalid clinical context');
    if (res.data) Object.assign(clinicalForm, res.data, { patientId });
    clinicalReady.value = true;
  } catch (e) {
    if (!isCurrentClinical(epoch, patientId)) return;
    clinicalError.value = 'Clinical information could not be loaded. Retry before saving.';
    ElMessage.error(clinicalError.value);
  } finally {
    if (isCurrentClinical(epoch, patientId)) clinicalLoading.value = false;
  }
}

async function handleSaveClinical() {
  const epoch = clinicalEpoch, patientId = clinicalPatientId.value;
  if (clinicalSaving.value || !clinicalReady.value || !isCurrentClinical(epoch, patientId)
      || clinicalForm.patientId !== patientId) return;
  const snapshot = { ...clinicalForm };
  clinicalSaving.value = true;
  clinicalFormSaving.value = true;
  try {
    const res = await saveClinical(snapshot);
    if (!isCurrentClinical(epoch, patientId)) return;
    if (res.code === 200) {
      ElMessage.success('Clinical information saved successfully');
      clinicalDialogVisible.value = false;
    } else {
      ElMessage.error(res.msg || 'Failed to save. Please try again.');
    }
  } catch (e) {
    if (isCurrentClinical(epoch, patientId)) ElMessage.error('Failed to save. Please try again.');
  } finally {
    clinicalSaving.value = false;
    if (epoch === clinicalEpoch) clinicalFormSaving.value = false;
  }
}

watch(clinicalDialogVisible, visible => { if (!visible) invalidateClinicalDialog(); }, { flush: 'sync' });
onUnmounted(invalidateClinicalDialog);

async function loadPatients() {
  try {
    const res = await getPatientList();
    if (res.code === 200) patients.value = res.data || [];
  } catch (e) {
    console.error(e);
  }
}

function showAddDialog() {
  if (profileUnmounted) return;
  invalidateProfileDialog();
  isEdit.value = false;
  Object.assign(form, {
    id: null, name: '', gender: '', birthDate: '', phone: '', idCard: '',
    address: '', emergencyContact: '', emergencyPhone: '', medicalHistory: '',
    remark: '', status: 1, specialtyRoleIds: []
  });
  selectedRolesReady.value = true;
  dialogVisible.value = true;
  if (!catalogReady.value) loadSpecialtyRoles();
}

async function showEditDialog(row) {
  if (!row?.id || profileUnmounted) return;
  invalidateProfileDialog();
  const epoch = profileEpoch, patientId = row.id;
  isEdit.value = true;
  const source = patients.value.find(item => item.id === patientId) ?? row;
  Object.assign(form, { ...source, specialtyRoleIds: [] });
  dialogVisible.value = true;
  await Promise.all([loadSpecialtyRoles(epoch), loadPatientSpecialties(epoch, patientId)]);
}

async function handleSave() {
  const epoch = profileEpoch;
  if (profileSaving.value || !isCurrentProfile(epoch) || !specialtyReady.value
      || specialtyLoading.value || !formRef.value) return;
  const save = isEdit.value ? updatePatient : savePatient;
  const snapshot = { ...form, specialtyRoleIds: [...form.specialtyRoleIds] };
  const validator = formRef.value;
  profileSaving.value = true;
  let submitted = false;
  try {
    const valid = await validator.validate();
    if (valid === false || !isCurrentProfile(epoch) || !specialtyReady.value || specialtyLoading.value) return;
    submitted = true;
    const res = await save(snapshot);
    if (!isCurrentProfile(epoch)) return;
    if (res.code === 200) {
      ElMessage.success(res.data || 'Saved successfully');
      dialogVisible.value = false;
      loadPatients();
      window.dispatchEvent(new Event('patient-specialty-changed'));
    } else {
      ElMessage.error(res.msg || 'Failed to save');
    }
  } catch {
    if (submitted && isCurrentProfile(epoch)) ElMessage.error('Failed to save. Please try again.');
  } finally {
    if (isCurrentProfile(epoch)) profileSaving.value = false;
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

<style scoped>
.nurse-assignment-entry{min-height:44px;min-width:44px}
.nurse-assignment-entry:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}
</style>
