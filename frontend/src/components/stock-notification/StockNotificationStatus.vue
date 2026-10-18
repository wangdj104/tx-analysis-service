<template>
  <div class="stock-policy-status"><p role="status">{{ t('status') }}: {{ t(status) }}</p>
    <button v-if="policy?.schemaStatus==='READY' && policy.marked===true" type="button" @click="$emit('review',stockId)">{{ t(policy.canWrite ? policy.enabled ? 'reviewDisable' : 'reviewEnable' : 'view') }}</button>
  </div>
</template>
<script setup>
import { computed } from 'vue'
import { stockNotificationText as t, stockNotificationStatus } from '@/utils/archiveStockNotificationCopy'
const props=defineProps({policy:Object,stockId:[Number,String]});defineEmits(['review'])
const status=computed(()=>stockNotificationStatus(props.policy))
</script>
<style scoped>
.stock-policy-status{min-width:0;overflow-wrap:anywhere}.stock-policy-status p{white-space:normal;line-height:1.5;margin:.3rem 0}button{min-height:48px;min-width:48px;padding:.65rem .85rem;white-space:normal;line-height:1.4;color:inherit;background:var(--el-fill-color-light);border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);cursor:pointer}
</style>
