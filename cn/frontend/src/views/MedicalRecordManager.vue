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
            <el-form-item label="患者" required>
              <el-select v-model="uploadForm.patientId" placeholder="选择患者" filterable allow-create style="max-width: 320px">
                <el-option
                  v-for="patient in patientList"
                  :key="patient.id"
                  :label="patient.patientName"
                  :value="patient.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="报告类型">
              <el-select v-model="uploadForm.recordType" placeholder="请选择" style="max-width: 320px">
                <el-option label="血液检查" value="BLOOD" />
                <el-option label="尿液检查" value="URINE" />
                <el-option label="肝功能" value="LIVER" />
                <el-option label="肾功能" value="KIDNEY" />
                <el-option label="骨代谢" value="BONE" />
                <el-option label="铁代谢" value="IRON" />
                <el-option label="影像报告" value="IMAGE" />
                <el-option label="其他" value="OTHER" />
              </el-select>
            </el-form-item>
            <el-form-item label="处理方式">
              <el-radio-group v-model="uploadMode">
                <el-radio-button value="recognize">上传并识别</el-radio-button>
                <el-radio-button value="archive">仅归档</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="报告文件" required>
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
                <div class="upload-text">将报告拖到此处、<em>点击上传</em>或粘贴图片，支持多文件。</div>
                <template #tip>
                  <div class="el-upload__tip">支持 JPG、PNG 和 PDF；PDF 最多处理前 30 页，手机照片会在上传前压缩。</div>
                </template>
              </el-upload>
              <div v-if="isMobile" class="mobile-upload-actions">
                <el-button type="primary" plain size="small" @click="triggerCameraUpload('page')">
                  <el-icon><Camera /></el-icon> 拍照
                </el-button>
                <el-button type="info" plain size="small" @click="triggerAlbumUpload('page')">
                  <el-icon><Picture /></el-icon> 从相册选择
                </el-button>
              </div>
            </el-form-item>

            <!-- onlyarchivemode: recordinformationtablesingle -->
            <template v-if="uploadMode === 'archive'">
              <el-form-item label="报告日期">
                <el-date-picker v-model="archiveForm.recordDate" type="date" value-format="YYYY-MM-DD" style="max-width: 320px" />
              </el-form-item>
              <el-form-item label="医院">
                <el-input v-model="archiveForm.hospitalName" style="max-width: 400px" />
              </el-form-item>
              <el-form-item label="医生">
                <el-input v-model="archiveForm.doctorName" style="max-width: 320px" />
              </el-form-item>
              <el-form-item label="备注">
                <el-input v-model="archiveForm.remark" type="textarea" :rows="2" style="max-width: 400px" />
              </el-form-item>

              <!-- onlyarchivemode: ManualfillwriteExaminationitem -->
              <el-form-item label-width="0">
                <el-divider content-position="left">检验项目明细（可选）</el-divider>
                <div class="recognize-items-toolbar">
                  <span class="recognize-items-count">共 {{ archiveItems.length }} 项</span>
                  <div class="recognize-items-actions">
                    <el-button size="small" type="primary" plain @click="addArchiveItem">新增一行</el-button>
                  </div>
                </div>
                <el-table :data="archiveItems" border size="small" max-height="280" class="app-data-table recognize-items-table">
                  <el-table-column prop="itemName" label="检验项目" min-width="140">
                    <template #default="{ row }">
                      <el-input v-model="row.itemName" size="small" placeholder="检验项目名称" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="resultValue" label="结果" width="120">
                    <template #default="{ row }">
                      <el-input v-model="row.resultValue" size="small" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="unit" label="单位" width="88">
                    <template #default="{ row }">
                      <el-input v-model="row.unit" size="small" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="referenceRange" label="参考范围" width="120">
                    <template #default="{ row }">
                      <el-input v-model="row.referenceRange" size="small" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="isAbnormal" label="状态" width="88" align="center">
                    <template #default="{ row }">
                      <el-select v-model="row.isAbnormal" size="small" style="width: 76px">
                        <el-option label="正常" :value="0" />
                        <el-option label="偏高" :value="1" />
                        <el-option label="偏低" :value="-1" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="72" fixed="right" align="center">
                    <template #default="{ $index }">
                      <el-button link type="danger" size="small" @click="removeArchiveItem($index)">删除</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </el-form-item>
            </template>

            <el-form-item>
              <el-button v-if="uploadMode === 'recognize'" type="primary" :loading="recognizeLoading" @click="startRecognize">开始识别</el-button>
              <el-button v-if="uploadMode === 'archive'" type="success" :loading="archiveSaving" @click="saveArchiveRecord">保存记录</el-button>
              <el-button v-if="recognizeResult" type="success" @click="saveRecognizedRecord">
                {{ recognizedRecords.length > 1 ? `保存 ${recognizedRecords.length} 份记录` : '保存记录' }}
              </el-button>
            </el-form-item>
          </el-form>

          <!-- onlyarchivemode: imagePreview -->
          <div v-if="uploadMode === 'archive' && selectedFiles.length > 0" class="archive-preview">
            <el-divider content-position="left">附件预览</el-divider>
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
            <span>AI 正在提取草稿，请稍候……</span>
          </div>
          <div v-if="recognizeResult" class="recognize-result">
            <el-divider content-position="left">识别草稿——保存前请核对</el-divider>
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
              <el-form-item v-if="recognizedRecords.length <= 1" label="检查类型">
                <el-select v-model="currentRecognizeRecord.recordType" style="max-width: 320px">
                  <el-option v-for="(label, val) in recordTypeMap" :key="val" :label="label" :value="val" />
                </el-select>
              </el-form-item>
              <el-form-item v-else label="检查类型">
                <el-tag type="primary">{{ getRecordTypeName(currentRecognizeRecord.recordType) }}</el-tag>
              </el-form-item>
              <el-form-item label="检查日期">
                <el-date-picker v-model="currentRecognizeRecord.recordDate" type="date" value-format="YYYY-MM-DD" style="width: 100%; max-width: 320px" />
              </el-form-item>
              <el-form-item label="医院">
                <el-input v-model="currentRecognizeRecord.hospitalName" style="max-width: 400px" />
              </el-form-item>
              <el-form-item label="医生">
                <el-input v-model="currentRecognizeRecord.doctorName" style="max-width: 320px" />
              </el-form-item>
            </el-form>
            <div class="recognize-items-toolbar">
              <span class="recognize-items-count">共 {{ currentRecognizeRecord.items.length }} 项</span>
              <div class="recognize-items-actions">
                <el-button size="small" @click="dedupeRecognizedItemsLocal">合并重复项</el-button>
                <el-button size="small" type="primary" plain @click="addRecognizedItem">新增一行</el-button>
              </div>
            </div>
            <el-table :data="currentRecognizeRecord.items" border size="small" max-height="280" class="app-data-table recognize-items-table">
              <el-table-column prop="itemName" label="检验项目" min-width="140">
                <template #default="{ row }">
                  <el-input v-model="row.itemName" size="small" placeholder="检验项目名称" />
                </template>
              </el-table-column>
              <el-table-column prop="resultValue" label="检测值" width="120">
                <template #default="{ row }">
                  <el-input v-model="row.resultValue" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="unit" label="单位" width="88">
                <template #default="{ row }">
                  <el-input v-model="row.unit" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="referenceRange" label="参考范围" width="120">
                <template #default="{ row }">
                  <el-input v-model="row.referenceRange" size="small" />
                </template>
              </el-table-column>
              <el-table-column prop="isAbnormal" label="状态" width="88" align="center">
                <template #default="{ row }">
                  <el-select v-model="row.isAbnormal" size="small" style="width: 76px">
                    <el-option label="正常" :value="0" />
                    <el-option label="偏高" :value="1" />
                    <el-option label="偏低" :value="-1" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="72" fixed="right" align="center">
                <template #default="{ $index }">
                  <el-button link type="danger" size="small" @click="removeRecognizedItem($index)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>



        <!-- Result Trends -->
        <div v-show="activeMenu === 'trend'" class="trend-panel">
          <div class="toolbar">
            <el-form :inline="true" class="filter-form">
              <el-form-item label="患者">
                <el-select v-model="trendForm.patientId" placeholder="选择患者" filterable clearable style="width: 220px">
                  <el-option
                    v-for="patient in patientList"
                    :key="patient.id"
                    :label="patient.patientName"
                    :value="patient.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="检验项目">
                <el-select
                  v-model="trendForm.itemName"
                  filterable
                  clearable
                  allow-create
                  default-first-option
                  placeholder="选择或输入检验项目"
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
                  <el-icon><Search /></el-icon>查询趋势
                </el-button>
              </el-form-item>
            </el-form>
          </div>
          <div v-if="trendData.length > 0" class="trend-chart-wrap">
            <v-chart class="trend-chart" :option="trendChartOption" autoresize />
          </div>
          <div v-if="trendData.length > 0" class="table-wrap">
            <el-table :data="trendData" class="app-data-table" stripe border size="small">
              <el-table-column prop="recordDate" label="检查日期" width="120" />
              <el-table-column prop="itemName" label="检验项目" min-width="140" />
              <el-table-column prop="resultValue" label="检测值" width="120" />
              <el-table-column prop="unit" label="单位" width="88" />
              <el-table-column prop="referenceRange" label="参考范围" width="140" />
              <el-table-column prop="isAbnormal" label="状态" width="88" align="center">
                <template #default="{ row }">
                  <el-tag :type="getAbnormalType(row.isAbnormal)" size="small">{{ getAbnormalText(row.isAbnormal) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="hospitalName" label="医院" min-width="160" show-overflow-tooltip />
            </el-table>
          </div>
          <div v-if="trendData.length === 0 && !trendLoading" class="stats-empty">
            <el-empty description="选择患者和检验项目后查看历史趋势" />
          </div>
        </div>

        <!-- Record List / Abnormalrecord -->
        <template v-if="activeMenu === 'list' || activeMenu === 'abnormal'">
        <div class="toolbar">
          <el-form :inline="true" :model="filterForm" class="filter-form">
            <el-form-item label="患者">
              <el-select v-model="filterForm.patientId" placeholder="全部患者" filterable clearable style="width: 200px">
                <el-option
                  v-for="patient in patientList"
                  :key="patient.id"
                  :label="patient.patientName"
                  :value="patient.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="检查类型">
              <el-select v-model="filterForm.recordType" placeholder="请选择" clearable>
                <el-option label="血液检查" value="BLOOD" />
                <el-option label="尿液检查" value="URINE" />
                <el-option label="肝功能" value="LIVER" />
                <el-option label="肾功能" value="KIDNEY" />
                <el-option label="骨代谢" value="BONE" />
                <el-option label="铁代谢" value="IRON" />
                <el-option label="影像报告" value="IMAGE" />
                <el-option label="其他" value="OTHER" />
              </el-select>
            </el-form-item>
            <el-form-item label="日期">
              <el-date-picker v-model="filterForm.timeValue" type="date" placeholder="选择日期" value-format="YYYY-MM-DD" />
            </el-form-item>
            <el-form-item label="检验项目">
              <el-input v-model="filterForm.itemName" placeholder="按检验项目名称搜索" clearable />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadRecords">
                <el-icon><Search /></el-icon>查询
              </el-button>
            </el-form-item>
            <el-form-item v-if="activeMenu === 'list' && canUpload">
              <el-button type="primary" @click="goUpload">
                <el-icon><Upload /></el-icon>上传报告
              </el-button>
            </el-form-item>
          </el-form>
        </div>

        <div class="list-panel" v-loading="loading">
          <div class="list-panel-head">
            <div class="list-panel-title">
              <el-icon><FirstAidKit /></el-icon>
              <span>{{ activeMenu === 'abnormal' ? '异常检验记录' : '医疗记录' }}</span>
              <span v-if="displayRecords.length" class="list-count">共 {{ displayRecords.length }} 条</span>
            </div>
          </div>
          <div class="table-wrap">
            <el-table
              :data="displayRecords"
              class="app-data-table app-data-table--list"
              stripe
              style="width: 100%"
            >
              <el-table-column v-if="listColVisible('recordDate')" prop="recordDate" label="日期" min-width="108" />
              <el-table-column v-if="listColVisible('patientName')" prop="patientName" label="患者" min-width="100" show-overflow-tooltip />
              <el-table-column v-if="listColVisible('recordType')" prop="recordType" label="类型" min-width="96" show-overflow-tooltip>
                <template #default="{ row }">
                  <el-tag size="small">{{ getRecordTypeName(row.recordType) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column v-if="listColVisible('hospitalName')" prop="hospitalName" label="医院" min-width="168" show-overflow-tooltip />
              <el-table-column v-if="listColVisible('doctorName')" prop="doctorName" label="医生" min-width="96" show-overflow-tooltip />
              <el-table-column v-if="listColVisible('items')" label="检验项目" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  <span v-if="row.items && row.items.length" class="cell-ellipsis">
                    {{ row.items.map(i => i.itemName).join(', ') }}
                  </span>
                  <span v-else class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column v-if="listColVisible('abnormal')" label="异常" min-width="72" align="center">
                <template #default="{ row }">
                  <el-tag v-if="hasAbnormal(row)" type="danger" size="small">{{ countAbnormal(row) }}</el-tag>
                  <span v-else class="text-muted">-</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="168" fixed="right" align="center">
                <template #header>
                  <TableActionHeader v-model="listVisibleCols" :columns="LIST_COLUMN_DEFS" @reset="resetListColumns" />
                </template>
                <template #default="{ row }">
                  <div class="table-actions">
                    <el-button link type="primary" size="small" @click="viewDetail(row)">查看</el-button>
                    <el-button link type="primary" size="small" @click="openEditDialog(row)">编辑</el-button>
                    <el-button link type="danger" size="small" @click="deleteRecord(row)">删除</el-button>
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
    <el-dialog v-model="uploadDialogVisible" title="上传报告" width="min(800px, 95vw)" :close-on-click-modal="false" destroy-on-close>
      <el-form :model="uploadForm" label-width="100px">
        <el-form-item label="患者姓名" required>
          <el-input v-model="uploadForm.patientName" placeholder="请输入患者姓名" />
        </el-form-item>
        <el-form-item label="检查类型">
          <el-select v-model="uploadForm.recordType" placeholder="请选择">
            <el-option label="血液检查" value="BLOOD" />
            <el-option label="尿液检查" value="URINE" />
            <el-option label="肝功能" value="LIVER" />
            <el-option label="肾功能" value="KIDNEY" />
            <el-option label="骨代谢" value="BONE" />
            <el-option label="铁代谢" value="IRON" />
            <el-option label="影像报告" value="IMAGE" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="报告文件" required>
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
            <div class="upload-text">将文件拖到此处、<em>点击上传</em>或粘贴图片，支持多文件。</div>
            <template #tip>
              <div class="el-upload__tip">支持 JPG、PNG 和 PDF；PDF 最多处理前 30 页。</div>
            </template>
          </el-upload>
          <div v-if="isMobile" class="mobile-upload-actions">
            <el-button type="primary" plain size="small" @click="triggerCameraUpload('dialog')">
              <el-icon><Camera /></el-icon> 拍照上传
            </el-button>
            <el-button type="info" plain size="small" @click="triggerAlbumUpload('dialog')">
              <el-icon><Picture /></el-icon> 从相册选择
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <div v-if="recognizeLoading" class="recognize-loading">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <span>AI 正在识别，大文件可能需要 1～3 分钟，请耐心等待……</span>
      </div>

      <div v-if="recognizeResult" class="recognize-result">
        <el-divider content-position="left">AI 识别结果</el-divider>
        <el-form :model="recognizedData" label-width="100px">
          <el-form-item label="检查日期">
            <el-date-picker v-model="recognizedData.recordDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="医院">
            <el-input v-model="recognizedData.hospitalName" />
          </el-form-item>
          <el-form-item label="医生">
            <el-input v-model="recognizedData.doctorName" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="recognizedData.remark" type="textarea" :rows="2" />
          </el-form-item>
        </el-form>

        <el-divider content-position="left">检验项目明细</el-divider>
        <div class="recognize-items-toolbar">
          <span class="recognize-items-count">共 {{ recognizedData.items.length }} 项</span>
          <div class="recognize-items-actions">
            <el-button size="small" @click="dedupeRecognizedItemsLocal">合并重复项</el-button>
            <el-button size="small" type="primary" plain @click="addRecognizedItem">新增一行</el-button>
          </div>
        </div>
        <el-table :data="recognizedData.items" border size="small" max-height="300" class="app-data-table recognize-items-table">
          <el-table-column prop="itemName" label="检验项目" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.itemName" size="small" placeholder="检验项目名称" />
            </template>
          </el-table-column>
          <el-table-column prop="resultValue" label="检测值" width="120">
            <template #default="{ row }">
              <el-input v-model="row.resultValue" size="small" />
            </template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="88">
            <template #default="{ row }">
              <el-input v-model="row.unit" size="small" />
            </template>
          </el-table-column>
          <el-table-column prop="referenceRange" label="参考范围" width="120">
            <template #default="{ row }">
              <el-input v-model="row.referenceRange" size="small" />
            </template>
          </el-table-column>
          <el-table-column prop="isAbnormal" label="状态" width="88" align="center">
            <template #default="{ row }">
              <el-select v-model="row.isAbnormal" size="small" style="width: 76px">
                <el-option label="正常" :value="0" />
                <el-option label="偏高" :value="1" />
                <el-option label="偏低" :value="-1" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="72" fixed="right" align="center">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="removeRecognizedItem($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <template #footer>
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button v-if="!recognizeResult" type="primary" :loading="recognizeLoading" @click="startRecognize">开始识别</el-button>
        <el-button v-else type="success" @click="saveRecognizedRecord">保存记录</el-button>
      </template>
    </el-dialog>

    <!-- Detailsdialog -->
    <el-dialog v-model="detailDialogVisible" title="医疗记录详情" width="min(900px, 95vw)" destroy-on-close>
      <el-descriptions :column="2" border v-if="currentRecord">
        <el-descriptions-item label="患者">{{ currentRecord.patientName }}</el-descriptions-item>
        <el-descriptions-item label="检查日期">{{ currentRecord.recordDate }}</el-descriptions-item>
        <el-descriptions-item label="检查类型">{{ getRecordTypeName(currentRecord.recordType) }}</el-descriptions-item>
        <el-descriptions-item label="医院">{{ currentRecord.hospitalName }}</el-descriptions-item>
        <el-descriptions-item label="科室">{{ currentRecord.deptName }}</el-descriptions-item>
        <el-descriptions-item label="医生">{{ currentRecord.doctorName }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">检验项目明细</el-divider>
      <el-table :data="currentRecord?.items || []" class="app-data-table" stripe>
        <el-table-column prop="itemName" label="检验项目" width="150" />
        <el-table-column prop="resultValue" label="检测值" width="120" />
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column prop="referenceRange" label="参考范围" width="150" />
        <el-table-column prop="isAbnormal" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getAbnormalType(row.isAbnormal)">{{ getAbnormalText(row.isAbnormal) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">附件图片</el-divider>
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
      <el-empty v-else description="暂无图片附件，请先保存记录再上传。" :image-size="64" />
      <div v-if="detailOtherAttachments.length" class="detail-other-attachments">
        <div v-for="att in detailOtherAttachments" :key="att.id" class="detail-pdf-item">
          <el-icon><Document /></el-icon>
          <span>{{ att.fileName || 'PDF 附件' }}</span>
          <el-tag size="small" type="info">文件已归档，暂不支持预览</el-tag>
        </div>
      </div>
    </el-dialog>

    <!-- Editdialog -->
    <el-dialog v-model="editDialogVisible" title="编辑医疗记录" width="min(900px, 95vw)" :close-on-click-modal="false" destroy-on-close>
      <el-form :model="editForm" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="患者">
              <el-select v-model="editForm.patientId" placeholder="选择患者" filterable style="width: 100%">
                <el-option v-for="patient in patientList" :key="patient.id" :label="patient.patientName" :value="patient.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="检查日期">
              <el-date-picker v-model="editForm.recordDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="检查类型">
              <el-select v-model="editForm.recordType" placeholder="请选择" style="width: 100%">
                <el-option label="血液检查" value="BLOOD" />
                <el-option label="尿液检查" value="URINE" />
                <el-option label="肝功能" value="LIVER" />
                <el-option label="肾功能" value="KIDNEY" />
                <el-option label="骨代谢" value="BONE" />
                <el-option label="铁代谢" value="IRON" />
                <el-option label="影像报告" value="IMAGE" />
                <el-option label="其他" value="OTHER" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="医院">
              <el-input v-model="editForm.hospitalName" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="医生">
              <el-input v-model="editForm.doctorName" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="备注">
              <el-input v-model="editForm.remark" type="textarea" :rows="1" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-divider content-position="left">检验项目明细</el-divider>
      <div class="recognize-items-toolbar">
        <span class="recognize-items-count">共 {{ editItems.length }} 项</span>
        <div class="recognize-items-actions">
          <el-button size="small" type="primary" plain @click="addEditItem">新增一行</el-button>
        </div>
      </div>
      <el-table :data="editItems" border size="small" max-height="300" class="app-data-table recognize-items-table">
        <el-table-column prop="itemName" label="检验项目" min-width="140">
          <template #default="{ row }">
            <el-input v-model="row.itemName" size="small" placeholder="检验项目名称" />
          </template>
        </el-table-column>
        <el-table-column prop="resultValue" label="检测值" width="120">
          <template #default="{ row }">
            <el-input v-model="row.resultValue" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="88">
          <template #default="{ row }">
            <el-input v-model="row.unit" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="referenceRange" label="参考范围" width="120">
          <template #default="{ row }">
            <el-input v-model="row.referenceRange" size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="isAbnormal" label="状态" width="88" align="center">
          <template #default="{ row }">
            <el-select v-model="row.isAbnormal" size="small" style="width: 76px">
              <el-option label="正常" :value="0" />
              <el-option label="偏高" :value="1" />
              <el-option label="偏低" :value="-1" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="72" fixed="right" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="removeEditItem($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">附件图片</el-divider>
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
          <el-icon><Upload /></el-icon> 上传新附件
        </el-button>
        <template #tip>
          <div class="el-upload__tip">可上传报告图片作为附件归档，支持 JPG、PNG、PDF。</div>
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
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEditRecord">保存</el-button>
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
  { key: 'recordDate', label: '日期' },
  { key: 'patientName', label: '患者' },
  { key: 'recordType', label: '类型' },
  { key: 'hospitalName', label: '医院', default: false },
  { key: 'doctorName', label: '医生', default: false },
  { key: 'items', label: '检验项目' },
  { key: 'abnormal', label: '异常' }
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
  hospitalName: '县人民医院',
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
  BLOOD: '血液检查',
  URINE: '尿液检查',
  LIVER: '肝功能',
  KIDNEY: '肾功能',
  BONE: '骨代谢',
  IRON: '铁代谢',
  IMAGE: '影像报告',
  OTHER: '其他'
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
    ElMessage.success('附件已添加');
  } catch (e) {
    ElMessage.error('附件处理失败：' + e.message);
  }
}

const pageTitle = computed(() => {
  const map = {
    list: '医疗记录',
    upload: '上传并识别',
    abnormal: '异常结果',
    trend: '指标趋势'
  };
  return map[activeMenu.value] || '医疗记录';
});

const pageSubtitle = computed(() => {
  const map = {
    list: '管理历史报告并核对导入数据质量。',
    upload: '上传报告，并在保存前审核 AI 提取的草稿。',
    abnormal: '集中跟进已标记为偏高或偏低的结果。',
    trend: '跟踪选定检验指标随时间的变化。'
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
  archiveForm.hospitalName = '潢川县人民医院';
  archiveForm.doctorName = '';
  archiveForm.remark = '';
  archiveItems.value = [];
  recognizedRecords.value = [];
  activeRecognizeTab.value = '0';
  recognizedData.items = [];
  recognizedData.recordDate = '';
  recognizedData.hospitalName = '潢川县人民医院';
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
  return '正常';
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
    ElMessage.success(`已合并 ${removed} 个重复项目`);
  } else {
    ElMessage.info('当前没有可合并的重复项目');
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
    ElMessage.error('加载失败：' + e.message);
  } finally {
    loading.value = false;
  }
}

async function loadTrend() {
  const patient = patientList.value.find(p => p.id === trendForm.patientId);
  trendForm.patientName = patient?.patientName || '';
  if (!trendForm.patientId || !trendForm.itemName) {
    ElMessage.warning('请选择患者和检验项目');
    return;
  }
  trendLoading.value = true;
  try {
    const res = await api.getItemTrend(trendForm.patientId, trendForm.patientName, trendForm.itemName);
    if (res.code === 200) {
      trendData.value = res.data || [];
      if (trendData.value.length === 0) {
        ElMessage.info('未找到该指标的历史记录');
      }
    } else {
      ElMessage.error(res.message || '查询失败');
    }
  } catch (e) {
    ElMessage.error('查询失败：' + e.message);
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
    ElMessage.warning('检验项目列表加载失败，你仍可手动输入项目');
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
        ElMessage.info(`已压缩：${formatFileSize(before)} → ${formatFileSize(next.size)}`);
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
    ElMessage.warning('请至少上传一份报告文件');
    return;
  }
  if (!uploadForm.patientId) {
    ElMessage.warning('请选择患者');
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
      ElMessage.error(res.msg || res.message || '识别失败');
    }
  } catch (e) {
    const status = e.response?.status;
    if (status === 413) {
      ElMessage.error('图片过大而被拒绝，请选择较小的文件或联系管理员。');
    } else if (e.message?.includes('timeout')) {
      ElMessage.error('识别超时，请尝试较小的文件或在网络更稳定时重试。');
    } else {
      ElMessage.error('识别失败：' + (e.message || '未知错误'));
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
    ElMessage.warning('请至少上传一份报告文件');
    return;
  }
  if (!uploadForm.patientId) {
    ElMessage.warning('请选择患者');
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
      ElMessage.success('记录保存成功');
      resetUploadState();
      uploadRef.value?.clearFiles();
      loadRecords();
    } else {
      ElMessage.error(res.message || res.msg || '保存失败');
    }
  } catch (e) {
    ElMessage.error('保存失败：' + e.message);
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
        ElMessage.success(res.data || res.msg || '保存成功');
        uploadDialogVisible.value = false;
        resetUploadState();
        loadRecords();
      } else {
        ElMessage.error(res.message || res.msg || '保存失败');
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
      ElMessage.success('保存成功');
      uploadDialogVisible.value = false;
      resetUploadState();
      loadRecords();
    } else {
      ElMessage.error(res.message || res.msg || '保存失败');
    }
  } catch (e) {
    ElMessage.error('保存失败：' + e.message);
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
    ElMessage.error('详情加载失败：' + e.message);
  }
}

async function deleteRecord(row) {
  try {
    await ElMessageBox.confirm('确认删除这条医疗记录吗？', '提示', { type: 'warning' });
    const res = await api.deleteRecord(row.id);
    if (res.code === 200) {
      ElMessage.success('删除成功');
      loadRecords();
    } else {
      ElMessage.error(res.message || '删除失败');
    }
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + e.message);
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
    ElMessage.error('表单数据加载失败：' + e.message);
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
      ElMessage.success('保存成功');
      editDialogVisible.value = false;
      loadRecords();
    } else {
      ElMessage.error(res.message || '保存失败');
    }
  } catch (e) {
    ElMessage.error('保存失败：' + e.message);
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
      ElMessage.warning('最多可粘贴 5 张图片');
      break;
    }
    targetRef.value?.handleStart(file);
    added++;
  }
  if (added > 0) {
    ElMessage.success(`已粘贴 ${added} 张图片`);
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
