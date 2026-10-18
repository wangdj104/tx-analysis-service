<template>
  <section class="original-import-facts" data-testid="original-import-facts" :aria-label="t(preview ? 'previewHeading' : 'heading')">
    <h3>{{ t(preview ? 'previewHeading' : 'heading') }}</h3>
    <p v-if="view.state === 'notRetained'">{{ t('notRetained') }}</p>
    <p v-else-if="view.state === 'unavailable'" role="alert">{{ t('unavailable') }}</p>
    <template v-else>
      <p>{{ t(preview ? 'previewHelp' : 'help') }}</p>
      <div class="original-import-group">
        <dl v-for="(reading, index) in view.readings" :key="index" class="original-import-reading">
          <dt>{{ reading.label }} · {{ t('value') }}</dt><dd>{{ reading.value }}</dd>
          <dt>{{ t('unit') }}</dt><dd>{{ reading.unit }}</dd>
          <dd v-if="reading.unknownBpUnit" class="original-import-note">{{ t('nativeBp') }}</dd>
        </dl>
        <dl class="original-import-time">
          <dt>{{ t('timestamp') }} · {{ view.selectedField }}</dt><dd>{{ view.timestamp }}</dd>
          <dd>{{ view.basis }} · {{ view.precision }}</dd>
          <template v-if="preview"><dt>{{ t('projection') }}</dt><dd>{{ view.projection }}</dd></template>
          <template v-if="view.deviceUnit !== null"><dt>{{ t('deviceUnit') }}</dt><dd>{{ view.deviceUnit }}</dd></template>
        </dl>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { originalFactsText as t, presentOriginalFacts } from '@/utils/originalImportFacts'
const props = defineProps({ originalImport: { type: Object, default: null }, preview: Boolean })
const view = computed(() => presentOriginalFacts(props.originalImport, props.preview))
</script>

<style scoped>
.original-import-facts{min-width:0;margin:16px 0;padding:16px;border:1px solid var(--line);border-radius:12px;background:var(--paper);color:var(--ink-800);line-height:1.6;overflow-wrap:anywhere}
.original-import-facts h3{margin:0 0 8px;color:var(--ink-800);font-size:1rem}.original-import-facts p{margin:8px 0}.original-import-group{padding:12px;background:var(--surface-subtle);border-radius:8px}.original-import-reading,.original-import-time{display:grid;grid-template-columns:minmax(120px,1fr) minmax(0,2fr);gap:6px 12px;margin:8px 0}.original-import-facts dt{font-weight:600}.original-import-facts dd{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.original-import-note,.original-import-time dd:nth-child(3){grid-column:1/-1}@media(max-width:600px){.original-import-reading,.original-import-time{grid-template-columns:1fr}.original-import-facts{padding:12px}.original-import-facts dd{margin-bottom:6px}}
</style>
