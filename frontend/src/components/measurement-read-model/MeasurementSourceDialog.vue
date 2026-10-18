<template>
 <el-dialog :model-value="!!locator" :title="t('source')" width="min(760px, 94vw)" :close-on-click-modal="false" :before-close="beforeClose" destroy-on-close @update:model-value="value => { if (!value) close() }">
  <div class="measurement-read-model" ref="dialogHeading" tabindex="-1">
   <p v-if="locked" role="status">{{t('pendingLeave')}}</p>
   <p v-if="loading" role="status">{{t('sourceLoading')}}</p>
   <p v-if="!loading && flow.state.capabilityPhase==='disabled'" role="status">{{t('sourceFeatureOff')}}</p>
   <p v-else-if="!loading && ['unknown','unavailable'].includes(flow.state.capabilityPhase) && locator" role="alert">{{t('sourceCapabilityUnknown')}}</p>
   <p v-else-if="failed && !ready" role="alert">{{t('sourceUnavailable')}}</p>
   <SourceMeasurementPanel v-if="ready" :flow="flow" @changed="changed" />
   <button type="button" data-action="close-source" :disabled="locked" @click="close()">{{t('sourceClose')}}</button>
  </div>
 </el-dialog>
</template>
<script setup>
import { computed, ref, toRef, watch, nextTick, onUnmounted } from 'vue';
import { useAlertWorkflow } from '@/composables/useAlertWorkflow';
import { useAlertWorkflowNavigation } from '@/composables/useAlertWorkflowNavigation';
import { captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession';
import SourceMeasurementPanel from '@/components/alert-workflow/SourceMeasurementPanel.vue';
import { measurementText as t } from '@/utils/measurementReadModelCopy';
import './measurement-read-model.css';
const props = defineProps({ patientId: Number, locator: Object, contextKey: [String, Number], sourceContext: String, opener: Object, fallback: Object });
const emit = defineEmits(['close', 'changed', 'locked', 'access-denied']);
const flow = useAlertWorkflow(toRef(props, 'patientId'));
useAlertWorkflowNavigation(flow);
const loading = ref(false), failed = ref(false), dialogHeading = ref(null);
const locked = computed(() => ['submitting', 'uncertain'].includes(flow.state.sourcePhase));
const ready = computed(() => !!props.locator && flow.state.capabilityPhase === 'enabled' && (flow.state.source || flow.state.receipt || flow.state.sourceReadPhase !== 'idle'));
let epoch = 0, focusEpoch = 0, disposed = false, sourceOwner = null, deferredChanged = null, closedLocator = null, closeFocus = null, emittingChanged = false;
function clear() { sourceOwner = null; deferredChanged = null; epoch++; flow.reset(); loading.value = false; failed.value = false; }
async function close(force = false, restore = true) {
    if (!props.locator || closedLocator === props.locator) return true;
    if (!force && locked.value) return false;
    const owner = ownsSource() ? sourceOwner : null;
    const opener = props.opener, fallback = props.fallback, patient = props.patientId, auth = owner?.auth, actor = owner?.actor;
    // 成功回执先于重读完成；当前所有者此时主动关闭也应只刷新父页一次。
    const receipt = flow.state.receipt;
    const committed = flow.state.sourcePhase === 'succeeded' && receipt?.sourceType === owner?.locator.sourceType && String(receipt?.sourceId) === String(owner?.locator.sourceId);
    const notifyChanged = !!owner && (deferredChanged === owner || committed);
    closedLocator = props.locator;
    clear();
    const ticket = { version: ++focusEpoch, patient, auth, actor, sourceContext: owner?.sourceContext, refresh: false };
    closeFocus = ticket;
    emit('close');
    if (notifyChanged) {
        // 同一次关闭触发的父级同步刷新可移除按钮；保留本次焦点回退，其他失效仍取消它。
        emittingChanged = true;
        try { emit('changed'); } finally { emittingChanged = false; }
    }
    await nextTick();
    if (restore && owner && !disposed && ticket.version === focusEpoch && patient === props.patientId && ticket.sourceContext === props.sourceContext && !props.locator && actor === globalThis.localStorage?.getItem('userId') && isAuthSessionCurrent(auth)) {
        const target = [opener, fallback].find(node => node?.isConnected && !node.matches?.(':disabled') && !node.closest?.('[hidden],[inert]') && node.getClientRects?.().length !== 0);
        target?.focus?.({ preventScroll: true });
    }
    if (closeFocus === ticket) closeFocus = null;
    return true;
}
async function beforeClose(done) { if (await close()) done(); }
function changed() {
    if (!ownsSource() || flow.state.sourcePhase !== 'succeeded') return;
    deferredChanged = sourceOwner;
    // 写入已成功、重读未成功时保留回执；只有当前所有者明确关闭才通知父级刷新。
    if (flow.state.receipt && flow.state.sourceReadPhase === 'unavailable') return;
    close(true);
}
watch(locked, value => emit('locked', value), { immediate: true, flush: 'sync' });
function ownsSource() { return !disposed && sourceOwner && sourceOwner.version === epoch && sourceOwner.patient === props.patientId && sourceOwner.locator === props.locator && sourceOwner.contextKey === props.contextKey && sourceOwner.sourceContext === props.sourceContext && sourceOwner.actor === globalThis.localStorage?.getItem('userId') && isAuthSessionCurrent(sourceOwner.auth); }
// 原来源及其历史读取失权时，同步撤去当前页；旧请求不能影响新所有者。
watch(() => flow.state.capabilityPhase, phase => {
    if (phase !== 'denied' || !ownsSource()) return;
    emit('access-denied');
    invalidate();
}, { flush: 'sync' });
// 原面板重读恢复后，外层提示也必须随当前读取状态更新。
watch(() => flow.state.sourceReadPhase, phase => {
    if (!ownsSource()) return;
    if (['loading', 'succeeded'].includes(phase)) failed.value = false;
    else if (phase === 'unavailable') failed.value = true;
}, { flush: 'sync' });
watch(() => [props.patientId, props.contextKey, props.sourceContext], ([patient, , sourceContext], [previousPatient, , previousSourceContext]) => {
    if (closeFocus?.refresh && sourceContext === previousSourceContext && sourceContext === closeFocus.sourceContext && patient === previousPatient && patient === closeFocus.patient && closeFocus.actor === globalThis.localStorage?.getItem('userId') && isAuthSessionCurrent(closeFocus.auth) && closeFocus.version === focusEpoch) { clear(); return; }
    invalidate();
}, { flush: 'sync' });
watch(() => props.locator, async locator => {
    closedLocator = null;
    clear();
    if (!locator || !props.patientId) return;
    focusEpoch++;
    const version = epoch, patient = props.patientId;
    sourceOwner = { version, patient, locator, contextKey: props.contextKey, sourceContext: props.sourceContext, auth: captureAuthSession(), actor: globalThis.localStorage?.getItem('userId') };
    const owns = () => ownsSource() && version === epoch && patient === props.patientId && props.locator === locator;
    loading.value = true;
    const capability = await flow.loadCapabilities();
    if (!owns()) return;
    if (capability.status !== 'enabled') { loading.value = false; failed.value = true; return; }
    const result = await flow.readSource(locator.sourceType, locator.sourceId);
    if (!owns()) return;
    loading.value = false;
    failed.value = result.status !== 'succeeded';
    await nextTick();
    if (owns()) dialogHeading.value?.focus?.({ preventScroll: true });
}, { immediate: true, flush: 'sync' });
function invalidate({ sourceContext } = {}) {
    // 来源切换是明确的边界，包含同栈切走再切回；不与普通刷新共用焦点例外。
    if (sourceContext === undefined && emittingChanged && closeFocus?.version === focusEpoch && closeFocus.sourceContext === props.sourceContext && closeFocus?.patient === props.patientId && closeFocus.actor === globalThis.localStorage?.getItem('userId') && isAuthSessionCurrent(closeFocus.auth)) { closeFocus.refresh = true; clear(); return; }
    focusEpoch++; clear(); if (props.locator && closedLocator !== props.locator) { closedLocator = props.locator; emit('close'); }
}
defineExpose({ invalidate });
function sessionChanged() { invalidate(); }
function storageChanged(event) { if (event.key == null || ['token','userId','username','realName','permissionSession','userMenus','userRoleCodes'].includes(event.key)) sessionChanged(); }
globalThis.window?.addEventListener('auth-session-cleared', sessionChanged);
globalThis.window?.addEventListener('auth-session-changed', sessionChanged);
globalThis.window?.addEventListener('storage', storageChanged);
onUnmounted(() => { disposed = true; clear(); globalThis.window?.removeEventListener('auth-session-cleared', sessionChanged); globalThis.window?.removeEventListener('auth-session-changed', sessionChanged); globalThis.window?.removeEventListener('storage', storageChanged); });
</script>
