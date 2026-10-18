<template>
  <section class="record-snapshot">
    <dl class="record-fields">
      <div v-for="field in headerFields" :key="field"><dt>{{ t.fields[field] }}</dt><dd><span class="exact-value">{{ exact(snapshot.record[field]) }}</span></dd></div>
    </dl>
    <h3>{{ t.items }}</h3>
    <p v-if="!snapshot.items.length">{{ t.noItems }}</p>
    <article v-for="item in snapshot.items" :key="item.id" class="record-item">
      <h4>{{ t.itemId }} {{ item.id }}</h4>
      <dl class="record-fields">
        <div v-for="field in itemFields" :key="field"><dt>{{ t.fields[field] }}</dt><dd><span class="exact-value">{{ exact(item[field]) }}</span></dd></div>
        <div><dt>{{ t.flag }}</dt><dd>{{ flag(item.isAbnormal) }}</dd></div>
        <div><dt>{{ t.fields.aiConfidence }}</dt><dd>{{ exact(item.aiConfidence) }}</dd></div>
      </dl>
    </article>
    <details>
      <summary>{{ t.readonly }}</summary>
      <dl class="record-fields">
        <div v-for="field in metadataFields" :key="field"><dt>{{ t.fields[field] }}</dt><dd><span class="exact-value">{{ exact(snapshot.record[field]) }}</span></dd></div>
      </dl>
    </details>
    <h3>{{ t.attachments }}</h3>
    <ul v-if="snapshot.attachments.length"><li v-for="file in snapshot.attachments" :key="file.id">{{ file.fileName }} · {{ file.fileType }} · {{ file.fileSize ?? t.null }} {{ t.bytes }}</li></ul>
    <p v-else>{{ t.noAttachments }}</p>
  </section>
</template>

<script setup>
import { medicalRecordCopy as t } from './medicalRecordCorrectionCopy'
import { HEADER_FIELDS, ITEM_FIELDS } from '@/composables/useMedicalRecordCorrection'
defineProps({ snapshot: { type: Object, required: true } })
const headerFields = Object.keys(HEADER_FIELDS), itemFields = Object.keys(ITEM_FIELDS)
const metadataFields = ['patientName', 'patientId', 'userId', 'recordType', 'sourceType', 'sourceExternalId', 'confidenceScore', 'createdAt', 'updatedAt', 'verifiedBy', 'verifiedAt', 'verificationNote']
const exact = value => value == null ? t.null : value === '' ? t.empty : typeof value === 'string' && !value.trim() ? `${t.whitespace} (${[...value].length}) «${value}»` : String(value)
const flag = value => ({ '-1': t.lowFlag, 0: t.normalFlag, 1: t.highFlag })[value] || t.unknownFlag
</script>

<style scoped>
.record-snapshot { min-width: 0; line-height: 1.6; overflow-wrap: anywhere; }
.record-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 20px; }
.record-fields > div { min-width: 0; }
.record-fields dt { font-weight: 600; color: var(--ink-700); }
.record-fields dd { margin: 4px 0 0; }
.exact-value { white-space: pre-wrap; overflow-wrap: anywhere; }
.record-item { margin: 12px 0; padding: 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-subtle); }
.record-item h4 { margin: 0 0 10px; }
summary { min-height: 44px; padding: 10px 0; cursor: pointer; font-weight: 600; }
summary:focus-visible { outline: 3px solid var(--care-600); outline-offset: 2px; }
:global(body.care-senior) summary { min-height: 48px; }
@media (max-width: 600px) { .record-fields { grid-template-columns: minmax(0, 1fr); } }
</style>
