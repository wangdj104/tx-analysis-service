<template>
  <el-container class="module-page health-analysis-manager">
    <el-main class="main-content">
      <div class="page-inner">
        <div class="page-header">
          <div class="top-bar">
            <div class="left">
              <div>
                <h1>{{ currentTitle }}</h1>
                <p class="subtitle">{{ currentSubtitle }}</p>
              </div>
            </div>
          </div>
        </div>

        <div v-if="activeTab === 'complication'" class="content-panel">
          <ComplicationTrackingManager />
        </div>
        <div v-if="activeTab === 'alert'" class="content-panel">
          <AlertManager />
        </div>
        <div v-if="activeTab === 'bp-pattern'" class="content-panel">
          <BpPatternManager />
        </div>
        <div v-if="activeTab === 'nutrition'" class="content-panel">
          <NutritionDiaryManager />
        </div>
        <div v-if="activeTab === 'nutrition-assessment'" class="content-panel">
          <NutritionAssessmentManager />
        </div>
        <div v-if="activeTab === 'health-report'" class="content-panel">
          <HealthReportManager />
        </div>
        <div v-if="activeTab === 'data-export'" class="content-panel">
          <DataExportManager />
        </div>
        <div v-if="activeTab === 'automation'" class="content-panel">
          <HealthAutomationManager />
        </div>
      </div>
    </el-main>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import ComplicationTrackingManager from '@/views/ComplicationTrackingManager.vue';
import AlertManager from '@/views/AlertManager.vue';
import BpPatternManager from '@/views/BpPatternManager.vue';
import NutritionDiaryManager from '@/views/NutritionDiaryManager.vue';
import NutritionAssessmentManager from '@/views/NutritionAssessmentManager.vue';
import HealthReportManager from '@/views/HealthReportManager.vue';
import DataExportManager from '@/views/DataExportManager.vue';
import HealthAutomationManager from '@/views/HealthAutomationManager.vue';

const route = useRoute();

const activeTab = ref('complication');

const tabInfo = {
  complication: { title: '并发症跟踪', subtitle: '记录透析相关并发症，持续跟踪严重程度与转归。' },
  alert: { title: '健康告警', subtitle: '配置阈值规则，并记录异常结果的处置过程。' },
  'bp-pattern': { title: '血压规律分析', subtitle: '分析血压波动、体位变化及其与体重增长的关系。' },
  nutrition: { title: '营养日记', subtitle: '记录每日摄入，跟踪营养和液体摄入规律。' },
  'nutrition-assessment': { title: '营养评估', subtitle: '结合饮食、人体测量和检验数据筛查营养风险。' },
  'health-report': { title: '健康报告', subtitle: '生成涵盖透析、生命体征和营养状况的综合报告。' },
  'data-export': { title: '数据导出', subtitle: '导出选定健康数据，供医生查看或个人归档。' },
  automation: { title: '自动健康分析', subtitle: '创建定时分析草稿，分享前始终需要人工审核。' }
};

const currentTitle = computed(() => tabInfo[activeTab.value]?.title || '健康分析');
const currentSubtitle = computed(() => tabInfo[activeTab.value]?.subtitle || '');

function resolveTab(query) {
  const q = (query || '').toLowerCase();
  if (q === 'alert' || q === 'bp-pattern' || q === 'nutrition' || q === 'nutrition-assessment' || q === 'health-report' || q === 'data-export' || q === 'complication' || q === 'automation') return q;
  return 'complication';
}

onMounted(() => {
  activeTab.value = resolveTab(route.query.tab);
});

watch(() => route.query.tab, (newTab) => {
  activeTab.value = resolveTab(newTab);
});
</script>

<style scoped src="@/styles/module-layout.css"></style>

<style scoped>
/* ============ sidebar (Phoneenddrawer)  ============ */
.health-analysis-manager .menu-toggle {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: #fff;
  border: 1px solid var(--app-border, #e2e8f0);
  cursor: pointer;
  flex-shrink: 0;
  font-size: 18px;
  color: #475569;
  box-shadow: var(--app-shadow-xs, 0 1px 2px rgb(15 23 42 / 6%));
}

/* penetrateto childcomponent HealthAnalysisModuleSidebar within sidebar */
.health-analysis-manager :deep(.side-menu) {
  background: linear-gradient(180deg, #f8f9ff 0%, #f0f4ff 60%, #f5f7fb 100%);
  box-shadow: 4px 0 24px rgb(15 23 42 / 8%);
  border-right: 1px solid rgb(226 232 240 / 0.8);
  transition: left 0.28s ease;
}

.health-analysis-manager :deep(.side-menu .el-menu) {
  border-right: none;
  background: transparent !important;
  padding: 12px 6px 8px;
}

.health-analysis-manager :deep(.side-menu .el-menu-item) {
  border-radius: 10px;
  margin: 2px 0;
  height: 44px;
  line-height: 44px;
  font-weight: 500;
  transition: all 0.2s ease;
}

.health-analysis-manager :deep(.side-menu .el-menu-item:hover) {
  background: rgb(99 102 241 / 8%) !important;
}

.health-analysis-manager :deep(.side-menu .el-menu-item.is-active) {
  background: linear-gradient(90deg, rgb(79 106 246 / 15%) 0%, rgb(99 102 241 / 5%) 100%) !important;
  color: #4f6af6 !important;
  font-weight: 600;
  box-shadow: inset 3px 0 0 0 #4f6af6;
}

.health-analysis-manager .sidebar-mask {
  position: fixed;
  inset: 52px 0 0 0;
  background: rgb(15 23 42 / 45%);
  backdrop-filter: blur(2px);
  z-index: 1001;
}

@media (max-width: 768px) {
  .health-analysis-manager :deep(.side-menu) {
    position: fixed !important;
    left: -240px;
    top: 52px;
    bottom: 0;
    z-index: 1002;
    width: 240px !important;
  }

  .health-analysis-manager :deep(.side-menu.mobile-show) {
    left: 0;
  }

  .health-analysis-manager .page-inner {
    padding: 12px 14px 24px;
  }

  .health-analysis-manager .page-header {
    padding: 14px 16px;
  }

  /* childmodulecontentpanelPhoneendwithinmargin */
  .health-analysis-manager :deep(.module-page .content-panel) {
    padding: 12px 14px !important;
  }
}

/* childmodulenestedfitconfigure: letchildmoduleselffitshouldcontainerpagelayout */

/* childmodule-pagenot againfill the viewporthighlevel, changefor selffitshould */
:deep(.module-page) {
  height: auto !important;
  background: transparent !important;
  border: none !important;
}

/* childmodule el-containernot againfixedsethighlevel */
:deep(.module-page > .el-container),
:deep(.module-page.el-container) {
  height: auto !important;
  min-height: 0 !important;
}

/* childmoduleel-maingoremovepadding and overflowlimit */
:deep(.module-page .el-main) {
  padding: 0 !important;
  overflow: visible !important;
  height: auto !important;
}

/* childmodule page-innerCancelmax-width and padding (outsidelayeralready has )  */
:deep(.module-page .page-inner) {
  max-width: none !important;
  padding: 0 !important;
}

/* hidechildmoduleyourself page-header (containerpagealready has systemoneheader)  */
:deep(.module-page .page-header) {
  display: none !important;
}

/* childmodulecontent-panelnot againneedneedanimation (avoiddelay)  */
:deep(.module-page .content-panel) {
  animation: none !important;
}
</style>
