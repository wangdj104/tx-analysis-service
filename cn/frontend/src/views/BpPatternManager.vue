<template>
  <el-container class="module-page dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>血压规律分析</h1>
                <p class="subtitle">分析血压变异性、体位性低血压，以及体重增长与血压的相关性。</p>
              </div>
            </div>
          </div>
        </div>

        <div class="content-panel">
          <div class="toolbar" style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px;">
            <div>
              <el-button type="primary" @click="handleAnalyze" :loading="analyzing">
                <el-icon><DataAnalysis /></el-icon>runanalysis
              </el-button>
              <el-button @click="loadData">
                <el-icon><Refresh /></el-icon>Refresh
              </el-button>
            </div>
            <TimeScopeFilter
              v-model:timeType="timeType"
              v-model:timeValue="timeValue"
              :stack="isMobile"
              @dimension-change="onDimensionChange"
              @date-change="onDateChange"
            />
          </div>

          <!-- latestanalysisresultoverview -->
          <div v-if="currentAnalysis" class="analysis-overview">
            <el-row :gutter="16">
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="平均收缩压" :value="currentAnalysis.avgSystolic || 0" suffix="mmHg" />
              </el-col>
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="平均舒张压" :value="currentAnalysis.avgDiastolic || 0" suffix="mmHg" />
              </el-col>
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="收缩压标准差" :value="currentAnalysis.stdDeviation || 0">
                  <template #suffix>
                    <span v-if="currentAnalysis.stdDeviation > 15" style="color: #f56c6c; font-size: 12px;"> (slightlylarge)</span>
                  </template>
                </el-statistic>
              </el-col>
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="体位性低血压" :value="currentAnalysis.orthostaticCount || 0" suffix="次" />
              </el-col>
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="低血压（<90）" :value="currentAnalysis.lowBpCount || 0" suffix="次" />
              </el-col>
              <el-col :xs="12" :sm="8" :md="4">
                <el-statistic title="体重—血压相关性" :value="currentAnalysis.correlationWeightGainBp || 0" />
              </el-col>
            </el-row>
            <el-alert v-if="currentAnalysis.analysisSummary" :title="currentAnalysis.analysisSummary" type="info" :closable="false" style="margin-top: 12px;" />
          </div>

          <!-- NonedataNotice -->
          <el-empty v-if="!currentAnalysis && !historyList.length" description="暂无分析数据，请选择时间范围后运行分析。" />

          <!-- Analysis history -->
          <div v-if="historyList.length" class="list-panel-head" style="margin: -20px -24px 12px; padding: 12px 16px;">
            <div class="list-panel-title">
              <el-icon><TrendCharts /></el-icon>
              <span>分析历史</span>
              <span class="list-count">{{ historyList.length }} items</span>
            </div>
          </div>
          <el-table v-if="historyList.length" :data="historyList" stripe class="app-data-table">
            <el-table-column prop="analysisDate" label="分析日期" width="108" />
            <el-table-column label="时间维度" width="80">
              <template #default="{ row }">{{ timeTypeLabel(row.timeType) }}</template>
            </el-table-column>
            <el-table-column prop="timeValue" label="周期" width="100" />
            <el-table-column label="平均血压" width="130">
              <template #default="{ row }">{{ row.avgSystolic || '-' }}/{{ row.avgDiastolic || '-' }} mmHg</template>
            </el-table-column>
            <el-table-column label="变异性（σ）" width="100">
              <template #default="{ row }">
                <span :style="row.stdDeviation > 15 ? 'color: #f56c6c; font-weight: 600;' : ''">{{ row.stdDeviation || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="异常读数" width="130">
              <template #default="{ row }">
                <el-tag v-if="row.lowBpCount > 0" type="warning" size="small">低血压 {{ row.lowBpCount }} 次</el-tag>
                <el-tag v-if="row.highBpCount > 0" type="danger" size="small" style="margin-left: 4px;">高血压 {{ row.highBpCount }} 次</el-tag>
                <el-tag v-if="row.orthostaticCount > 0" type="info" size="small" style="margin-left: 4px;">体位性 {{ row.orthostaticCount }} 次</el-tag>
                <span v-if="!row.lowBpCount && !row.highBpCount && !row.orthostaticCount" style="color: #67c23a;">正常</span>
              </template>
            </el-table-column>
            <el-table-column prop="analysisSummary" label="分析摘要" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="120" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="showDetail(row)">详情</el-button>
                <el-popconfirm title="确认删除吗？" @confirm="handleDelete(row.id)">
                  <template #reference><el-button link type="danger" size="small">删除</el-button></template>
                </el-popconfirm>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-main>

    <!-- Detailsdialog -->
    <el-dialog v-model="detailVisible" title="血压规律分析详情" :width="isMobile ? '94%' : '640px'" destroy-on-close>
      <template v-if="detailRecord">
        <el-descriptions :column="isMobile ? 1 : 2" border size="default">
          <el-descriptions-item label="分析日期">{{ detailRecord.analysisDate }}</el-descriptions-item>
          <el-descriptions-item label="时间维度">{{ timeTypeLabel(detailRecord.timeType) }}</el-descriptions-item>
          <el-descriptions-item label="时间范围">{{ detailRecord.timeValue }}</el-descriptions-item>
          <el-descriptions-item label="平均收缩压">{{ detailRecord.avgSystolic }} mmHg</el-descriptions-item>
          <el-descriptions-item label="平均舒张压">{{ detailRecord.avgDiastolic }} mmHg</el-descriptions-item>
          <el-descriptions-item label="收缩压范围">{{ detailRecord.minSystolic }}-{{ detailRecord.maxSystolic }} mmHg</el-descriptions-item>
          <el-descriptions-item label="收缩压标准差">
            <span :style="detailRecord.stdDeviation > 15 ? 'color: #f56c6c; font-weight: 600;' : ''">
              {{ detailRecord.stdDeviation }}
            </span>
            <span v-if="detailRecord.stdDeviation > 15" style="color: #f56c6c; font-size: 12px;"> 波动较大</span>
          </el-descriptions-item>
          <el-descriptions-item label="平均超滤量">{{ detailRecord.avgUfAmount }} kg</el-descriptions-item>
          <el-descriptions-item label="体位性低血压次数">
            <el-tag :type="detailRecord.orthostaticCount > 0 ? 'warning' : 'success'" size="small">{{ detailRecord.orthostaticCount }} times</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="低血压次数（<90）">
            <el-tag :type="detailRecord.lowBpCount > 0 ? 'warning' : 'success'" size="small">{{ detailRecord.lowBpCount }} times</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="高血压次数（>140）">
            <el-tag :type="detailRecord.highBpCount > 0 ? 'danger' : 'success'" size="small">{{ detailRecord.highBpCount }} times</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="体重增长与血压相关性">
            <span :style="correlationStyle(detailRecord.correlationWeightGainBp)">
              {{ detailRecord.correlationWeightGainBp }}
            </span>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="detailRecord.analysisSummary" style="margin-top: 16px; padding: 12px 16px; background: #f8fafc; border-radius: 8px; border: 1px solid #e2e8f0;">
          <p style="font-size: 13px; font-weight: 600; color: #334155; margin-bottom: 8px;">分析摘要</p>
          <p style="font-size: 13px; color: #64748b; line-height: 1.6;">{{ detailRecord.analysisSummary }}</p>
        </div>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { DataAnalysis, TrendCharts, Refresh } from '@element-plus/icons-vue';
import { analyzeBpPattern, listBpPatterns, deleteBpPattern } from '@/api/bpPattern.js';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { useMobile } from '@/composables/useMobile';
import TimeScopeFilter from '@/components/TimeScopeFilter.vue';

const { currentPatientId } = useCurrentPatient();
const { isMobile } = useMobile();
const timeType = ref('month');
const timeValue = ref('');
const analyzing = ref(false);
const currentAnalysis = ref(null);
const historyList = ref([]);
const detailVisible = ref(false);
const detailRecord = ref(null);

const TIME_TYPE_MAP = { month: '按月', week: '按周', year: '按年' };
function timeTypeLabel(v) { return TIME_TYPE_MAP[v] || v; }

function correlationStyle(val) {
  if (!val) return '';
  const abs = Math.abs(val);
  if (abs >= 0.7) return 'color: #f56c6c; font-weight: 600;';
  if (abs >= 0.4) return 'color: #e6a23c; font-weight: 600;';
  return 'color: #67c23a;';
}

function onDimensionChange() { loadData(); }
function onDateChange() { loadData(); }

async function loadData() {
  if (!currentPatientId.value) { historyList.value = []; currentAnalysis.value = null; return; }
  try {
    const res = await listBpPatterns(currentPatientId.value);
    if (res.code === 200) {
      historyList.value = res.data || [];
      currentAnalysis.value = historyList.value.length > 0 ? historyList.value[0] : null;
    }
  } catch (e) { console.error(e); }
}

async function handleAnalyze() {
  if (!currentPatientId.value) { ElMessage.warning('请先选择患者。'); return; }
  if (!timeValue.value) { ElMessage.warning('请选择时间范围'); return; }
  analyzing.value = true;
  try {
    const res = await analyzeBpPattern(currentPatientId.value, timeType.value, timeValue.value);
    if (res.code === 200) { ElMessage.success('分析完成'); loadData(); }
    else ElMessage.error(res.msg || '分析失败');
  } catch (e) { ElMessage.error('分析失败'); }
  finally { analyzing.value = false; }
}

function showDetail(row) {
  detailRecord.value = row;
  detailVisible.value = true;
}

async function handleDelete(id) {
  try {
    const res = await deleteBpPattern(id);
    if (res.code === 200) { ElMessage.success('删除成功'); loadData(); }
    else ElMessage.error(res.msg || '删除失败');
  } catch (e) { ElMessage.error('删除失败'); }
}

watch(currentPatientId, () => loadData());
onMounted(() => loadData());
</script>

<style scoped src="@/styles/module-layout.css"></style>

<style scoped>
.analysis-overview {
  margin-bottom: 20px;
}
.analysis-overview :deep(.el-statistic__content) {
  font-size: 20px;
}
@media (max-width: 768px) {
  .analysis-overview .el-col {
    margin-bottom: 12px;
  }
  .analysis-overview :deep(.el-statistic__content) {
    font-size: 18px;
  }
}
</style>
