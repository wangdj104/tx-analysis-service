<template>
  <div class="appearance-control">
    <label class="appearance-selector">
      <span>主题</span>
      <select aria-label="主题" :value="appearanceTheme" @change="changeTheme">
      <option value="platform">平台默认</option>
      <option value="white">纯净白</option>
      <option value="blue">雾蓝</option>
      <option value="mint">薄荷绿</option>
      <option value="sand">暖米色</option>
      <option value="dark">深色</option>
      </select>
    </label>
    <span v-if="storageNotice" class="appearance-storage-notice" role="status">{{ storageNotice }}</span>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { appearanceTheme, setAppearanceTheme } from '@/utils/healthAppearance';
const storageNotice = ref('');
function changeTheme(event) {
  storageNotice.value = setAppearanceTheme(event.target.value) ? '' : "已切换主题；浏览器无法保存，刷新后可能恢复默认。";
}
</script>
