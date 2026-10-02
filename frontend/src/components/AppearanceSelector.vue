<template>
  <div class="appearance-control">
    <label class="appearance-selector">
      <span>Theme</span>
      <select aria-label="Theme" :value="appearanceTheme" @change="changeTheme">
      <option value="platform">Platform default</option>
      <option value="white">Clean white</option>
      <option value="blue">Mist blue</option>
      <option value="mint">Mint</option>
      <option value="sand">Warm beige</option>
      <option value="dark">Dark</option>
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
  storageNotice.value = setAppearanceTheme(event.target.value) ? '' : "Theme applied for this visit. Browser storage is unavailable, so it may reset after refresh.";
}
</script>
