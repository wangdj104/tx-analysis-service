<template>
  <el-container class="module-page medical-record-manager">
    <section v-if="focusedMode" class="focused-source">
      <h1>{{ correctionCopy.linkedTitle }}</h1>
      <h2>{{ knownPatientName(currentPatientId) || correctionCopy.patientProfile }}</h2>
      <p v-if="currentPatientId != null">{{ correctionCopy.patientId }} {{ currentPatientId }}</p>
      <template v-if="focusedPatientConflict"><p role="alert">{{ correctionCopy.patientConflict }}</p><button type="button" @click="recoverCurrentPatient">{{ correctionCopy.recoverPatient }}</button></template>
      <template v-else><p v-if="sourceFocus.state.loading" role="status">{{ correctionCopy.checkingSource }}</p><p v-if="sourceFocus.state.error" role="alert">{{ correctionCopy.sourceUnavailable }}</p><button type="button" :disabled="sourceFocus.state.loading" @click="sourceFocus.reload">{{ correctionCopy.recheckSource }}</button><button v-if="sourceFocus.state.record" type="button" @click="currentRecord=sourceFocus.state.record;detailDialogVisible=true">{{ correctionCopy.openDetails }}</button></template>
    </section>
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
          <p class="submission-patient">Recording for: {{ patientIdentity(uploadForm.patientId, uploadForm.patientName) }}</p>
          <el-form :disabled="uploadSaving" :model="uploadForm" label-width="100px" class="upload-form">
            <el-form-item label="Patient" required>
              <PatientSelect v-model="uploadForm.patientId" placeholder="Select a patient" filterable style="max-width: 320px" :patients="patientList" />
            </el-form-item>
            <el-form-item label="Report type">
              <el-select v-model="uploadForm.recordType" placeholder="Select" style="max-width: 320px">
                <el-option label="Blood test" value="BLOOD" />
                <el-option label="Urinalysis" value="URINE" />
                <el-option label="Liver function" value="LIVER" />
                <el-option label="Kidney function" value="KIDNEY" />
                <el-option label="Bone metabolism" value="BONE" />
                <el-option label="Iron metabolism" value="IRON" />
                <el-option label="Imaging report" value="IMAGE" />
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
                  <el-table-column prop="referenceRange" label="Reference range" width="120">
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
              <el-form-item v-if="recognizedRecords.length <= 1" label="Record type">
                <el-select :disabled="uploadSaving" v-model="currentRecognizeRecord.recordType" style="max-width: 320px">
                  <el-option v-for="(label, val) in recordTypeMap" :key="val" :label="label" :value="val" />
                </el-select>
              </el-form-item>
              <el-form-item v-else label="Record type">
                <el-tag type="primary">{{ getRecordTypeName(currentRecognizeRecord.recordType) }}</el-tag>
              </el-form-item>
              <el-form-item label="Report date">
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
                <el-button :disabled="uploadSaving" size="small" @click="dedupeRecognizedItemsLocal">Merge duplicate items</el-button>
                <el-button :disabled="uploadSaving" size="small" type="primary" plain @click="addRecognizedItem">Add row</el-button>
              </div>
            </div>
            <el-table :data="currentRecognizeRecord.items" border size="small" max-height="280" class="app-data-table recognize-items-table">
              <el-table-column prop="itemName" label="Test item" min-width="140">
                <template #default="{ row }">
                  <el-input :disabled="uploadSaving" v-model="row.itemName" size="small" placeholder="Test item name" />
                </template>
              </el-table-column>
              <el-table-column prop="resultValue" label="Measured value" width="120">
                <template #default="{ row }">
                  <el-input :disabled="uploadSaving" v-model="row.resultValue" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="unit" label="Unit" width="88">
                <template #default="{ row }">
                  <el-input :disabled="uploadSaving" v-model="row.unit" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="referenceRange" label="Reference range" width="120">
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
          <div class="toolbar control-row-toolbar">
            <el-form :inline="true" class="filter-form">
              <el-form-item label="Patient">
                <PatientSelect v-model="trendForm.patientId" placeholder="Select a patient" filterable clearable style="width: 220px" :patients="patientList" />
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
                  <el-icon><Search /></el-icon>View trend
                </el-button>
              </el-form-item>
            </el-form>
          </div>
          <div class="trend-query-state" aria-live="polite">
            <p v-if="trendQuery">Query: {{ patientIdentity(trendQuery.patientId, trendQuery.patientName) }} · {{ trendQuery.itemName }}</p>
            <p v-if="trendLoading" role="status">Loading this test item’s history…</p>
            <p v-else-if="trendStatus === 'idle'">Not queried yet. Select a patient and test item, then view the trend.</p>
            <p v-else-if="trendStatus === 'empty'">No historical records for this patient and test item.</p>
            <div v-else-if="trendStatus === 'error'" role="alert"><p>{{ trendError }}</p><el-button data-testid="retry-trend" :loading="trendLoading" @click="loadTrend">Retry query</el-button></div>
            <p v-if="trendData.length && trendResultQuery">Displayed result: {{ patientIdentity(trendResultQuery.patientId, trendResultQuery.patientName) }} · {{ trendResultQuery.itemName }}</p>
          </div>
          <p v-if="trendData.length" class="current-review-notice">{{ reviewCopy.trendUnknown }}</p>
          <div v-if="trendData.length > 0" class="trend-chart-wrap">
            <v-chart class="trend-chart" :option="trendChartOption" autoresize />
          </div>
          <div v-if="trendData.length > 0" class="table-wrap">
            <el-table :data="trendData" class="app-data-table" stripe border size="small">
              <el-table-column prop="recordDate" label="Report date" width="120" />
              <el-table-column prop="itemName" label="Test item" min-width="140" />
              <el-table-column prop="resultValue" label="Measured value" width="120" />
              <el-table-column prop="unit" label="Unit" width="88" />
              <el-table-column prop="referenceRange" label="Reference range" width="140" />
              <el-table-column prop="isAbnormal" label="Status" min-width="180" align="center">
                <template #default="{ row }">
                  <el-tag class="trend-status-tag" :type="getTrendStatusType(row)" size="small">{{ getTrendStatusText(row) }}</el-tag>
                  <small class="source-reported-flag">{{ reviewCopy.rawFlags }}: {{ getAbnormalText(row.isAbnormal) }}</small>
                </template>
              </el-table-column>
              <el-table-column prop="hospitalName" label="Hospital" min-width="160" show-overflow-tooltip />
            </el-table>
          </div>

        </div>

        <!-- Record List / Abnormalrecord -->
        <template v-if="activeMenu === 'list' || activeMenu === 'abnormal'">
        <div class="toolbar control-row-toolbar">
          <el-form :inline="true" :model="filterForm" class="filter-form">
            <el-form-item label="Patient">
              <PatientSelect v-model="filterForm.patientId" placeholder="All patients" filterable clearable style="width: 200px" :patients="patientList" />
            </el-form-item>
            <el-form-item label="Record type">
              <el-select v-model="filterForm.recordType" placeholder="Select" clearable>
                <el-option label="Blood test" value="BLOOD" />
                <el-option label="Urinalysis" value="URINE" />
                <el-option label="Liver function" value="LIVER" />
                <el-option label="Kidney function" value="KIDNEY" />
                <el-option label="Bone metabolism" value="BONE" />
                <el-option label="Iron metabolism" value="IRON" />
                <el-option label="Imaging report" value="IMAGE" />
                <el-option label="Other" value="OTHER" />
              </el-select>
            </el-form-item>
            <el-form-item label="Date">
              <el-date-picker v-model="filterForm.timeValue" type="date" placeholder="Select a date" value-format="YYYY-MM-DD" />
            </el-form-item>
            <el-form-item label="Test item">
              <el-input v-model="filterForm.itemName" placeholder="Search by test item name" clearable />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadRecords">
                <el-icon><Search /></el-icon>query
              </el-button>
            </el-form-item>
            <el-form-item><el-checkbox v-model="filterForm.includeInvalidated" :disabled="correctionViewState.locked || correctionViewState.dirty">{{ correctionCopy.includeInvalidated }}</el-checkbox></el-form-item>
            <el-form-item v-if="activeMenu === 'list' && canUpload">
              <el-button type="primary" @click="goUpload">
                <el-icon><Upload /></el-icon>Upload report
              </el-button>
            </el-form-item>
          </el-form>
        </div>

        <p v-if="recordStatus === 'error'" role="alert">{{ reviewCopy.listError }}</p>
        <p v-if="activeMenu === 'abnormal' && hasQualificationExclusions" class="current-review-notice">{{ reviewCopy.excluded }}</p>
        <div ref="correctionSection" class="list-panel" tabindex="-1" v-loading="loading">
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
              <el-table-column v-if="listColVisible('recordType')" prop="recordType" label="Record type" min-width="96" show-overflow-tooltip>
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
              <el-table-column :label="reviewCopy.heading" min-width="260">
                <template #default="{ row }">
                  <div class="current-review-summary"><p>{{ reviewPresentation(row).sourceText }}</p><p>{{ reviewPresentation(row).doctorText }}</p><el-tag :type="reviewPresentation(row).automaticTone">{{ reviewPresentation(row).automaticText }}</el-tag></div>
                </template>
              </el-table-column>
              <el-table-column v-if="listColVisible('abnormal')" label="Abnormal" min-width="72" align="center">
                <template #default="{ row }">
                  <el-tag v-if="hasAbnormal(row)" type="danger" size="small">{{ countAbnormal(row) }}</el-tag>
                  <span v-else-if="!recordAutomaticEligible(row)" class="text-muted">{{ reviewPresentation(row).automaticText }}</span>
                  <span v-else class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="Actions" width="168" fixed="right" align="center">
                <template #header>
                  <TableActionHeader v-model="listVisibleCols" :columns="LIST_COLUMN_DEFS" @reset="resetListColumns" />
                </template>
                <template #default="{ row }">
                  <div class="table-actions">
                    <el-button class="medical-record-management-entry" link type="primary" size="small" @click="viewDetail(row)">View</el-button>
                    <el-button class="medical-record-management-entry" link type="primary" size="small" @click="openCorrection(row)">{{ correctionCopy.listAction }}</el-button>
                    <el-button v-if="!focusedMode" class="medical-record-management-entry" data-testid="medical-review-workflow-launch" link type="primary" size="small" @click="openReviewWorkflow(row)">{{ workflowCopy.managerEntry }}</el-button>
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
      <p class="submission-patient">Recording for: {{ patientIdentity(uploadForm.patientId, uploadForm.patientName) }}</p>
      <el-form :disabled="uploadSaving" :model="uploadForm" label-width="100px">
        <el-form-item label="Patient name" required>
          <el-input v-model="uploadForm.patientName" placeholder="Enter patient name" />
        </el-form-item>
        <el-form-item label="Record type">
          <el-select v-model="uploadForm.recordType" placeholder="Select">
            <el-option label="Blood test" value="BLOOD" />
            <el-option label="Urinalysis" value="URINE" />
            <el-option label="Liver function" value="LIVER" />
            <el-option label="Kidney function" value="KIDNEY" />
            <el-option label="Bone metabolism" value="BONE" />
            <el-option label="Iron metabolism" value="IRON" />
            <el-option label="Imaging report" value="IMAGE" />
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
              <el-icon><Camera /></el-icon> Take photo
            </el-button>
            <el-button type="info" plain size="small" @click="triggerAlbumUpload('dialog')">
              <el-icon><Picture /></el-icon> Choose from library
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <div v-if="recognizeLoading" class="recognize-loading">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <span>AI is recognizing the report. Large files may take 1–3 minutes. Please wait…</span>
      </div>

      <div v-if="recognizeResult" class="recognize-result">
        <el-divider content-position="left">AI recognition results</el-divider>
        <el-form :model="recognizedData" label-width="100px">
          <el-form-item label="Report date">
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
            <el-button size="small" @click="dedupeRecognizedItemsLocal">Merge duplicate items</el-button>
            <el-button size="small" type="primary" plain @click="addRecognizedItem">Add row</el-button>
          </div>
        </div>
        <el-table :data="recognizedData.items" border size="small" max-height="300" class="app-data-table recognize-items-table">
          <el-table-column prop="itemName" label="Test item" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.itemName" size="small" placeholder="Test item name" />
            </template>
          </el-table-column>
          <el-table-column prop="resultValue" label="Measured value" width="120">
            <template #default="{ row }">
              <el-input v-model="row.resultValue" size="small" />
            </template>
          </el-table-column>
          <el-table-column prop="unit" label="Unit" width="88">
            <template #default="{ row }">
              <el-input v-model="row.unit" size="small" />
            </template>
          </el-table-column>
          <el-table-column prop="referenceRange" label="Reference range" width="120">
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

    <MedicalReviewWorkflowDialog v-if="workflowTarget && !focusedMode" :key="workflowTarget.epoch" :record-id="workflowTarget.recordId" :patient-id="workflowTarget.patientId" :patient-name="knownPatientName(workflowTarget.patientId)" initial-channel="SOURCE" :current-patient-id="currentPatientId" :context-patient-id="filterForm.patientId" :route-key="route.fullPath" :navigation-key="workflowNavigation" :fallback-element="correctionSection" @closed="workflowTarget.closed" @changed="workflowTarget.changed" @view-state="workflowTarget.viewState" />
    <MedicalRecordCorrectionPanel v-if="correctionTarget && !focusedMode" :record-id="correctionTarget.recordId" :patient-id="correctionTarget.patientId" :patient-name="knownPatientName(correctionTarget.patientId)" :current-patient-id="currentPatientId" :route-key="route.fullPath" :fallback-element="correctionSection" @closed="correctionTarget = null" @view-state="Object.assign(correctionViewState, $event)" @changed="loadRecords" @legacy-edit="openLegacyEditor" @legacy-delete="deleteLegacyRecord" @original="openCorrectionOriginal" />

    <!-- Detailsdialog -->
    <el-dialog v-model="detailDialogVisible" :before-close="closeMedicalDetail" @close="closeMedicalDetail" title="Medical record details" width="min(900px, 95vw)" destroy-on-close>
      <section v-if="currentRecord" class="current-review-summary" aria-live="polite">
        <h3>{{ reviewCopy.heading }}</h3>
        <p>{{ currentReview.sourceText }}</p><p v-if="currentReview.sourceActorText">{{ currentReview.sourceActorText }}</p>
        <p>{{ currentReview.doctorText }}</p><p v-if="currentReview.doctorActorText">{{ currentReview.doctorActorText }}</p>
        <el-tag :type="currentReview.automaticTone">{{ currentReview.automaticText }}</el-tag>
        <p v-if="currentReview.observationText">{{ currentReview.observationText }}</p><p>{{ reviewCopy.explanation }}</p>
      </section>
      <section v-if="currentRecord"><h4>{{ currentReview.legacyHeading }}</h4><p>{{ correctionCopy.version }} {{ currentRecord.contentVersion }} · {{ correctionCopy.validities[currentRecord.validity] || correctionCopy.validities.UNKNOWN }} · {{ correctionCopy.reviews[currentRecord.verificationStatus] || correctionCopy.null }}</p><p v-if="currentRecord.sourceStatus">{{ correctionCopy.sourceStatuses[currentRecord.sourceStatus] || correctionCopy.sourceStatuses.UNAVAILABLE }}</p><p>{{ correctionCopy.verifiedHelp }}</p><button v-if="!focusedMode" class="medical-record-management-entry" type="button" @click="openCorrection(currentRecord)">{{ correctionCopy.listAction }}</button></section>
      <OriginalImportFacts v-if="currentRecord" :original-import="currentRecord.originalImport" />
      <h3 v-if="currentRecord">Current record</h3>
      <el-descriptions :column="2" border v-if="currentRecord">
        <el-descriptions-item label="Patient">{{ patientIdentity(currentRecord.patientId, currentRecord.patientName) }}</el-descriptions-item>
        <el-descriptions-item label="Report date">{{ currentRecord.recordDate }}</el-descriptions-item>
        <el-descriptions-item label="Record type">{{ getRecordTypeName(currentRecord.recordType) }}</el-descriptions-item>
        <el-descriptions-item label="Hospital">{{ currentRecord.hospitalName }}</el-descriptions-item>
        <el-descriptions-item label="Department">{{ currentRecord.deptName }}</el-descriptions-item>
        <el-descriptions-item label="Clinician">{{ currentRecord.doctorName }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">Examination item details</el-divider>
      <el-table :data="currentRecord?.items || []" class="app-data-table medical-result-table" stripe>
        <el-table-column prop="itemName" label="Test item" width="150" />
        <el-table-column prop="resultValue" label="Measured value" width="120" />
        <el-table-column prop="unit" label="Unit" width="80" />
        <el-table-column prop="referenceRange" label="Reference range" width="150" />
        <el-table-column prop="isAbnormal" label="Status" width="100">
          <template #default="{ row }">
            <el-tag :type="getRecordFlagType(currentRecord, row.isAbnormal)">{{ getRecordFlagText(currentRecord, row.isAbnormal) }}</el-tag>
            <small class="source-reported-flag">{{ reviewCopy.rawFlags }}: {{ getAbnormalText(row.isAbnormal) }}</small>
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
            <div><dt>Status</dt><dd><span class="medical-result-status" :class="'medical-result-status--' + getRecordFlagType(currentRecord, item.isAbnormal)">{{ getRecordFlagText(currentRecord, item.isAbnormal) }}</span></dd></div>
            <div><dt>{{ reviewCopy.rawFlags }}</dt><dd>{{ getAbnormalText(item.isAbnormal) }}</dd></div>
          </dl>
        </article>
      </section>

      <el-divider content-position="left">Attachment images</el-divider>
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
      <p class="submission-patient">Editing for: {{ patientIdentity(editForm.patientId, editForm.patientName) }}</p>
      <el-form :disabled="editLocked" :model="editForm" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Patient">
              <PatientSelect :disabled="editLocked" v-model="editForm.patientId" placeholder="Select a patient" filterable style="width: 100%" :patients="patientList" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Report date">
              <el-date-picker :disabled="editLocked" v-model="editForm.recordDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="Record type">
              <el-select :disabled="editLocked" v-model="editForm.recordType" placeholder="Select" style="width: 100%">
                <el-option label="Blood test" value="BLOOD" />
                <el-option label="Urinalysis" value="URINE" />
                <el-option label="Liver function" value="LIVER" />
                <el-option label="Kidney function" value="KIDNEY" />
                <el-option label="Bone metabolism" value="BONE" />
                <el-option label="Iron metabolism" value="IRON" />
                <el-option label="Imaging report" value="IMAGE" />
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
          <el-button :disabled="editLocked" size="small" type="primary" plain @click="addEditItem">Add row</el-button>
        </div>
      </div>
      <el-table :data="editItems" border size="small" max-height="300" class="app-data-table recognize-items-table">
        <el-table-column prop="itemName" label="Test item" min-width="140">
          <template #default="{ row }">
            <el-input :disabled="editLocked" v-model="row.itemName" size="small" placeholder="Test item name" />
          </template>
        </el-table-column>
        <el-table-column prop="resultValue" label="Measured value" width="120">
          <template #default="{ row }">
            <el-input :disabled="editLocked" v-model="row.resultValue" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="Unit" width="88">
          <template #default="{ row }">
            <el-input :disabled="editLocked" v-model="row.unit" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="referenceRange" label="Reference range" width="120">
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

      <el-divider content-position="left">Attachment images</el-divider>
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
          <el-icon><Upload /></el-icon> Upload attachment
        </el-button>
        <template #tip>
          <div class="el-upload__tip">Upload report images or PDFs to archive as attachments. JPG, PNG, and PDF are supported.</div>
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
import PatientSelect from '@/components/PatientSelect.vue';
import MedicalReviewWorkflowDialog from '@/components/medical-record/MedicalReviewWorkflowDialog.vue';
import { medicalReviewWorkflowCopy as workflowCopy } from '@/components/medical-record/medicalReviewWorkflowCopy';
import MedicalRecordCorrectionPanel from '@/components/medical-record/MedicalRecordCorrectionPanel.vue';
import { medicalRecordCopy as correctionCopy } from '@/components/medical-record/medicalRecordCorrectionCopy';
import OriginalImportFacts from '@/components/OriginalImportFacts.vue';
import { formatPatientIdentity } from '@/utils/patientPresentation';
import { useFocusedCareSource } from '@/composables/useFocusedCareSource';
import { localDateKey } from '@/utils/familyHealth';
import { dedupeRecognizedItems } from '@/utils/medicalRecordItems';
import { medicalReviewPresentation } from '@/utils/medicalReviewQualification';
import recordsCareSmall from '@/assets/illustrations/records-care-small.webp';
import recordsCare from '@/assets/illustrations/records-care.webp';
import { ref, reactive, computed, onMounted, onUnmounted, watch, inject, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { captureAuthSession, isAuthSessionCurrent, readPermissionCache, AUTH_STORAGE_KEYS } from '@/utils/authSession';
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
  { key: 'recordType', label: 'Record type' },
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
  itemName: '',
  includeInvalidated: false
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
  activeMenu.value = menuTabs.includes(tab) ? tab : 'list';
}, { flush: 'sync' });

