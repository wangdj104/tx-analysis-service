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
              <el-form-item label="患者">
                <el-select v-model="uploadForm.patientId" placeholder="选择患者（可选）" filterable clearable style="max-width: 320px">
                  <el-option
                    v-for="patient in patientList"
                    :key="patient.id"
                    :label="patient.patientName"
                    :value="patient.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="上传文件" required>
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
                  <div class="upload-text">将药盒或说明书拖到此处，或<em>点击上传</em>，也可<em>粘贴</em>图片（支持多图）。</div>
                  <template #tip>
                    <div class="el-upload__tip">支持图片（JPG/PNG）、PDF 和 Word（DOC/DOCX），大文件识别耗时可能较长。</div>
                  </template>
                </el-upload>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="recognizeLoading" @click="startRecognize">开始识别</el-button>
                <el-button v-if="recognizeResult" type="success" @click="saveRecognizedDrug">
                  {{ recognizedDrugs.length > 1 ? `将 ${recognizedDrugs.length} 种药品保存到药品库` : '保存到药品库' }}
                </el-button>
              </el-form-item>
            </el-form>

            <div v-if="recognizeLoading" class="recognize-loading">
              <el-icon class="is-loading" :size="24"><Loading /></el-icon>
              <span>AI 正在识别，大文件可能需要 1～3 分钟，请耐心等待……</span>
            </div>

            <div v-if="recognizeResult" class="recognize-result">
              <el-divider content-position="left">识别结果（可编辑后保存）</el-divider>
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
                  :label="(drug.drugName || '药品 ' + (idx + 1))"
                  :name="String(idx)"
                />
              </el-tabs>
              <el-form :model="currentRecognizeDrug" label-width="100px">
                <el-form-item label="药品名称" required>
                  <el-input v-model="currentRecognizeDrug.drugName" />
                </el-form-item>
                <el-form-item label="通用名称">
                  <el-input v-model="currentRecognizeDrug.genericName" />
                </el-form-item>
                <el-form-item label="规格">
                  <el-input v-model="currentRecognizeDrug.specification" placeholder="例如：10mg×28片" />
                </el-form-item>
                <el-form-item label="单位">
                  <el-input v-model="currentRecognizeDrug.unit" placeholder="片 / 支 / 瓶" />
                </el-form-item>
                <el-form-item label="剂型">
                  <el-select v-model="currentRecognizeDrug.dosageForm" placeholder="请选择">
                    <el-option label="片剂" value="TABLET" />
                    <el-option label="胶囊" value="CAPSULE" />
                    <el-option label="注射剂" value="INJECTION" />
                    <el-option label="口服液" value="SOLUTION" />
                    <el-option label="粉剂" value="POWDER" />
                  </el-select>
                </el-form-item>
                <el-form-item label="药品分类">
                  <el-select v-model="currentRecognizeDrug.category" placeholder="请选择">
                    <el-option label="降压药" value="ANTIHYPERTENSIVE" />
                    <el-option label="磷结合剂" value="PHOSPHATE_BINDER" />
                    <el-option label="铁剂" value="IRON_SUPPLEMENT" />
                    <el-option label="维生素" value="VITAMIN" />
                    <el-option label="促红细胞生成素" value="ESA" />
                    <el-option label="钙剂" value="CALCIUM" />
                    <el-option label="活性维生素 D" value="VD" />
                    <el-option label="利尿剂" value="DIURETIC" />
                    <el-option label="抗生素" value="ANTIBIOTIC" />
                    <el-option label="其他" value="OTHER" />
                  </el-select>
                </el-form-item>
                <el-form-item label="生产厂家">
                  <el-input v-model="currentRecognizeDrug.manufacturer" />
                </el-form-item>
                <el-form-item label="批准文号">
                  <el-input v-model="currentRecognizeDrug.approvalNumber" />
                </el-form-item>
                <el-form-item label="默认剂量">
                  <el-input v-model="currentRecognizeDrug.defaultDosage" placeholder="例如：每次 1 片，每日 1 次" />
                </el-form-item>
                <el-form-item label="备注">
                  <el-input v-model="currentRecognizeDrug.remark" type="textarea" :rows="2" />
                </el-form-item>
              </el-form>
            </div>
          </div>

          <!-- Medicationdatabase -->
          <div v-show="activeMenu === 'drugs'">
            <div class="toolbar">
              <el-button type="primary" @click="showDrugDialog()">
                <el-icon><Plus /></el-icon>新增药品
              </el-button>
            </div>
            <div class="table-wrap">
              <el-table :data="drugs" class="app-data-table app-data-table--list" stripe style="width: 100%">
                <el-table-column v-if="drugColVisible('drugName')" prop="drugName" label="药品" min-width="120" show-overflow-tooltip />
                <el-table-column v-if="drugColVisible('genericName')" prop="genericName" label="通用名称" min-width="120" show-overflow-tooltip />
                <el-table-column v-if="drugColVisible('specification')" prop="specification" label="规格" min-width="112" show-overflow-tooltip />
                <el-table-column v-if="drugColVisible('dosageForm')" prop="dosageForm" label="剂型" min-width="92" show-overflow-tooltip>
                  <template #default="{ row }">
                    <el-tag size="small">{{ getDosageFormName(row.dosageForm) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column v-if="drugColVisible('category')" prop="category" label="分类" min-width="108" show-overflow-tooltip>
                  <template #default="{ row }">
                    <el-tag type="info" size="small">{{ getCategoryName(row.category) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column v-if="drugColVisible('manufacturer')" prop="manufacturer" label="生产厂家" min-width="140" show-overflow-tooltip />
                <el-table-column v-if="drugColVisible('defaultDosage')" prop="defaultDosage" label="默认剂量" min-width="120" show-overflow-tooltip />
                <el-table-column v-if="drugColVisible('isActive')" prop="isActive" label="状态" min-width="80" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.isActive === 1 ? 'success' : 'info'" size="small">
                      {{ row.isActive === 1 ? '启用' : '停用' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="132" fixed="right" align="center">
                  <template #header>
                    <TableActionHeader v-model="drugVisibleCols" :columns="DRUG_COLUMN_DEFS" @reset="resetDrugColumns" />
                  </template>
                  <template #default="{ row }">
                    <div class="table-actions">
                      <el-button link type="primary" size="small" @click="showDrugDialog(row)">编辑</el-button>
                      <el-button link type="danger" size="small" @click="deleteDrug(row)">删除</el-button>
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
                <el-form-item label="患者">
                  <el-select v-model="logFilter.patientId" placeholder="选择患者" filterable clearable style="width: 200px">
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
                    <el-icon><Search /></el-icon>查询
                  </el-button>
                </el-form-item>
              </el-form>
              <el-button type="primary" @click="showLogDialog()">
                <el-icon><Plus /></el-icon>新增记录
              </el-button>
            </div>
            <div class="table-wrap">
              <el-table :data="logs" class="app-data-table app-data-table--list" stripe style="width: 100%">
                <el-table-column v-if="logColVisible('administrationTime')" prop="administrationTime" label="用药时间" min-width="148" />
                <el-table-column v-if="logColVisible('patientName')" prop="patientName" label="患者" min-width="100" show-overflow-tooltip />
                <el-table-column v-if="logColVisible('drugName')" prop="medication.drugName" label="药品" min-width="120" show-overflow-tooltip />
                <el-table-column v-if="logColVisible('dosage')" prop="dosage" label="剂量" min-width="80" show-overflow-tooltip />
                <el-table-column v-if="logColVisible('adminRoute')" prop="adminRoute" label="途径" min-width="80" show-overflow-tooltip>
                  <template #default="{ row }">
                    {{ getAdminRouteName(row.adminRoute) }}
                  </template>
                </el-table-column>
                <el-table-column v-if="logColVisible('prescribedBy')" prop="prescribedBy" label="开方医生" min-width="88" show-overflow-tooltip />
                <el-table-column v-if="logColVisible('effectEvaluation')" prop="effectEvaluation" label="效果" min-width="80" show-overflow-tooltip>
                  <template #default="{ row }">
                    <el-tag :type="getEffectType(row.effectEvaluation)" size="small">{{ getEffectName(row.effectEvaluation) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column v-if="logColVisible('sideEffect')" prop="sideEffect" label="不良反应" min-width="120" show-overflow-tooltip />
                <el-table-column label="操作" width="132" fixed="right" align="center">
                  <template #header>
                    <TableActionHeader v-model="logVisibleCols" :columns="LOG_COLUMN_DEFS" @reset="resetLogColumns" />
                  </template>
                  <template #default="{ row }">
                    <div class="table-actions">
                      <el-button link type="primary" size="small" @click="showLogDialog(row)">编辑</el-button>
                      <el-button link type="danger" size="small" @click="deleteLog(row)">删除</el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>

          <!-- Browse Categories -->
          <div v-show="activeMenu === 'category'" class="category-panel">
            <div v-if="drugsByCategory.length === 0" class="category-empty">
              <el-empty description="暂无药品数据" />
            </div>
            <div v-for="group in drugsByCategory" :key="group.category" class="category-group">
              <div class="category-group-head">
                <span class="category-group-title">{{ group.name }}</span>
                <span class="category-group-count">{{ group.items.length }} 种</span>
              </div>
              <div class="table-wrap table-wrap--compact">
              <el-table :data="group.items" class="app-data-table app-data-table--compact" stripe size="small" :fit="false">
                <el-table-column prop="drugName" label="药品名称" width="160" show-overflow-tooltip />
                <el-table-column prop="specification" label="规格" width="120" show-overflow-tooltip />
                <el-table-column prop="defaultDosage" label="默认剂量" width="140" show-overflow-tooltip />
                <el-table-column prop="isActive" label="状态" width="88" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.isActive === 1 ? 'success' : 'info'" size="small">
                      {{ row.isActive === 1 ? '启用' : '停用' }}
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
    <el-dialog v-model="drugDialogVisible" :title="editingDrug.id ? '编辑药品' : '新增药品'" width="600px" destroy-on-close>
      <el-form :model="editingDrug" label-width="100px">
        <el-form-item label="药品名称" required>
          <el-input v-model="editingDrug.drugName" placeholder="请输入药品名称" />
        </el-form-item>
        <el-form-item label="通用名称">
          <el-input v-model="editingDrug.genericName" placeholder="请输入通用名称" />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="editingDrug.specification" placeholder="例如：10mg×28片" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="editingDrug.unit" placeholder="片 / 支 / 瓶" />
        </el-form-item>
        <el-form-item label="剂型">
          <el-select v-model="editingDrug.dosageForm" placeholder="请选择">
            <el-option label="片剂" value="TABLET" />
            <el-option label="胶囊" value="CAPSULE" />
            <el-option label="注射剂" value="INJECTION" />
            <el-option label="口服液" value="SOLUTION" />
            <el-option label="粉剂" value="POWDER" />
          </el-select>
        </el-form-item>
        <el-form-item label="药品分类">
          <el-select v-model="editingDrug.category" placeholder="请选择">
            <el-option label="降压药" value="ANTIHYPERTENSIVE" />
            <el-option label="磷结合剂" value="PHOSPHATE_BINDER" />
            <el-option label="铁剂" value="IRON_SUPPLEMENT" />
            <el-option label="维生素" value="VITAMIN" />
            <el-option label="促红细胞生成素" value="ESA" />
            <el-option label="钙剂" value="CALCIUM" />
            <el-option label="活性维生素 D" value="VD" />
            <el-option label="利尿剂" value="DIURETIC" />
            <el-option label="抗生素" value="ANTIBIOTIC" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="生产厂家">
          <el-input v-model="editingDrug.manufacturer" placeholder="请输入生产厂家" />
        </el-form-item>
        <el-form-item label="批准文号">
          <el-input v-model="editingDrug.approvalNumber" placeholder="请输入批准文号" />
        </el-form-item>
        <el-form-item label="默认剂量">
          <el-input v-model="editingDrug.defaultDosage" placeholder="例如：每次 1 片，每日 1 次" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editingDrug.remark" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="是否启用">
          <el-switch v-model="editingDrug.isActive" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drugDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDrug">保存</el-button>
      </template>
    </el-dialog>

    <!-- medicationrecordEditdialog -->
    <el-dialog v-model="logDialogVisible" :title="editingLog.id ? '编辑用药记录' : '新增用药记录'" width="600px" destroy-on-close>
      <el-form :model="editingLog" label-width="100px">
        <el-form-item label="患者" required>
          <el-select v-model="editingLog.patientId" placeholder="选择患者" filterable>
            <el-option v-for="patient in patientList" :key="patient.id" :label="patient.patientName" :value="patient.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="药品" required>
          <el-select v-model="editingLog.medicationId" placeholder="选择药品" filterable>
            <el-option v-for="drug in activeDrugs" :key="drug.id" :label="drug.drugName" :value="drug.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="剂量">
          <el-input v-model="editingLog.dosage" placeholder="例如：1 片" />
        </el-form-item>
        <el-form-item label="给药途径">
          <el-select v-model="editingLog.adminRoute" placeholder="请选择">
            <el-option label="口服" value="ORAL" />
            <el-option label="静脉注射" value="IV" />
            <el-option label="皮下注射" value="SC" />
            <el-option label="肌肉注射" value="IM" />
          </el-select>
        </el-form-item>
        <el-form-item label="用药时间">
          <el-date-picker
            v-model="editingLog.administrationTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="开方医生">
          <el-input v-model="editingLog.prescribedBy" placeholder="请输入医生姓名" />
        </el-form-item>
        <el-form-item label="效果评估">
          <el-select v-model="editingLog.effectEvaluation" placeholder="请选择">
            <el-option label="良好" value="GOOD" />
            <el-option label="一般" value="MODERATE" />
            <el-option label="较差" value="POOR" />
          </el-select>
        </el-form-item>
        <el-form-item label="不良反应">
          <el-input v-model="editingLog.sideEffect" placeholder="如有不良反应，请描述。" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editingLog.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="logDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveLog">保存</el-button>
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
  { key: 'drugName', label: '药品' },
  { key: 'genericName', label: '通用名称', default: false },
  { key: 'specification', label: '规格' },
  { key: 'dosageForm', label: '剂型' },
  { key: 'category', label: '分类' },
  { key: 'manufacturer', label: '生产厂家', default: false },
  { key: 'defaultDosage', label: '默认剂量' },
  { key: 'isActive', label: '状态' }
];
const { visibleKeys: drugVisibleCols, isVisible: drugColVisible, resetColumns: resetDrugColumns } =
  useTableColumns('medication-drug-list', DRUG_COLUMN_DEFS);

const LOG_COLUMN_DEFS = [
  { key: 'administrationTime', label: '用药时间' },
  { key: 'patientName', label: '患者' },
  { key: 'drugName', label: '药品' },
  { key: 'dosage', label: '剂量' },
  { key: 'adminRoute', label: '途径' },
  { key: 'prescribedBy', label: '开方医生', default: false },
  { key: 'effectEvaluation', label: '效果', default: false },
  { key: 'sideEffect', label: '不良反应', default: false }
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
  TABLET: '片剂',
  CAPSULE: '胶囊',
  INJECTION: '注射剂',
  SOLUTION: '口服液',
  POWDER: '粉剂'
};

const categoryMap = {
  ANTIHYPERTENSIVE: '降压药',
  PHOSPHATE_BINDER: '磷结合剂',
  IRON_SUPPLEMENT: '铁剂',
  VITAMIN: '维生素',
  ESA: '促红细胞生成素',
  CALCIUM: '钙剂',
  VD: '活性维生素 D',
  DIURETIC: '利尿剂',
  ANTIBIOTIC: '抗生素',
  OTHER: '其他'
};

const adminRouteMap = {
  ORAL: '口服',
  IV: '静脉注射',
  SC: '皮下注射',
  IM: '肌肉注射'
};

const effectMap = {
  GOOD: { name: '良好', type: 'success' },
  MODERATE: { name: '一般', type: 'warning' },
  POOR: { name: '较差', type: 'danger' }
};

const pageTitle = computed(() => {
  const map = {
    drugs: '药品库管理',
    logs: '用药记录',
    category: '分类浏览',
    remind: '用药提醒'
  };
  return map[activeMenu.value] || '用药管理';
});

const pageSubtitle = computed(() => {
  const map = {
    drugs: '维护透析相关药品基础信息，供用药记录选择。',
    logs: '记录患者每次用药情况和效果评估。',
    category: '按药品分类查看药品库。',
    remind: '配置服药时间、重复日期和用量。'
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
    ElMessage.error('药品加载失败：' + e.message);
  }
}

async function loadActiveDrugs() {
  try {
    const res = await api.listActiveMedications(currentPatientId.value);
    if (res.code === 200) {
      activeDrugs.value = res.data || [];
    }
  } catch (e) {
    ElMessage.error('药品加载失败：' + e.message);
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
      ElMessage.success('保存成功');
      drugDialogVisible.value = false;
      loadDrugs();
      loadActiveDrugs();
    } else {
      ElMessage.error(res.message || '保存失败');
    }
  } catch (e) {
    ElMessage.error('保存失败：' + e.message);
  }
}

async function deleteDrug(row) {
  try {
    await ElMessageBox.confirm('确认删除该药品吗？', '提示', { type: 'warning' });
    const res = await api.deleteMedication(row.id);
    if (res.code === 200) {
      ElMessage.success('删除成功');
      loadDrugs();
      loadActiveDrugs();
    } else {
      ElMessage.error(res.message || '删除失败');
    }
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + e.message);
  }
}

function handleFileChange(file) {
  selectedFiles.value.push(file.raw);
}

async function startRecognize() {
  if (!selectedFiles.value || selectedFiles.value.length === 0) {
    ElMessage.warning('请先上传文件');
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
      ElMessage.error(res.msg || res.message || '识别失败');
    }
  } catch (e) {
    if (e.message?.includes('timeout')) {
      ElMessage.error('识别超时：文件过大或网络较慢，请稍后重试');
    } else {
      ElMessage.error('识别失败：' + e.message);
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
      ElMessage.warning('没有可保存的药品');
      return;
    }

    let res;
    if (drugs.length === 1) {
      res = await api.saveMedication(drugs[0]);
    } else {
      res = await saveMedicationsBatch(drugs);
    }

    if (res.code === 200) {
      ElMessage.success('保存成功');
      recognizeResult.value = false;
      recognizedDrugs.value = [];
      activeRecognizeTab.value = '0';
      selectedFiles.value = [];
      if (uploadRef.value) uploadRef.value.clearFiles();
      loadDrugs();
      loadActiveDrugs();
    } else {
      ElMessage.error(res.message || '保存失败');
    }
  } catch (e) {
    ElMessage.error('保存失败：' + e.message);
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
    ElMessage.error('用药记录加载失败：' + e.message);
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
      ElMessage.success('保存成功');
      logDialogVisible.value = false;
      loadLogs();
    } else {
      ElMessage.error(res.message || '保存失败');
    }
  } catch (e) {
    ElMessage.error('保存失败：' + e.message);
  }
}

async function deleteLog(row) {
  try {
    await ElMessageBox.confirm('确认删除这条用药记录吗？', '提示', { type: 'warning' });
    const res = await api.deleteLog(row.id);
    if (res.code === 200) {
      ElMessage.success('删除成功');
      loadLogs();
    } else {
      ElMessage.error(res.message || '删除失败');
    }
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + e.message);
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
      ElMessage.warning('最多支持 10 张图片');
      break;
    }
    uploadRef.value?.handleStart(file);
    added++;
  }
  if (added > 0) {
    ElMessage.success(`已粘贴 ${added} 张图片`);
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
