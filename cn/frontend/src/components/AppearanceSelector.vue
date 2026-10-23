<template>
  <div class="appearance-control">
    <el-popover v-if="compact" v-model:visible="pickerOpen" trigger="click" role="dialog" title="外观设置" placement="bottom-end" :width="240" popper-class="appearance-popover" @after-enter="themeSelect?.focus()">
      <template #reference>
        <button ref="toggleButton" type="button" class="workspace-icon-button appearance-toggle" aria-label="外观设置" title="外观设置" :aria-expanded="pickerOpen"><el-icon :size="20"><Brush /></el-icon></button>
      </template>
      <label class="appearance-selector appearance-selector--popover" @keydown.esc.stop.prevent="closePicker">
        <span>主题</span>
        <select ref="themeSelect" aria-label="主题" :aria-describedby="storageNotice ? storageNoticeId : undefined" :value="appearanceTheme" @change="changeTheme"><option v-for="theme in themes" :key="theme.value" :value="theme.value">{{ theme.label }}</option></select>
      </label>
      <span v-if="storageNotice" :id="storageNoticeId" class="appearance-storage-notice appearance-storage-notice--popover" role="status">{{ storageNotice }}</span>
    </el-popover>
    <label v-else class="appearance-selector">
      <span>主题</span>
      <select aria-label="主题" :aria-describedby="storageNotice ? storageNoticeId : undefined" :value="appearanceTheme" @change="changeTheme"><option v-for="theme in themes" :key="theme.value" :value="theme.value">{{ theme.label }}</option></select>
    </label>
    <span v-if="storageNotice && !compact" :id="storageNoticeId" class="appearance-storage-notice" role="status">{{ storageNotice }}</span>
  </div>
</template>

<script setup>
import { nextTick, ref, useId, watch } from 'vue';
import { Brush } from '@element-plus/icons-vue';
import { appearanceTheme, setAppearanceTheme } from '@/utils/healthAppearance';
const props = defineProps({ compact: { type: Boolean, default: false } });
const pickerOpen = ref(false), themeSelect = ref(null), toggleButton = ref(null);
const storageNoticeId = `appearance-storage-notice-${useId()}`;
watch(() => props.compact, compact => { if (!compact) pickerOpen.value = false; }, { flush: 'sync' });
const themes = [{"value":"platform","label":"平台默认"},{"value":"white","label":"纯净白"},{"value":"blue","label":"雾蓝"},{"value":"mint","label":"薄荷绿"},{"value":"sand","label":"暖米色"},{"value":"dark","label":"深色"}];
function closePicker() { pickerOpen.value = false; nextTick(() => toggleButton.value?.focus()); }
const storageNotice = ref('');
function changeTheme(event) {
  storageNotice.value = setAppearanceTheme(event.target.value) ? '' : "已切换主题；浏览器无法保存，刷新后可能恢复默认。";
}
</script>
