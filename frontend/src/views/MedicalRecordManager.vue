<template>
  <el-container class="module-page medical-record-manager">
    <section v-if="focusedMode" class="focused-source"><h1>Linked medical record</h1><p v-if="sourceFocus.locator.value">Patient #{{sourceFocus.locator.value.patientId}} · #{{sourceFocus.locator.value.sourceId}}</p><p v-if="sourceFocus.state.loading" role="status">Checking source access…</p><p v-if="sourceFocus.state.error" role="alert">This linked record is restricted, unavailable, or its context changed.</p><button type="button" :disabled="sourceFocus.state.loading" @click="sourceFocus.reload">Recheck source</button><button v-if="sourceFocus.state.record" type="button" @click="detailDialogVisible=true">Open record details</button></section>
    <el-main v-if="!focusedMode" class="main-content">
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
          <el-form :disabled="uploadSaving" :model="uploadForm" label-width="100px" class="upload-form">
            <el-form-item label="Patient" required>
              <el-select v-model="uploadForm.patientId" placeholder="Select a patient" filterable style="max-width: 320px">
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
                :file-list="uploadFiles"
                :on-remove="handleFileRemove"
                :disabled="uploadSaving"
                :on-change="handleFileChange"
                class="upload-drop"
              >
                <el-icon :size="40"><UploadFilled /></el-icon>
                <div class="upload-text">Drag reports here, <em>click to upload</em>, or paste images. Multiple files are supported.</div>
                <template #tip>
                  <div class="el-upload__tip">JPG, PNG, and PDF are supported. PDFs are limited to the first 30 pages. Mobile photos are compressed before upload.</div>
                </template>
              </el-upload>
              <p v-if="filesProcessing" role="status" aria-live="polite">Preparing photos. Please wait…</p>
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
              <el-button v-if="uploadMode === 'recognize'" type="primary" :loading="recognizeLoading" :disabled="filesProcessing || uploadSaving" @click="startRecognize">Start recognition</el-button>
              <el-button v-if="uploadMode === 'archive'" type="success" :loading="archiveSaving" :disabled="filesProcessing" @click="saveArchiveRecord">Save record</el-button>
              <el-button v-if="recognizeResult" type="success" :loading="recognizedSaving" :disabled="filesProcessing || archiveSaving" @click="saveRecognizedRecord">
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
            <el-form :disabled="uploadSaving" :model="currentRecognizeRecord" label-width="100px">
              <el-form-item v-if="recognizedRecords.length <= 1" label="Examination Type">
                <el-select :disabled="uploadSaving" v-model="currentRecognizeRecord.recordType" style="max-width: 320px">
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
                <el-input :disabled="uploadSaving" v-model="currentRecognizeRecord.hospitalName" style="max-width: 400px" />
              </el-form-item>
              <el-form-item label="Clinician">
                <el-input :disabled="uploadSaving" v-model="currentRecognizeRecord.doctorName" style="max-width: 320px" />
              </el-form-item>
            </el-form>
            <div class="recognize-items-toolbar">
              <span class="recognize-items-count">total {{ currentRecognizeRecord.items.length }} item</span>
              <div class="recognize-items-actions">
                <el-button :disabled="uploadSaving" size="small" @click="dedupeRecognizedItemsLocal">mergeduplicateitem</el-button>
                <el-button :disabled="uploadSaving" size="small" type="primary" plain @click="addRecognizedItem">Addonerow</el-button>
              </div>
            </div>
            <el-table :data="currentRecognizeRecord.items" border size="small" max-height="280" class="app-data-table recognize-items-table">
              <el-table-column prop="itemName" label="Test item" min-width="140">
                <template #default="{ row }">
                  <el-input :disabled="uploadSaving" v-model="row.itemName" size="small" placeholder="Test item name" />
                </template>
              </el-table-column>
              <el-table-column prop="resultValue" label="measured value" width="120">
                <template #default="{ row }">
                  <el-input :disabled="uploadSaving" v-model="row.resultValue" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="unit" label="Unit" width="88">
                <template #default="{ row }">
                  <el-input :disabled="uploadSaving" v-model="row.unit" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="referenceRange" label="Reference Range" width="120">
                <template #default="{ row }">
                  <el-input :disabled="uploadSaving" v-model="row.referenceRange" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="isAbnormal" label="Status" width="88" align="center">
                <template #default="{ row }">
                  <el-select :disabled="uploadSaving" v-model="row.isAbnormal" size="small" style="width: 76px">
                    <el-option label="Normal" :value="0" />
                    <el-option label="high" :value="1" />
                    <el-option label="low" :value="-1" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="Actions" width="72" fixed="right" align="center">
                <template #default="{ $index }">
                  <el-button :disabled="uploadSaving" link type="danger" size="small" @click="removeRecognizedItem($index)">Delete</el-button>
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
      <el-form :disabled="uploadSaving" :model="uploadForm" label-width="100px">
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
            :file-list="uploadFiles"
                :on-remove="handleFileRemove"
                :disabled="uploadSaving"
                :on-change="handleFileChange"
          >
            <el-icon :size="40"><UploadFilled /></el-icon>
            <div class="upload-text">Drag files here, <em>click to upload</em>, or paste images. Multiple files are supported.</div>
            <template #tip>
              <div class="el-upload__tip">JPG, PNG, and PDF are supported. PDFs are limited to the first 30 pages.</div>
            </template>
          </el-upload>
          <p v-if="filesProcessing" role="status" aria-live="polite">Preparing photos. Please wait…</p>
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
        <el-button v-if="!recognizeResult" type="primary" :loading="recognizeLoading" :disabled="filesProcessing || uploadSaving" @click="startRecognize">Start recognition</el-button>
        <el-button v-else type="success" :loading="recognizedSaving" :disabled="filesProcessing || archiveSaving" @click="saveRecognizedRecord">Save record</el-button>
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
      <el-table :data="currentRecord?.items || []" class="app-data-table medical-result-table" stripe>
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
      <section class="medical-result-cards" aria-label="Examination item details">
        <p v-if="!currentRecord?.items?.length" class="medical-result-empty">No examination items</p>
        <article v-for="(item, index) in currentRecord?.items || []" :key="item.id ?? index" class="medical-result-card">
          <h3>{{ item.itemName || 'Unnamed examination item' }}</h3>
          <dl>
            <div><dt>Measured value</dt><dd>{{ item.resultValue === null || item.resultValue === undefined || item.resultValue === '' ? '—' : item.resultValue }}</dd></div>
            <div><dt>Unit</dt><dd>{{ item.unit || '—' }}</dd></div>
            <div><dt>Reference range</dt><dd>{{ item.referenceRange || '—' }}</dd></div>
            <div><dt>Status</dt><dd><span class="medical-result-status" :class="'medical-result-status--' + getAbnormalType(item.isAbnormal)">{{ getAbnormalText(item.isAbnormal) }}</span></dd></div>
          </dl>
        </article>
      </section>

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
    <el-dialog :model-value="editDialogVisible" :key="editDialogKey" @update:model-value="editDialogModelChange" :before-close="editDialogBeforeClose" title="Edit medical record" width="min(900px, 95vw)" :close-on-click-modal="false" destroy-on-close>
      <p v-if="editLoading" role="status">Loading medical record…</p>
      <p v-if="editFilesProcessing" role="status">Preparing attachments…</p>
      <el-form :disabled="editLocked" :model="editForm" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Patient">
              <el-select :disabled="editLocked" v-model="editForm.patientId" placeholder="Select a patient" filterable style="width: 100%">
                <el-option v-for="patient in patientList" :key="patient.id" :label="patient.patientName" :value="patient.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Examination Date">
              <el-date-picker :disabled="editLocked" v-model="editForm.recordDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Examination Type">
              <el-select :disabled="editLocked" v-model="editForm.recordType" placeholder="Select" style="width: 100%">
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
              <el-input :disabled="editLocked" v-model="editForm.hospitalName" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Clinician">
              <el-input :disabled="editLocked" v-model="editForm.doctorName" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Notes">
              <el-input :disabled="editLocked" v-model="editForm.remark" type="textarea" :rows="1" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-divider content-position="left">Examination item details</el-divider>
      <div class="recognize-items-toolbar">
        <span class="recognize-items-count">total {{ editItems.length }} item</span>
        <div class="recognize-items-actions">
          <el-button :disabled="editLocked" size="small" type="primary" plain @click="addEditItem">Addonerow</el-button>
        </div>
      </div>
      <el-table :data="editItems" border size="small" max-height="300" class="app-data-table recognize-items-table">
        <el-table-column prop="itemName" label="Test item" min-width="140">
          <template #default="{ row }">
            <el-input :disabled="editLocked" v-model="row.itemName" size="small" placeholder="Test item name" />
          </template>
        </el-table-column>
        <el-table-column prop="resultValue" label="measured value" width="120">
          <template #default="{ row }">
            <el-input :disabled="editLocked" v-model="row.resultValue" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="Unit" width="88">
          <template #default="{ row }">
            <el-input :disabled="editLocked" v-model="row.unit" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="referenceRange" label="Reference Range" width="120">
          <template #default="{ row }">
            <el-input :disabled="editLocked" v-model="row.referenceRange" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="isAbnormal" label="Status" width="88" align="center">
          <template #default="{ row }">
            <el-select :disabled="editLocked" v-model="row.isAbnormal" size="small" style="width: 76px">
              <el-option label="Normal" :value="0" />
              <el-option label="high" :value="1" />
              <el-option label="low" :value="-1" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="Actions" width="72" fixed="right" align="center">
          <template #default="{ $index }">
            <el-button :disabled="editLocked" link type="danger" size="small" @click="removeEditItem($index)">Delete</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">Attachmentimage</el-divider>
      <el-upload
        :key="editSession"
        :disabled="editLocked"
        ref="editAttachmentUploadRef"
        action="#"
        :auto-upload="false"
        :limit="10"
        multiple
        accept="image/*,.pdf"
        :on-change="editAttachmentChange"
        :show-file-list="false"
        class="edit-attachment-upload"
      >
        <el-button :disabled="editLocked" type="primary" plain size="small">
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
              :disabled="editLocked"
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
        <el-button :disabled="editLocked || editFilesProcessing" :loading="editSaving" type="primary" @click="saveEditRecord">Save</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { useFocusedCareSource } from '@/composables/useFocusedCareSource';
