<template>
  <el-aside :width="isMobile ? '240px' : '220px'" class="side-menu" :class="{ 'mobile-show': isMobile && sidebarVisible }">
    <el-menu :default-active="activeKey" @select="handleSelect" background-color="transparent" text-color="#94a3b8" active-text-color="#818cf8">
      <el-menu-item v-if="hasMenu('/health-analysis?tab=complication')" index="complication"><el-icon><Warning /></el-icon><span>Complication Tracking</span></el-menu-item>
      <el-menu-item v-if="hasMenu('/health-analysis?tab=alert')" index="alert"><el-icon><Bell /></el-icon><span>Health Alerts</span></el-menu-item>
      <el-menu-item v-if="hasMenu('/health-analysis?tab=bp-pattern')" index="bp-pattern"><el-icon><TrendCharts /></el-icon><span>Blood Pressure Pattern Analysis</span></el-menu-item>
      <el-menu-item v-if="hasMenu('/health-analysis?tab=nutrition')" index="nutrition"><el-icon><Apple /></el-icon><span>Nutrition Diary</span></el-menu-item>
      <el-menu-item v-if="hasMenu('/health-analysis?tab=nutrition-assessment')" index="nutrition-assessment"><el-icon><DataAnalysis /></el-icon><span>Nutrition Assessment</span></el-menu-item>
      <el-menu-item v-if="hasMenu('/health-analysis?tab=health-report')" index="health-report"><el-icon><DocumentChecked /></el-icon><span>Health Report</span></el-menu-item>
      <el-menu-item v-if="hasMenu('/health-analysis?tab=data-export')" index="data-export"><el-icon><Download /></el-icon><span>Data Export</span></el-menu-item>
      <el-menu-item index="automation"><el-icon><Timer /></el-icon><span>Automated Analysis</span></el-menu-item>
    </el-menu>
  </el-aside>
</template>
<script setup>
import { useRouter } from 'vue-router';
import { Warning, Bell, TrendCharts, Apple, DocumentChecked, Download, DataAnalysis, Timer } from '@element-plus/icons-vue';
import { useMenuPermission } from '@/composables/useMenuPermission';
defineProps({activeKey:{type:String,required:true},isMobile:{type:Boolean,default:false},sidebarVisible:{type:Boolean,default:false}});
const emit=defineEmits(['close']);const router=useRouter();const {hasMenu}=useMenuPermission();
function handleSelect(index){emit('close');router.push({path:'/health-analysis',query:{tab:index}})}
</script>
