<template>
  <div class="appearance-control">
    <el-popover v-if="compact" v-model:visible="pickerOpen" trigger="click" role="dialog" title="Appearance settings" placement="bottom-end" :width="240" popper-class="appearance-popover" @after-enter="themeSelect?.focus()">
      <template #reference>
        <button ref="toggleButton" type="button" class="workspace-icon-button appearance-toggle" aria-label="Appearance settings" title="Appearance settings" :aria-expanded="pickerOpen"><el-icon :size="20"><Brush /></el-icon></button>
      </template>
      <label class="appearance-selector appearance-selector--popover" @keydown.esc.stop.prevent="closePicker">
        <span>Theme</span>
        <select ref="themeSelect" aria-label="Theme" :aria-describedby="storageNotice ? storageNoticeId : undefined" :value="appearanceTheme" @change="changeTheme"><option v-for="theme in themes" :key="theme.value" :value="theme.value">{{ theme.label }}</option></select>
      </label>
      <span v-if="storageNotice" :id="storageNoticeId" class="appearance-storage-notice appearance-storage-notice--popover" role="status">{{ storageNotice }}</span>
    </el-popover>
    <label v-else class="appearance-selector">
      <span>Theme</span>
      <select aria-label="Theme" :aria-describedby="storageNotice ? storageNoticeId : undefined" :value="appearanceTheme" @change="changeTheme"><option v-for="theme in themes" :key="theme.value" :value="theme.value">{{ theme.label }}</option></select>
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
const themes = [{"value":"platform","label":"Platform default"},{"value":"white","label":"Clean white"},{"value":"blue","label":"Mist blue"},{"value":"mint","label":"Mint"},{"value":"sand","label":"Warm beige"},{"value":"dark","label":"Dark"}];
function closePicker() { pickerOpen.value = false; nextTick(() => toggleButton.value?.focus()); }
const storageNotice = ref('');
function changeTheme(event) {
  storageNotice.value = setAppearanceTheme(event.target.value) ? '' : "Theme applied for this visit. Browser storage is unavailable, so it may reset after refresh.";
}
</script>
