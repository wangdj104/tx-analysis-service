<template>
  <el-aside
    :width="isMobile ? '240px' : '220px'"
    class="side-menu"
    :class="{ 'mobile-show': isMobile && sidebarVisible }"
  >
    <el-menu
      :default-active="activeKey"
      @select="handleSelect"
      background-color="transparent"
      text-color="#94a3b8"
      active-text-color="#818cf8"
    >
      <el-menu-item index="data">
        <el-icon><Document /></el-icon>
        <span>数据录入</span>
      </el-menu-item>
      <el-menu-item index="analysis">
        <el-icon><TrendCharts /></el-icon>
        <span>趋势分析</span>
      </el-menu-item>
      <el-menu-item index="ai">
        <el-icon><Cpu /></el-icon>
        <span>AI 分析</span>
      </el-menu-item>
      <el-menu-item index="dry-weight">
        <el-icon><ScaleToOriginal /></el-icon>
        <span>干体重管理</span>
      </el-menu-item>
    </el-menu>
  </el-aside>
</template>

<script setup>
import { useRouter } from 'vue-router';
import { Document, TrendCharts, Cpu, ScaleToOriginal } from '@element-plus/icons-vue';

defineProps({
  activeKey: { type: String, required: true },
  isMobile: { type: Boolean, default: false },
  sidebarVisible: { type: Boolean, default: false }
});

const emit = defineEmits(['close']);

const router = useRouter();

function handleSelect(index) {
  emit('close');
  if (index === 'dry-weight') {
    router.push('/dry-weight');
  } else if (index === 'data') {
    router.push('/dialysis');
  } else {
    router.push({ path: '/dialysis', query: { tab: index } });
  }
}
</script>
