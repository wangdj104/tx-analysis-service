<template>
  <el-container class="module-page medical-record-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar record-heading">
            <div class="left">
              <div>
                <h1>{{ pageTitle }}</h1>
                <p class="subtitle">{{ pageSubtitle }}</p>
              </div>
            </div>
            <img
              class="record-heading-art"
              :src="recordsCareSmall"
              :srcset="`${recordsCareSmall} 320w, ${recordsCare} 640w`"
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
            <el-form-item label="Patient" required>
              <el-select v-model="uploadForm.patientId" placeholder="Select a patient" filterable allow-create style="max-width: 320px">
                <el-option
                  v-for="patient in patientList"
                  :key="patient.id"
                  :label="patient.patientName"
                  :value="patient.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="Report type">
              <el-select v-model="uploadForm.recordType" placeholder="Select" style="max-width: 320px">
                <el-option label="blood test" value="BLOOD" />
                <el-option label="urinalysis" value="URINE" />
                <el-option label="liver function" value="LIVER" />
                <el-option label="kidney function" value="KIDNEY" />
                <el-option label="bone metabolism" value="BONE" />
                <el-option label="iron metabolism" value="IRON" />
                <el-option label="imaging report" value="IMAGE" />
                <el-option label="Other" value="OTHER" />
              </el-select>
            </el-form-item>
            <el-form-item label="Processing mode">
              <el-radio-group v-model="uploadMode">
                <el-radio-button value="recognize">Upload and recognize</el-radio-button>
                <el-radio-button value="archive">Archive only</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="Report files" required>
              <el-upload
                ref="uploadRef"
                drag
                action="#"
                :auto-upload="false"
                :limit="10"
                multiple
                accept="image/*,.pdf"
                :on-change="handleFileChange"
                class="upload-drop"
              >
                <el-icon :size="40"><UploadFilled /></el-icon>
                <div class="upload-text">Drag reports here, <em>click to upload</em>, or paste images. Multiple files are supported.</div>
                <template #tip>
                  <div class="el-upload__tip">JPG, PNG, and PDF are supported. PDFs are limited to the first 30 pages. Mobile photos are compressed before upload.</div>
                </template>
              </el-upload>
              <div v-if="isMobile" class="mobile-upload-actions">
                <el-button type="primary" plain size="small" @click="triggerCameraUpload('page')">
                  <el-icon><Camera /></el-icon> Take photo
                </el-button>
                <el-button type="info" plain size="small" @click="triggerAlbumUpload('page')">
                  <el-icon><Picture /></el-icon> Choose from library
                </el-button>
              </div>
            </el-form-item>

            <!-- onlyarchivemode: recordinformationtablesingle -->
            <template v-if="uploadMode === 'archive'">
              <el-form-item label="Report date">
                <el-date-picker v-model="archiveForm.recordDate" type="date" value-format="YYYY-MM-DD" style="max-width: 320px" />
              </el-form-item>
              <el-form-item label="Hospital">
                <el-input v-model="archiveForm.hospitalName" style="max-width: 400px" />
              </el-form-item>
              <el-form-item label="Clinician">
                <el-input v-model="archiveForm.doctorName" style="max-width: 320px" />
              </el-form-item>
              <el-form-item label="Notes">
                <el-input v-model="archiveForm.remark" type="textarea" :rows="2" style="max-width: 400px" />
              </el-form-item>

              <!-- onlyarchivemode: ManualfillwriteExaminationitem -->
              <el-form-item label-width="0">
                <el-divider content-position="left">Test item details (optional)</el-divider>
                <div class="recognize-items-toolbar">
                  <span class="recognize-items-count">{{ archiveItems.length }} items</span>
                  <div class="recognize-items-actions">
                    <el-button size="small" type="primary" plain @click="addArchiveItem">Add row</el-button>
                  </div>
                </div>
                <el-table :data="archiveItems" border size="small" max-height="280" class="app-data-table recognize-items-table">
                  <el-table-column prop="itemName" label="Test item" min-width="140">
                    <template #default="{ row }">
                      <el-input v-model="row.itemName" size="small" placeholder="Test item name" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="resultValue" label="Result" width="120">
                    <template #default="{ row }">
                      <el-input v-model="row.resultValue" size="small" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="unit" label="Unit" width="88">
                    <template #default="{ row }">
                      <el-input v-model="row.unit" size="small" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="referenceRange" label="Reference Range" width="120">
                    <template #default="{ row }">
                      <el-input v-model="row.referenceRange" size="small" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="isAbnormal" label="Status" width="88" align="center">
                    <template #default="{ row }">
                      <el-select v-model="row.isAbnormal" size="small" style="width: 76px">
                        <el-option label="Normal" :value="0" />
                        <el-option label="High" :value="1" />
                        <el-option label="Low" :value="-1" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="Actions" width="72" fixed="right" align="center">
                    <template #default="{ $index }">
                      <el-button link type="danger" size="small" @click="removeArchiveItem($index)">Delete</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </el-form-item>
            </template>

            <el-form-item>
              <el-button v-if="uploadMode === 'recognize'" type="primary" :loading="recognizeLoading" @click="startRecognize">Start recognition</el-button>
              <el-button v-if="uploadMode === 'archive'" type="success" :loading="archiveSaving" @click="saveArchiveRecord">Save record</el-button>
              <el-button v-if="recognizeResult" type="success" @click="saveRecognizedRecord">
                {{ recognizedRecords.length > 1 ? `Save ${recognizedRecords.length} records` : 'Save record' }}
              </el-button>
            </el-form-item>
          </el-form>

          <!-- onlyarchivemode: imagePreview -->
          <div v-if="uploadMode === 'archive' && selectedFiles.length > 0" class="archive-preview">
            <el-divider content-position="left">Attachment preview</el-divider>
            <div class="attachment-images">
              <div v-for="(file, index) in selectedFiles" :key="index" class="attachment-image-wrapper">
                <el-image
                  :src="getFilePreviewUrl(file)"
                  fit="cover"
                  class="attachment-image"
                />
                <span class="attachment-file-name">{{ file.name }}</span>
              </div>
            </div>
          </div>

          <div v-if="recognizeLoading" class="recognize-loading">
            <el-icon class="is-loading" :size="24"><Loading /></el-icon>
            <span>AI is extracting a draft. Please wait…</span>
          </div>
          <div v-if="recognizeResult" class="recognize-result">
            <el-divider content-position="left">Recognition draft — review before saving</el-divider>
            <el-alert
              v-if="recognizeWarning"
              :title="recognizeWarning"
              type="warning"
              :closable="false"
              show-icon
              style="margin-bottom: 12px"
            />
            <el-tabs v-if="recognizedRecords.length > 1" v-model="activeRecognizeTab" class="recognize-tabs">
              <el-tab-pane
                v-for="(rec, idx) in recognizedRecords"
                :key="idx"
                :label="getRecordTypeName(rec.recordType) + ' (' + rec.items.length + ')'"
                :name="String(idx)"
              />
            </el-tabs>
            <el-form :model="currentRecognizeRecord" label-width="100px">
              <el-form-item v-if="recognizedRecords.length <= 1" label="Examination Type">
                <el-select v-model="currentRecognizeRecord.recordType" style="max-width: 320px">
                  <el-option v-for="(label, val) in recordTypeMap" :key="val" :label="label" :value="val" />
                </el-select>
              </el-form-item>
              <el-form-item v-else label="Examination Type">
                <el-tag type="primary">{{ getRecordTypeName(currentRecognizeRecord.recordType) }}</el-tag>
              </el-form-item>
              <el-form-item label="Examination Date">
                <el-date-picker v-model="currentRecognizeRecord.recordDate" type="date" value-format="YYYY-MM-DD" style="width: 100%; max-width: 320px" />
              </el-form-item>
              <el-form-item label="Hospital">
                <el-input v-model="currentRecognizeRecord.hospitalName" style="max-width: 400px" />
              </el-form-item>
              <el-form-item label="Clinician">
                <el-input v-model="currentRecognizeRecord.doctorName" style="max-width: 320px" />
              </el-form-item>
            </el-form>
            <div class="recognize-items-toolbar">
              <span class="recognize-items-count">total {{ currentRecognizeRecord.items.length }} item</span>
              <div class="recognize-items-actions">
                <el-button size="small" @click="dedupeRecognizedItemsLocal">mergeduplicateitem</el-button>
                <el-button size="small" type="primary" plain @click="addRecognizedItem">Addonerow</el-button>
              </div>
            </div>
            <el-table :data="currentRecognizeRecord.items" border size="small" max-height="280" class="app-data-table recognize-items-table">
              <el-table-column prop="itemName" label="Test item" min-width="140">
                <template #default="{ row }">
                  <el-input v-model="row.itemName" size="small" placeholder="Test item name" />
                </template>
              </el-table-column>
              <el-table-column prop="resultValue" label="measured value" width="120">
                <template #default="{ row }">
                  <el-input v-model="row.resultValue" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="unit" label="Unit" width="88">
                <template #default="{ row }">
                  <el-input v-model="row.unit" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="referenceRange" label="Reference Range" width="120">
                <template #default="{ row }">
                  <el-input v-model="row.referenceRange" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="isAbnormal" label="Status" width="88" align="center">
                <template #default="{ row }">
                  <el-select v-model="row.isAbnormal" size="small" style="width: 76px">
                    <el-option label="Normal" :value="0" />
                    <el-option label="high" :value="1" />
                    <el-option label="low" :value="-1" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="Actions" width="72" fixed="right" align="center">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" @click="removeRecognizedItem($index)">Delete</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>



        <!-- Result Trends -->
        <div v-show="activeMenu === 'trend'" class="trend-panel">
          <div class="toolbar">
            <el-form :inline="true" class="filter-form">
              <el-form-item label="Patient">
                <el-select v-model="trendForm.patientId" placeholder="Select a patient" filterable clearable style="width: 220px">
                  <el-option
                    v-for="patient in patientList"
                    :key="patient.id"
                    :label="patient.patientName"
                    :value="patient.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="Test item">
                <el-select
                  v-model="trendForm.itemName"
                  filterable
                  clearable
                  allow-create
                  default-first-option
                  placeholder="Select or enter a test item"
                  style="width: 220px"
                >
                  <el-option
                    v-for="name in allItemNames"
                    :key="name"
                    :label="name"
                    :value="name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="trendLoading" @click="loadTrend">
                  <el-icon><Search /></el-icon>querytrend
                </el-button>
              </el-form-item>
            </el-form>
          </div>
          <div v-if="trendData.length > 0" class="trend-chart-wrap">
            <v-chart class="trend-chart" :option="trendChartOption" autoresize />
          </div>
          <div v-if="trendData.length > 0" class="table-wrap">
            <el-table :data="trendData" class="app-data-table" stripe border size="small">
              <el-table-column prop="recordDate" label="Examination Date" width="120" />
              <el-table-column prop="itemName" label="Test item" min-width="140" />
              <el-table-column prop="resultValue" label="measured value" width="120" />
              <el-table-column prop="unit" label="Unit" width="88" />
              <el-table-column prop="referenceRange" label="Reference Range" width="140" />
              <el-table-column prop="isAbnormal" label="Status" width="88" align="center">
                <template #default="{ row }">
                  <el-tag :type="getAbnormalType(row.isAbnormal)" size="small">{{ getAbnormalText(row.isAbnormal) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="hospitalName" label="Hospital" min-width="160" show-overflow-tooltip />
            </el-table>
          </div>
          <div v-if="trendData.length === 0 && !trendLoading" class="stats-empty">
            <el-empty description="Select a patient and test item to view its history" />
          </div>
        </div>

        <!-- Record List / Abnormalrecord -->
        <template v-if="activeMenu === 'list' || activeMenu === 'abnormal'">
        <div class="toolbar">
          <el-form :inline="true" :model="filterForm" class="filter-form">
            <el-form-item label="Patient">
              <el-select v-model="filterForm.patientId" placeholder="AllPatient" filterable clearable style="width: 200px">
                <el-option
                  v-for="patient in patientList"
                  :key="patient.id"
                  :label="patient.patientName"
                  :value="patient.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="Examination Type">
              <el-select v-model="filterForm.recordType" placeholder="Select" clearable>
                <el-option label="blood test" value="BLOOD" />
                <el-option label="urinalysis" value="URINE" />
                <el-option label="liverfeature" value="LIVER" />
                <el-option label="kidneyfeature" value="KIDNEY" />
                <el-option label="bone metabolism" value="BONE" />
                <el-option label="iron metabolism" value="IRON" />
                <el-option label="imagingReport" value="IMAGE" />
                <el-option label="Other" value="OTHER" />
              </el-select>
            </el-form-item>
            <el-form-item label="Date">
              <el-date-picker v-model="filterForm.timeValue" type="date" placeholder="selectDate" value-format="YYYY-MM-DD" />
            </el-form-item>
            <el-form-item label="Test item">
              <el-input v-model="filterForm.itemName" placeholder="Search by test item name" clearable />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadRecords">
                <el-icon><Search /></el-icon>query
              </el-button>
            </el-form-item>
            <el-form-item v-if="activeMenu === 'list' && canUpload">
              <el-button type="primary" @click="goUpload">
                <el-icon><Upload /></el-icon>Upload report
              </el-button>
            </el-form-item>
          </el-form>
        </div>

        <div class="list-panel" v-loading="loading">
          <div class="list-panel-head">
            <div class="list-panel-title">
              <el-icon><FirstAidKit /></el-icon>
              <span>{{ activeMenu === 'abnormal' ? 'Abnormal test records' : 'Medical records' }}</span>
              <span v-if="displayRecords.length" class="list-count">{{ displayRecords.length }} items</span>
            </div>
          </div>
          <div class="table-wrap">
            <el-table
              :data="displayRecords"
              class="app-data-table app-data-table--list"
              stripe
              style="width: 100%"
            >
              <el-table-column v-if="listColVisible('recordDate')" prop="recordDate" label="Date" min-width="108" />
              <el-table-column v-if="listColVisible('patientName')" prop="patientName" label="Patient" min-width="100" show-overflow-tooltip />
              <el-table-column v-if="listColVisible('recordType')" prop="recordType" label="type" min-width="96" show-overflow-tooltip>
                <template #default="{ row }">
                  <el-tag size="small">{{ getRecordTypeName(row.recordType) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="listColVisible('hospitalName')" prop="hospitalName" label="Hospital" min-width="168" show-overflow-tooltip />
              <el-table-column v-if="listColVisible('doctorName')" prop="doctorName" label="Clinician" min-width="96" show-overflow-tooltip />
              <el-table-column v-if="listColVisible('items')" label="Test items" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  <span v-if="row.items && row.items.length" class="cell-ellipsis">
                    {{ row.items.map(i => i.itemName).join(', ') }}
                  </span>
                  <span v-else class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column v-if="listColVisible('abnormal')" label="Abnormal" min-width="72" align="center">
                <template #default="{ row }">
                  <el-tag v-if="hasAbnormal(row)" type="danger" size="small">{{ countAbnormal(row) }}</el-tag>
                  <span v-else class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="Actions" width="168" fixed="right" align="center">
                <template #header>
                  <TableActionHeader v-model="listVisibleCols" :columns="LIST_COLUMN_DEFS" @reset="resetListColumns" />
                </template>
                <template #default="{ row }">
                  <div class="table-actions">
                    <el-button link type="primary" size="small" @click="viewDetail(row)">View</el-button>
                    <el-button link type="primary" size="small" @click="openEditDialog(row)">Edit</el-button>
                    <el-button link type="danger" size="small" @click="deleteRecord(row)">Delete</el-button>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
        </template>
        </div>
      </div>
    </el-main>

    <!-- Upload and Recognizedialog (listpageshortcutentry)  -->
    <el-dialog v-model="uploadDialogVisible" title="Upload report" width="min(800px, 95vw)" :close-on-click-modal="false" destroy-on-close>
      <el-form :model="uploadForm" label-width="100px">
        <el-form-item label="PatientName" required>
          <el-input v-model="uploadForm.patientName" placeholder="Enter PatientName" />
        </el-form-item>
        <el-form-item label="Examination Type">
          <el-select v-model="uploadForm.recordType" placeholder="Select">
            <el-option label="blood test" value="BLOOD" />
            <el-option label="urinalysis" value="URINE" />
            <el-option label="liverfeature" value="LIVER" />
            <el-option label="kidneyfeature" value="KIDNEY" />
            <el-option label="bone metabolism" value="BONE" />
            <el-option label="iron metabolism" value="IRON" />
            <el-option label="imagingReport" value="IMAGE" />
            <el-option label="Other" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="Report files" required>
          <el-upload
            ref="uploadDialogRef"
            drag
            action="#"
            :auto-upload="false"
            :limit="10"
            multiple
            accept="image/*,.pdf,.doc,.docx"
            :on-change="handleFileChange"
          >
            <el-icon :size="40"><UploadFilled /></el-icon>
            <div class="upload-text">Drag files here, <em>click to upload</em>, or paste images. Multiple files are supported.</div>
            <template #tip>
              <div class="el-upload__tip">JPG, PNG, and PDF are supported. PDFs are limited to the first 30 pages.</div>
            </template>
          </el-upload>
          <div v-if="isMobile" class="mobile-upload-actions">
            <el-button type="primary" plain size="small" @click="triggerCameraUpload('dialog')">
              <el-icon><Camera /></el-icon> take a photoUpload
            </el-button>
            <el-button type="info" plain size="small" @click="triggerAlbumUpload('dialog')">
              <el-icon><Picture /></el-icon> from photo libraryselect
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <div v-if="recognizeLoading" class="recognize-loading">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <span>AI positivein recognitionin, largefilecan canneedneed 1~3 minutes, Please please wait...</span>
      </div>

      <div v-if="recognizeResult" class="recognize-result">
        <el-divider content-position="left">AI recognitionresult</el-divider>
        <el-form :model="recognizedData" label-width="100px">
          <el-form-item label="Examination Date">
            <el-date-picker v-model="recognizedData.recordDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="Hospital">
            <el-input v-model="recognizedData.hospitalName" />
          </el-form-item>
          <el-form-item label="Clinician">
            <el-input v-model="recognizedData.doctorName" />
          </el-form-item>
          <el-form-item label="Notes">
            <el-input v-model="recognizedData.remark" type="textarea" :rows="2" />
          </el-form-item>
        </el-form>

        <el-divider content-position="left">Examination item details</el-divider>
        <div class="recognize-items-toolbar">
          <span class="recognize-items-count">total {{ recognizedData.items.length }} item</span>
          <div class="recognize-items-actions">
            <el-button size="small" @click="dedupeRecognizedItemsLocal">mergeduplicateitem</el-button>
            <el-button size="small" type="primary" plain @click="addRecognizedItem">Addonerow</el-button>
          </div>
        </div>
        <el-table :data="recognizedData.items" border size="small" max-height="300" class="app-data-table recognize-items-table">
          <el-table-column prop="itemName" label="Test item" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.itemName" size="small" placeholder="Test item name" />
            </template>
          </el-table-column>
          <el-table-column prop="resultValue" label="measured value" width="120">
            <template #default="{ row }">
              <el-input v-model="row.resultValue" size="small" />
            </template>
          </el-table-column>
          <el-table-column prop="unit" label="Unit" width="88">
            <template #default="{ row }">
              <el-input v-model="row.unit" size="small" />
            </template>
          </el-table-column>
          <el-table-column prop="referenceRange" label="Reference Range" width="120">
            <template #default="{ row }">
              <el-input v-model="row.referenceRange" size="small" />
            </template>
          </el-table-column>
          <el-table-column prop="isAbnormal" label="Status" width="88" align="center">
            <template #default="{ row }">
              <el-select v-model="row.isAbnormal" size="small" style="width: 76px">
                <el-option label="Normal" :value="0" />
                <el-option label="high" :value="1" />
                <el-option label="low" :value="-1" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="Actions" width="72" fixed="right" align="center">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="removeRecognizedItem($index)">Delete</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <template #footer>
        <el-button @click="uploadDialogVisible = false">Cancel</el-button>
        <el-button v-if="!recognizeResult" type="primary" :loading="recognizeLoading" @click="startRecognize">Start recognition</el-button>
        <el-button v-else type="success" @click="saveRecognizedRecord">Save record</el-button>
      </template>
    </el-dialog>

    <!-- Detailsdialog -->
    <el-dialog v-model="detailDialogVisible" title="Medical record details" width="min(900px, 95vw)" destroy-on-close>
      <el-descriptions :column="2" border v-if="currentRecord">
        <el-descriptions-item label="Patient">{{ currentRecord.patientName }}</el-descriptions-item>
        <el-descriptions-item label="Examination Date">{{ currentRecord.recordDate }}</el-descriptions-item>
        <el-descriptions-item label="Examination Type">{{ getRecordTypeName(currentRecord.recordType) }}</el-descriptions-item>
        <el-descriptions-item label="Hospital">{{ currentRecord.hospitalName }}</el-descriptions-item>
        <el-descriptions-item label="Department">{{ currentRecord.deptName }}</el-descriptions-item>
        <el-descriptions-item label="Clinician">{{ currentRecord.doctorName }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">Examination item details</el-divider>
      <el-table :data="currentRecord?.items || []" class="app-data-table" stripe>
        <el-table-column prop="itemName" label="Test item" width="150" />
        <el-table-column prop="resultValue" label="measured value" width="120" />
        <el-table-column prop="unit" label="Unit" width="80" />
        <el-table-column prop="referenceRange" label="Reference Range" width="150" />
        <el-table-column prop="isAbnormal" label="Status" width="100">
          <template #default="{ row }">
            <el-tag :type="getAbnormalType(row.isAbnormal)">{{ getAbnormalText(row.isAbnormal) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">Attachmentimage</el-divider>
      <div v-if="detailImageAttachments.length" class="attachment-images">
        <el-image
          v-for="(att, index) in detailImageAttachments"
          :key="att.id || index"
          :src="getImageSrc(att)"
          :preview-src-list="getPreviewSrcList(detailImageAttachments)"
          :initial-index="index"
          fit="cover"
          class="attachment-image"
        />
      </div>
      <el-empty v-else description="No image attachments. Save the record before uploading images." :image-size="64" />
      <div v-if="detailOtherAttachments.length" class="detail-other-attachments">
        <div v-for="att in detailOtherAttachments" :key="att.id" class="detail-pdf-item">
          <el-icon><Document /></el-icon>
          <span>{{ att.fileName || 'PDF Attachment' }}</span>
          <el-tag size="small" type="info">Archived file; preview unavailable</el-tag>
        </div>
      </div>
    </el-dialog>

    <!-- Editdialog -->
    <el-dialog v-model="editDialogVisible" title="Edit medical record" width="min(900px, 95vw)" :close-on-click-modal="false" destroy-on-close>
      <el-form :model="editForm" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Patient">
              <el-select v-model="editForm.patientId" placeholder="Select a patient" filterable style="width: 100%">
                <el-option v-for="patient in patientList" :key="patient.id" :label="patient.patientName" :value="patient.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Examination Date">
              <el-date-picker v-model="editForm.recordDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Examination Type">
              <el-select v-model="editForm.recordType" placeholder="Select" style="width: 100%">
                <el-option label="blood test" value="BLOOD" />
                <el-option label="urinalysis" value="URINE" />
                <el-option label="liverfeature" value="LIVER" />
                <el-option label="kidneyfeature" value="KIDNEY" />
                <el-option label="bone metabolism" value="BONE" />
                <el-option label="iron metabolism" value="IRON" />
                <el-option label="imagingReport" value="IMAGE" />
                <el-option label="Other" value="OTHER" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Hospital">
              <el-input v-model="editForm.hospitalName" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Clinician">
              <el-input v-model="editForm.doctorName" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Notes">
              <el-input v-model="editForm.remark" type="textarea" :rows="1" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-divider content-position="left">Examination item details</el-divider>
      <div class="recognize-items-toolbar">
        <span class="recognize-items-count">total {{ editItems.length }} item</span>
        <div class="recognize-items-actions">
          <el-button size="small" type="primary" plain @click="addEditItem">Addonerow</el-button>
        </div>
      </div>
      <el-table :data="editItems" border size="small" max-height="300" class="app-data-table recognize-items-table">
        <el-table-column prop="itemName" label="Test item" min-width="140">
          <template #default="{ row }">
            <el-input v-model="row.itemName" size="small" placeholder="Test item name" />
          </template>
        </el-table-column>
        <el-table-column prop="resultValue" label="measured value" width="120">
          <template #default="{ row }">
            <el-input v-model="row.resultValue" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="Unit" width="88">
          <template #default="{ row }">
            <el-input v-model="row.unit" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="referenceRange" label="Reference Range" width="120">
          <template #default="{ row }">
            <el-input v-model="row.referenceRange" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="isAbnormal" label="Status" width="88" align="center">
          <template #default="{ row }">
            <el-select v-model="row.isAbnormal" size="small" style="width: 76px">
              <el-option label="Normal" :value="0" />
              <el-option label="high" :value="1" />
              <el-option label="low" :value="-1" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="Actions" width="72" fixed="right" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="removeEditItem($index)">Delete</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">Attachmentimage</el-divider>
      <el-upload
        ref="editAttachmentUploadRef"
        action="#"
        :auto-upload="false"
        :limit="10"
        multiple
        accept="image/*,.pdf"
        :on-change="handleEditAttachmentChange"
        :show-file-list="false"
        class="edit-attachment-upload"
      >
        <el-button type="primary" plain size="small">
          <el-icon><Upload /></el-icon> UploadnewAttachment
        </el-button>
        <template #tip>
          <div class="el-upload__tip">can UploadReportimageasfor Attachmentarchive, support jpg, png, pdf</div>
        </template>
      </el-upload>

      <template v-if="editAttachments.length > 0">
        <div class="attachment-images">
          <div v-for="(att, index) in editAttachments" :key="att.id || index" class="attachment-image-wrapper">
            <el-image
              :src="getImageSrc(att)"
              :preview-src-list="getPreviewSrcList(editAttachments)"
              :initial-index="index"
              fit="cover"
              class="attachment-image"
            />
            <el-button
              type="danger"
              size="small"
              circle
              class="attachment-delete-btn"
              @click="removeEditAttachment(index)"
            >
              ×
            </el-button>
          </div>
        </div>
      </template>

      <template #footer>
        <el-button @click="editDialogVisible = false">Cancel</el-button>
        <el-button type="primary" @click="saveEditRecord">Save</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { localDateKey } from '@/utils/familyHealth';
import { dedupeRecognizedItems } from '@/utils/medicalRecordItems';
import recordsCareSmall from '@/assets/illustrations/records-care-small.webp';
import recordsCare from '@/assets/illustrations/records-care.webp';
import { ref, reactive, computed, onMounted, onUnmounted, watch, inject } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { readPermissionCache } from '@/utils/authSession';
import { canAccessWorkspace } from '@/utils/workspaceAccess';
import { ElMessage, ElMessageBox } from 'element-plus';
import { UploadFilled, Loading, Search, Upload, Setting, Camera, Picture, Document } from '@element-plus/icons-vue';
import * as api from '../api/medicalRecord.js';
import { getPatientNames } from '@/api/patient';
import { useCurrentPatient } from '@/composables/useCurrentPatient';

import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { LineChart as EchartsLineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent } from 'echarts/components';
import VChart from 'vue-echarts';

import TableActionHeader from '@/components/TableActionHeader.vue';
import { useTableColumns } from '@/composables/useTableColumns';
import { useMobile } from '@/composables/useMobile';
import { compressImageFile, formatFileSize, isImageFile } from '@/utils/imageCompress';

use([CanvasRenderer, EchartsLineChart, GridComponent, TooltipComponent, LegendComponent, TitleComponent]);

const LIST_COLUMN_DEFS = [
  { key: 'recordDate', label: 'Date' },
  { key: 'patientName', label: 'Patient' },
  { key: 'recordType', label: 'type' },
  { key: 'hospitalName', label: 'Hospital', default: false },
  { key: 'doctorName', label: 'Clinician', default: false },
  { key: 'items', label: 'Test items' },
  { key: 'abnormal', label: 'Abnormal' }
];
const { visibleKeys: listVisibleCols, isVisible: listColVisible, resetColumns: resetListColumns } =
  useTableColumns('medical-record-list', LIST_COLUMN_DEFS);

const { currentPatientId } = useCurrentPatient();

const filterForm = reactive({
  patientId: currentPatientId.value,
  patientName: '',
  recordType: '',
  timeValue: '',
  itemName: ''
});

const route = useRoute();
const router = useRouter();
const permissionMenus = inject('userMenus', ref([]));
const canUpload = computed(() => {
  permissionMenus.value;
  const permissions = readPermissionCache() || {};
  return canAccessWorkspace('/medical-record?tab=upload', permissions.menuPaths, permissions.roleCodes);
});
const menuTabs = ['list', 'upload', 'abnormal', 'trend'];
const activeMenu = ref(menuTabs.includes(route.query.tab) ? route.query.tab : 'list');
watch(() => route.query.tab, (tab) => {
  handleMenuSelect(menuTabs.includes(tab) ? tab : 'list');
});

const { isMobile } = useMobile();
const records = ref([]);
const uploadDialogVisible = ref(false);
const detailDialogVisible = ref(false);
const uploadRef = ref(null);
const uploadDialogRef = ref(null);
const editAttachmentUploadRef = ref(null);
const currentRecord = ref(null);
const recognizeLoading = ref(false);
const recognizeResult = ref(false);
const recognizeWarning = ref('');
const archiveSaving = ref(false);
const selectedFiles = ref([]);
const loading = ref(false);
const editDialogVisible = ref(false);
const editForm = reactive({
  id: null,
  patientId: currentPatientId.value,
  patientName: '',
  recordDate: '',
  recordType: 'BLOOD',
  hospitalName: '',
  doctorName: '',
  remark: ''
});
const editItems = ref([]);
const editAttachments = ref([]);

const patientList = ref([]);

const uploadForm = reactive({
  patientId: currentPatientId.value,
  patientName: '',
  recordType: ''
});

const uploadMode = ref('recognize');

const archiveForm = reactive({
  recordDate: '',
  hospitalName: 'Huangchuan CountypersonpeopleHospital',
  doctorName: '',
  remark: ''
});

const archiveItems = ref([]);

const recognizedData = reactive({
  recordDate: '',
  hospitalName: '',
  doctorName: '',
  remark: '',
  items: []
});

const recognizedRecords = ref([]);
const activeRecognizeTab = ref('0');

const currentRecognizeRecord = computed(() => {
  const idx = Number(activeRecognizeTab.value) || 0;
  if (!recognizedRecords.value.length) {
    return {
      recordType: uploadForm.recordType || 'BLOOD',
      recordDate: recognizedData.recordDate,
      hospitalName: recognizedData.hospitalName,
      doctorName: recognizedData.doctorName,
      remark: '',
      items: recognizedData.items
    };
  }
  return recognizedRecords.value[idx] || recognizedRecords.value[0];
});

const trendForm = reactive({
  patientId: currentPatientId.value,
  patientName: '',
  itemName: ''
});
const trendData = ref([]);
const trendLoading = ref(false);
const allItemNames = ref([]);

const recordTypeMap = {
  BLOOD: 'blood test',
  URINE: 'urinalysis',
  LIVER: 'liverfeature',
  KIDNEY: 'kidneyfeature',
  BONE: 'bone metabolism',
  IRON: 'iron metabolism',
  IMAGE: 'imagingReport',
  OTHER: 'Other'
};

function getRecordTypeName(type) {
  return recordTypeMap[type] || type;
}

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      const result = e.target.result;
      // goremove data:image/xxx;base64, before suffix, onlykeeppure base64
      const base64 = result.includes(',') ? result.split(',')[1] : result;
      resolve(base64);
    };
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

function attachmentMimeType(att) {
  const name = (att.fileName || '').toLowerCase();
  if (name.endsWith('.png')) return 'image/png';
  if (name.endsWith('.gif')) return 'image/gif';
  if (name.endsWith('.webp')) return 'image/webp';
  if (name.endsWith('.bmp')) return 'image/bmp';
  if (name.endsWith('.pdf') || att.fileType === 'PDF') return 'application/pdf';
  return 'image/jpeg';
}

function getImageSrc(att) {
  if (!att?.fileContent) return '';
  const raw = String(att.fileContent).trim();
  if (raw.startsWith('data:')) return raw;
  return `data:${attachmentMimeType(att)};base64,${raw}`;
}

function getPreviewSrcList(attachments) {
  return (attachments || [])
    .map(att => getImageSrc(att))
    .filter(Boolean);
}

function removeEditAttachment(index) {
  editAttachments.value.splice(index, 1);
}

async function handleEditAttachmentChange(file) {
  const rawFile = file.raw;
  try {
    const base64 = await fileToBase64(rawFile);
    editAttachments.value.push({
      fileName: rawFile.name,
      fileType: rawFile.name.toLowerCase().endsWith('.pdf') ? 'PDF' : 'IMAGE',
      fileSize: rawFile.size,
      fileContent: base64
    });
    ElMessage.success('Attachment added');
  } catch (e) {
    ElMessage.error('Failed to process attachment: ' + e.message);
  }
}

const pageTitle = computed(() => {
  const map = {
    list: 'Medical records',
    upload: 'Upload and Recognize',
    abnormal: 'Abnormal results',
    trend: 'Result Trends'
  };
  return map[activeMenu.value] || 'Medical records';
});

const pageSubtitle = computed(() => {
  const map = {
    list: 'Manage historical reports and review imported data quality.',
    upload: 'Upload reports and review the AI-extracted draft before saving.',
    abnormal: 'Focus follow-up on stored results marked high or low.',
    trend: 'Track how a selected test result changes over time.'
  };
  return map[activeMenu.value] || '';
});

const displayRecords = computed(() => {
  let list = records.value;
  if (activeMenu.value === 'abnormal') {
    list = list.filter(hasAbnormal);
  }
  if (filterForm.itemName && filterForm.itemName.trim()) {
    const keyword = filterForm.itemName.trim();
    list = list.filter(r => r.items?.some(i => i.itemName?.includes(keyword)));
  }
  return list;
});

const detailImageAttachments = computed(() => {
  const list = currentRecord.value?.attachments || [];
  return list.filter(att => att.fileType !== 'PDF' && getImageSrc(att));
});

const detailOtherAttachments = computed(() => {
  const list = currentRecord.value?.attachments || [];
  return list.filter(att => att.fileType === 'PDF' || !getImageSrc(att));
});



const trendChartOption = computed(() => {
  const dates = trendData.value.map(d => d.recordDate);
  const values = trendData.value.map(d => {
    const v = parseFloat(String(d.resultValue).replace(/[^0-9.]/g, ''));
    return isNaN(v) ? null : v;
  });
  const unit = trendData.value[0]?.unit || '';
  return {
    tooltip: { trigger: 'axis', confine: true },
    legend: { bottom: 0 },
    grid: { left: '3%', right: '4%', bottom: '12%', containLabel: true },
    toolbox: isMobile.value ? undefined : { feature: { saveAsImage: {} } },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: isMobile.value ? 45 : 30, fontSize: 11 } },
    yAxis: { type: 'value', scale: true, name: unit },
    series: [{
      name: trendForm.itemName || 'measured value',
      type: 'line',
      data: values,
      smooth: true,
      connectNulls: true,
      itemStyle: { color: '#6366f1' },
      label: { show: true, position: 'top', fontSize: 11, formatter: p => p.value }
    }]
  };
});

function handleMenuSelect(index) {
  activeMenu.value = index;
  if (index === 'list' || index === 'abnormal') {
    loadRecords();
  }
}

async function goUpload() {
  if (!canUpload.value) return;
  const failure = await router.push({ path: route.path, query: { tab: 'upload' } });
  if (!failure && route.query.tab === 'upload') resetUploadState();
}

function resetUploadState() {
  uploadForm.patientId = null;
  uploadForm.patientName = '';
  uploadForm.recordType = '';
  uploadMode.value = 'recognize';
  archiveForm.recordDate = '';
  archiveForm.hospitalName = 'Huangchuan CountypersonpeopleHospital';
  archiveForm.doctorName = '';
  archiveForm.remark = '';
  archiveItems.value = [];
  recognizedRecords.value = [];
  activeRecognizeTab.value = '0';
  recognizedData.items = [];
  recognizedData.recordDate = '';
  recognizedData.hospitalName = 'Huangchuan CountypersonpeopleHospital';
  recognizedData.doctorName = '';
  recognizedData.remark = '';
  recognizeResult.value = false;
  recognizeWarning.value = '';
  selectedFiles.value = [];
}

function buildRecognizeRecordFromApi(rec) {
  const today = localDateKey();
  return reactive({
    recordType: rec.recordType || uploadForm.recordType || 'BLOOD',
    recordDate: rec.checkDate || rec.recordDate || today,
    hospitalName: rec.hospitalName || '',
    doctorName: rec.doctorName || '',
    remark: rec.remark || '',
    items: mapRecognizedItemsFromApi(rec.items)
  });
}

function applyOcrResultToRecords(data) {
  const today = localDateKey();
  if (data.records && data.records.length > 0) {
    recognizedRecords.value = data.records.map(r => buildRecognizeRecordFromApi(r));
  } else {
    recognizedRecords.value = [buildRecognizeRecordFromApi({
      recordType: data.recordType,
      checkDate: data.checkDate,
      hospitalName: data.hospitalName,
      doctorName: data.doctorName,
      items: data.items
    })];
  }
  activeRecognizeTab.value = '0';
  const first = recognizedRecords.value[0];
  if (first) {
    recognizedData.recordDate = first.recordDate || today;
    recognizedData.hospitalName = first.hospitalName;
    recognizedData.doctorName = first.doctorName;
    if (first.recordType) uploadForm.recordType = first.recordType;
  }
}

function hasAbnormal(record) {
  return record.items?.some(i => i.isAbnormal !== 0);
}

function countAbnormal(record) {
  return record.items?.filter(i => i.isAbnormal !== 0).length || 0;
}

function getAbnormalType(status) {
  if (status === 1) return 'danger';
  if (status === -1) return 'warning';
  return 'success';
}

function getAbnormalText(status) {
  if (status === 1) return 'high';
  if (status === -1) return 'low';
  return 'Normal';
}


function mapRecognizedItemsFromApi(items) {
  return dedupeRecognizedItems((items || []).map(item => ({
    itemName: item.itemName,
    resultValue: item.resultValue,
    unit: item.unit,
    referenceRange: item.referenceRange,
    isAbnormal: item.isAbnormal
  })));
}

function removeRecognizedItem(index) {
  currentRecognizeRecord.value.items.splice(index, 1);
}

function addRecognizedItem() {
  currentRecognizeRecord.value.items.push({
    itemName: '',
    resultValue: '',
    unit: '',
    referenceRange: '',
    isAbnormal: 0
  });
}

function dedupeRecognizedItemsLocal() {
  const rec = currentRecognizeRecord.value;
  const before = rec.items.length;
  rec.items = dedupeRecognizedItems(rec.items);
  const removed = before - rec.items.length;
  if (removed > 0) {
    ElMessage.success(`Merged ${removed} duplicate items`);
  } else {
    ElMessage.info('not sendcurrentcan merge duplicateitem');
  }
}

async function loadRecords() {
  loading.value = true;
  try {
    const res = await api.listRecords(filterForm);
    if (res.code === 200) {
      records.value = res.data || [];
    }
  } catch (e) {
    ElMessage.error('Failed to load: ' + e.message);
  } finally {
    loading.value = false;
  }
}

async function loadTrend() {
  const patient = patientList.value.find(p => p.id === trendForm.patientId);
  trendForm.patientName = patient?.patientName || '';
  if (!trendForm.patientId || !trendForm.itemName) {
    ElMessage.warning('Select a patient and a test item');
    return;
  }
  trendLoading.value = true;
  try {
    const res = await api.getItemTrend(trendForm.patientId, trendForm.patientName, trendForm.itemName);
    if (res.code === 200) {
      trendData.value = res.data || [];
      if (trendData.value.length === 0) {
        ElMessage.info('not findto this indicator historyrecord');
      }
    } else {
      ElMessage.error(res.message || 'Query failed');
    }
  } catch (e) {
    ElMessage.error('Query failed: ' + e.message);
  } finally {
    trendLoading.value = false;
  }
}

async function loadItemNames() {
  try {
    const res = await api.getAllItemNames(currentPatientId.value);
    if (res.code === 200) {
      const dbNames = res.data || [];
      const defaults = [
        'whitecell', 'red blood cells', 'hemoglobin', 'bloodsmallpanel',
        'creatinine', 'blood urea nitrogen', 'urineacid',
        'ALTconvertaminotransferase', 'ASTconvertaminotransferase', 'totalbilirubin',
        'serum calcium', 'serum phosphorus', 'parathyroid hormone',
        'ferritin', 'convertferritinfull and level',
        'potassium', 'sodium', 'chloride',
        'twooxygentransformcarboncombinestrength', 'albumin',
        'totalbilefixedalcohol', 'glycerolthreeester',
        'Blood Glucose', 'glycatedhemoglobin',
        'PTH', 'β2slightglobuleproteinwhite',
        'Creverseshouldproteinwhite', 'iron'
      ];
      const merged = Array.from(new Set([...dbNames, ...defaults]));
      allItemNames.value = merged.sort((a, b) => a.localeCompare(b, 'zh'));
    }
  } catch (e) {
    console.error('Failed to load test items', e);
    ElMessage.warning('The test item list could not be loaded; you can still enter an item manually');
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

function showUploadDialog() {
  resetUploadState();
  uploadDialogVisible.value = true;
}

async function handleFileChange(file) {
  const raw = file?.raw;
  if (!raw) return;
  try {
    if (isImageFile(raw)) {
      const before = raw.size;
      const next = await compressImageFile(raw);
      if (next.size < before) {
        ElMessage.info(`Compressed: ${formatFileSize(before)} → ${formatFileSize(next.size)}`);
      }
      selectedFiles.value.push(next);
      return;
    }
  } catch (_) { /* compressfailedthenuseoriginalchart */ }
  selectedFiles.value.push(raw);
}

function triggerCameraUpload(target) {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = 'image/*';
  input.capture = 'environment';
  input.onchange = (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const targetRef = target === 'dialog' ? uploadDialogRef : uploadRef;
    targetRef.value?.handleStart(file);
  };
  input.click();
}

function triggerAlbumUpload(target) {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = 'image/*,.pdf';
  input.multiple = true;
  input.onchange = (e) => {
    const files = Array.from(e.target.files || []);
    if (!files.length) return;
    const targetRef = target === 'dialog' ? uploadDialogRef : uploadRef;
    files.forEach(file => targetRef.value?.handleStart(file));
  };
  input.click();
}

async function startRecognize() {
  if (!selectedFiles.value || selectedFiles.value.length === 0) {
    ElMessage.warning('Upload at least one report file');
    return;
  }
  if (!uploadForm.patientId) {
    ElMessage.warning('Select a patient');
    return;
  }
  recognizeLoading.value = true;
  try {
    const res = await api.uploadAndRecognize(selectedFiles.value, uploadForm.patientId, uploadForm.recordType);
    if (res.code === 200) {
      const data = res.data;
      if (data?.error) {
        ElMessage.error(data.error);
        recognizeResult.value = false;
        return;
      }
      applyOcrResultToRecords(data);
      recognizeWarning.value = data.warning || '';
      recognizeResult.value = true;
      if (data.warning) {
        ElMessage.warning(data.warning);
      }
    } else {
      ElMessage.error(res.msg || res.message || 'Recognition failed');
    }
  } catch (e) {
    const status = e.response?.status;
    if (status === 413) {
      ElMessage.error('The image was rejected because it is too large. Choose a smaller file or contact an administrator.');
    } else if (e.message?.includes('timeout')) {
      ElMessage.error('Recognition timed out. Try a smaller file or retry on a faster connection.');
    } else {
      ElMessage.error('Recognition failed: ' + (e.message || 'Unknown error'));
    }
  } finally {
    recognizeLoading.value = false;
  }
}

async function buildUploadAttachments() {
  if (!selectedFiles.value?.length) return [];
  const attachments = [];
  for (const file of selectedFiles.value) {
    const name = file.name?.toLowerCase() || '';
    if (name.endsWith('.pdf')) {
      attachments.push({
        fileName: file.name,
        fileType: 'PDF',
        fileSize: file.size,
        fileContent: null
      });
      continue;
    }
    const base64 = await fileToBase64(file);
    attachments.push({
      fileName: file.name,
      fileType: 'IMAGE',
      fileSize: file.size,
      fileContent: base64
    });
  }
  return attachments;
}

function mapItemsForSave(items) {
  return (items || []).map(item => ({
    itemName: item.itemName,
    resultValue: item.resultValue,
    unit: item.unit,
    referenceRange: item.referenceRange,
    isAbnormal: item.isAbnormal
  }));
}

function getFilePreviewUrl(file) {
  return URL.createObjectURL(file);
}

function addArchiveItem() {
  archiveItems.value.push({
    itemName: '',
    resultValue: '',
    unit: '',
    referenceRange: '',
    isAbnormal: 0
  });
}

function removeArchiveItem(index) {
  archiveItems.value.splice(index, 1);
}

async function saveArchiveRecord() {
  if (!selectedFiles.value || selectedFiles.value.length === 0) {
    ElMessage.warning('Upload at least one report file');
    return;
  }
  if (!uploadForm.patientId) {
    ElMessage.warning('Select a patient');
    return;
  }
  archiveSaving.value = true;
  try {
    const patient = patientList.value.find(p => p.id === uploadForm.patientId);
    const record = {
      patientId: uploadForm.patientId,
      patientName: patient?.patientName || '',
      recordType: uploadForm.recordType || 'IMAGE',
      recordDate: archiveForm.recordDate || localDateKey(),
      hospitalName: archiveForm.hospitalName,
      doctorName: archiveForm.doctorName,
      remark: archiveForm.remark || '',
      aiRawResult: null
    };
    const attachments = await buildUploadAttachments();
    const items = archiveItems.value.map(item => ({
      itemName: item.itemName,
      resultValue: item.resultValue,
      unit: item.unit,
      referenceRange: item.referenceRange,
      isAbnormal: item.isAbnormal
    }));
    const res = await api.saveRecord(record, items, attachments);
    if (res.code === 200) {
      ElMessage.success('Record saved successfully');
      resetUploadState();
      uploadRef.value?.clearFiles();
      loadRecords();
    } else {
      ElMessage.error(res.message || res.msg || 'Failed to save');
    }
  } catch (e) {
    ElMessage.error('Failed to save: ' + e.message);
  } finally {
    archiveSaving.value = false;
  }
}

async function saveRecognizedRecord() {
  try {
    const list = recognizedRecords.value.length
      ? recognizedRecords.value
      : [currentRecognizeRecord.value];
    const attachments = await buildUploadAttachments();

    if (list.length > 1) {
      const records = list.map(rec => ({
        patientId: uploadForm.patientId,
        patientName: uploadForm.patientName,
        recordType: rec.recordType || 'BLOOD',
        recordDate: rec.recordDate,
        hospitalName: rec.hospitalName,
        doctorName: rec.doctorName,
        remark: rec.remark || '',
        aiRawResult: JSON.stringify(rec)
      }));
      const itemsList = list.map(rec => mapItemsForSave(rec.items));
      const res = await api.saveRecordsBatch(records, itemsList, attachments);
      if (res.code === 200) {
        ElMessage.success(res.data || res.msg || 'Saved successfully');
        uploadDialogVisible.value = false;
        resetUploadState();
        loadRecords();
      } else {
        ElMessage.error(res.message || res.msg || 'Failed to save');
      }
      return;
    }

    const rec = list[0];
    const record = {
      patientId: uploadForm.patientId,
      patientName: uploadForm.patientName,
      recordType: rec.recordType || uploadForm.recordType || 'BLOOD',
      recordDate: rec.recordDate,
      hospitalName: rec.hospitalName,
      doctorName: rec.doctorName,
      remark: rec.remark || '',
      aiRawResult: JSON.stringify(rec)
    };
    const items = mapItemsForSave(rec.items);
    const res = await api.saveRecord(record, items, attachments);
    if (res.code === 200) {
      ElMessage.success('Saved successfully');
      uploadDialogVisible.value = false;
      resetUploadState();
      loadRecords();
    } else {
      ElMessage.error(res.message || res.msg || 'Failed to save');
    }
  } catch (e) {
    ElMessage.error('Failed to save: ' + e.message);
  }
}

async function viewDetail(row) {
  try {
    const res = await api.getRecord(row.id);
    if (res.code === 200) {
      currentRecord.value = res.data;
      detailDialogVisible.value = true;
    }
  } catch (e) {
    ElMessage.error('Failed to load details: ' + e.message);
  }
}

async function deleteRecord(row) {
  try {
    await ElMessageBox.confirm('ConfirmneedDeletethisitemsMedical Records?', 'Notice', { type: 'warning' });
    const res = await api.deleteRecord(row.id);
    if (res.code === 200) {
      ElMessage.success('Deleted successfully');
      loadRecords();
    } else {
      ElMessage.error(res.message || 'Failed to delete');
    }
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('Failed to delete: ' + e.message);
  }
}

async function openEditDialog(row) {
  try {
    // firstgetcompletedata (includeAttachment)
    const res = await api.getRecord(row.id);
    if (res.code === 200) {
      const fullData = res.data;
      editForm.id = fullData.id;
      editForm.patientId = fullData.patientId || null;
      editForm.patientName = fullData.patientName || '';
      editForm.recordDate = fullData.recordDate || '';
      editForm.recordType = fullData.recordType || 'BLOOD';
      editForm.hospitalName = fullData.hospitalName || '';
      editForm.doctorName = fullData.doctorName || '';
      editForm.remark = fullData.remark || '';
      editItems.value = (fullData.items || []).map(item => ({
        id: item.id,
        itemName: item.itemName,
        resultValue: item.resultValue,
        unit: item.unit,
        referenceRange: item.referenceRange,
        isAbnormal: item.isAbnormal
      }));
      editAttachments.value = (fullData.attachments || []).map(att => ({
        id: att.id,
        fileName: att.fileName,
        fileType: att.fileType,
        fileSize: att.fileSize,
        fileContent: att.fileContent
      }));
      editDialogVisible.value = true;
    }
  } catch (e) {
    ElMessage.error('Failed to load form data: ' + e.message);
  }
}

function addEditItem() {
  editItems.value.push({
    itemName: '',
    resultValue: '',
    unit: '',
    referenceRange: '',
    isAbnormal: 0
  });
}

function removeEditItem(index) {
  editItems.value.splice(index, 1);
}

async function saveEditRecord() {
  try {
    const record = {
      id: editForm.id,
      patientId: editForm.patientId,
      patientName: editForm.patientName,
      recordType: editForm.recordType,
      recordDate: editForm.recordDate,
      hospitalName: editForm.hospitalName,
      doctorName: editForm.doctorName,
      remark: editForm.remark
    };
    const items = editItems.value.map(item => ({
      id: item.id,
      itemName: item.itemName,
      resultValue: item.resultValue,
      unit: item.unit,
      referenceRange: item.referenceRange,
      isAbnormal: item.isAbnormal
    }));
    // Edittimekeepcurrenthas Attachment (from  editAttachments get, keep id toconvenientafter endrangepointalready has  and newUpload )
    const attachments = editAttachments.value?.length > 0
      ? editAttachments.value.map(att => ({
          id: att.id,
          fileName: att.fileName,
          fileType: att.fileType || 'IMAGE',
          fileSize: att.fileSize,
          fileContent: att.fileContent
        }))
      : [];
    const res = await api.updateRecord(record, items, attachments);
    if (res.code === 200) {
      ElMessage.success('Saved successfully');
      editDialogVisible.value = false;
      loadRecords();
    } else {
      ElMessage.error(res.message || 'Failed to save');
    }
  } catch (e) {
    ElMessage.error('Failed to save: ' + e.message);
  }
}

function handlePaste(e) {
  const isUploadPage = activeMenu.value === 'upload';
  const isUploadDialog = uploadDialogVisible.value;
  if (!isUploadPage && !isUploadDialog) return;
  const items = e.clipboardData?.items;
  if (!items) return;
  let added = 0;
  const targetRef = isUploadDialog ? uploadDialogRef : uploadRef;
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    if (item.type.indexOf('image') === -1) continue;
    const file = item.getAsFile();
    if (!file) continue;
    if (selectedFiles.value.length + added >= 5) {
      ElMessage.warning('You can paste up to 5 images');
      break;
    }
    targetRef.value?.handleStart(file);
    added++;
  }
  if (added > 0) {
    ElMessage.success(`Pasted ${added} images`);
  }
}

onMounted(() => {
  window.addEventListener('paste', handlePaste);
  loadPatientList();
  loadRecords();
  loadItemNames();
});

onUnmounted(() => {
  window.removeEventListener('paste', handlePaste);
});

// switchPatientafter againloadExaminationitemlist
watch(currentPatientId, (newVal, oldVal) => {
  if (newVal !== oldVal) {
    loadItemNames();
  }
});
</script>

<style scoped src="@/styles/module-layout.css"></style>
<style scoped>
.medical-record-manager .top-bar.record-heading {
  align-items: center;
  flex-wrap: nowrap;
  gap: 24px;
  min-height: 128px;
  padding: 14px 22px;
  border: 1px solid #dce8dc;
  border-radius: 16px;
  background: linear-gradient(110deg, #edf5ec 0%, #f9f6eb 100%);
}

.record-heading .left {
  flex: 1;
}

.record-heading .left > div {
  min-width: 0;
  overflow-wrap: anywhere;
}

.record-heading-art {
  display: block;
  flex: 0 0 160px;
  width: 160px;
  height: auto;
  border-radius: 12px;
}

@media (max-width: 768px) {
  .medical-record-manager .top-bar.record-heading {
    gap: 12px;
    min-height: 108px;
    padding: 12px;
  }

  .record-heading-art {
    flex-basis: 96px;
    width: 96px;
  }
}

@media (max-width: 375px) {
  .record-heading-art {
    flex-basis: 80px;
    width: 80px;
  }
}

.upload-text {
  margin-top: 8px;
  color: #606266;
}

.upload-text em {
  color: #409eff;
  font-style: normal;
  font-weight: 600;
}

.recognize-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 32px;
  color: #6366f1;
  font-size: 14px;
}

.recognize-result {
  margin-top: 16px;
  max-height: 500px;
  overflow-y: auto;
  padding-right: 4px;
}

.recognize-items-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.recognize-items-count {
  font-size: 13px;
  color: #64748b;
}

.recognize-items-actions {
  display: flex;
  gap: 8px;
}

.recognize-items-table :deep(.el-input__wrapper) {
  box-shadow: none;
}

.upload-panel .upload-drop {
  max-width: 480px;
}

.mobile-upload-actions {
  display: flex;
  gap: 10px;
  margin-top: 10px;
  flex-wrap: wrap;
}



.stats-empty {
  padding: 24px 0;
  animation: panel-enter 0.35s cubic-bezier(0.25, 0.46, 0.45, 0.94) both;
}

.trend-chart-wrap {
  background: #fff;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  padding: 16px;
  margin-bottom: 16px;
}

.trend-chart {
  width: 100%;
  height: 360px;
}

/* Attachmentimagestyle */
.attachment-images {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 12px 0;
}

.attachment-image-wrapper {
  position: relative;
  width: 150px;
  height: 150px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #e2e8f0;
}

.attachment-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
  cursor: pointer;
}

.attachment-delete-btn {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 24px;
  height: 24px;
  padding: 0;
  font-size: 14px;
  background: rgba(0, 0, 0, 0.6);
  border: none;
  color: #fff;
  opacity: 0;
  transition: opacity 0.2s;
}

.attachment-image-wrapper:hover .attachment-delete-btn {
  opacity: 1;
}

.edit-attachment-upload {
  margin-bottom: 8px;
}

.archive-preview {
  margin-top: 8px;
}

.archive-preview .attachment-image-wrapper {
  display: flex;
  flex-direction: column;
  height: auto;
  width: 150px;
}

.archive-preview .attachment-image {
  height: 150px;
}

.detail-other-attachments {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.detail-pdf-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #475569;
  padding: 8px 12px;
  background: #f8fafc;
  border-radius: 8px;
}
.attachment-file-name {
  font-size: 12px;
  color: #64748b;
  padding: 4px 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: center;
  background: #f8fafc;
}

</style>