import { localDateKey } from '@/utils/familyHealth';
import { dedupeRecognizedItems } from '@/utils/medicalRecordItems';
import recordsCareSmall from '@/assets/illustrations/records-care-small.webp';
import recordsCare from '@/assets/illustrations/records-care.webp';
import { ref, reactive, computed, onMounted, onUnmounted, watch, inject } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { captureAuthSession, isAuthSessionCurrent, readPermissionCache } from '@/utils/authSession';
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
import VChart from '@/components/HealthChart.vue';

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
const uploadFiles = ref([]);
const selectedFiles = computed(() => uploadFiles.value.filter(file => file.prepared).map(file => file.prepared));
const filesProcessing = computed(() => uploadFiles.value.some(file => !file.prepared));
const recognizedSaving = ref(false);
const uploadSaving = computed(() => archiveSaving.value || recognizedSaving.value);
let uploadVersion = 0;
let resettingUpload = false;
const uploadPreviewUrls = new Map();
const loading = ref(false);
const editDialogVisible = ref(false);
const editSession = ref(0);
const editDialogKey = ref(0);
const editLoading = ref(false), editReady = ref(false), editSaving = ref(false);
const editPendingAttachments = ref(0);
const editFilesProcessing = computed(() => editPendingAttachments.value > 0);
const editLocked = computed(() => editLoading.value || !editReady.value || editSaving.value);
let editSaveRequest = 0, editDisposed = false;

