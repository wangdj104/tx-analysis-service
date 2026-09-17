<template>
  <nav class="workspace-nav" aria-label="mainnavigation">
    <section v-for="group in groups" :key="group.label" class="workspace-nav__group">
      <p class="workspace-nav__label">{{ group.label }}</p>
      <router-link v-for="item in group.items" :key="item.label" :to="item.entryPath || item.path || item.children?.[0]?.path"
        class="workspace-nav__link" :class="{ 'is-active': activeLabel === item.label }"
        :aria-current="activeLabel === item.label ? 'page' : undefined" @click="$emit('navigate')">
        <el-icon :size="19"><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
        <span v-if="activeLabel === item.label" class="workspace-nav__dot"></span>
      </router-link>
    </section>
  </nav>
</template>

<script setup>
defineProps({ groups: { type: Array, default: () => [] }, activeLabel: { type: String, default: '' } })
defineEmits(['navigate'])
</script>