const { isMobile } = useMobile();
const records = ref([]);
const uploadDialogVisible = ref(false);
const detailDialogVisible = ref(false);
const uploadRef = ref(null);
const uploadDialogRef = ref(null);
const editAttachmentUploadRef = ref(null);
const currentRecord = ref(null);
const correctionTarget = ref(null), correctionSection = ref(null);
const correctionViewState = reactive({ locked: false, dirty: false, validity: null });
watch(correctionTarget, target => { if (!target) Object.assign(correctionViewState, { locked: false, dirty: false, validity: null }); }, { flush: 'sync' });
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
let editSaveRequest = 0, editDisposed = false, editAuthority = null;

function invalidateEditDialog() {
  editAuthority?.controller.abort(); editAuthority = null;
  editSession.value++;
  editSaveRequest++;
  editLoading.value = false;
  editReady.value = false;
  editSaving.value = false;
  editPendingAttachments.value = 0;
}

function isCurrentEdit(session) {
  return !editDisposed && editDialogVisible.value && session === editSession.value && !!editAuthority
    && isAuthSessionCurrent(editAuthority.auth) && editAuthority.auth.actorId === localStorage.getItem('userId')
    && editAuthority.patient === currentPatientId.value && editAuthority.path === route.fullPath
    && editAuthority.permission === JSON.stringify(readPermissionCache());
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
function patientLabel(patient) { return formatPatientIdentity(patient); }
function patientIdentity(id, fallback = '') {
  const patient = patientList.value.find(p => String(p.id) === String(id));
  return patientLabel(patient || { id, patientName: fallback });
}


const uploadForm = reactive({
  patientId: currentPatientId.value,
  patientName: '',
  recordType: ''
});

const uploadMode = ref('recognize');

const archiveForm = reactive({
  recordDate: '',
  hospitalName: "Huangchuan County People's Hospital",
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
const trendStatus = ref('idle'), trendError = ref(''), trendQuery = ref(null), trendResultQuery = ref(null);
const allItemNames = ref([]);
const recordStatus = ref('idle');
const workflowTarget = ref(null), workflowViewState = reactive({ locked: false, dirty: false });
const workflowNavigation = computed(() => JSON.stringify([filterForm, activeMenu.value]));
let workflowEpoch = 0, workflowListOwner = null;
function retireReviewWorkflow() { workflowEpoch++; workflowTarget.value = null; Object.assign(workflowViewState, { locked: false, dirty: false }); }
function openReviewWorkflow(row) {
  const owner = workflowListOwner;
  if (!owner || !ownsMedicalRead(owner) || owner.menus !== JSON.stringify(permissionMenus.value) || focusedMode.value || recordStatus.value !== 'loaded' || !records.value.includes(row)
      || !Number.isSafeInteger(row?.id) || row.id < 1 || !(row.patientId == null || Number.isSafeInteger(row.patientId) && row.patientId > 0)
      || workflowTarget.value || uploadDialogVisible.value || editDialogVisible.value || detailDialogVisible.value || correctionTarget.value) return;
  const epoch = ++workflowEpoch, navigation = workflowNavigation.value; let refreshNeeded = false;
  const owned = () => epoch === workflowEpoch && workflowTarget.value?.epoch === epoch && ownsMedicalRead(owner) && owner.menus === JSON.stringify(permissionMenus.value) && workflowNavigation.value === navigation && !focusedMode.value;
  workflowTarget.value = { epoch, recordId: row.id, patientId: row.patientId ?? null,
    changed: payload => { if (owned() && payload && Object.keys(payload).length === 3 && payload.recordId === row.id && ['SOURCE', 'DOCTOR'].includes(payload.channel) && Number.isSafeInteger(payload.decisionVersion) && payload.decisionVersion > 0) refreshNeeded = true; },
    viewState: payload => { if (owned() && payload && Object.keys(payload).length === 2 && typeof payload.locked === 'boolean' && typeof payload.dirty === 'boolean') Object.assign(workflowViewState, payload); },
    closed: payload => { if (!owned() || !payload || Object.keys(payload).length !== 1 || typeof payload.retired !== 'boolean') return; const refresh = refreshNeeded && !payload.retired; refreshNeeded = false; retireReviewWorkflow(); if (refresh) void loadRecords(); }
  };
}
watch(workflowNavigation, retireReviewWorkflow, { flush: 'sync' });
const reviewCopy = {
  "heading": "Current source and review",
  "explanation": "Source verification and a recorded DOCTOR review are not a diagnosis or a judgment of clinical fitness",
  "excluded": "Pending, changed, invalidated or unavailable records are excluded from current automatic abnormal indicators. An empty list does not establish clinical normality; authorized source-reported flags remain in Medical records.",
  "rawFlags": "Source-reported flags",
  "trendUnknown": "This trend response does not supply separate SOURCE/DOCTOR review details. Compatible display of raw historical values does not establish current review qualification.",
  "notPlotted": "Not plotted; current qualification could not be verified",
  "listError": "Current records could not be loaded. Query again.",
  "trendError": "Query failed. Retry this patient and test item.",
  "trendDenied": "Access changed. Previous trend results were cleared. Retry to check current access.",
  "namesError": "The test item list could not be loaded; you can still enter an item manually"
};
const reviewPresentation = record => medicalReviewPresentation(record?.reviewSummary, 'en');
const currentReview = computed(() => reviewPresentation(currentRecord.value));
let recordListRequest = 0, trendRequest = 0, itemNamesRequest = 0, patientNamesRequest = 0;
let listController = null, trendController = null, itemNamesController = null;
let medicalReadDisposed = false, detailReadEpoch = 0, detailController = null;
function captureMedicalReadOwner() {
  return { auth: { ...captureAuthSession(), actorId: localStorage.getItem('userId') }, patient: currentPatientId.value,
    path: route.fullPath, permission: JSON.stringify(readPermissionCache()) };
}
function ownsMedicalRead(owner) {
  return !medicalReadDisposed && owner.patient === currentPatientId.value && owner.path === route.fullPath
    && owner.permission === JSON.stringify(readPermissionCache()) && isAuthSessionCurrent(owner.auth)
    && owner.auth.actorId === localStorage.getItem('userId');
}
function invalidateRecords() {
  workflowListOwner = null; retireReviewWorkflow();
  recordListRequest++; listController?.abort(); listController = null;
  records.value = []; loading.value = false; recordStatus.value = 'idle';
}
function invalidateTrend() {
  trendRequest++; trendController?.abort(); trendController = null;
  trendData.value = []; trendLoading.value = false;
  trendStatus.value = 'idle'; trendError.value = ''; trendQuery.value = null; trendResultQuery.value = null;
}
function invalidateItemNames() {
  itemNamesRequest++; itemNamesController?.abort(); itemNamesController = null; allItemNames.value = [];
}
function invalidatePatientNames() { patientNamesRequest++; patientList.value = []; }
function invalidateMedicalReads() { invalidateRecords(); invalidateTrend(); invalidateItemNames(); invalidatePatientNames(); }
// Register context retirement before the focused-source watcher starts a replacement read.
watch(currentPatientId, invalidateMedicalContext, { flush: 'sync' });
watch(() => route.fullPath, invalidateMedicalContext, { flush: 'sync' });
watch(permissionMenus, invalidateMedicalContext, { deep: true, flush: 'sync' });
watch(() => [trendForm.patientId, trendForm.patientName, trendForm.itemName], invalidateTrend, { flush: 'sync' });
watch(() => [filterForm.patientId, filterForm.patientName, filterForm.recordType, filterForm.timeValue, filterForm.itemName], () => {
  invalidateRecords(); clearMedicalDetail();
}, { flush: 'sync' });

const recordTypeMap = {
  BLOOD: 'Blood test',
  URINE: 'Urinalysis',
  LIVER: 'Liver function',
  KIDNEY: 'Kidney function',
  BONE: 'Bone metabolism',
  IRON: 'Iron metabolism',
  IMAGE: 'Imaging report',
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



function hasPlotContract(row) {
  return row?.managed === true || ['plotEligible', 'numericValue', 'plotReason'].some(key => Object.prototype.hasOwnProperty.call(row || {}, key));
}
function legacyTrendShape(row) {
  return row?.managed === false && row.validity === 'UNTRACKED' && row.sourceStatus === 'UNTRACKED';
}
function trendNumericValue(row) {
  if (hasPlotContract(row)) {
    if (!['plotEligible', 'numericValue', 'plotReason'].every(key => Object.prototype.hasOwnProperty.call(row, key))
      || row.plotEligible !== true || row.plotReason !== null || row.sourceStatus !== 'CURRENT'
      || !(row.managed === true && row.validity === 'ACTIVE' || row.managed === false && row.validity === 'UNTRACKED')
      || typeof row.numericValue !== 'number' || !Number.isFinite(row.numericValue)) return null;
    return row.numericValue;
  }
  if (!legacyTrendShape(row)) return null;
  const value = parseFloat(String(row.resultValue).replace(/[^0-9.]/g, ''));
  return isNaN(value) ? null : value;
}
const trendChartOption = computed(() => {
  const dates = trendData.value.map(d => d.recordDate);
  const hasManaged = trendData.value.some(hasPlotContract);
  const values = trendData.value.map(trendNumericValue);
  const unit = trendData.value[0]?.unit || '';
  return {
    tooltip: { trigger: 'axis', confine: true },
    legend: { bottom: 0 },
    grid: { left: '3%', right: '4%', bottom: '12%', containLabel: true },
    toolbox: isMobile.value ? undefined : { feature: { saveAsImage: {} } },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: isMobile.value ? 45 : 30, fontSize: 11 } },
    yAxis: { type: 'value', scale: true, name: unit },
    series: [{
      name: trendForm.itemName || 'Measured value',
      type: 'line',
      data: values,
      smooth: true,
      connectNulls: !hasManaged,
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
  archiveForm.hospitalName = "Huangchuan County People's Hospital";
  archiveForm.doctorName = '';
  archiveForm.remark = '';
  archiveItems.value = [];
  recognizedRecords.value = [];
  activeRecognizeTab.value = '0';
  recognizedData.items = [];
  recognizedData.recordDate = '';
  recognizedData.hospitalName = "Huangchuan County People's Hospital";
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

function recordAutomaticEligible(record) {
  const presentation = reviewPresentation(record);
  if (presentation.mode === 'PARTICIPANT') return presentation.automaticInputEligible === true && presentation.sourceStatus === 'CURRENT';
  if (presentation.mode !== 'NONE') return false;
  return !(record.managed === true && (record.sourceStatus !== 'CURRENT' || record.validity !== 'ACTIVE' || record.verificationStatus !== 'VERIFIED'));
}
function hasAbnormal(record) {
  return recordAutomaticEligible(record) && record.items?.some(i => i.isAbnormal === -1 || i.isAbnormal === 1);
}
function countAbnormal(record) {
  return recordAutomaticEligible(record) ? record.items?.filter(i => i.isAbnormal === -1 || i.isAbnormal === 1).length || 0 : 0;
}
const hasQualificationExclusions = computed(() => records.value.some(record => !recordAutomaticEligible(record)));
function getRecordFlagType(record, flag) {
  return recordAutomaticEligible(record) ? getAbnormalType(flag) : 'info';
}
function getRecordFlagText(record, flag) {
  return recordAutomaticEligible(record) ? getAbnormalText(flag) : reviewPresentation(record).automaticText;
}
function getTrendStatusType(row) {
  return trendNumericValue(row) === null ? 'info' : getAbnormalType(row.isAbnormal);
}
function getTrendStatusText(row) {
  if (trendNumericValue(row) !== null) return getAbnormalText(row.isAbnormal);
  if (hasPlotContract(row)) {
    const labels = {"SOURCE_CHANGED": "Source changed", "SOURCE_INVALIDATED": "Source invalidated", "SOURCE_REVIEW_REQUIRED": "Awaiting source review", "SOURCE_UNAVAILABLE": "Source qualification unavailable", "UNIT_UNKNOWN": "Not plotted: unit unknown", "UNIT_MIXED": "Not plotted: units differ", "VALUE_NOT_PLOTTABLE": "Text only: not plottable"};
    if (row.plotEligible !== false || !['plotEligible', 'numericValue', 'plotReason'].every(key => Object.prototype.hasOwnProperty.call(row, key))) return reviewCopy.notPlotted;
    const reason = row.sourceStatus !== 'CURRENT' ? row.sourceStatus : row.plotReason;
    return labels[reason] || reviewCopy.notPlotted;
  }
  return legacyTrendShape(row) ? getAbnormalText(row.isAbnormal) : reviewCopy.notPlotted;
}

function getAbnormalType(status) {
  if (status === 1) return 'danger';
  if (status === -1) return 'warning';
  return status === 0 ? 'success' : 'info';
}

function getAbnormalText(status) {
  if (status === 1) return 'high';
  if (status === -1) return 'low';
  return status === 0 ? 'Normal' : 'Unknown';
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

// 聚焦证据只走原窄权限读取；患者冲突不切换档案，也不升级到普通详情、历史或写接口。
function validMedicalRecordBody(record) {
  return !!record && typeof record === 'object' && !Array.isArray(record)
    && ['items', 'attachments'].every(key => record[key] == null || Array.isArray(record[key])
      && record[key].every(value => !!value && typeof value === 'object' && !Array.isArray(value)))
    && (record.items == null || record.items.every(item => item.itemName == null || typeof item.itemName === 'string'))
    && (record.attachments == null || record.attachments.every(file => file.fileName == null || typeof file.fileName === 'string'));
}
async function readFocusedMedicalSource(source, options) {
  if (Number(source.patientId) !== Number(currentPatientId.value)) throw new Error('PATIENT_CONTEXT_CONFLICT');
  const result = await api.getRecord(source.sourceId, source.patientId, { ...options, includeImportFacts: true });
  if (result.code !== 200 || !validMedicalRecordBody(result.data)) throw new Error('INVALID_DETAIL');
  return result;
}
const sourceFocus=useFocusedCareSource(route,currentPatientId,readFocusedMedicalSource,'MEDICAL_RECORD');
const focusedMode=sourceFocus.attempted;
const focusedPatientConflict = computed(() => !!sourceFocus.locator.value && Number(sourceFocus.locator.value.patientId) !== Number(currentPatientId.value));
watch(()=>sourceFocus.state.record,record=>{currentRecord.value=record;detailDialogVisible.value=!!record;},{flush:'sync'});
watch(focusedMode, () => {
  clearMedicalDetail(); invalidateMedicalReads(); uploadDialogVisible.value = false; editDialogVisible.value = false;
}, { flush: 'sync' });
watch(() => route.fullPath, () => {
  if (!focusedMode.value && route.fullPath?.split('?')[0] === '/medical-record') {
    loadItemNames(); loadPatientList();
    if (['list', 'abnormal'].includes(activeMenu.value)) loadRecords();
  }
});

function dedupeRecognizedItemsLocal() {
  const rec = currentRecognizeRecord.value;
  const before = rec.items.length;
  rec.items = dedupeRecognizedItems(rec.items);
  const removed = before - rec.items.length;
  if (removed > 0) {
    ElMessage.success(`Merged ${removed} duplicate items`);
  } else {
    ElMessage.info('No duplicate items to merge');
  }
}

onUnmounted(() => { medicalReadDisposed = true; invalidateMedicalReads(); });
async function loadRecords() {
  if (focusedMode.value || medicalReadDisposed) return;
  invalidateRecords();
  const request = recordListRequest, owner = captureMedicalReadOwner(), params = { ...filterForm };
  const controller = new AbortController(); listController = controller;
  const isCurrent = () => request === recordListRequest && listController === controller && !controller.signal.aborted
    && ownsMedicalRead(owner) && !focusedMode.value && Object.keys(params).every(key => params[key] === filterForm[key]);
  if (!owner.auth.token) return;
  loading.value = true; recordStatus.value = 'loading';
  try {
    const res = await api.listRecords(params, { expectedAuth: owner.auth, signal: controller.signal });
    if (!isCurrent()) return;
    if (res.code !== 200 || !Array.isArray(res.data) || res.data.some(row => !validMedicalRecordBody(row)
      || !Number.isSafeInteger(row.id) || row.id <= 0)) throw new Error('INVALID_RECORD_LIST');
    records.value = res.data; recordStatus.value = 'loaded'; workflowListOwner = { ...owner, menus: JSON.stringify(permissionMenus.value) };
  } catch {
    if (isCurrent()) { records.value = []; recordStatus.value = 'error'; }
  } finally { if (isCurrent()) loading.value = false; }
}
function failTrend(error) {
  trendData.value = []; trendResultQuery.value = null;
  const denied = [401, 403].includes(Number(error?.response?.status || error?.code));
  trendStatus.value = 'error'; trendError.value = denied ? reviewCopy.trendDenied : reviewCopy.trendError;
}
async function loadTrend() {
  if (focusedMode.value || medicalReadDisposed) return;
  invalidateTrend();
  const patient = patientList.value.find(p => p.id === trendForm.patientId);
  trendForm.patientName = patient?.patientName || '';
  if (!trendForm.patientId || !trendForm.itemName) {
    ElMessage.warning("Select a patient and a test item"); return;
  }
  const request = trendRequest, owner = captureMedicalReadOwner(), params = { ...trendForm };
  const controller = new AbortController(); trendController = controller;
  const isCurrent = () => request === trendRequest && trendController === controller && !controller.signal.aborted
    && ownsMedicalRead(owner) && !focusedMode.value && Object.keys(params).every(key => params[key] === trendForm[key]);
  if (!owner.auth.token) return;
  trendQuery.value = { ...params }; trendStatus.value = 'loading'; trendLoading.value = true;
  try {
    const res = await api.getItemTrend(params.patientId, params.patientName, params.itemName, { expectedAuth: owner.auth, signal: controller.signal });
    if (!isCurrent()) return;
    if (res.code !== 200 || !Array.isArray(res.data) || res.data.some(row => !row || typeof row !== 'object' || Array.isArray(row))) { failTrend(res); return; }
    trendData.value = res.data; trendResultQuery.value = { ...params };
    trendStatus.value = trendData.value.length ? 'loaded' : 'empty';
  } catch (error) { if (isCurrent()) failTrend(error); }
  finally { if (isCurrent()) trendLoading.value = false; }
}

async function loadItemNames() {
  if (focusedMode.value || medicalReadDisposed) return;
  invalidateItemNames();
  const request = itemNamesRequest, owner = captureMedicalReadOwner(), patientId = currentPatientId.value;
  const controller = new AbortController(); itemNamesController = controller;
  const isCurrent = () => request === itemNamesRequest && itemNamesController === controller && !controller.signal.aborted
    && ownsMedicalRead(owner) && !focusedMode.value && patientId === currentPatientId.value;
  if (!owner.auth.token) return;
  try {
    const res = await api.getAllItemNames(patientId, { expectedAuth: owner.auth, signal: controller.signal });
    if (!isCurrent()) return;
    if (res.code !== 200 || !Array.isArray(res.data) || res.data.some(name => typeof name !== 'string')) throw new Error('INVALID_ITEM_NAMES');
    {
      const dbNames = res.data;
      const defaults = [
        'White blood cells', 'Red blood cells', 'Hemoglobin', 'Platelets',
        'Creatinine', 'Blood urea nitrogen', 'Uric acid',
        'Alanine aminotransferase (ALT)', 'Aspartate aminotransferase (AST)', 'Total bilirubin',
        'Serum calcium', 'Serum phosphorus', 'Parathyroid hormone',
        'Ferritin', 'Transferrin saturation', 'Potassium', 'Sodium', 'Chloride',
        'Carbon dioxide combining power', 'Albumin', 'Total cholesterol', 'Triglycerides',
        'Blood glucose', 'Glycated hemoglobin', 'PTH', 'Beta-2 microglobulin', 'C-reactive protein', 'Iron'
      ];
      const merged = Array.from(new Set([...dbNames, ...defaults]));
      allItemNames.value = merged.sort((a, b) => a.localeCompare(b, 'en'));
    }
  } catch {
    if (isCurrent()) { allItemNames.value = []; ElMessage.warning(reviewCopy.namesError); }
  }
}
async function loadPatientList() {
  if (focusedMode.value || medicalReadDisposed) return;
  invalidatePatientNames();
  const request = patientNamesRequest, owner = captureMedicalReadOwner();
  const isCurrent = () => request === patientNamesRequest && ownsMedicalRead(owner) && !focusedMode.value;
  if (!owner.auth.token) return;
  try {
    const res = await getPatientNames();
    if (!isCurrent()) return;
    if (res.code === 200 && Array.isArray(res.data) && res.data.every(row => row && typeof row === 'object' && !Array.isArray(row))) patientList.value = res.data;
  } catch { if (isCurrent()) patientList.value = []; }
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

function knownPatientName(id) {
  const patient = patientList.value.find(value => Number(value.id) === Number(id));
  return patient?.patientName || patient?.name || '';
}
function openCorrection(row) {
  if (focusedMode.value || !Number.isSafeInteger(row?.id)) return;
  clearMedicalDetail();
  correctionTarget.value = { recordId: row.id, patientId: row.patientId ?? null };
}
function openCorrectionOriginal(target) {
  correctionTarget.value = null;
  viewDetail({ id: target.recordId, patientId: target.patientId });
}
function openLegacyEditor(target) {
  correctionTarget.value = null;
  openEditDialog({ id: target.recordId, patientId: target.patientId });
}
function deleteLegacyRecord(target) {
  correctionTarget.value = null;
  deleteRecord({ id: target.recordId, patientId: target.patientId });
}
async function recoverCurrentPatient() {
  filterForm.patientId = currentPatientId.value;
  await router.replace({ path: route.path, query: { tab: 'list' } });
  await nextTick();
  if (!focusedMode.value) correctionSection.value?.focus();
}

// 筛选改变不是撤权。锁定请求或有草稿时拒绝切换，保留同一个请求 key/body。
let restoringInvalidatedFilter = false;
function changeInvalidatedFilter(enabled, previous) {
  if (restoringInvalidatedFilter) return;
  if (correctionViewState.locked || correctionViewState.dirty) {
    restoringInvalidatedFilter = true;
    filterForm.includeInvalidated = previous;
    restoringInvalidatedFilter = false;
    return;
  }
  if (!enabled) {
    records.value = records.value.filter(record => record.validity !== 'INVALIDATED');
    // 尚未返回的纯读还不知道有效性，取消其所属选择，免得旧响应重新打开已过滤记录。
    if (currentRecord.value?.validity === 'INVALIDATED' || (!currentRecord.value && detailController)) clearMedicalDetail();
    if (correctionTarget.value && (correctionViewState.validity === 'INVALIDATED' || correctionViewState.validity == null)) correctionTarget.value = null;
  }
  loadRecords();
}
watch(() => filterForm.includeInvalidated, changeInvalidatedFilter, { flush: 'sync' });

// 原始事实与详情共用患者、来源、权限和会话所有权；切换上下文立即撤销旧读取。
function clearMedicalDetail() {
  detailReadEpoch++; detailController?.abort(); detailController=null;
  currentRecord.value=null; detailDialogVisible.value=false;
}
function clearLegacyEditor() {
  editDialogVisible.value = false;
  invalidateEditDialog();
  for (const field of Object.keys(editForm)) editForm[field] = null;
  editItems.value = [];
  editAttachments.value = [];
}
function clearCorrectionContext() {
  correctionTarget.value = null;
  clearLegacyEditor();
}
function invalidateMedicalContext() { invalidateMedicalReads(); clearMedicalDetail(); clearCorrectionContext(); sourceFocus.invalidate(); }
function closeMedicalDetail(done) { clearMedicalDetail(); if (focusedMode.value) sourceFocus.invalidate(); if (typeof done === 'function') done(); }
function detailStorageChanged(event) { if (event.key == null || [...AUTH_STORAGE_KEYS, 'permissionSession', 'userMenus', 'userMenuNames', 'userRoleCodes'].includes(event.key)) invalidateMedicalContext() }
watch(detailDialogVisible, (visible, previous) => { if (!visible && previous) closeMedicalDetail() }, { flush: 'sync' });
onMounted(() => { window.addEventListener('auth-session-cleared', invalidateMedicalContext); window.addEventListener('auth-session-changed', invalidateMedicalContext); window.addEventListener('permissions-changed', invalidateMedicalContext); window.addEventListener('storage', detailStorageChanged) });
onUnmounted(() => { clearMedicalDetail(); window.removeEventListener('auth-session-cleared', invalidateMedicalContext); window.removeEventListener('auth-session-changed', invalidateMedicalContext); window.removeEventListener('permissions-changed', invalidateMedicalContext); window.removeEventListener('storage', detailStorageChanged) });
async function viewDetail(row) {
  if(focusedMode.value)return;
  clearMedicalDetail();
  const epoch=detailReadEpoch, auth={...captureAuthSession(),actorId:localStorage.getItem('userId')}, patient=currentPatientId.value, path=route.fullPath, permission=JSON.stringify(readPermissionCache());
  const owns=()=>!medicalReadDisposed&&!focusedMode.value&&epoch===detailReadEpoch&&patient===currentPatientId.value&&path===route.fullPath&&permission===JSON.stringify(readPermissionCache())&&isAuthSessionCurrent(auth)&&auth.actorId===localStorage.getItem('userId');
  detailController=new AbortController();
  try {
    const res=await api.getRecord(row.id,undefined,{includeImportFacts:true,expectedAuth:auth,signal:detailController.signal});
    if(!owns())return;
    if(res.code!==200||!validMedicalRecordBody(res.data)||String(res.data?.id)!==String(row.id)||String(res.data?.patientId??'')!==String(row.patientId??''))throw new Error('INVALID_DETAIL');
    currentRecord.value=res.data; detailDialogVisible.value=true;
  } catch { if(owns()){currentRecord.value=null;ElMessage.error('Unable to load record details')} }
  finally { if(epoch===detailReadEpoch&&!owns())clearMedicalDetail() }
}

async function deleteRecord(row) {
  if (focusedMode.value) return;
  const auth = { ...captureAuthSession(), actorId: localStorage.getItem('userId') }, patient = currentPatientId.value, path = route.fullPath, permission = JSON.stringify(readPermissionCache());
  const owns = () => !medicalReadDisposed && !focusedMode.value && patient === currentPatientId.value && path === route.fullPath && permission === JSON.stringify(readPermissionCache()) && isAuthSessionCurrent(auth) && auth.actorId === localStorage.getItem('userId');
  try {
    await ElMessageBox.confirm('Delete this medical record?', 'Notice', { type: 'warning' });
    if (!owns()) return;
    const res = await api.deleteRecord(row.id, { expectedAuth: auth });
    if (!owns()) return;
    if (res.code === 200) {
      ElMessage.success('Deleted successfully');
      loadRecords();
    } else {
      ElMessage.error(res.message || 'Failed to delete');
    }
  } catch (e) {
    if (owns() && e !== 'cancel') ElMessage.error('Failed to delete: ' + e.message);
  }
}

async function openEditDialog(row) {
  if (!row?.id || editDisposed || focusedMode.value) return;
  invalidateEditDialog();
  editAuthority = { auth: { ...captureAuthSession(), actorId: localStorage.getItem('userId') }, patient: currentPatientId.value, path: route.fullPath, permission: JSON.stringify(readPermissionCache()), controller: new AbortController() };
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
    const res = await api.getRecord(recordId, undefined, { expectedAuth: editAuthority.auth, signal: editAuthority.controller.signal });
    if (!isCurrentEdit(session)) return;
    if (res.code !== 200 || !res.data || String(res.data.id) !== String(recordId) || (res.data.patientId ?? null) !== (row.patientId ?? null)) {
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
    if (!isCurrentEdit(session)) return;
    const status = Number(e?.response?.status || e?.code);
    if (status === 401 || status === 403) {
      clearLegacyEditor();
      ElMessage.error("Access changed. This editor and its draft were cleared. Check access again.");
    } else ElMessage.error('Failed to load form data: ' + e.message);
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
    const res = await api.updateRecord(record, items, attachments, { expectedAuth: editAuthority.auth, signal: editAuthority.controller.signal });
    if (!isCurrentSave()) return;
    if (res.code === 200) {
      ElMessage.success('Saved successfully');
      editDialogVisible.value = false;
      loadRecords();
    } else {
      ElMessage.error(res.message || 'Failed to save');
    }
  } catch (e) {
    if (!isCurrentSave()) return;
    const status = Number(e?.response?.status || e?.code);
    if (status === 401 || status === 403) {
      clearLegacyEditor();
      ElMessage.error("Access changed. This editor and its draft were cleared. Check access again.");
    } else ElMessage.error('Failed to save: ' + e.message);
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
    loadPatientList();
  }
}, { flush: 'sync' });
</script>

<style scoped src="@/styles/module-layout.css"></style>
<style scoped>
.current-review-summary, .current-review-notice { overflow-wrap: anywhere; white-space: normal; }
.current-review-summary p { margin: 6px 0; }
.current-review-summary :deep(.el-tag) { height: auto; white-space: normal; line-height: 1.5; }
.current-review-notice { color: var(--ink-600); }
.source-reported-flag { display: block; margin-top: 6px; color: var(--ink-600); }
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

<style scoped src="@/styles/control-row-toolbar.css"></style>

<style scoped>
.medical-record-management-entry { min-height: 44px; max-width: 100%; padding: 8px 10px; font: inherit; white-space: normal; overflow-wrap: anywhere; }
.medical-record-management-entry:focus-visible { outline: 3px solid var(--care-600); outline-offset: 2px; }
:global(body.care-senior) .medical-record-management-entry { min-height: 48px; }
</style>

<style scoped>
.trend-status-tag {
  max-width: 100%;
  height: auto;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: normal;
  line-height: 1.5;
}

.trend-status-tag :deep(.el-tag__content) {
  min-width: 0;
}
</style>
