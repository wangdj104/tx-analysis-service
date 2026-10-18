<template>
  <el-dialog :model-value="s.dialog" :title="t('title')" width="min(700px,96vw)" :before-close="beforeClose" :close-on-click-modal="!flow.locked()" :close-on-press-escape="!flow.locked()" :show-close="!flow.locked()" class="stock-policy-dialog">
    <div class="stock-policy-content">
      <p v-if="s.error" role="alert">{{ t(s.error) }}</p>
      <p v-if="s.phase==='submitting'" role="status">{{ t('submitting') }}</p>
      <section v-if="s.phase==='unknown'" role="status" class="policy-notice"><p>{{ t('unknown') }}</p><p>{{ t('retryHelp') }}</p>
        <div class="policy-actions"><button type="button" :disabled="s.reading" @click="flow.checkReceipt()">{{ t('checkReceipt') }}</button><button type="button" :disabled="s.reading" @click="flow.retry()">{{ t('retry') }}</button></div>
      </section>
      <section v-if="s.receipt" class="policy-notice" role="status"><h3>{{ t('savedReceipt') }}</h3><p>{{ t(s.receipt.operation) }} · {{ t('version') }} {{ s.receipt.controlVersion }} · {{ t('recordedAt') }} {{ s.receipt.recordedAt }}</p><p>{{ t('receiptHelp') }}</p></section>
      <section v-if="['conflict','invalid'].includes(s.phase)" class="policy-notice" role="alert"><p>{{ t(s.phase) }}</p><button type="button" :disabled="s.currentPhase==='loading'" @click="flow.reviewAgain()">{{ t('newReview') }}</button></section>
      <h3>{{ t('current') }}</h3><p v-if="s.currentPhase==='loading'" role="status">{{ t('loading') }}</p>
      <p v-else-if="s.currentPhase==='unavailable'" role="status">{{ t('unavailable') }}</p>
      <template v-if="s.currentPhase==='ready' && s.current">
        <p role="status">{{ t(stockNotificationStatus(s.current)) }}<span v-if="s.current.marked"> · {{ t('version') }} {{ s.current.controlVersion }}</span></p>
        <dl><template v-for="[key,value] in facts" :key="key"><dt>{{ t(key) }}</dt><dd>{{ value }}</dd></template></dl>
        <p>{{ t('reviewHelp') }}</p><p v-if="!s.current.canWrite" role="status">{{ t('readOnly') }}</p>
        <p v-if="s.current.marked && !s.current.currentFacts.reviewReady" role="status">{{ t('reviewBlocked') }}</p>
        <template v-if="s.current.marked && s.current.canWrite">
          <label v-if="!s.current.enabled && s.current.currentFacts.reviewReady" class="policy-ack"><input v-model="s.acknowledged" type="checkbox" :disabled="flow.locked() || ['conflict','invalid'].includes(s.phase)"/><span>{{ t('acknowledge') }}</span></label>
          <button v-if="s.current.enabled" type="button" :disabled="!flow.can('DISABLE')" @click="flow.submit('DISABLE')">{{ t('disable') }}</button>
          <button v-else type="button" :disabled="!flow.can('ENABLE')" @click="flow.submit('ENABLE')">{{ t('enable') }}</button>
        </template>
      </template>
      <div class="policy-actions"><button v-if="s.target" type="button" :disabled="s.phase==='submitting' || s.reading || s.currentPhase==='loading'" @click="flow.refreshCurrent()">{{ t('refresh') }}</button><button type="button" :disabled="flow.locked()" @click="flow.close()">{{ t('close') }}</button></div>
      <p>{{ t('legacyHelp') }}</p><p>{{ t('inFlight') }}</p><p>{{ t('historical') }}</p>
    </div>
  </el-dialog>
</template>
<script setup>
import { computed, onBeforeUnmount, toRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useArchiveStockNotification } from '@/composables/useArchiveStockNotification'
import { policySnapshot } from '@/utils/archiveStockNotification'
import { stockNotificationText as t, stockNotificationStatus } from '@/utils/archiveStockNotificationCopy'
const props=defineProps({patientId:[Number,String],active:{type:Boolean,default:true}}),emit=defineEmits(['changed']),router=useRouter()
const flow=useArchiveStockNotification(toRef(props,'patientId'),{onForbidden:context=>emit('changed',{...context,policy:policySnapshot(null)})}),s=flow.state
const display=value=>value==null||value===''?t('unknownValue'):typeof value==='boolean'?t(value?'yes':'no'):String(value)
const facts=computed(()=>{
  const f=s.current?.currentFacts;if(!f)return []
  return [['patient',`${display(f.patientName)} · ${f.patientId}`],['stock',f.stockId],['medication',`${display(f.drugName)} · ${f.medicationId}`],['active',display(f.active)],['quantity',display(f.quantity)],['unit',display(f.unit)],['warningQuantity',display(f.warningQuantity)],['warningDays',display(f.warningDays)],['dailyUse',display(f.dailyUse)],['low',display(f.low)],['ownership',t(f.ownership)],['recipientRule',t(f.recipientRule)],['owner',display(f.ownerId)],...(f.currentRule?[['assigned',display(f.currentRule.assignedUserId)]]:[])]
})
function beforeClose(done){if(flow.close())done()}
const removeGuard=router.beforeEach(()=>flow.protectNavigation())
const removeAfter=router.afterEach((to,from,failure)=>{if(!failure&&to.fullPath!==from.fullPath)flow.retireView()})
watch(()=>props.active,active=>{if(active===false)flow.retireView()},{flush:'sync'})
function beforeContext(event){if(!flow.protectNavigation())event.preventDefault()}
function beforeUnload(event){if(flow.locked()){event.preventDefault();event.returnValue=''}}
window.addEventListener('care-plan-before-context-change',beforeContext);window.addEventListener('beforeunload',beforeUnload)
watch(()=>[s.target,s.currentPhase,s.current],()=>{if(s.target&&s.dialog)emit('changed',{patientId:s.patientId,stockId:s.target,viewId:s.viewId,policy:policySnapshot(s.currentPhase==='ready'?s.current:null)})},{flush:'sync'})
onBeforeUnmount(()=>{removeGuard();removeAfter();flow.dispose();window.removeEventListener('care-plan-before-context-change',beforeContext);window.removeEventListener('beforeunload',beforeUnload)})
defineExpose({open:flow.open,protectNavigation:flow.protectNavigation,ownsProjection:flow.ownsProjection})
</script>
<style scoped>
.stock-policy-content{min-width:0;overflow-wrap:anywhere;line-height:1.6}.stock-policy-content h3{margin-top:1rem}.policy-notice{border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);padding:.75rem;margin:.75rem 0;background:var(--el-fill-color-light)}dl{display:grid;grid-template-columns:minmax(8rem,1fr) minmax(0,2fr);gap:.5rem;margin:1rem 0}dt{font-weight:600}dd{margin:0;overflow-wrap:anywhere}.policy-actions{display:flex;flex-wrap:wrap;gap:.5rem;margin:.75rem 0}button{min-height:48px;min-width:48px;padding:.65rem .85rem;white-space:normal;line-height:1.4;color:inherit;background:var(--el-fill-color-light);border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);cursor:pointer}button:disabled{opacity:.6;cursor:default}.policy-ack{display:flex;align-items:flex-start;gap:.65rem;min-height:48px;padding:.75rem 0}.policy-ack input{min-width:24px;min-height:24px;margin-top:.2rem;flex-shrink:0}@media(max-width:520px){dl{grid-template-columns:1fr;gap:.15rem}dd{margin-bottom:.55rem}.policy-actions button{width:100%}}
</style>
