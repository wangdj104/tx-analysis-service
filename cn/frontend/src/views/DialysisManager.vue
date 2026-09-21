<template>
  <el-container class="dialysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
      <div v-show="activeMenu !== 'ai'" class="page-header">
      <div class="top-bar">
        <div class="left">
            <div>
          <h1>{{ pageTitle }}</h1>
              <p class="subtitle">{{ pageSubtitle }}</p>
            </div>
        </div>
        <div class="right">
          <TimeScopeFilter
            v-model:time-type="currentTimeType"
            v-model:time-value="currentTimeValue"
            :stack="isMobile"
            @dimension-change="onTimeTypeChange"
            @date-change="onTimeChange"
          />
        </div>
      </div>
      </div>

      <!-- dataentry -->
      <div v-show="activeMenu === 'data'" class="content-panel">
        <div class="toolbar">
          <el-button type="primary" @click="handleAdd">
            <el-icon><Plus /></el-icon>新增记录
          </el-button>
          <DialysisTextImport :patient-id="currentPatientId" @saved="loadData"/>
          <el-button @click="loadData">
            <el-icon><Refresh /></el-icon>刷新
          </el-button>
        </div>

        <div class="list-panel" v-loading="loading">
          <div class="list-panel-head">
            <div class="list-panel-title">
              <el-icon><Document /></el-icon>
              <span>透析记录列表</span>
              <span v-if="records.length" class="list-count">{{ records.length }} 条</span>
              <span class="list-filter-tag">{{ listScopeLabel }}</span>
            </div>
          </div>

          <!-- Phoneendcardtablet -->
          <div v-if="isMobile" class="record-cards">
            <article
              v-for="(row, index) in records"
              :key="rowKey(row, index)"
              class="record-card"
              :class="[cardStatusClass(row.dehydrationStatus), { 'record-card--incomplete': row.recordType === 'INCOMPLETE' }]"
            >
              <div class="record-card-head">
                <div class="date-block">
                  <span class="date">{{ formatDateMain(row.recordDate) }}</span>
                  <span class="date-week">{{ formatDateWeek(row.recordDate) }}</span>
                </div>
                <el-tag v-if="row.recordType === 'INCOMPLETE'" type="info" size="small" effect="dark" round>不完整</el-tag>
                <el-tag v-else :type="dehydrationTagType(row.dehydrationStatus)" size="small" effect="dark" round>
                  {{ dehydrationLabel(row.dehydrationStatus) }}
                </el-tag>
              </div>
              <div class="record-highlight">
                <div class="hl-item">
                  <span class="hl-label">体重增长</span>
                  <span class="hl-value" :class="weightGainClass(row)">{{ formatNum(row.weightGain) }}<small>kg</small></span>
                </div>
                <div class="hl-item">
                  <span class="hl-label">超滤量</span>
                  <span class="hl-value text-green">{{ formatNum(row.ufAmount) }}<small>kg</small></span>
                </div>
                <div class="hl-item">
                  <span class="hl-label">血压</span>
                  <span class="bp-chip bp-chip--compact" :class="bpChipClass(row.systolicBp, row.diastolicBp)">
                    <span :class="bpSysClass(row.systolicBp)">{{ row.systolicBp ?? '-' }}</span>
                    <span class="bp-slash">/</span>
                    <span :class="bpDiaClass(row.diastolicBp)">{{ row.diastolicBp ?? '-' }}</span>
                  </span>
                </div>
              </div>
              <div class="record-grid">
                <div class="cell"><label>透前体重</label><span :class="{ 'muted': row.recordType === 'INCOMPLETE' }">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.onWeight) }} kg</span></div>
                <div class="cell"><label>透后体重</label><span :class="{ 'muted': row.recordType === 'INCOMPLETE' }">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.offWeight) }} kg</span></div>
                <div class="cell"><label>上次透后体重</label><span :class="{ 'muted': row.recordType === 'INCOMPLETE' }">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.lastOffWeight) }} kg</span></div>
                <div class="cell"><label>干体重</label><span :class="{ 'muted': row.recordType === 'INCOMPLETE' }">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.dryWeight) }} kg</span></div>
                <div class="cell"><label>间隔</label><span>{{ row.intervalDays ?? '-' }} 天</span></div>
                <div class="cell"><label>日均体重增长</label><span :class="[dailyWeightGainClass(row), { 'muted': row.recordType === 'INCOMPLETE' }]">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.dailyWeightGain) }} kg</span></div>
              </div>
              <div class="record-actions">
                <el-button type="primary" plain size="small" @click="handleEdit(row)">编辑</el-button>
                <el-popconfirm title="确认删除吗？" @confirm="handleDelete(row.id)">
                  <template #reference>
                    <el-button type="danger" plain size="small">删除</el-button>
                  </template>
                </el-popconfirm>
              </div>
            </article>
            <div v-if="!loading && records.length === 0" class="list-empty-state">
              <el-empty :image-size="88">
                <template #description>
                  <p class="empty-title">{{ listScopeLabel }}暂无记录</p>
                  <p class="empty-hint">可切换至按年或查看全部，以浏览更多历史记录。</p>
                </template>
                <div class="empty-actions">
                  <el-button size="small" @click="switchToYearFilter">查看本年</el-button>
                  <el-button size="small" @click="loadAllRecords">查看全部</el-button>
                  <el-button type="primary" size="small" @click="handleAdd">新增记录</el-button>
                </div>
              </el-empty>
            </div>
          </div>

          <!-- desktopendtablegrid -->
          <div v-else class="table-wrap">
            <el-table
              v-if="records.length > 0"
              :data="records"
              class="app-data-table app-data-table--wide app-data-table--fluid"
              style="width: 100%"
              :fit="true"
              :max-height="tableMaxHeight"
              stripe
              :row-key="rowKey"
            >
              <el-table-column v-if="dialysisColVisible('recordDate')" min-width="108">
                <template #header><span class="col-head">日期</span></template>
                <template #default="{ row }">
                  <div class="cell-date">
                    <span class="date-main">{{ row.recordDate }}</span>
                    <span class="date-sub">{{ formatDateWeek(row.recordDate) }}</span>
                  </div>
            </template>
          </el-table-column>
              <el-table-column v-if="dialysisColVisible('lastOffWeight')" min-width="76" align="right" class-name="col-weight">
                <template #header><span class="col-head col-head--right">上次透后</span></template>
                <template #default="{ row }">
                  <span class="num-cell" :class="{ 'muted': row.recordType === 'INCOMPLETE' }">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.lastOffWeight) }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="dialysisColVisible('onWeight')" min-width="72" align="right" class-name="col-weight">
                <template #header><span class="col-head col-head--right">透前</span></template>
                <template #default="{ row }">
                  <span class="num-cell" :class="{ 'muted': row.recordType === 'INCOMPLETE' }">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.onWeight) }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="dialysisColVisible('offWeight')" min-width="72" align="right" class-name="col-weight">
                <template #header><span class="col-head col-head--right">透后</span></template>
                <template #default="{ row }">
                  <span class="num-cell" :class="{ 'muted': row.recordType === 'INCOMPLETE' }">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.offWeight) }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="dialysisColVisible('dryWeight')" min-width="80" align="right" class-name="col-weight">
                <template #header><span class="col-head col-head--right">干体重</span></template>
                <template #default="{ row }">
                  <span class="num-cell ref" :class="{ 'muted': row.recordType === 'INCOMPLETE' }">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.dryWeight) }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="dialysisColVisible('intervalDays')" min-width="64" align="center">
                <template #header><span class="col-head col-head--center">间隔</span></template>
                <template #default="{ row }">
                  <span class="interval-badge">{{ row.intervalDays ?? '-' }}天</span>
                </template>
              </el-table-column>
              <el-table-column v-if="dialysisColVisible('weightGain')" min-width="68" align="right" class-name="col-fluid">
                <template #header><span class="col-head col-head--right">体重增长</span></template>
                <template #default="{ row }">
                  <span class="num-cell" :class="[weightGainClass(row), { 'muted': row.recordType === 'INCOMPLETE' }]">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.weightGain) }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="dialysisColVisible('ufAmount')" min-width="68" align="right" class-name="col-fluid">
                <template #header><span class="col-head col-head--right">超滤量</span></template>
                <template #default="{ row }">
                  <span class="num-cell text-green" :class="{ 'muted': row.recordType === 'INCOMPLETE' }">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.ufAmount) }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="dialysisColVisible('bloodPressure')" min-width="96" align="center">
                <template #header>
                  <el-tooltip content="收缩压 120–140、舒张压 70–90 为参考目标，超出部分将标红" placement="top">
                    <span class="col-head col-head--center">血压</span>
                  </el-tooltip>
                </template>
                <template #default="{ row }">
                  <span v-if="row.recordType === 'INCOMPLETE'" class="num-cell muted">-</span>
                  <span v-else class="bp-chip" :class="bpChipClass(row.systolicBp, row.diastolicBp)">
                    <span :class="bpSysClass(row.systolicBp)">{{ row.systolicBp ?? '-' }}</span>
                    <span class="bp-slash">/</span>
                    <span :class="bpDiaClass(row.diastolicBp)">{{ row.diastolicBp ?? '-' }}</span>
                  </span>
                </template>
              </el-table-column>
              <el-table-column v-if="dialysisColVisible('dailyWeightGain')" min-width="68" align="right">
                <template #header><span class="col-head col-head--right">日均增长</span></template>
                <template #default="{ row }">
                  <span class="num-cell" :class="[dailyWeightGainClass(row), { 'muted': row.recordType === 'INCOMPLETE' }]">{{ row.recordType === 'INCOMPLETE' ? '-' : formatNum(row.dailyWeightGain) }}</span>
                </template>
              </el-table-column>
              <el-table-column v-if="dialysisColVisible('dehydrationStatus')" min-width="88" align="center">
                <template #header><span class="col-head col-head--center">脱水状态</span></template>
                <template #default="{ row }">
                  <el-tag v-if="row.recordType === 'INCOMPLETE'" type="info" size="small" round effect="light">不完整</el-tag>
                  <el-tag v-else :type="dehydrationTagType(row.dehydrationStatus)" size="small" round effect="light">
                    {{ dehydrationLabel(row.dehydrationStatus) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column min-width="120" align="center" class-name="col-actions" fixed="right">
                <template #header>
                  <TableActionHeader
                    v-model="dialysisVisibleCols"
                    :columns="DIALYSIS_COLUMN_DEFS"
                    @reset="resetDialysisColumns"
                  />
                </template>
                <template #default="{ row }">
                  <div class="table-actions">
                    <el-button link type="primary" size="small" @click="handleEdit(row)">编辑</el-button>
                    <el-popconfirm title="确认删除吗？" @confirm="handleDelete(row.id)">
                <template #reference>
                        <el-button link type="danger" size="small">删除</el-button>
                </template>
              </el-popconfirm>
                  </div>
            </template>
          </el-table-column>
        </el-table>
            <div v-else-if="!loading" class="list-empty-state">
              <el-empty :image-size="96">
                <template #description>
                  <p class="empty-title">{{ listScopeLabel }}暂无记录</p>
                  <p class="empty-hint">数据可能位于其他时间段，请尝试扩大筛选范围。</p>
                </template>
                <div class="empty-actions">
                  <el-button size="small" @click="switchToYearFilter">查看本年</el-button>
                  <el-button size="small" @click="loadAllRecords">查看全部</el-button>
                  <el-button type="primary" size="small" @click="handleAdd">新增记录</el-button>
                </div>
              </el-empty>
            </div>
          </div>
        </div>
      </div>

      <!-- AI analysis -->
      <div v-show="activeMenu === 'ai'" v-loading="aiLoading" class="ai-page">
        <el-alert title="AI 分析结果仅供健康管理参考，不能替代医生诊断或治疗方案；如有不适请及时联系医护人员。" type="warning" :closable="false" show-icon class="ai-disclaimer" />
        <header class="ai-hero">
          <div class="ai-hero-top">
            <div class="ai-hero-brand">
              <div class="ai-hero-icon" aria-hidden="true">
                <el-icon :size="26"><Cpu /></el-icon>
              </div>
              <div class="ai-hero-copy">
                <h2 class="ai-hero-title">AI 智能分析</h2>
                <p class="ai-hero-desc">基于 DeepSeek 大模型，对透析数据进行专业医学解读。</p>
              </div>
            </div>
            <div class="ai-hero-filters">
              <TimeScopeFilter
                v-model:time-type="currentTimeType"
                v-model:time-value="currentTimeValue"
                :stack="isMobile"
                @dimension-change="onTimeTypeChange"
                @date-change="onTimeChange"
              />
            </div>
          </div>
          <div class="ai-hero-meta">
            <span class="ai-period-chip">
              <el-icon :size="14"><Calendar /></el-icon>
              {{ timeRangeLabel }}
            </span>
            <span class="ai-model-badge">DeepSeek</span>
          </div>
        </header>

        <section class="ai-workspace">
          <div class="ai-toolbar">
            <div class="ai-toolbar-left">
              <span class="ai-toolbar-label">{{ showAiHistory ? '历史分析' : '分析报告' }}</span>
              <span v-if="!showAiHistory && aiResult" class="ai-toolbar-hint">已生成，可保存或重新分析</span>
            </div>
            <div class="ai-toolbar-actions">
              <el-button v-if="currentAiResult && !showAiHistory" type="success" @click="handleSaveAnalysis">
                保存分析
              </el-button>
              <el-button :class="{ 'is-active': showAiHistory }" @click="toggleAiHistory">
                {{ showAiHistory ? '返回分析' : '历史记录' }}
              </el-button>
              <el-button type="primary" @click="loadAiAnalysis" :loading="aiLoading" :disabled="showAiHistory">
                <el-icon class="el-icon--left"><Cpu /></el-icon>
                开始分析
              </el-button>
            </div>
          </div>

          <div class="ai-body">
            <!-- historyrecordlist -->
            <div v-if="showAiHistory" class="ai-history-panel">
              <div v-if="aiHistory.length === 0" class="ai-history-empty">
                <el-empty description="暂无保存的分析记录">
                  <template #description>
                    <p class="ai-empty-title">暂无历史记录</p>
                    <p class="ai-empty-sub">分析完成后点击“保存分析”，即可在此查看。</p>
                  </template>
                </el-empty>
              </div>
              <div v-else class="ai-history-list">
                <div
                  v-for="item in aiHistory"
                  :key="item.id"
                  class="ai-history-item"
                  @click="viewHistoryItem(item)"
                >
                  <div class="ai-history-left">
                    <span class="ai-history-period">{{ item.periodLabel || item.timeValue }}</span>
                    <span class="ai-history-time">{{ formatDateTime(item.createdAt) }}</span>
                  </div>
                  <div class="ai-history-right">
                    <div class="ai-history-tags">
                      <el-tag v-if="item.dwAdjustNeeded" :type="item.dwAdjustNeeded === 'Yes' ? 'warning' : 'success'" size="small">
                        {{ item.dwAdjustNeeded === 'Yes' ? '建议调整' : '无需调整' }}
                      </el-tag>
                      <el-tag v-if="item.weightControlEval" :type="evalTagType(item.weightControlEval)" size="small">
                        体重{{ item.weightControlEval }}
                      </el-tag>
                      <el-tag v-if="item.dehydrationEval" :type="evalTagType(item.dehydrationEval)" size="small">
                        脱水{{ item.dehydrationEval }}
                      </el-tag>
                      <el-tag v-if="item.bpControlEval" :type="evalTagType(item.bpControlEval)" size="small">
                        血压{{ item.bpControlEval }}
                      </el-tag>
                    </div>
                    <el-button link type="danger" size="small" @click.stop="handleDeleteAnalysis(item.id)">
                      <el-icon><Delete /></el-icon>
                    </el-button>
                  </div>
                </div>
              </div>
            </div>

            <!-- analysisresultdisplay -->
            <template v-else>
              <div v-if="aiResult" class="ai-content" v-html="formatAiResult(aiResult)" />
              <div v-else class="ai-empty-state">
                <div class="ai-empty-visual" aria-hidden="true">
                  <div class="ai-empty-orbit"></div>
                  <div class="ai-empty-core">
                    <el-icon :size="36"><Cpu /></el-icon>
                  </div>
                </div>
                <h3 class="ai-empty-heading">准备就绪，开始智能分析</h3>
                <p class="ai-empty-text">选择上方时间范围后，点击“开始分析”生成脱水、体重与干体重建议。</p>
                <ul class="ai-empty-features">
                  <li><el-icon :size="16"><TrendCharts /></el-icon><span>体重增长与超滤趋势解读</span></li>
                  <li><el-icon :size="16"><ScaleToOriginal /></el-icon><span>干体重调整建议</span></li>
                  <li><el-icon :size="16"><Document /></el-icon><span>可保存历史，便于对比</span></li>
                </ul>
                <el-button type="primary" size="large" @click="loadAiAnalysis" :loading="aiLoading">
                  立即分析
                </el-button>
              </div>
            </template>
          </div>
        </section>
      </div>

      <!-- Trend Analysis -->
      <div v-show="activeMenu === 'analysis'" class="analysis-page" v-loading="chartLoading">
        <template v-if="statsHero">
          <div class="analysis-hero">
            <div class="hero-left">
              <p class="hero-eyebrow">{{ timeRangeLabel }}</p>
              <div class="hero-count-row">
                <span class="hero-count">{{ statsHero.totalCount }}</span>
                <span class="hero-count-unit">次透析</span>
              </div>
              <div class="hero-quick">
                <div class="hero-quick-item">
                  <span class="q-label">平均体重增长</span>
                  <span class="q-value">{{ statsHero.avgGain }} <small>kg</small></span>
                </div>
                <div class="hero-quick-item">
                  <span class="q-label">平均超滤量</span>
                  <span class="q-value">{{ statsHero.avgUf }} <small>kg</small></span>
                </div>
              </div>
            </div>
            <div class="hero-ring-wrap">
              <div
                class="hero-ring"
                :style="{ '--progress': statsHero.matchRate }"
                :class="matchRateLevel"
              >
                <div class="hero-ring-inner">
                  <span class="ring-num">{{ statsHero.matchRate }}%</span>
                  <span class="ring-label">脱水达标率</span>
                </div>
              </div>
              <p class="ring-sub">达标 {{ statsHero.matchCount }} 次</p>
            </div>
            <div class="hero-status">
              <div class="status-pill danger" v-if="statsHero.tooMuch > 0">
                <span class="pill-num">{{ statsHero.tooMuch }}</span>
                <span class="pill-txt">超滤过量</span>
              </div>
              <div class="status-pill warning" v-if="statsHero.insufficient > 0">
                <span class="pill-num">{{ statsHero.insufficient }}</span>
                <span class="pill-txt">超滤不足</span>
              </div>
              <div class="status-pill ok" v-if="statsHero.tooMuch === 0 && statsHero.insufficient === 0 && statsHero.totalCount > 0">
                <span class="pill-txt">脱水状态良好</span>
              </div>
            </div>
          </div>

          <div v-if="insightTags.length" class="insight-strip">
            <span v-for="tag in insightTags" :key="tag.text" class="insight-chip" :class="'chip-' + tag.type">
              {{ tag.text }}
            </span>
          </div>

          <div class="metric-groups">
            <section v-for="group in statGroups" :key="group.title" class="metric-group">
              <h3 class="group-title">
                <el-icon><component :is="group.icon" /></el-icon>
                {{ group.title }}
              </h3>
              <div class="metric-grid">
                <div
                  v-for="item in group.items"
                  :key="item.label"
                  class="metric-cell"
                  :class="'level-' + (item.level || 'normal')"
                >
                  <span class="cell-label">{{ item.label }}</span>
                  <span v-if="item.hint" class="cell-hint">{{ item.hint }}</span>
                  <span class="cell-value">
                    {{ item.value }}<small v-if="item.unit">{{ item.unit }}</small>
                  </span>
                </div>
              </div>
            </section>
          </div>
        </template>

        <div v-else-if="!chartLoading" class="analysis-empty">
          <el-empty description="当前时间段暂无数据">
            <el-button type="primary" @click="goToDataEntry">前往录入数据</el-button>
          </el-empty>
        </div>

        <div class="charts-block">
          <div class="block-title-row">
            <h2 class="block-title">趋势图表</h2>
            <div class="block-title-actions">
              <el-button v-if="hasChartData" size="small" @click="handleExportData">
                <el-icon><Download /></el-icon>导出数据
              </el-button>
              <el-button v-if="hasChartData" size="small" @click="downloadAllCharts">
                <el-icon><Picture /></el-icon>下载全部图表
              </el-button>
            </div>
          </div>
          <p v-if="!hasChartData" class="block-hint">录入透析记录后，图表将自动展示体重、体重增长和血压变化。</p>

          <div v-if="hasChartData" class="chart-panel chart-panel--full chart-panel--overview">
            <div class="chart-panel-head">
              <span class="dot dot-blue"></span>
              <span>体重与体重增长阈值总览</span>
            </div>
            <div class="chart-body chart-body--overview">
              <v-chart class="chart" :option="weightOverviewChartOption" autoresize />
            </div>
          </div>

          <el-row :gutter="12">
          <el-col :xs="24" :md="12">
              <div class="chart-panel">
                <div class="chart-panel-head">
                  <span class="dot dot-blue"></span>
                  <span>透前体重趋势</span>
                </div>
                <div class="chart-body">
                  <v-chart v-if="hasChartData" class="chart" :option="onWeightChartOption" autoresize />
                  <div v-else class="chart-placeholder"><el-icon :size="40"><TrendCharts /></el-icon><span>暂无数据</span></div>
                </div>
              </div>
          </el-col>
          <el-col :xs="24" :md="12">
              <div class="chart-panel">
                <div class="chart-panel-head">
                  <span class="dot dot-green"></span>
                  <span>透后体重趋势</span>
                </div>
                <div class="chart-body">
                  <v-chart v-if="hasChartData" class="chart" :option="offWeightChartOption" autoresize />
                  <div v-else class="chart-placeholder"><el-icon :size="40"><TrendCharts /></el-icon><span>暂无数据</span></div>
                </div>
              </div>
          </el-col>
        </el-row>

          <el-row :gutter="12">
          <el-col :xs="24" :md="12">
              <div class="chart-panel">
                <div class="chart-panel-head">
                  <span class="dot dot-cyan"></span>
                  <span>透析间期体重增长 / 超滤量</span>
                </div>
                <div class="chart-body">
                  <v-chart v-if="hasChartData" class="chart" :option="ufChartOption" autoresize />
                  <div v-else class="chart-placeholder"><el-icon :size="40"><Drizzling /></el-icon><span>暂无数据</span></div>
                </div>
              </div>
          </el-col>
          <el-col :xs="24" :md="12">
              <div class="chart-panel">
                <div class="chart-panel-head">
                  <span class="dot dot-purple"></span>
                  <span>日均体重增长</span>
                </div>
                <div class="chart-body">
                  <v-chart v-if="hasChartData" class="chart" :option="dailyGainChartOption" autoresize />
                  <div v-else class="chart-placeholder"><el-icon :size="40"><Sunrise /></el-icon><span>暂无数据</span></div>
                </div>
              </div>
          </el-col>
        </el-row>

          <el-row :gutter="12">
          <el-col :xs="24" :md="12">
              <div class="chart-panel">
                <div class="chart-panel-head">
                  <span class="dot dot-red"></span>
                  <span>血压趋势</span>
                </div>
                <div class="chart-body">
                  <v-chart v-if="hasChartData" class="chart" :option="bpChartOption" autoresize />
                  <div v-else class="chart-placeholder"><el-icon :size="40"><Monitor /></el-icon><span>暂无数据</span></div>
                </div>
              </div>
          </el-col>
            <el-col :xs="24" :md="12">
              <div class="chart-panel">
                <div class="chart-panel-head">
                  <span class="dot dot-amber"></span>
                  <span>超滤状态分布</span>
                </div>
                <div class="chart-body">
                  <v-chart v-if="hasChartData" class="chart" :option="dehydrationPieOption" autoresize />
                  <div v-else class="chart-placeholder"><el-icon :size="40"><DataAnalysis /></el-icon><span>暂无数据</span></div>
                </div>
              </div>
          </el-col>
        </el-row>

          <div v-if="monthlyStats.length" class="chart-panel chart-panel--full">
            <div class="chart-panel-head">
              <span class="dot dot-blue"></span>
              <span>月均透析间期体重增长 / 超滤量</span>
            </div>
            <div class="chart-body">
              <v-chart class="chart" :option="monthlyChartOption" autoresize />
            </div>
          </div>
        </div>
      </div>

      </div>

    <!-- Add/Editdialog -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" :width="isMobile ? '92%' : '560px'" destroy-on-close>
      <el-form :model="form" :label-width="isMobile ? 'auto' : '130px'" :label-position="isMobile ? 'top' : 'right'" :rules="rules" ref="formRef">
        <el-form-item label="患者">
          <el-select v-model="form.patientId" placeholder="选择患者" filterable style="width:100%">
            <el-option v-for="patient in patientList" :key="patient.id" :label="patient.patientName" :value="patient.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="透析日期" prop="recordDate">
          <el-date-picker v-model="form.recordDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width:100%" />
        </el-form-item>
        <el-form-item label="间隔天数" prop="intervalDays">
          <div class="interval-field">
            <el-input-number
              v-model="form.intervalDays"
              :min="1"
              :max="365"
              :precision="0"
              controls-position="right"
              placeholder="留空自动计算"
              class="interval-input"
            />
            <el-button link type="primary" @click="form.intervalDays = null">使用自动计算</el-button>
          </div>
          <p class="form-tip">留空或点击“使用自动计算”时，将根据上次透析日期估算。</p>
        </el-form-item>
        <el-form-item label="上次透后体重（kg）">
          <el-input-number v-model="form.lastOffWeight" :precision="2" :step="0.01" style="width:100%" />
        </el-form-item>
        <el-form-item label="本次透前体重（kg）" prop="onWeight">
          <el-input-number v-model="form.onWeight" :precision="2" :step="0.01" style="width:100%" />
        </el-form-item>
        <el-form-item label="本次透后体重（kg）" prop="offWeight">
          <el-input-number v-model="form.offWeight" :precision="2" :step="0.01" style="width:100%" />
        </el-form-item>
        <el-form-item label="收缩压">
          <el-input-number v-model="form.systolicBp" :min="0" style="width:100%" />
        </el-form-item>
        <el-form-item label="舒张压">
          <el-input-number v-model="form.diastolicBp" :min="0" style="width:100%" />
        </el-form-item>
        <el-form-item label="透析时长（分钟）">
          <el-input-number v-model="form.sessionMinutes" :min="30" :max="1440" :step="15" style="width:100%" />
        </el-form-item>
        <el-form-item label="Kt/V">
          <el-input-number v-model="form.ktv" :min="0" :max="10" :precision="2" :step="0.1" style="width:100%" />
        </el-form-item>
        <el-form-item label="URR (%)">
          <el-input-number v-model="form.urr" :min="0" :max="100" :precision="1" :step="1" style="width:100%" />
        </el-form-item>
        <el-form-item label="血管通路备注">
          <el-input v-model="form.accessIssue" maxlength="255" placeholder="未发现通路问题时可留空" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.isIncomplete" label="不完整记录（包含估算值或部分数据缺失）" />
        </el-form-item>
        <el-form-item label="备注" v-if="form.isIncomplete">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="请说明缺失或估算的数据及原因" />
        </el-form-item>
        <div v-if="dialogVisible" class="form-preview" :class="'form-preview--' + formPreview.level">
          <div class="form-preview__head">
            <span class="form-preview__title">实时校验</span>
            <span class="form-preview__status">{{ formPreview.statusText }}</span>
          </div>
          <div class="form-preview__grid">
            <div class="form-preview__item">
              <span>体重增长</span>
              <strong>{{ formPreview.weightGain }}</strong>
            </div>
            <div class="form-preview__item">
              <span>超滤量</span>
              <strong>{{ formPreview.ufAmount }}</strong>
            </div>
            <div class="form-preview__item">
              <span>日均体重增长</span>
              <strong>{{ formPreview.dailyGain }}</strong>
            </div>
            <div class="form-preview__item">
              <span>3% / 5% threshold</span>
              <strong>{{ formPreview.thresholdText }}</strong>
            </div>
          </div>
          <p class="form-preview__hint">{{ formPreview.hint }}</p>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

  </el-main>
</el-container>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { LineChart as EchartsLineChart, BarChart as EchartsBarChart, PieChart as EchartsPieChart } from 'echarts/charts';
import {
  GridComponent, TooltipComponent, LegendComponent, TitleComponent,
  ToolboxComponent, DataZoomComponent
} from 'echarts/components';
import VChart from 'vue-echarts';
import html2canvas from 'html2canvas';
import {
  listRecords, saveRecord, updateRecord, deleteRecord, getStats, getChartData
} from '@/api/dialysis.js';
import { getPatientNames } from '@/api/patient';
import { useCurrentPatient } from '@/composables/useCurrentPatient';
import { analyzeDialysis, saveAnalysis, listHistory, deleteAnalysis } from '@/api/ai.js';
import DialysisTextImport from '@/components/DialysisTextImport.vue';
import TimeScopeFilter from '@/components/TimeScopeFilter.vue';
import TableActionHeader from '@/components/TableActionHeader.vue';
import { useTableColumns } from '@/composables/useTableColumns';

const DIALYSIS_COLUMN_DEFS = [
  { key: 'recordDate', label: '日期' },
  { key: 'lastOffWeight', label: '上次透后', default: false },
  { key: 'onWeight', label: '透前体重' },
  { key: 'offWeight', label: '透后体重' },
  { key: 'dryWeight', label: '干体重' },
  { key: 'intervalDays', label: '间隔', default: false },
  { key: 'weightGain', label: '体重增长' },
  { key: 'ufAmount', label: '超滤量' },
  { key: 'bloodPressure', label: '血压' },
  { key: 'dailyWeightGain', label: '日均增长', default: false },
  { key: 'dehydrationStatus', label: '脱水状态' }
];
const { visibleKeys: dialysisVisibleCols, isVisible: dialysisColVisible, resetColumns: resetDialysisColumns } =
  useTableColumns('dialysis-record-list', DIALYSIS_COLUMN_DEFS);

use([
  CanvasRenderer, EchartsLineChart, EchartsBarChart, EchartsPieChart,
  GridComponent, TooltipComponent, LegendComponent, TitleComponent,
  ToolboxComponent, DataZoomComponent
]);

const router = useRouter();
const route = useRoute();

const activeMenu = ref('data');
const loading = ref(false);
const chartLoading = ref(false);
const records = ref([]);
const dialogVisible = ref(false);
const dialogTitle = ref('新增透析记录');
const formRef = ref(null);
const isEdit = ref(false);

const { currentPatientId } = useCurrentPatient();
const patientList = ref([]);

// Time dimension
const currentTimeType = ref('month');
const currentTimeValue = ref('');

// Phoneendfitconfigure
const isMobile = ref(false);

// AI analysis
const aiLoading = ref(false);
const aiResult = ref('');
const currentAiResult = ref(null);
const aiHistory = ref([]);
const showAiHistory = ref(false);

const pageTitle = computed(() => {
  if (activeMenu.value === 'data') return '透析记录';
  if (activeMenu.value === 'analysis') return '透析趋势分析';
  return 'AI 辅助分析';
});

const pageSubtitle = computed(() => {
  if (activeMenu.value === 'data') return '记录每次透析及相关临床质量指标。';
  if (activeMenu.value === 'analysis') return '查看历史趋势，为临床决策提供参考。';
  return '根据选定透析数据生成待人工审核的分析草稿。';
});

function getDefaultTimeValue() {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  return `${y}-${m}`;
}

const form = reactive({
  id: null, patientId: currentPatientId.value, recordDate: null, intervalDays: null, lastOffWeight: null, onWeight: null,
  offWeight: null, systolicBp: null, diastolicBp: null, sessionMinutes: null, ktv: null, urr: null,
  accessIssue: '', isIncomplete: false, remark: ''
});

const formPreview = computed(() => {
  if (form.isIncomplete) {
    return {
      level: 'muted',
      statusText: '不完整',
      weightGain: '-',
      ufAmount: '-',
      dailyGain: '-',
      thresholdText: '-',
      hint: '不完整记录会保留日期、间隔和备注，但不计入体重统计。'
    };
  }

  const lastOff = numberOrNull(form.lastOffWeight);
  const on = numberOrNull(form.onWeight);
  const off = numberOrNull(form.offWeight);
  const interval = numberOrNull(form.intervalDays) || 2;
  const dry = resolveFormDryWeight();
  const gain = lastOff != null && on != null ? round2(on - lastOff) : null;
  const uf = on != null && off != null ? round2(on - off) : null;
  const daily = gain != null && interval > 0 ? round2(gain / interval) : null;
  const t3 = dry != null ? round2(dry * 0.03) : null;
  const t5 = dry != null ? round2(dry * 0.05) : null;

  let level = 'normal';
  let statusText = '等待数据';
  let hint = '请输入本次及上次体重，以预览本次透析状态。';
  if (gain != null && uf != null) {
    const diff = round2(uf - gain);
    if (diff > 0.3) {
      level = 'danger';
      statusText = '超滤过量';
      hint = `超滤量比体重增长多 ${formatSignedKg(diff)}，请确认目标透后体重是否合适。`;
    } else if (diff < -0.3) {
      level = 'warning';
      statusText = '超滤不足';
      hint = `超滤量比体重增长少 ${formatSignedKg(Math.abs(diff))}，请复核透后体重和液体管理。`;
    } else {
      level = 'ok';
      statusText = '超滤达标';
      hint = '超滤量与透析间期体重增长基本一致。';
    }
  }
  if (gain != null && t5 != null && gain > t5) {
    level = 'danger';
    hint = `体重增长已超过干体重 5% 阈值 ${formatKgText(t5)}，建议重点关注。`;
  }

  return {
    level,
    statusText,
    weightGain: formatKgText(gain),
    ufAmount: formatKgText(uf),
    dailyGain: daily == null ? '-' : `${daily.toFixed(2)} kg/days`,
    thresholdText: t3 != null && t5 != null ? `${t3.toFixed(2)} / ${t5.toFixed(2)} kg` : '暂无干体重',
    hint
  };
});

const rules = {
  recordDate: [{ required: true, message: '请选择日期', trigger: 'change' }],
  onWeight: [{
    validator: (_rule, value, callback) => {
      if (form.isIncomplete) return callback();
      if (value == null || value === '') return callback(new Error('请输入透前体重'));
      return callback();
    },
    trigger: 'blur'
  }],
  offWeight: [{
    validator: (_rule, value, callback) => {
      if (form.isIncomplete) return callback();
      if (value == null || value === '') return callback(new Error('请输入透后体重'));
      return callback();
    },
    trigger: 'blur'
  }],
  intervalDays: [{
    validator: (_rule, value, callback) => {
      if (value == null || value === '') return callback();
      if (Number(value) < 1) return callback(new Error('间隔天数不能少于 1 天'));
      return callback();
    },
    trigger: 'blur'
  }]
};

const statsHero = ref(null);
const statGroups = ref([]);
const monthlyStats = ref([]);
const insightTags = ref([]);
const listFilterOverride = ref('');

const hasChartData = computed(() => chartDates.value.length > 0);

const listScopeLabel = computed(() => listFilterOverride.value || timeRangeLabel.value);

const tableMaxHeight = computed(() => (isMobile.value ? undefined : 520));

function rowKey(row, index) {
  return row?.id ?? row?.recordDate ?? row?.yearMonth ?? `row-${index}`;
}

const timeRangeLabel = computed(() => {
  const t = currentTimeType.value;
  const v = currentTimeValue.value;
  if (t === 'year') return `${v} Yearleveloverview`;
  if (t === 'week') return `${v} when weekoverview`;
  return `${v} Monthly overview`;
});

const matchRateLevel = computed(() => {
  const r = Number(statsHero.value?.matchRate || 0);
  if (r >= 80) return 'ring-good';
  if (r >= 60) return 'ring-mid';
  return 'ring-low';
});

// charttabledata
const chartDates = ref([]);
const chartOnWeight = ref([]);
const chartOffWeight = ref([]);
const chartDryWeight = ref([]);
const chartWeightGain = ref([]);
const chartUfAmount = ref([]);
const chartSystolic = ref([]);
const chartDiastolic = ref([]);
const chart3pct = ref([]);
const chart5pct = ref([]);
const chartDailyGain = ref([]);
const pieData = ref([]);

const commonChartConfig = computed(() => ({
  tooltip: { trigger: 'axis', confine: true },
  legend: { bottom: 0, type: isMobile.value ? 'scroll' : 'plain' },
  grid: { left: '3%', right: '4%', bottom: isMobile.value ? '22%' : '15%', containLabel: true },
  toolbox: isMobile.value ? undefined : { feature: { saveAsImage: {} } }
}));

const weightOverviewChartOption = computed(() => ({
  ...commonChartConfig.value,
  xAxis: { type: 'category', data: chartDates.value, axisLabel: { rotate: isMobile.value ? 45 : 30, fontSize: 10 } },
  yAxis: [
    { type: 'value', name: '体重 kg', scale: true },
    { type: 'value', name: '体重增长 kg', min: 0 }
  ],
  series: [
    { name: '透前体重', type: 'line', data: chartOnWeight.value, smooth: true, itemStyle: { color: '#6366f1' } },
    { name: '透后体重', type: 'line', data: chartOffWeight.value, smooth: true, itemStyle: { color: '#10b981' } },
    { name: '干体重', type: 'line', data: chartDryWeight.value, lineStyle: { type: 'dashed', color: '#64748b' }, symbol: 'none' },
    { name: '体重增长', type: 'bar', yAxisIndex: 1, data: chartWeightGain.value, barMaxWidth: 18, itemStyle: { color: '#38bdf8', borderRadius: [4, 4, 0, 0] } },
    { name: '3% 阈值', type: 'line', yAxisIndex: 1, data: chart3pct.value, lineStyle: { type: 'dashed', color: '#f59e0b' }, symbol: 'none' },
    { name: '5% 阈值', type: 'line', yAxisIndex: 1, data: chart5pct.value, lineStyle: { type: 'dashed', color: '#ef4444' }, symbol: 'none' }
  ]
}));

const onWeightChartOption = computed(() => ({
  ...commonChartConfig.value,
  title: { show: false },
  xAxis: { type: 'category', data: chartDates.value, axisLabel: { rotate: isMobile.value ? 45 : 30, fontSize: 10 } },
  yAxis: { type: 'value', scale: true },
  series: [
    { name: '透前体重', type: 'line', data: chartOnWeight.value, smooth: true, itemStyle: { color: '#6366f1' } },
    { name: '干体重参考', type: 'line', data: chartDryWeight.value, lineStyle: { type: 'dashed', color: '#E6A23C' }, symbol: 'none' }
  ]
}));

const offWeightChartOption = computed(() => ({
  ...commonChartConfig.value,
  title: { show: false },
  xAxis: { type: 'category', data: chartDates.value, axisLabel: { rotate: isMobile.value ? 45 : 30, fontSize: 10 } },
  yAxis: { type: 'value', scale: true },
  series: [
    { name: '透后体重', type: 'line', data: chartOffWeight.value, smooth: true, itemStyle: { color: '#10b981' } },
    { name: '干体重参考', type: 'line', data: chartDryWeight.value, lineStyle: { type: 'dashed', color: '#E6A23C' }, symbol: 'none' }
  ]
}));

const ufChartOption = computed(() => ({
  ...commonChartConfig.value,
  title: { show: false },
  xAxis: { type: 'category', data: chartDates.value, axisLabel: { rotate: isMobile.value ? 45 : 30, fontSize: 10 } },
  yAxis: { type: 'value' },
  series: [
    { name: '透析间期体重增长', type: 'line', data: chartWeightGain.value, smooth: true, itemStyle: { color: '#3b82f6' } },
    { name: '超滤量', type: 'line', data: chartUfAmount.value, smooth: true, itemStyle: { color: '#10b981' } },
    { name: '3% 阈值', type: 'line', data: chart3pct.value, lineStyle: { type: 'dashed', color: '#E6A23C' }, symbol: 'none' },
    { name: '5% 阈值', type: 'line', data: chart5pct.value, lineStyle: { type: 'dashed', color: '#F56C6C' }, symbol: 'none' }
  ]
}));

const dailyGainChartOption = computed(() => ({
  ...commonChartConfig.value,
  title: { show: false },
  xAxis: { type: 'category', data: chartDates.value, axisLabel: { rotate: isMobile.value ? 45 : 30, fontSize: 10 } },
  yAxis: { type: 'value', name: 'kg/days' },
  series: [
    { name: '日均体重增长', type: 'line', data: chartDailyGain.value, smooth: true, areaStyle: { opacity: 0.12 }, itemStyle: { color: '#8b5cf6' } }
  ]
}));

const bpChartOption = computed(() => ({
  ...commonChartConfig.value,
  title: { show: false },
  xAxis: { type: 'category', data: chartDates.value, axisLabel: { rotate: isMobile.value ? 45 : 30, fontSize: 10 } },
  yAxis: { type: 'value', min: 40 },
  series: [
    { name: '收缩压', type: 'line', data: chartSystolic.value, smooth: true, itemStyle: { color: '#ef4444' } },
    { name: '舒张压', type: 'line', data: chartDiastolic.value, smooth: true, itemStyle: { color: '#3b82f6' } },
    { name: '理想收缩压', type: 'line', data: chartDates.value.map(() => 130), lineStyle: { type: 'dashed', color: '#67C23A' }, symbol: 'none' },
    { name: '理想舒张压', type: 'line', data: chartDates.value.map(() => 80), lineStyle: { type: 'dashed', color: '#67C23A' }, symbol: 'none' }
  ]
}));

const dehydrationPieOption = computed(() => ({
  title: { show: false },
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, type: isMobile.value ? 'scroll' : 'plain' },
  series: [{
    type: 'pie', radius: ['40%', '70%'], avoidLabelOverlap: false,
    itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
    label: { show: true, formatter: '{b}: {c} ({d}%)' },
    data: pieData.value
  }]
}));

const monthlyChartOption = computed(() => {
  const months = monthlyStats.value.map(m => m.month);
  const avgGain = monthlyStats.value.map(m => Number(m.avg_weight_gain || 0).toFixed(2));
  const avgUf = monthlyStats.value.map(m => Number(m.avg_uf_amount || 0).toFixed(2));
  return {
    ...commonChartConfig.value,
    title: { show: false },
    xAxis: { type: 'category', data: months },
    yAxis: { type: 'value' },
    series: [
      { name: '平均透析间期体重增长', type: 'bar', data: avgGain, itemStyle: { color: '#409EFF' } },
      { name: '平均超滤量', type: 'bar', data: avgUf, itemStyle: { color: '#67C23A' } }
    ]
  };
});

/** Dry Weight 3%/5% threshold (excellentfirstuseafter endfield, Nothenby Dry Weightestimate)  */
function dryWeightThresholds(row) {
  const dry = row.dryWeight != null ? Number(row.dryWeight) : null;
  const t3 =
    row.weight3pct != null ? Number(row.weight3pct) : dry != null ? dry * 0.03 : null;
  const t5 =
    row.weight5pct != null ? Number(row.weight5pct) : dry != null ? dry * 0.05 : null;
  return { t3, t5 };
}

/** twotimesDialysisbetweenweight gain: 3%～5% ideal, exceed 5% bright red */
function weightGainClass(row) {
  const gain = row.weightGain;
  if (gain == null || gain === '') return '';
  const g = Number(gain);
  if (Number.isNaN(g)) return '';
  const { t3, t5 } = dryWeightThresholds(row);
  if (t5 != null && g > t5) return 'gain-danger';
  if (t3 != null && t5 != null && g >= t3 && g <= t5) return 'gain-ideal';
  return '';
}

/** Average daily weight gain and interdialytic weight gainsamerule (totalamountover 5% thenDayaverageoneandhighlight in red)  */
function dailyWeightGainClass(row) {
  return weightGainClass(row);
}

const BP_TARGET = { sysMin: 120, sysMax: 140, diaMin: 70, diaMax: 90 };

function bpSysInRange(v) {
  const s = Number(v);
  return !Number.isNaN(s) && s >= BP_TARGET.sysMin && s <= BP_TARGET.sysMax;
}

function bpDiaInRange(v) {
  const d = Number(v);
  return !Number.isNaN(d) && d >= BP_TARGET.diaMin && d <= BP_TARGET.diaMax;
}

/** systolicsingleindependentby  120–140 highlight */
function bpSysClass(v) {
  if (v == null || v === '') return 'bp-part-na';
  return bpSysInRange(v) ? 'bp-part-ok' : 'bp-part-alert';
}

/** diastolicsingleindependentby  70–90 highlight */
function bpDiaClass(v) {
  if (v == null || v === '') return 'bp-part-na';
  return bpDiaInRange(v) ? 'bp-part-ok' : 'bp-part-alert';
}

/** Trend Analysis: averagevalueYesNofallin  120–140 / 70–90 */
function bpAvgLevel(value, isSystolic) {
  const n = Number(value);
  if (Number.isNaN(n)) return 'normal';
  return isSystolic
    ? n >= BP_TARGET.sysMin && n <= BP_TARGET.sysMax
      ? 'ok'
      : 'danger'
    : n >= BP_TARGET.diaMin && n <= BP_TARGET.diaMax
      ? 'ok'
      : 'danger';
}

/** wholegridbackground: doubleon targetgreenbottom, doubleAbnormalredbottom, oneon targetoneAbnormalfor inpropertybottom */
function bpChipClass(sys, dia) {
  const sysOk = bpSysInRange(sys);
  const diaOk = bpDiaInRange(dia);
  if (sys == null || sys === '' || dia == null || dia === '') return '';
  if (Number.isNaN(Number(sys)) || Number.isNaN(Number(dia))) return '';
  if (sysOk && diaOk) return 'bp-chip--all-ok';
  if (!sysOk && !diaOk) return 'bp-chip--all-alert';
  return 'bp-chip--partial';
}

function dehydrationTagType(status) {
  if (status === 'TOO_MUCH') return 'danger';
  if (status === 'INSUFFICIENT') return 'warning';
  return 'success';
}

function dehydrationLabel(status) {
  if (status === 'TOO_MUCH') return '超滤过量';
  if (status === 'INSUFFICIENT') return '超滤不足';
  if (status === 'MATCH') return '超滤达标';
  return '-';
}

function buildStatDisplay(s) {
  const fmt = (v) => (v != null && v !== '' ? Number(v).toFixed(2) : '0');
  const total = s.totalCount || 0;

  if (total === 0) {
    statsHero.value = null;
    statGroups.value = [];
    return;
  }

  const over5 = s.over5pctCount || 0;
  const idealGain = s.idealGainCount || 0;
  const under3 = s.under3pctCount || 0;
  const bpAbn = s.bpAbnormalCount || 0;
  const bpSysAbn = s.bpSysAbnormalCount || 0;
  const bpDiaAbn = s.bpDiaAbnormalCount || 0;
  const avgSys = Number(s.avgSystolicBp);
  const avgDia = Number(s.avgDiastolicBp);

  statsHero.value = {
    totalCount: total,
    matchRate: Number(s.dehydrationMatchRate ?? 0),
    matchCount: s.matchCount || 0,
    avgGain: fmt(s.avgWeightGain),
    avgUf: fmt(s.avgUfAmount),
    tooMuch: s.tooMuchCount || 0,
    insufficient: s.insufficientCount || 0
  };

  statGroups.value = [
    {
      title: '体重与液体清除',
      icon: 'ScaleToOriginal',
      items: [
        { label: '平均透析间期体重增长', value: fmt(s.avgWeightGain), unit: 'kg' },
        { label: '平均超滤量', value: fmt(s.avgUfAmount), unit: 'kg' },
        { label: '日均体重增长', value: fmt(s.avgDailyWeightGain), unit: 'kg/天' },
        { label: '体重增长峰值', value: fmt(s.maxWeightGain), unit: 'kg' },
        { label: '平均间隔', value: fmt(s.avgIntervalDays), unit: '天' }
      ]
    },
    {
      title: '血压监测',
      icon: 'Monitor',
      items: [
        {
          label: '平均收缩压',
          value: fmt(s.avgSystolicBp),
          unit: 'mmHg',
          hint: '目标 120–140',
          level: bpAvgLevel(avgSys, true)
        },
        {
          label: '平均舒张压',
          value: fmt(s.avgDiastolicBp),
          unit: 'mmHg',
          hint: '目标 70–90',
          level: bpAvgLevel(avgDia, false)
        },
        {
          label: '收缩压异常',
          value: bpSysAbn,
          unit: '次',
          hint: '超出 120–140',
          level: bpSysAbn > 0 ? 'danger' : 'ok'
        },
        {
          label: '舒张压异常',
          value: bpDiaAbn,
          unit: '次',
          hint: '超出 70–90',
          level: bpDiaAbn > 0 ? 'warn' : 'ok'
        },
        {
          label: '血压异常总数',
          value: bpAbn,
          unit: '次',
          hint: '收缩压或舒张压任一超出范围',
          level: bpAbn > 0 ? 'warn' : 'ok'
        }
      ]
    },
    {
      title: '风险提示',
      icon: 'Warning',
      items: [
        { label: '体重增长超标（>5%）', value: over5, unit: '次', level: over5 > 0 ? 'danger' : 'ok' },
        { label: '体重增长达标（3%-5%）', value: idealGain, unit: '次', level: idealGain > 0 ? 'ok' : 'normal' },
        { label: '体重增长偏低（<3%）', value: under3, unit: '次', level: under3 > 0 ? 'warn' : 'ok' },
        {
          label: '超滤达标',
          value: s.matchCount || 0,
          unit: '次',
          hint: '超滤量与体重增长差值 ≤0.3kg',
          level: (s.matchCount || 0) > 0 ? 'ok' : 'normal'
        },
        { label: '超滤过量', value: s.tooMuchCount || 0, unit: '次', level: (s.tooMuchCount || 0) > 0 ? 'danger' : 'ok' },
        { label: '超滤不足', value: s.insufficientCount || 0, unit: '次', level: (s.insufficientCount || 0) > 0 ? 'warn' : 'ok' }
      ]
    }
  ];
}

function goToDataEntry() {
  activeMenu.value = 'data';
}

function buildInsightTags(s) {
  const tags = [];
  const rate = Number(s.dehydrationMatchRate || 0);
  if (rate >= 80) tags.push({ text: `超滤达标率 ${rate}% · 良好`, type: 'success' });
  else if (rate >= 60) tags.push({ text: `超滤达标率 ${rate}% · 一般`, type: 'warning' });
  else if (s.totalCount > 0) tags.push({ text: `超滤达标率 ${rate}% · 需要改善`, type: 'danger' });

  if ((s.over5pctCount || 0) > 0) {
    tags.push({ text: `${s.over5pctCount} 次体重增长超过干体重的 5%`, type: 'danger' });
  }
  if ((s.idealGainCount || 0) > 0) {
    tags.push({ text: `${s.idealGainCount} 次体重增长处于 3%～5% 理想范围`, type: 'success' });
  }

  if ((s.bpSysAbnormalCount || 0) > 0) {
    tags.push({ text: `收缩压异常 ${s.bpSysAbnormalCount} 次（目标 120～140）`, type: 'warning' });
  }
  if ((s.bpDiaAbnormalCount || 0) > 0) {
    tags.push({ text: `舒张压异常 ${s.bpDiaAbnormalCount} 次（目标 70～90）`, type: 'warning' });
  }

  if ((s.tooMuchCount || 0) > (s.insufficientCount || 0)) {
    tags.push({ text: '本周期超滤过量出现次数较多', type: 'info' });
  } else if ((s.insufficientCount || 0) > (s.tooMuchCount || 0)) {
    tags.push({ text: '本周期超滤不足出现次数较多', type: 'info' });
  }

  insightTags.value = tags;
}

function formatDateWithWeek(dateStr) {
  if (!dateStr) return '-';
  return `${formatDateMain(dateStr)} (${formatDateWeek(dateStr)})`;
}

function formatDateMain(dateStr) {
  if (!dateStr) return '-';
  return String(dateStr).slice(0, 10);
}

function formatDateWeek(dateStr) {
  if (!dateStr) return '-';
  const weekDays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const d = new Date(dateStr);
  if (isNaN(d.getTime())) return '-';
  return weekDays[d.getDay()];
}

function formatDateTime(val) {
  if (!val) return '-';
  return String(val).replace('T', ' ').slice(0, 16);
}

function formatNum(val) {
  if (val == null || val === '') return '-';
  const n = Number(val);
  return Number.isNaN(n) ? val : n.toFixed(2);
}

function numberOrNull(val) {
  if (val == null || val === '') return null;
  const n = Number(val);
  return Number.isNaN(n) ? null : n;
}

function round2(val) {
  return Math.round(Number(val) * 100) / 100;
}

function formatKgText(val) {
  if (val == null || val === '') return '-';
  const n = Number(val);
  return Number.isNaN(n) ? '-' : `${n.toFixed(2)} kg`;
}

function formatSignedKg(val) {
  const n = Number(val);
  if (Number.isNaN(n)) return '-';
  return `${n.toFixed(2)} kg`;
}

function resolveFormDryWeight() {
  if (!form.recordDate) return null;
  const month = String(form.recordDate).slice(0, 7);
  const patientId = form.patientId || currentPatientId.value;
  const matched = records.value.find(r =>
    r.patientId === patientId &&
    r.recordDate &&
    String(r.recordDate).slice(0, 7) === month &&
    r.dryWeight != null
  );
  return matched ? Number(matched.dryWeight) : null;
}

function cardStatusClass(status) {
  if (status === 'TOO_MUCH') return 'card-status-danger';
  if (status === 'INSUFFICIENT') return 'card-status-warn';
  if (status === 'MATCH') return 'card-status-ok';
  return '';
}

async function loadData() {
  listFilterOverride.value = '';
  loading.value = true;
  try {
    const res = await listRecords(currentTimeType.value, currentTimeValue.value, currentPatientId.value);
    if (res.code === 200) {
      records.value = Array.isArray(res.data) ? res.data : [];
    } else {
      records.value = [];
      ElMessage.error(res.msg || '加载记录失败');
    }
  } catch {
    records.value = [];
    ElMessage.error('加载记录失败，请检查网络或后端服务');
  } finally {
    loading.value = false;
  }
}

async function loadAllRecords() {
  listFilterOverride.value = 'Allhistory';
  loading.value = true;
  try {
    const res = await listRecords(null, '', currentPatientId.value);
    if (res.code === 200) {
      records.value = Array.isArray(res.data) ? res.data : [];
      if (records.value.length) {
        ElMessage.success(`已加载全部 ${records.value.length} 条记录`);
      } else {
        ElMessage.info('数据库中暂无透析记录');
      }
    } else {
      records.value = [];
      ElMessage.error(res.msg || '加载失败');
    }
  } catch {
    records.value = [];
    ElMessage.error('加载失败');
  } finally {
    loading.value = false;
  }
}

function switchToYearFilter() {
  listFilterOverride.value = '';
  const y = new Date().getFullYear();
  currentTimeType.value = 'year';
  currentTimeValue.value = String(y);
  onTimeChange();
}

/** usecharttableclearfineheavycalculatestatistics, avoidlegacydatamissingfield or after endnot increaselevelguidecausecountfor  0 */
function reconcileAnalysisStats(stats, chart) {
  if (!stats || !chart) return stats;
  const merged = { ...stats };
  const sysList = chart.systolicBpList || [];
  const diaList = chart.diastolicBpList || [];
  const n = Math.max(sysList.length, diaList.length);
  let bpSys = 0;
  let bpDia = 0;
  let bpAny = 0;
  for (let i = 0; i < n; i++) {
    const s = sysList[i];
    const d = diaList[i];
    const sysBad = s != null && s !== '' && (Number(s) > 140 || Number(s) < 120);
    const diaBad = d != null && d !== '' && (Number(d) > 90 || Number(d) < 70);
    if (sysBad) bpSys++;
    if (diaBad) bpDia++;
    if (sysBad || diaBad) bpAny++;
  }
  merged.bpSysAbnormalCount = bpSys;
  merged.bpDiaAbnormalCount = bpDia;
  merged.bpAbnormalCount = bpAny;

  const gains = chart.weightGainList || [];
  const dryList = chart.dryWeightList || [];
  const t3List = chart.weight3pctList || [];
  const t5List = chart.weight5pctList || [];
  let over5 = 0;
  let ideal = 0;
  let under3 = 0;
  gains.forEach((gain, i) => {
    if (gain == null || gain === '') return;
    const g = Number(gain);
    if (Number.isNaN(g)) return;
    const dry = dryList[i] != null ? Number(dryList[i]) : null;
    const t3 =
      t3List[i] != null ? Number(t3List[i]) : dry != null && !Number.isNaN(dry) ? dry * 0.03 : null;
    const t5 =
      t5List[i] != null ? Number(t5List[i]) : dry != null && !Number.isNaN(dry) ? dry * 0.05 : null;
    if (t3 == null || t5 == null || Number.isNaN(t3) || Number.isNaN(t5)) return;
    const t3r = Math.round(t3 * 100) / 100;
    const t5r = Math.round(t5 * 100) / 100;
    if (g > t5r) over5++;
    else if (g < t3r) under3++;
    else ideal++;
  });
  merged.over5pctCount = over5;
  merged.idealGainCount = ideal;
  merged.under3pctCount = under3;
  return merged;
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

async function loadStats() {
  chartLoading.value = true;
  try {
    const [statsRes, chartRes] = await Promise.all([
      getStats(currentTimeType.value, currentTimeValue.value, currentPatientId.value),
      getChartData(currentTimeType.value, currentTimeValue.value, currentPatientId.value)
    ]);
    const chartData = chartRes.code === 200 ? chartRes.data : null;
    if (statsRes.code === 200) {
      const s = reconcileAnalysisStats(statsRes.data, chartData);
      buildStatDisplay(s);
      if (!s.totalCount) {
        insightTags.value = [];
      }
      monthlyStats.value = s.monthlyStats || [];
      pieData.value = [
        { value: s.tooMuchCount || 0, name: '超滤过量', itemStyle: { color: '#ef4444' } },
        { value: s.insufficientCount || 0, name: '超滤不足', itemStyle: { color: '#f59e0b' } },
        { value: s.matchCount || 0, name: '超滤达标', itemStyle: { color: '#10b981' } }
      ];
      if (s.totalCount) buildInsightTags(s);
    }
    if (chartData) {
      const c = chartData;
      chartDates.value = c.dateList || [];
      chartOnWeight.value = c.onWeightList || [];
      chartOffWeight.value = c.offWeightList || [];
      chartDryWeight.value = c.dryWeightList || [];
      chartWeightGain.value = c.weightGainList || [];
      chartUfAmount.value = c.ufAmountList || [];
      chartSystolic.value = c.systolicBpList || [];
      chartDiastolic.value = c.diastolicBpList || [];
      chart3pct.value = c.weight3pctList || [];
      chart5pct.value = c.weight5pctList || [];
      chartDailyGain.value = c.dailyWeightGainList || [];
    }
  } finally {
    chartLoading.value = false;
  }
}

function handleAdd() {
  isEdit.value = false;
  dialogTitle.value = '新增透析记录';
  Object.assign(form, {
    id: null, patientId: currentPatientId.value, recordDate: null, intervalDays: null, lastOffWeight: null, onWeight: null,
    offWeight: null, systolicBp: null, diastolicBp: null, sessionMinutes: null, ktv: null, urr: null,
    accessIssue: '', isIncomplete: false, remark: ''
  });
  dialogVisible.value = true;
}

function handleEdit(row) {
  isEdit.value = true;
  dialogTitle.value = '编辑透析记录';
  Object.assign(form, {
    id: row.id, patientId: row.patientId || currentPatientId.value, recordDate: row.recordDate, intervalDays: row.intervalDays ?? null,
    lastOffWeight: row.lastOffWeight, onWeight: row.onWeight, offWeight: row.offWeight,
    systolicBp: row.systolicBp, diastolicBp: row.diastolicBp,
    sessionMinutes: row.sessionMinutes, ktv: row.ktv, urr: row.urr, accessIssue: row.accessIssue || '',
    isIncomplete: row.recordType === 'INCOMPLETE',
    remark: row.remark || ''
  });
  dialogVisible.value = true;
}

async function handleDelete(id) {
  const res = await deleteRecord(id);
  if (res.code === 200) {
    ElMessage.success('删除成功');
    loadData();
  } else {
    ElMessage.error(res.msg || '删除失败');
  }
}

async function submitForm() {
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  const duplicate = records.value.find(row =>
    row.id !== form.id &&
    Number(row.patientId) === Number(form.patientId) &&
    row.recordDate === form.recordDate
  );
  if (duplicate) {
    ElMessage.warning(`${form.recordDate} 已存在透析记录，请编辑当天原记录。`);
    return;
  }

  const api = isEdit.value ? updateRecord : saveRecord;
  const payload = { ...form };
  if (payload.intervalDays == null || payload.intervalDays === '') {
    payload.intervalDays = null;
  }
  payload.recordType = payload.isIncomplete ? 'INCOMPLETE' : 'NORMAL';
  delete payload.isIncomplete;
  const res = await api(payload);
  if (res.code === 200) {
    ElMessage.success(isEdit.value ? '更新成功' : '保存成功');
    dialogVisible.value = false;
    loadData();
  } else {
    ElMessage.error(res.msg || '操作失败');
  }
}

function onTimeTypeChange() {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  if (currentTimeType.value === 'year') {
    currentTimeValue.value = String(y);
  } else if (currentTimeType.value === 'month') {
    currentTimeValue.value = `${y}-${m}`;
  } else {
    currentTimeValue.value = `${y}-${m}-01`;
  }
  onTimeChange();
}

function onTimeChange() {
  loadData();
  if (activeMenu.value === 'analysis') loadStats();
  if (activeMenu.value === 'ai') loadAiAnalysis();
}

async function loadAiAnalysis() {
  aiLoading.value = true;
  try {
    if (!currentPatientId.value) {
      aiResult.value = '请先选择患者，再进行 AI 分析。';
      currentAiResult.value = null;
      return;
    }
    const res = await analyzeDialysis(currentTimeType.value, currentTimeValue.value, currentPatientId.value);
    if (res.code === 200) {
      currentAiResult.value = res.data;
      aiResult.value = res.data?.rawText || '';
    } else {
      aiResult.value = '分析失败：' + (res.msg || '未知错误');
      currentAiResult.value = null;
    }
  } finally {
    aiLoading.value = false;
  }
}

async function handleSaveAnalysis() {
  if (!currentAiResult.value) return;
  if (!currentPatientId.value) {
    ElMessage.warning('请先选择患者。');
    return;
  }
  const vo = currentAiResult.value;
  const record = {
    timeType: currentTimeType.value,
    timeValue: currentTimeValue.value,
    periodLabel: vo.periodLabel || timeRangeLabel.value,
    analysisContent: vo.rawText,
    patientId: currentPatientId.value,
    dwAdjustNeeded: vo.dwAdjustNeeded,
    dwAdjustAmount: vo.dwAdjustAmount,
    dwTargetWeight: vo.dwTargetWeight,
    dwAdjustReason: vo.dwAdjustReason,
    weightControlEval: vo.weightControlEval,
    dehydrationEval: vo.dehydrationEval,
    bpControlEval: vo.bpControlEval,
    mainRisk: vo.mainRisk,
    dietAdvice: vo.dietAdvice,
    fluidAdvice: vo.fluidAdvice,
    exerciseAdvice: vo.exerciseAdvice,
    medicationAdvice: vo.medicationAdvice,
    followUpAdvice: vo.followUpAdvice,
    totalCount: vo.totalCount,
    avgOnWeight: vo.avgOnWeight,
    avgOffWeight: vo.avgOffWeight,
    avgWeightGain: vo.avgWeightGain,
    avgUfAmount: vo.avgUfAmount,
    dehydrationMatchRate: vo.dehydrationMatchRate
  };
  const res = await saveAnalysis(record);
  if (res.code === 200) {
    ElMessage.success('分析结果保存成功。');
    loadAiHistory();
  } else {
    ElMessage.error(res.msg || '保存失败');
  }
}

async function loadAiHistory() {
  try {
    if (!currentPatientId.value) {
      aiHistory.value = [];
      return;
    }
    const res = await listHistory(currentTimeType.value, currentTimeValue.value, currentPatientId.value);
    if (res.code === 200) {
      aiHistory.value = Array.isArray(res.data) ? res.data : [];
    }
  } catch (e) {
    console.error('loadhistoryfailed', e);
  }
}

function toggleAiHistory() {
  showAiHistory.value = !showAiHistory.value;
  if (showAiHistory.value) {
    loadAiHistory();
  }
}

function viewHistoryItem(item) {
  aiResult.value = item.analysisContent;
  currentAiResult.value = null;
  showAiHistory.value = false;
}

async function handleDeleteAnalysis(id) {
  try {
    const res = await deleteAnalysis(id);
    if (res.code === 200) {
      ElMessage.success('删除成功');
      loadAiHistory();
    } else {
      ElMessage.error(res.msg || '删除失败');
    }
  } catch (e) {
    ElMessage.error('删除失败');
  }
}

function formatAiResult(text) {
  if (!text) return '';
  const marker = '【干体重调整结论】';
  const idx = text.indexOf(marker);
  const bodyPart = idx >= 0 ? text.slice(0, idx) : text;
  const conclusionPart = idx >= 0 ? text.slice(idx + marker.length) : '';

  let html = bodyPart
    .replace(/\n/g, '<br>')
    .replace(/^(\d+[\., ].*)$/gm, '<strong>$1</strong>');

  if (conclusionPart.trim()) {
    // filterremoveend  ```json ... ``` replacecodeblock
    const cleanConclusion = conclusionPart.replace(/```json[\s\S]*?```\s*$/, '').trim();
    const lines = cleanConclusion.split('\n').map(l => l.trim()).filter(Boolean);
    const rows = lines.map((line) => {
      const m = line.match(/^(.+?)[: :]\s*(.+)$/);
      if (!m) return `<p class="ai-dw-line">${line}</p>`;
      const key = m[1];
      const val = m[2];
      const highlight = key.includes('adjustment amount') ? ' highlight' : '';
      return `<div class="ai-dw-line"><span class="ai-dw-key">${key}</span><span class="ai-dw-val${highlight}">${val}</span></div>`;
    }).join('');
    html += `<div class="ai-dw-conclusion"><strong>${marker}</strong>${rows}</div>`;
  }

  return html;
}

function evalTagType(value) {
  if (value === 'excellent') return 'success';
  if (value === 'Good') return 'primary';
  if (value === 'Fair') return 'warning';
  if (value === 'difference') return 'danger';
  return 'info';
}

async function handleExportData() {
  try {
    const res = await listRecords(currentTimeType.value, currentTimeValue.value);
    if (res.code !== 200 || !res.data || !res.data.length) {
      ElMessage.warning('当前时间段没有可导出的数据');
      return;
    }
    const rows = res.data;
    const headers = ['日期', '上次透后体重 (kg)', '透前体重 (kg)', '透后体重 (kg)', '间隔天数', '透析间期体重增长 (kg)', '超滤量 (kg)', '收缩压', '舒张压', '液体清除状态', '备注'];
    const statusMap = { TOO_MUCH: '超滤过量', INSUFFICIENT: '超滤不足', MATCH: '超滤达标', '': '' };
    const csvRows = rows.map(r => [
      r.recordDate || '',
      r.lastOffWeight ?? '',
      r.onWeight ?? '',
      r.offWeight ?? '',
      r.intervalDays ?? '',
      r.weightGain ?? '',
      r.ufAmount ?? '',
      r.systolicBp ?? '',
      r.diastolicBp ?? '',
      statusMap[r.dehydrationStatus] || '',
      (r.remark || '').replace(/,/g, ', ').replace(/\n/g, ' ')
    ]);
    const csvContent = [headers, ...csvRows].map(arr => arr.join(',')).join('\n');
    const BOM = '\uFEFF';
    const blob = new Blob([BOM + csvContent], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    const url = URL.createObjectURL(blob);
    link.href = url;
    const fileName = `Dialysisdata_${currentTimeValue.value || 'All'}.csv`;
    link.setAttribute('download', fileName);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    ElMessage.success(`已导出 ${rows.length} 条记录`);
  } catch (e) {
    ElMessage.error('导出失败：' + (e.message || '未知错误'));
  }
}

async function downloadAllCharts() {
  const el = document.querySelector('.dialysis-manager .charts-block');
  if (!el) {
    ElMessage.warning('未找到图表区域');
    return;
  }
  try {
    ElMessage.info('正在生成图片，请稍候……');
    const canvas = await html2canvas(el, {
      backgroundColor: '#ffffff',
      scale: 2,
      useCORS: true,
      allowTaint: true,
      logging: false
    });
    const link = document.createElement('a');
    link.download = `Dialysistrendcharttable_${currentTimeValue.value || 'All'}.png`;
    link.href = canvas.toDataURL('image/png');
    link.click();
    ElMessage.success('图表已下载');
  } catch (e) {
    ElMessage.error('下载失败：' + (e.message || '未知错误'));
  }
}

function syncActiveMenuFromRoute() {
  const tab = route.query.tab;
  if (tab === 'analysis' || tab === 'ai') {
    activeMenu.value = tab;
  } else {
    activeMenu.value = 'data';
  }
}

watch(() => route.query.tab, () => {
  syncActiveMenuFromRoute();
  if (activeMenu.value === 'analysis') loadStats();
  if (activeMenu.value === 'ai') loadAiAnalysis();
});

watch(currentPatientId, (newVal, oldVal) => {
  if (newVal !== oldVal) {
    form.patientId = newVal;
    if (activeMenu.value === 'data') {
      loadData();
    }
    if (activeMenu.value === 'analysis') {
      loadStats();
    }
  }
});

function checkMobile() {
  isMobile.value = window.innerWidth <= 768;
}

// listen"dataincomplete"selectselectStatus, Automaticclear/restoreWeightfield validateNotice
watch(() => form.isIncomplete, (val) => {
  if (val) {
    formRef.value?.clearValidate(['onWeight', 'offWeight']);
  }
});

onMounted(() => {
  currentTimeValue.value = getDefaultTimeValue();
  checkMobile();
  window.addEventListener('resize', checkMobile);
  syncActiveMenuFromRoute();
  loadPatientList();
  loadData();
  if (activeMenu.value === 'analysis') loadStats();
  if (activeMenu.value === 'ai') loadAiAnalysis();
});

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile);
});
</script>

<style scoped src="./dialysis-manager.css"></style>