function invalidateEditDialog() {
  editSession.value++;
  editSaveRequest++;
  editLoading.value = false;
  editReady.value = false;
  editSaving.value = false;
  editPendingAttachments.value = 0;
}

function isCurrentEdit(session) {
  return !editDisposed && editDialogVisible.value && session === editSession.value;
}

// Element Plus emits model=false only after its leave transition. Invalidate
// immediately at close-start and keep an older dialog's model event from closing
// a replacement, even before Vue has mounted that replacement's keyed instance.
const editDialogBeforeClose = computed(() => {
  const session = editDialogKey.value;
  return done => {
    if (isCurrentEdit(session)) editDialogVisible.value = false;
    done();
  };
});
const editDialogModelChange = computed(() => {
  const key = editDialogKey.value;
  return visible => {
    if (!editDisposed && key === editDialogKey.value) editDialogVisible.value = visible;
  };
});

// The keyed upload widget retains the originating callback even if Element Plus
// delivers its queued change event after another editor has already opened.
const editAttachmentChange = computed(() => {
  const session = editSession.value;
  return file => handleEditAttachmentChange(file, session);
});
watch(editDialogVisible, visible => { if (!visible) invalidateEditDialog(); }, { flush: 'sync' });
onUnmounted(() => { editDisposed = true; invalidateEditDialog(); });
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
  if (editLocked.value || !isCurrentEdit(editSession.value)) return;
  editAttachments.value.splice(index, 1);
}

