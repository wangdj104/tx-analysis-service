<template>
  <el-container class="module-page medication-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar medication-heading">
            <div class="left">
              <div>
                <h1>{{ pageTitle }}</h1>
                <p class="subtitle">{{ pageSubtitle }}</p>
              </div>
            </div>
            <img
              class="medication-heading-art"
              :src="medicationCareSmall"
              :srcset="`${medicationCareSmall} 320w, ${medicationCare} 640w`"
              sizes="(max-width: 375px) 80px, (max-width: 768px) 96px, 160px"
              width="320"
              height="213"
              alt=""
              decoding="async"
            />
          </div>
        </div>

        <div class="content-panel">
          <!-- Upload and Recognize -->
          <div v-show="activeMenu === 'upload'" class="upload-panel">
            <el-form :model="uploadForm" label-width="100px" class="upload-form">
              <el-form-item label="Patient">
                <el-select v-model="uploadForm.patientId" placeholder="Select a patient (Optional) " filterable clearable style="max-width: 320px">
                  <el-option
                    v-for="patient in patientList"
                    :key="patient.id"
                    :label="patient.patientName"
                    :value="patient.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="Uploadfile" required>
                <el-upload
                  ref="uploadRef"
                  drag
                  action="#"
                  :auto-upload="false"
                  :limit="10"
                  multiple
                  accept="image/*,.pdf,.doc,.docx"
                  :on-change="handleFileChange"
                  class="upload-drop"
                >
                  <el-icon :size="40"><UploadFilled /></el-icon>
                  <div class="upload-text">will Medicationpackage or instructionsdocumentdragto thisplace,  or <em>clickUpload</em>, alsocan <em>paste</em>image (supportmultipleimages) </div>
                  <template #tip>
                    <div class="el-upload__tip">supportimage(jpg/png), PDF, Word(doc/docx), largefilerecognitioncan canneedneedrelativelylongTime, Please please wait</div>
                  </template>
                </el-upload>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="recognizeLoading" @click="startRecognize">Start recognition</el-button>
                <el-button v-if="recognizeResult" type="success" @click="saveRecognizedDrug">
                  {{ recognizedDrugs.length > 1 ? `Save ${recognizedDrugs.length} itemsMedicationto Medicationdatabase` : 'Saveto Medicationdatabase' }}
                </el-button>
              </el-form-item>
            </el-form>

            <div v-if="recognizeLoading" class="recognize-loading">
              <el-icon class="is-loading" :size="24"><Loading /></el-icon>
              <span>AI positivein recognitionin, largefilecan canneedneed 1~3 minutes, Please please wait...</span>
            </div>

            <div v-if="recognizeResult" class="recognize-result">
              <el-divider content-position="left">recognitionresult (can Editafter Save) </el-divider>
              <el-alert
                v-if="recognizeWarning"
                :title="recognizeWarning"
                type="warning"
                :closable="false"
                show-icon
                style="margin-bottom: 12px"
              />
              <el-tabs v-if="recognizedDrugs.length > 1" v-model="activeRecognizeTab" class="recognize-tabs">
                <el-tab-pane
                  v-for="(drug, idx) in recognizedDrugs"
                  :key="idx"
                  :label="(drug.drugName || 'Medication ' + (idx + 1))"
                  :name="String(idx)"
                />
              </el-tabs>
              <el-form :model="currentRecognizeDrug" label-width="100px">
                <el-form-item label="Medication Name" required>
                  <el-input v-model="currentRecognizeDrug.drugName" />
                </el-form-item>
                <el-form-item label="Generic Name">
                  <el-input v-model="currentRecognizeDrug.genericName" />
                </el-form-item>
                <el-form-item label="Specification">
                  <el-input v-model="currentRecognizeDrug.specification" placeholder="for example : 10mg*28tablet" />
                </el-form-item>
                <el-form-item label="Unit">
                  <el-input v-model="currentRecognizeDrug.unit" placeholder="tablet/dose/bottle" />
                </el-form-item>
                <el-form-item label="dosage form">
                  <el-select v-model="currentRecognizeDrug.dosageForm" placeholder="Select">
                    <el-option label="tablet" value="TABLET" />
                    <el-option label="capsule" value="CAPSULE" />
                    <el-option label="injection" value="INJECTION" />
                    <el-option label="oral solution" value="SOLUTION" />
                    <el-option label="powder" value="POWDER" />
                  </el-select>
                </el-form-item>
                <el-form-item label="Medicationcategory">
                  <el-select v-model="currentRecognizeDrug.category" placeholder="Select">
                    <el-option label="antihypertensive" value="ANTIHYPERTENSIVE" />
                    <el-option label="phosphate binder" value="PHOSPHATE_BINDER" />
                    <el-option label="iron supplement" value="IRON_SUPPLEMENT" />
                    <el-option label="vitamin" value="VITAMIN" />
                    <el-option label="erythropoietin" value="ESA" />
                    <el-option label="calcium supplement" value="CALCIUM" />
                    <el-option label="active vitamin DD" value="VD" />
                    <el-option label="diuretic" value="DIURETIC" />
                    <el-option label="antibiotic" value="ANTIBIOTIC" />
                    <el-option label="Other" value="OTHER" />
                  </el-select>
                </el-form-item>
                <el-form-item label="manufacturer">
                  <el-input v-model="currentRecognizeDrug.manufacturer" />
                </el-form-item>
                <el-form-item label="approval number">
                  <el-input v-model="currentRecognizeDrug.approvalNumber" />
                </el-form-item>
                <el-form-item label="DefaultDose">
                  <el-input v-model="currentRecognizeDrug.defaultDosage" placeholder="for example : each times1tablet, each days1times" />
                </el-form-item>
                <el-form-item label="Notes">
                  <el-input v-model="currentRecognizeDrug.remark" type="textarea" :rows="2" />
                </el-form-item>
              </el-form>
            </div>
          </div>

          <!-- Medicationdatabase -->
          <div v-show="activeMenu === 'drugs'">
            <div class="toolbar">
              <el-button type="primary" @click="showDrugDialog()">
                <el-icon><Plus /></el-icon>AddMedication
              </el-button>
            </div>
            <div class="table-wrap">
              <el-table :data="drugs" class="app-data-table app-data-table--list" stripe style="width: 100%">
                <el-table-column v-if="drugColVisible('drugName')" prop="drugName" label="Medication" min-width="120" show-overflow-tooltip />
                <el-table-column v-if="drugColVisible('genericName')" prop="genericName" label="Generic Name" min-width="120" show-overflow-tooltip />
                <el-table-column v-if="drugColVisible('specification')" prop="specification" label="Specification" min-width="112" show-overflow-tooltip />
                <el-table-column v-if="drugColVisible('dosageForm')" prop="dosageForm" label="dosage form" min-width="92" show-overflow-tooltip>
                  <template #default="{ row }">
                    <el-tag size="small">{{ getDosageFormName(row.dosageForm) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column v-if="drugColVisible('category')" prop="category" label="category" min-width="108" show-overflow-tooltip>
                  <template #default="{ row }">
                    <el-tag type="info" size="small">{{ getCategoryName(row.category) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column v-if="drugColVisible('manufacturer')" prop="manufacturer" label="manufacturer" min-width="140" show-overflow-tooltip />
                <el-table-column v-if="drugColVisible('defaultDosage')" prop="defaultDosage" label="DefaultDose" min-width="120" show-overflow-tooltip />
                <el-table-column v-if="drugColVisible('isActive')" prop="isActive" label="Status" min-width="80" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.isActive === 1 ? 'success' : 'info'" size="small">
                      {{ row.isActive === 1 ? 'Enabled' : 'Disabled' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="Actions" width="132" fixed="right" align="center">
                  <template #header>
                    <TableActionHeader v-model="drugVisibleCols" :columns="DRUG_COLUMN_DEFS" @reset="resetDrugColumns" />
                  </template>
                  <template #default="{ row }">
                    <div class="table-actions">
                      <el-button link type="primary" size="small" @click="showDrugDialog(row)">Edit</el-button>
                      <el-button link type="danger" size="small" @click="deleteDrug(row)">Delete</el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>

          <!-- medicationrecord -->
          <div v-show="activeMenu === 'logs'">
            <div class="toolbar">
              <el-form :inline="true" class="filter-form">
                <el-form-item label="Patient">
                  <el-select v-model="logFilter.patientId" placeholder="Select a patient" filterable clearable style="width: 200px">
                    <el-option
                      v-for="patient in patientList"
                      :key="patient.id"
                      :label="patient.patientName"
                      :value="patient.id"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="loadLogs">
                    <el-icon><Search /></el-icon>query
                  </el-button>
                </el-form-item>
              </el-form>
              <el-button type="primary" @click="showLogDialog()">
                <el-icon><Plus /></el-icon>Add record
              </el-button>
            </div>
            <div class="table-wrap">
              <el-table :data="logs" class="app-data-table app-data-table--list" stripe style="width: 100%">
                <el-table-column v-if="logColVisible('administrationTime')" prop="administrationTime" label="administrationTime" min-width="148" />
                <el-table-column v-if="logColVisible('patientName')" prop="patientName" label="Patient" min-width="100" show-overflow-tooltip />
                <el-table-column v-if="logColVisible('drugName')" prop="medication.drugName" label="Medication" min-width="120" show-overflow-tooltip />
                <el-table-column v-if="logColVisible('dosage')" prop="dosage" label="Dose" min-width="80" show-overflow-tooltip />
                <el-table-column v-if="logColVisible('adminRoute')" prop="adminRoute" label="route" min-width="80" show-overflow-tooltip>
                  <template #default="{ row }">
                    {{ getAdminRouteName(row.adminRoute) }}
                  </template>
                </el-table-column>
                <el-table-column v-if="logColVisible('prescribedBy')" prop="prescribedBy" label="Clinician" min-width="88" show-overflow-tooltip />
                <el-table-column v-if="logColVisible('effectEvaluation')" prop="effectEvaluation" label="validresult" min-width="80" show-overflow-tooltip>
                  <template #default="{ row }">
                    <el-tag :type="getEffectType(row.effectEvaluation)" size="small">{{ getEffectName(row.effectEvaluation) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column v-if="logColVisible('sideEffect')" prop="sideEffect" label="adverse reaction" min-width="120" show-overflow-tooltip />
                <el-table-column label="Actions" width="132" fixed="right" align="center">
                  <template #header>
                    <TableActionHeader v-model="logVisibleCols" :columns="LOG_COLUMN_DEFS" @reset="resetLogColumns" />
                  </template>
                  <template #default="{ row }">
                    <div class="table-actions">
                      <el-button link type="primary" size="small" @click="showLogDialog(row)">Edit</el-button>
                      <el-button link type="danger" size="small" @click="deleteLog(row)">Delete</el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>

          <!-- Browse Categories -->
          <div v-show="activeMenu === 'category'" class="category-panel">
            <div v-if="drugsByCategory.length === 0" class="category-empty">
              <el-empty description="No medication data" />
            </div>
            <div v-for="group in drugsByCategory" :key="group.category" class="category-group">
              <div class="category-group-head">
                <span class="category-group-title">{{ group.name }}</span>
                <span class="category-group-count">{{ group.items.length }} type</span>
              </div>
              <div class="table-wrap table-wrap--compact">
              <el-table :data="group.items" class="app-data-table app-data-table--compact" stripe size="small" :fit="false">
                <el-table-column prop="drugName" label="Medication Name" width="160" show-overflow-tooltip />
                <el-table-column prop="specification" label="Specification" width="120" show-overflow-tooltip />
                <el-table-column prop="defaultDosage" label="DefaultDose" width="140" show-overflow-tooltip />
                <el-table-column prop="isActive" label="Status" width="88" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.isActive === 1 ? 'success' : 'info'" size="small">
                      {{ row.isActive === 1 ? 'Enabled' : 'Disabled' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
              </div>
            </div>
          </div>

          <!-- Medication Reminders -->
          <div v-show="activeMenu === 'remind'" class="remind-panel">
            <MedicationReminderPanel :patient-id="currentPatientId" :medications="drugs" />

          </div>
        </div>
      </div>
    </el-main>

    <!-- MedicationEditdialog -->
    <el-dialog v-model="drugDialogVisible" :title="editingDrug.id ? 'EditMedication' : 'AddMedication'" width="600px" destroy-on-close>
      <el-form :model="editingDrug" label-width="100px">
        <el-form-item label="Medication Name" required>
          <el-input v-model="editingDrug.drugName" placeholder="Enter Medication Name" />
        </el-form-item>
        <el-form-item label="Generic Name">
          <el-input v-model="editingDrug.genericName" placeholder="Enter Generic Name" />
        </el-form-item>
        <el-form-item label="Specification">
          <el-input v-model="editingDrug.specification" placeholder="for example : 10mg*28tablet" />
        </el-form-item>
        <el-form-item label="Unit">
          <el-input v-model="editingDrug.unit" placeholder="tablet/dose/bottle" />
        </el-form-item>
        <el-form-item label="dosage form">
          <el-select v-model="editingDrug.dosageForm" placeholder="Select">
            <el-option label="tablet" value="TABLET" />
            <el-option label="capsule" value="CAPSULE" />
            <el-option label="injection" value="INJECTION" />
            <el-option label="oral solution" value="SOLUTION" />
            <el-option label="powder" value="POWDER" />
          </el-select>
        </el-form-item>
        <el-form-item label="Medicationcategory">
          <el-select v-model="editingDrug.category" placeholder="Select">
            <el-option label="antihypertensive" value="ANTIHYPERTENSIVE" />
            <el-option label="phosphate binder" value="PHOSPHATE_BINDER" />
            <el-option label="iron supplement" value="IRON_SUPPLEMENT" />
            <el-option label="vitamin" value="VITAMIN" />
            <el-option label="erythropoietin" value="ESA" />
            <el-option label="calcium supplement" value="CALCIUM" />
            <el-option label="active vitamin DD" value="VD" />
            <el-option label="diuretic" value="DIURETIC" />
            <el-option label="antibiotic" value="ANTIBIOTIC" />
            <el-option label="Other" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="manufacturer">
          <el-input v-model="editingDrug.manufacturer" placeholder="Enter manufacturer" />
        </el-form-item>
        <el-form-item label="approval number">
          <el-input v-model="editingDrug.approvalNumber" placeholder="Enter approval number" />
        </el-form-item>
        <el-form-item label="DefaultDose">
          <el-input v-model="editingDrug.defaultDosage" placeholder="for example : each times1tablet, each days1times" />
        </el-form-item>
        <el-form-item label="Notes">
          <el-input v-model="editingDrug.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="YesNoEnabled">
          <el-switch v-model="editingDrug.isActive" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drugDialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="saveDrug">Save</el-button>
      </template>
    </el-dialog>

    <!-- medicationrecordEditdialog -->
    <el-dialog v-model="logDialogVisible" :title="editingLog.id ? 'Editmedicationrecord' : 'Addmedicationrecord'" width="600px" destroy-on-close>
      <el-form :model="editingLog" label-width="100px">
        <el-form-item label="Patient" required>
          <el-select v-model="editingLog.patientId" placeholder="Select a patient" filterable>
            <el-option v-for="patient in patientList" :key="patient.id" :label="patient.patientName" :value="patient.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="Medication" required>
          <el-select v-model="editingLog.medicationId" placeholder="SelectMedication" filterable>
            <el-option v-for="drug in activeDrugs" :key="drug.id" :label="drug.drugName" :value="drug.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="Dose">
          <el-input v-model="editingLog.dosage" placeholder="for example : 1tablet" />
        </el-form-item>
        <el-form-item label="administrationroute">
          <el-select v-model="editingLog.adminRoute" placeholder="Select">
            <el-option label="oral" value="ORAL" />
            <el-option label="intravenous" value="IV" />
            <el-option label="subcutaneous " value="SC" />
            <el-option label="intramuscular" value="IM" />
          </el-select>
        </el-form-item>
        <el-form-item label="administrationTime">
          <el-date-picker
            v-model="editingLog.administrationTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="prescribeClinician">
          <el-input v-model="editingLog.prescribedBy" placeholder="Enter clinician name" />
        </el-form-item>
        <el-form-item label="effect assessment">
          <el-select v-model="editingLog.effectEvaluation" placeholder="Select">
            <el-option label="Good" value="GOOD" />
            <el-option label="Fair" value="MODERATE" />
            <el-option label="poor" value="POOR" />
          </el-select>
        </el-form-item>
        <el-form-item label="adverse reaction">
          <el-input v-model="editingLog.sideEffect" placeholder="for example has adverse reactionPlease Description" />
        </el-form-item>
        <el-form-item label="Notes">
          <el-input v-model="editingLog.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="logDialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="saveLog">Save</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import medicationCareSmall from '@/assets/illustrations/medication-care-small.webp';
import medicationCare from '@/assets/illustrations/medication-care.webp';
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { UploadFilled, Loading, Box, Setting } from '@element-plus/icons-vue';
import * as api from '../api/medication.js';
import { saveMedicationsBatch } from '../api/medication.js';
import { getPatientNames } from '@/api/patient';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { useRoute } from 'vue-router';
import MedicationReminderPanel from '@/components/MedicationReminderPanel.vue';

import TableActionHeader from '@/components/TableActionHeader.vue';
import { useTableColumns } from '@/composables/useTableColumns';

const route = useRoute();
const menuTabs = ['drugs', 'logs', 'upload', 'category', 'remind'];
const activeMenu = ref(menuTabs.includes(route.query.tab) ? route.query.tab : 'drugs');
watch(() => route.query.tab, (tab) => {
  handleMenuSelect(menuTabs.includes(tab) ? tab : 'drugs');
});
const isMobile = ref(false);
const patientList = ref([]);

const { currentPatientId } = useCurrentPatient();

const DRUG_COLUMN_DEFS = [
  { key: 'drugName', label: 'Medication' },
  { key: 'genericName', label: 'Generic Name', default: false },
  { key: 'specification', label: 'Specification' },
  { key: 'dosageForm', label: 'dosage form' },
  { key: 'category', label: 'category' },
  { key: 'manufacturer', label: 'manufacturer', default: false },
  { key: 'defaultDosage', label: 'DefaultDose' },
  { key: 'isActive', label: 'Status' }
];
const { visibleKeys: drugVisibleCols, isVisible: drugColVisible, resetColumns: resetDrugColumns } =
  useTableColumns('medication-drug-list', DRUG_COLUMN_DEFS);

const LOG_COLUMN_DEFS = [
  { key: 'administrationTime', label: 'administrationTime' },
  { key: 'patientName', label: 'Patient' },
  { key: 'drugName', label: 'Medication' },
  { key: 'dosage', label: 'Dose' },
  { key: 'adminRoute', label: 'route' },
  { key: 'prescribedBy', label: 'Clinician', default: false },
  { key: 'effectEvaluation', label: 'validresult', default: false },
  { key: 'sideEffect', label: 'adverse reaction', default: false }
];
const { visibleKeys: logVisibleCols, isVisible: logColVisible, resetColumns: resetLogColumns } =
  useTableColumns('medication-log-list', LOG_COLUMN_DEFS);

const drugs = ref([]);
const logs = ref([]);
const activeDrugs = ref([]);
const drugDialogVisible = ref(false);
const logDialogVisible = ref(false);
const uploadRef = ref(null);
const recognizeLoading = ref(false);
const recognizeResult = ref(false);
const recognizeWarning = ref('');
const selectedFiles = ref([]);
const activeRecognizeTab = ref('0');

const uploadForm = reactive({
  patientId: currentPatientId.value,
  patientName: '',
});

const recognizedDrugs = ref([]);

const currentRecognizeDrug = computed(() => {
  const idx = Number(activeRecognizeTab.value) || 0;
  if (!recognizedDrugs.value.length) {
    return {
      drugName: '',
      genericName: '',
      specification: '',
      unit: '',
      dosageForm: '',
      manufacturer: '',
      approvalNumber: '',
      category: '',
      defaultDosage: '',
      remark: ''
    };
  }
  return recognizedDrugs.value[idx] || recognizedDrugs.value[0];
});

const editingDrug = reactive({
  id: null,
  patientId: currentPatientId.value,
  drugName: '',
  genericName: '',
  specification: '',
  unit: '',
  dosageForm: '',
  manufacturer: '',
  approvalNumber: '',
  category: '',
  defaultDosage: '',
  remark: '',
  isActive: 1
});

const logFilter = reactive({
  patientId: currentPatientId.value,
  patientName: '',
});

const editingLog = reactive({
  id: null,
  patientId: currentPatientId.value,
  patientName: '',
  medicationId: null,
  dosage: '',
  adminRoute: '',
  administrationTime: '',
  prescribedBy: '',
  effectEvaluation: '',
  sideEffect: '',
  remark: ''
});

const dosageFormMap = {
  TABLET: 'tablet',
  CAPSULE: 'capsule',
  INJECTION: 'injection',
  SOLUTION: 'oral solution',
  POWDER: 'powder'
};

const categoryMap = {
  ANTIHYPERTENSIVE: 'antihypertensive',
  PHOSPHATE_BINDER: 'phosphate binder',
  IRON_SUPPLEMENT: 'iron supplement',
  VITAMIN: 'vitamin',
  ESA: 'erythropoietin',
  CALCIUM: 'calcium supplement',
  VD: 'active vitamin DD',
  DIURETIC: 'diuretic',
  ANTIBIOTIC: 'antibiotic',
  OTHER: 'Other'
};

const adminRouteMap = {
  ORAL: 'oral',
  IV: 'intravenous',
  SC: 'subcutaneous ',
  IM: 'intramuscular'
};

const effectMap = {
  GOOD: { name: 'Good', type: 'success' },
  MODERATE: { name: 'Fair', type: 'warning' },
  POOR: { name: 'poor', type: 'danger' }
};

const pageTitle = computed(() => {
  const map = {
    drugs: 'Medicationdatabasemanagement',
    logs: 'medicationrecord',
    category: 'Browse Categories',
    remind: 'Medication Reminders'
  };
  return map[activeMenu.value] || 'Medicationmanagement';
});

const pageSubtitle = computed(() => {
  const map = {
    drugs: 'maintainDialysisrelatedMedicationBasicinformation, providemedicationrecordselectuse',
    logs: 'recordPatienteach timesadministrationcondition and effect assessment',
    category: 'by MedicationcategorygroupViewdatabasewithinMedication',
    remind: 'Configure dose times, repeat days, and quantities'
  };
  return map[activeMenu.value] || '';
});

const drugsByCategory = computed(() => {
  const groups = {};
  for (const drug of drugs.value) {
    const cat = drug.category || 'OTHER';
    if (!groups[cat]) {
      groups[cat] = { category: cat, name: getCategoryName(cat), items: [] };
    }
    groups[cat].items.push(drug);
  }
  return Object.values(groups).sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
});

watch(currentPatientId, (newVal, oldVal) => {
  if (newVal !== oldVal) {
    uploadForm.patientId = newVal;
    logFilter.patientId = newVal;
    if (activeMenu.value === 'drugs' || activeMenu.value === 'category') {
      loadDrugs();
      loadActiveDrugs();
    }
    if (activeMenu.value === 'logs') {
      loadLogs();
    }
  }
});

function checkMobile() {
  isMobile.value = window.innerWidth <= 768;
}

function handleMenuSelect(index) {
  activeMenu.value = index;
  if (index === 'drugs' || index === 'category') {
    loadPatientList();
    loadDrugs();
    loadActiveDrugs();
  }
  if (index === 'logs') {
    loadPatientList();
    loadLogs();
  }
}

function getDosageFormName(code) {
  return dosageFormMap[code] || code;
}

function getCategoryName(code) {
  return categoryMap[code] || code;
}

function getAdminRouteName(code) {
  return adminRouteMap[code] || code;
}

function getEffectName(code) {
  return effectMap[code]?.name || code;
}

function getEffectType(code) {
  return effectMap[code]?.type || 'info';
}

async function loadDrugs() {
  try {
    const res = await api.listMedications(currentPatientId.value);
    if (res.code === 200) {
      drugs.value = res.data || [];
    }
  } catch (e) {
    ElMessage.error('Failed to load medications: ' + e.message);
  }
}

async function loadActiveDrugs() {
  try {
    const res = await api.listActiveMedications(currentPatientId.value);
    if (res.code === 200) {
      activeDrugs.value = res.data || [];
    }
  } catch (e) {
    ElMessage.error('Failed to load medications: ' + e.message);
  }
}

function showDrugDialog(drug = null) {
  if (drug) {
    Object.assign(editingDrug, drug);
  } else {
    Object.keys(editingDrug).forEach(k => {
      editingDrug[k] = k === 'isActive' ? 1 : '';
    });
    editingDrug.id = null;
    editingDrug.patientId = currentPatientId.value;
  }
  drugDialogVisible.value = true;
}

async function saveDrug() {
  try {
    let res;
    if (editingDrug.id) {
      res = await api.updateMedication(editingDrug);
    } else {
      res = await api.saveMedication(editingDrug);
    }
    if (res.code === 200) {
      ElMessage.success('Saved successfully');
      drugDialogVisible.value = false;
      loadDrugs();
      loadActiveDrugs();
    } else {
      ElMessage.error(res.message || 'Failed to save');
    }
  } catch (e) {
    ElMessage.error('Failed to save: ' + e.message);
  }
}

async function deleteDrug(row) {
  try {
    await ElMessageBox.confirm('ConfirmneedDeletethis Medication?', 'Notice', { type: 'warning' });
    const res = await api.deleteMedication(row.id);
    if (res.code === 200) {
      ElMessage.success('Deleted successfully');
      loadDrugs();
      loadActiveDrugs();
    } else {
      ElMessage.error(res.message || 'Failed to delete');
    }
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('Failed to delete: ' + e.message);
  }
}

function handleFileChange(file) {
  selectedFiles.value.push(file.raw);
}

async function startRecognize() {
  if (!selectedFiles.value || selectedFiles.value.length === 0) {
    ElMessage.warning('Please first Uploadfile');
    return;
  }
  recognizeLoading.value = true;
  try {
    const res = await api.uploadAndRecognize(selectedFiles.value, uploadForm.patientId);
    if (res.code === 200) {
      const data = res.data;
      if (data?.error) {
        ElMessage.error(data.error);
        recognizeResult.value = false;
        return;
      }

      if (data.drugs && data.drugs.length > 0) {
        recognizedDrugs.value = data.drugs.map(d => ({
          drugName: d.drugName || '',
          genericName: d.genericName || '',
          specification: d.specification || '',
          unit: d.unit || '',
          dosageForm: d.dosageForm || '',
          manufacturer: d.manufacturer || '',
          approvalNumber: d.approvalNumber || '',
          category: d.category || '',
          defaultDosage: d.defaultDosage || '',
          remark: d.remark || ''
        }));
      } else {
        // legacy compatibilityformat
        recognizedDrugs.value = [{
          drugName: data.drugName || '',
          genericName: data.genericName || '',
          specification: data.specification || '',
          unit: data.unit || '',
          dosageForm: data.dosageForm || '',
          manufacturer: data.manufacturer || '',
          approvalNumber: data.approvalNumber || '',
          category: data.category || '',
          defaultDosage: data.defaultDosage || '',
          remark: data.remark || ''
        }];
      }

      activeRecognizeTab.value = '0';
      recognizeWarning.value = data.warning || '';
      recognizeResult.value = true;
      if (data.warning) {
        ElMessage.warning(data.warning);
      }
    } else {
      ElMessage.error(res.msg || res.message || 'Recognition failed');
    }
  } catch (e) {
    if (e.message?.includes('timeout')) {
      ElMessage.error('recognitionovertime: filetoo large or networkrelativelyslowly, Please laterretry');
    } else {
      ElMessage.error('Recognition failed: ' + e.message);
    }
  } finally {
    recognizeLoading.value = false;
  }
}

async function saveRecognizedDrug() {
  try {
    const drugs = recognizedDrugs.value.map(d => ({
      drugName: d.drugName,
      genericName: d.genericName,
      specification: d.specification,
      unit: d.unit,
      dosageForm: d.dosageForm,
      manufacturer: d.manufacturer,
      approvalNumber: d.approvalNumber,
      category: d.category,
      defaultDosage: d.defaultDosage,
      remark: d.remark,
      patientId: currentPatientId.value,
      isActive: 1
    }));

    if (drugs.length === 0) {
      ElMessage.warning('nohas can Save Medication');
      return;
    }

    let res;
    if (drugs.length === 1) {
      res = await api.saveMedication(drugs[0]);
    } else {
      res = await saveMedicationsBatch(drugs);
    }

    if (res.code === 200) {
      ElMessage.success('Saved successfully');
      recognizeResult.value = false;
      recognizedDrugs.value = [];
      activeRecognizeTab.value = '0';
      selectedFiles.value = [];
      if (uploadRef.value) uploadRef.value.clearFiles();
      loadDrugs();
      loadActiveDrugs();
    } else {
      ElMessage.error(res.message || 'Failed to save');
    }
  } catch (e) {
    ElMessage.error('Failed to save: ' + e.message);
  }
}

async function loadLogs() {
  try {
    const params = {};
    if (logFilter.patientId) {
      params.patientId = logFilter.patientId;
    } else if (logFilter.patientName) {
      params.patientName = logFilter.patientName;
    }
    const res = await api.listLogs(params);
    if (res.code === 200) {
      logs.value = res.data || [];
    }
  } catch (e) {
    ElMessage.error('Failed to load medication records: ' + e.message);
  }
}

function showLogDialog(log = null) {
  if (log) {
    Object.assign(editingLog, log);
    editingLog.administrationTime = log.administrationTime;
  } else {
    Object.keys(editingLog).forEach(k => {
      editingLog[k] = '';
    });
    editingLog.id = null;
    editingLog.patientId = currentPatientId.value;
    editingLog.administrationTime = new Date().toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-');
  }
  logDialogVisible.value = true;
}

async function saveLog() {
  try {
    let res;
    if (editingLog.id) {
      res = await api.updateLog(editingLog);
    } else {
      res = await api.saveLog(editingLog);
    }
    if (res.code === 200) {
      ElMessage.success('Saved successfully');
      logDialogVisible.value = false;
      loadLogs();
    } else {
      ElMessage.error(res.message || 'Failed to save');
    }
  } catch (e) {
    ElMessage.error('Failed to save: ' + e.message);
  }
}

async function deleteLog(row) {
  try {
    await ElMessageBox.confirm('ConfirmneedDeletethis medicationrecord?', 'Notice', { type: 'warning' });
    const res = await api.deleteLog(row.id);
    if (res.code === 200) {
      ElMessage.success('Deleted successfully');
      loadLogs();
    } else {
      ElMessage.error(res.message || 'Failed to delete');
    }
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('Failed to delete: ' + e.message);
  }
}

function handlePaste(e) {
  if (activeMenu.value !== 'upload') return;
  const items = e.clipboardData?.items;
  if (!items) return;
  let added = 0;
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    if (item.type.indexOf('image') === -1) continue;
    const file = item.getAsFile();
    if (!file) continue;
    if (selectedFiles.value.length + added >= 10) {
      ElMessage.warning('most multiplesupport10imagesimage');
      break;
    }
    uploadRef.value?.handleStart(file);
    added++;
  }
  if (added > 0) {
    ElMessage.success(`Pasted ${added} images`);
  }
}

async function loadPatientList() {
  try {
    const res = await getPatientNames();
    if (res.code === 200) {
      patientList.value = res.data || [];
    }
  } catch (e) {
    console.error('Failed to load patients', e);
  }
}

onMounted(() => {
  checkMobile();
  window.addEventListener('resize', checkMobile);
  window.addEventListener('paste', handlePaste);
  loadPatientList();
  loadDrugs();
  loadActiveDrugs();
  if (activeMenu.value === 'logs') loadLogs();
});

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile);
  window.removeEventListener('paste', handlePaste);
});
</script>

<style scoped src="@/styles/module-layout.css"></style>
<style scoped>
.medication-manager .top-bar.medication-heading {
  align-items: center;
  flex-wrap: nowrap;
  gap: 24px;
  min-height: 128px;
  padding: 14px 22px;
  border: 1px solid #e5e7d9;
  border-radius: 16px;
  background: linear-gradient(110deg, #f7f4e9 0%, #edf5ec 100%);
}

.medication-heading .left {
  flex: 1;
}

.medication-heading .left > div {
  min-width: 0;
  overflow-wrap: anywhere;
}

.medication-heading-art {
  display: block;
  flex: 0 0 160px;
  width: 160px;
  height: auto;
  border-radius: 12px;
}

@media (max-width: 768px) {
  .medication-manager .top-bar.medication-heading {
    gap: 12px;
    min-height: 108px;
    padding: 12px;
  }

  .medication-heading-art {
    flex-basis: 96px;
    width: 96px;
  }
}

@media (max-width: 375px) {
  .medication-heading-art {
    flex-basis: 80px;
    width: 80px;
  }
}

.category-panel {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.category-group-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.category-group-title {
  font-size: 15px;
  font-weight: 700;
  color: #334155;
}

.category-group-count {
  font-size: 12px;
  color: #6366f1;
  background: #eef2ff;
  padding: 2px 10px;
  border-radius: 999px;
}

.category-empty,
.remind-panel {
  padding: 32px 0;
  animation: panel-enter 0.35s cubic-bezier(0.25, 0.46, 0.45, 0.94) both;
}

.remind-title {
  margin: 0 0 6px;
  font-size: 15px;
  font-weight: 600;
  color: #334155;
}

.remind-sub {
  margin: 0;
  font-size: 13px;
  color: #94a3b8;
}

/* mobileoptimize */
@media (max-width: 768px) {
  .category-group {
    margin-bottom: 16px;
  }

  .category-group-title {
    font-size: 14px;
  }

  .upload-panel :deep(.el-upload-dragger) {
    padding: 24px 16px;
  }

  .upload-text {
    font-size: 13px;
    line-height: 1.6;
  }

  .recognize-result :deep(.el-divider__text) {
    font-size: 13px;
  }

  .recognize-items-toolbar {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
    margin: 12px 0;
  }

  .recognize-items-count {
    text-align: center;
  }

  .recognize-items-actions {
    display: flex;
    gap: 8px;
  }

  .recognize-items-actions .el-button {
    flex: 1;
  }
}

@media (max-width: 375px) {
  .category-group-head {
    gap: 8px;
  }

  .category-group-title {
    font-size: 13px;
  }

  .category-group-count {
    font-size: 11px;
    padding: 2px 8px;
  }
}
.remind-panel > .el-empty { display: none; }
</style>