async function handleEditAttachmentChange(file, session = editSession.value) {
  if (!isCurrentEdit(session) || editLocked.value || !file?.raw) return;
  const rawFile = file.raw;
  editPendingAttachments.value++;
  try {
    const base64 = await fileToBase64(rawFile);
    if (!isCurrentEdit(session)) return;
    editAttachments.value.push({
      fileName: rawFile.name,
      fileType: rawFile.name.toLowerCase().endsWith('.pdf') ? 'PDF' : 'IMAGE',
      fileSize: rawFile.size,
      fileContent: base64
    });
    ElMessage.success('Attachment added');
  } catch (e) {
    if (isCurrentEdit(session)) ElMessage.error('Failed to process attachment: ' + e.message);
  } finally {
    if (isCurrentEdit(session)) editPendingAttachments.value--;
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

function invalidateUploadResult() {
  uploadVersion++;
  recognizeLoading.value = false;
  archiveSaving.value = false;
  recognizedSaving.value = false;
  recognizeResult.value = false;
  recognizeWarning.value = '';
  recognizedRecords.value = [];
  activeRecognizeTab.value = '0';
}

function resetUploadState(patientId = currentPatientId.value) {
  resettingUpload = true;
  invalidateUploadResult();
  uploadForm.patientId = patientId;
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
  releaseUploadPreviews();
  uploadFiles.value = [];
  uploadRef.value?.clearFiles();
  uploadDialogRef.value?.clearFiles();
  resettingUpload = false;
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

const sourceFocus=useFocusedCareSource(route,currentPatientId,(source,options)=>api.getRecord(source.sourceId,source.patientId,options),'MEDICAL_RECORD');
const focusedMode=sourceFocus.attempted;
watch(()=>sourceFocus.state.record,record=>{currentRecord.value=record;detailDialogVisible.value=!!record;},{flush:'sync'});
let detailReadEpoch=0
watch(focusedMode,()=>{detailReadEpoch++;recordListRequest++;records.value=[];itemNamesRequest++;allItemNames.value=[];currentRecord.value=null;detailDialogVisible.value=false;uploadDialogVisible.value=false;editDialogVisible.value=false;if(!focusedMode.value&&route.fullPath?.split('?')[0]==='/medical-record'){loadRecords();loadItemNames();loadPatientList()}},{flush:'sync'});

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

let recordListRequest = 0, recordListDisposed = false;
onUnmounted(() => {
  recordListDisposed = true;
  recordListRequest++;
  loading.value = false;
});

async function loadRecords() {
  if (focusedMode.value) return;
  if (recordListDisposed) return;
  const request = ++recordListRequest;
  const params = { ...filterForm };
  const isCurrent = () => !recordListDisposed && request === recordListRequest
    && Object.keys(params).every(key => params[key] === filterForm[key]);
  loading.value = true;
  try {
    const res = await api.listRecords(params);
    if (!isCurrent()) return;
    if (res.code === 200) {
      records.value = res.data || [];
    }
  } catch (e) {
    if (isCurrent()) ElMessage.error('Failed to load: ' + e.message);
  } finally {
    if (!recordListDisposed && request === recordListRequest) loading.value = false;
  }
}

let trendRequest = 0, itemNamesRequest = 0, medicalReadDisposed = false;
function invalidateTrend() {
  trendRequest++;
  trendData.value = [];
  trendLoading.value = false;
}
watch(() => [trendForm.patientId, trendForm.itemName], invalidateTrend, { flush: 'sync' });
onUnmounted(() => {
  medicalReadDisposed = true;
  invalidateTrend();
  itemNamesRequest++;
  allItemNames.value = [];
});

async function loadTrend() {
  if (medicalReadDisposed) return;
  const patient = patientList.value.find(p => p.id === trendForm.patientId);
  trendForm.patientName = patient?.patientName || '';
  if (!trendForm.patientId || !trendForm.itemName) {
    ElMessage.warning('Select a patient and a test item');
    return;
  }
  const request = ++trendRequest;
  const params = { ...trendForm };
  const isCurrent = () => !medicalReadDisposed && request === trendRequest
    && params.patientId === trendForm.patientId && params.itemName === trendForm.itemName;
  trendLoading.value = true;
  try {
    const res = await api.getItemTrend(params.patientId, params.patientName, params.itemName);
    if (!isCurrent()) return;
    if (res.code === 200) {
      trendData.value = res.data || [];
      if (trendData.value.length === 0) {
        ElMessage.info('not findto this indicator historyrecord');
      }
    } else {
      ElMessage.error(res.message || 'Query failed');
    }
  } catch (e) {
    if (isCurrent()) ElMessage.error('Query failed: ' + e.message);
  } finally {
    if (isCurrent()) trendLoading.value = false;
  }
}

async function loadItemNames() {
  if (focusedMode.value) return;
  if (medicalReadDisposed) return;
  const request = ++itemNamesRequest, patientId = currentPatientId.value;
  const isCurrent = () => !medicalReadDisposed && request === itemNamesRequest && patientId === currentPatientId.value;
  allItemNames.value = [];
  try {
    const res = await api.getAllItemNames(patientId);
    if (!isCurrent()) return;
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
    if (!isCurrent()) return;
    console.error('Failed to load test items', e);
    ElMessage.warning('The test item list could not be loaded; you can still enter an item manually');
  }
}

async function loadPatientList() {
  if (focusedMode.value) return;
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

async function handleFileChange(file, fileList) {
  if (uploadSaving.value || (fileList && !fileList.some(item => item.uid === file?.uid))) return;
  const raw = file?.raw;
  if (!raw || uploadFiles.value.some(item => item.uid === file.uid)) return;
  if (uploadFiles.value.length >= 10) {
    ElMessage.warning('Choose at most 10 report files');
    return;
  }
  const entry = reactive({ ...file, prepared: null });
  uploadFiles.value.push(entry);
  invalidateUploadResult();
  let prepared = raw;
  try {
    if (isImageFile(raw)) prepared = await compressImageFile(raw);
  } catch (_) { /* Keep the selected original if compression is unavailable. */ }
  // A removed file or a replaced patient draft must never be re-added by late compression.
  if (!uploadFiles.value.includes(entry)) return;
  entry.prepared = prepared;
}

function handleFileRemove(file) {
  const removed = uploadFiles.value.find(item => item.uid === file.uid);
  if (removed?.prepared) releaseUploadPreview(removed.prepared);
  uploadFiles.value = uploadFiles.value.filter(item => item.uid !== file.uid);
  invalidateUploadResult();
}

function uploadReady() {
  if (filesProcessing.value) {
    ElMessage.warning('Photos are still being prepared. Please try again when preparation finishes.');
    return false;
  }
  if (!patientList.value.some(patient => Number(patient.id) === Number(uploadForm.patientId)) || !uploadForm.patientId) {
    ElMessage.warning('Select an authorized patient');
    return false;
  }
  return true;
}

function triggerCameraUpload(target) {
  if (uploadSaving.value) return;
  const version = uploadVersion;
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = 'image/*';
  input.capture = 'environment';
  input.onchange = (e) => {
    if (version !== uploadVersion || uploadSaving.value) return;
    const file = e.target.files[0];
    if (!file) return;
    const targetRef = target === 'dialog' ? uploadDialogRef : uploadRef;
    targetRef.value?.handleStart(file);
  };
  input.click();
}

function triggerAlbumUpload(target) {
  if (uploadSaving.value) return;
  const version = uploadVersion;
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = 'image/*,.pdf';
  input.multiple = true;
  input.onchange = (e) => {
    if (version !== uploadVersion || uploadSaving.value) return;
    const files = Array.from(e.target.files || []);
    if (!files.length) return;
    const targetRef = target === 'dialog' ? uploadDialogRef : uploadRef;
    files.forEach(file => targetRef.value?.handleStart(file));
  };
  input.click();
}

async function startRecognize() {
  if (recognizeLoading.value || uploadSaving.value || !uploadReady()) return;
  if (!selectedFiles.value || selectedFiles.value.length === 0) {
    ElMessage.warning('Upload at least one report file');
    return;
  }
  if (!uploadForm.patientId) {
    ElMessage.warning('Select a patient');
    return;
  }
  invalidateUploadResult();
  const version = uploadVersion;
  const patientId = uploadForm.patientId;
  const files = [...selectedFiles.value];
  const recordType = uploadForm.recordType;
  recognizeLoading.value = true;
  try {
    const res = await api.uploadAndRecognize(files, patientId, recordType);
    if (version !== uploadVersion) return;
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
    if (version !== uploadVersion) return;
    const status = e.response?.status;
    if (status === 413) {
      ElMessage.error('The image was rejected because it is too large. Choose a smaller file or contact an administrator.');
    } else if (e.message?.includes('timeout')) {
      ElMessage.error('Recognition timed out. Try a smaller file or retry on a faster connection.');
    } else {
      ElMessage.error('Recognition failed: ' + (e.message || 'Unknown error'));
    }
  } finally {
    if (version === uploadVersion) recognizeLoading.value = false;
  }
}

async function buildUploadAttachments(files = [...selectedFiles.value]) {
  if (!files.length) return [];
  const attachments = [];
  for (const file of files) {
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
  if (!uploadPreviewUrls.has(file)) uploadPreviewUrls.set(file, URL.createObjectURL(file));
  return uploadPreviewUrls.get(file);
}

function releaseUploadPreview(file) {
  const url = uploadPreviewUrls.get(file);
  if (url) URL.revokeObjectURL(url);
  uploadPreviewUrls.delete(file);
}

function releaseUploadPreviews() {
  for (const file of uploadPreviewUrls.keys()) releaseUploadPreview(file);
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
  if (uploadSaving.value || !uploadReady()) return;
  if (!selectedFiles.value || selectedFiles.value.length === 0) {
    ElMessage.warning('Upload at least one report file');
    return;
  }
  if (!uploadForm.patientId) {
    ElMessage.warning('Select a patient');
    return;
  }
  const version = uploadVersion;
  const files = [...selectedFiles.value];
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
    const items = archiveItems.value.map(item => ({
      itemName: item.itemName,
      resultValue: item.resultValue,
      unit: item.unit,
      referenceRange: item.referenceRange,
      isAbnormal: item.isAbnormal
    }));
    const attachments = await buildUploadAttachments(files);
    if (version !== uploadVersion) return;
    const res = await api.saveRecord(record, items, attachments);
    if (version !== uploadVersion) return;
    if (res.code === 200) {
      ElMessage.success('Record saved successfully');
      resetUploadState();
      uploadRef.value?.clearFiles();
      loadRecords();
    } else {
      ElMessage.error(res.message || res.msg || 'Failed to save');
    }
  } catch (e) {
    if (version !== uploadVersion) return;
    ElMessage.error('Failed to save: ' + e.message);
  } finally {
    if (version === uploadVersion) archiveSaving.value = false;
  }
}

async function saveRecognizedRecord() {
  if (uploadSaving.value || !uploadReady() || !recognizeResult.value || !recognizedRecords.value.length) return;
  const version = uploadVersion;
  const patient = patientList.value.find(item => Number(item.id) === Number(uploadForm.patientId));
  const source = { ...uploadForm, patientName: patient?.patientName || uploadForm.patientName };
  const list = recognizedRecords.value.map(record => ({ ...record, items: mapItemsForSave(record.items) }));
  const files = [...selectedFiles.value];
  recognizedSaving.value = true;
  try {
    const attachments = await buildUploadAttachments(files);
    if (version !== uploadVersion) return;

    if (list.length > 1) {
      const records = list.map(rec => ({
        patientId: source.patientId,
        patientName: source.patientName,
        recordType: rec.recordType || 'BLOOD',
        recordDate: rec.recordDate,
        hospitalName: rec.hospitalName,
        doctorName: rec.doctorName,
        remark: rec.remark || '',
        aiRawResult: JSON.stringify(rec)
      }));
      const itemsList = list.map(rec => mapItemsForSave(rec.items));
      const res = await api.saveRecordsBatch(records, itemsList, attachments);
      if (version !== uploadVersion) return;
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
      patientId: source.patientId,
      patientName: source.patientName,
      recordType: rec.recordType || source.recordType || 'BLOOD',
      recordDate: rec.recordDate,
      hospitalName: rec.hospitalName,
      doctorName: rec.doctorName,
      remark: rec.remark || '',
      aiRawResult: JSON.stringify(rec)
    };
    const items = mapItemsForSave(rec.items);
    const res = await api.saveRecord(record, items, attachments);
    if (version !== uploadVersion) return;
    if (res.code === 200) {
      ElMessage.success('Saved successfully');
      uploadDialogVisible.value = false;
      resetUploadState();
      loadRecords();
    } else {
      ElMessage.error(res.message || res.msg || 'Failed to save');
    }
  } catch (e) {
    if (version !== uploadVersion) return;
    ElMessage.error('Failed to save: ' + e.message);
  } finally {
    if (version === uploadVersion) recognizedSaving.value = false;
  }
}

async function viewDetail(row) {
  if(focusedMode.value)return;
  const epoch=++detailReadEpoch,auth=captureAuthSession(),actor=localStorage.getItem('userId');
  const owns=()=>!medicalReadDisposed&&!focusedMode.value&&epoch===detailReadEpoch&&isAuthSessionCurrent(auth)&&actor===localStorage.getItem('userId');
  try {
    const res = await api.getRecord(row.id);
    if (owns() && res.code === 200) {
      currentRecord.value = res.data;
      if(owns())detailDialogVisible.value = true;
    }
  } catch { if(owns())ElMessage.error('Unable to load record details'); }
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
  if (!row?.id || editDisposed) return;
  invalidateEditDialog();
  const session = editSession.value, recordId = row.id;
  editDialogKey.value = session;
  Object.assign(editForm, {
    id: null, patientId: null, patientName: '', recordDate: '', recordType: 'BLOOD',
    hospitalName: '', doctorName: '', remark: ''
  });
  editItems.value = [];
  editAttachments.value = [];
  editDialogVisible.value = true;
  editLoading.value = true;
  try {
    const res = await api.getRecord(recordId);
    if (!isCurrentEdit(session)) return;
    if (res.code !== 200 || !res.data || String(res.data.id) !== String(recordId)) {
      throw new Error(res.message || 'Medical record could not be loaded');
    }
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
    editReady.value = true;
  } catch (e) {
    if (isCurrentEdit(session)) ElMessage.error('Failed to load form data: ' + e.message);
  } finally {
    if (isCurrentEdit(session)) editLoading.value = false;
  }
}

function addEditItem() {
  if (editLocked.value || !isCurrentEdit(editSession.value)) return;
  editItems.value.push({
    itemName: '',
    resultValue: '',
    unit: '',
    referenceRange: '',
    isAbnormal: 0
  });
}

function removeEditItem(index) {
  if (editLocked.value || !isCurrentEdit(editSession.value)) return;
  editItems.value.splice(index, 1);
}

async function saveEditRecord() {
  const session = editSession.value;
  if (!isCurrentEdit(session) || editLocked.value || editFilesProcessing.value) return;
  const request = ++editSaveRequest;
  const isCurrentSave = () => isCurrentEdit(session) && request === editSaveRequest;
  editSaving.value = true;
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
    if (!isCurrentSave()) return;
    if (res.code === 200) {
      ElMessage.success('Saved successfully');
      editDialogVisible.value = false;
      loadRecords();
    } else {
      ElMessage.error(res.message || 'Failed to save');
    }
  } catch (e) {
    if (isCurrentSave()) ElMessage.error('Failed to save: ' + e.message);
  } finally {
    if (isCurrentSave()) editSaving.value = false;
  }
}

function handlePaste(e) {
  if (uploadSaving.value) return;
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
  invalidateUploadResult();
  releaseUploadPreviews();
  uploadFiles.value = [];
});

// switchPatientafter againloadExaminationitemlist
watch(() => uploadForm.patientId, (patientId, previousId) => {
  if (!resettingUpload && patientId !== previousId) resetUploadState(patientId);
}, { flush: 'sync' });
watch(uploadDialogVisible, (visible, previous) => {
  if (!visible && previous) resetUploadState();
}, { flush: 'sync' });
watch(currentPatientId, (newVal, oldVal) => {
  if (newVal !== oldVal) {
    uploadDialogVisible.value = false;
    resetUploadState(newVal);
    loadItemNames();
  }
}, { flush: 'sync' });
</script>

<style scoped src="@/styles/module-layout.css"></style>
<style scoped>
.medical-record-manager .top-bar.record-heading {
  align-items: center;
  flex-wrap: nowrap;
  gap: 24px;
  min-height: 128px;
  padding: 14px 22px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: linear-gradient(110deg, var(--care-50) 0%, var(--warning-soft) 100%);
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
  color: var(--ink-500);
}

.upload-text em {
  color: var(--care-700);
  font-style: normal;
  font-weight: 600;
}

.recognize-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 32px;
  color: var(--care-700);
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
  color: var(--ink-500);
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
  background: var(--paper);
  border-radius: 12px;
  border: 1px solid var(--line);
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
  border: 1px solid var(--line);
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
  color: var(--on-accent);
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
  color: var(--ink-700);
  padding: 8px 12px;
  background: var(--surface-subtle);
  border-radius: 8px;
}
.attachment-file-name {
  font-size: 12px;
  color: var(--ink-500);
  padding: 4px 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: center;
  background: var(--surface-subtle);
}


/* Detail results: retain the desktop table and show complete labelled values on phones. */
.medical-result-cards {
  display: none;
  gap: 12px;
  min-width: 0;
}
.medical-result-card {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--paper);
  overflow-wrap: anywhere;
}
.medical-result-card h3 { margin: 0 0 12px; font-size: 15px; line-height: 1.5; }
.medical-result-card dl { display: grid; gap: 10px; margin: 0; }
.medical-result-card dl > div { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); gap: 12px; align-items: start; }
.medical-result-card dt { color: var(--ink-500); font-size: 12px; line-height: 1.5; }
.medical-result-card dd { min-width: 0; margin: 0; color: var(--ink-800); font-size: 14px; line-height: 1.5; }
.medical-result-status { display: inline-block; padding: 2px 8px; border-radius: 6px; background: var(--surface-subtle); }
.medical-result-status--success { color: var(--success); background: var(--success-soft); }
.medical-result-status--warning { color: var(--warning); background: var(--warning-soft); }
.medical-result-status--danger { color: var(--danger); background: var(--danger-soft); }

.medical-result-empty { margin: 0; padding: 16px; color: var(--ink-500); }
@media (max-width: 600px) {
  .medical-result-table { display: none; }
  .medical-result-cards { display: grid; }
}
</style>

<style scoped>.focused-source{padding:24px;min-width:0;overflow-wrap:anywhere}.focused-source button{min-height:44px;padding:10px 16px;margin:8px;font:inherit}.focused-source button:focus-visible{outline:3px solid var(--care-600)}</style>
