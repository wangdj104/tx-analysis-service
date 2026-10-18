<template>
  <section v-if="active && patientId" class="visit-follow-up" :aria-label="t('title')">
    <header><h2 tabindex="-1">{{ t('title') }}</h2><span>{{ t('patient') }} #{{ patientId }}</span><button type="button" :disabled="flow.state.phase==='submitting'" @click="refresh">{{ t('refresh') }}</button></header>
    <p v-if="flow.state.readPhase==='loading'" role="status">{{ t('loading') }}</p>
    <p v-if="flow.state.readPhase==='unavailable' || flow.state.capabilityPhase==='unavailable'" role="alert">{{ t(flow.state.error) }}</p>
    <p v-if="flow.state.capabilities?.legacyVisitsAllowed" role="status">{{ t('legacy') }}</p>
    <p v-if="flow.state.capabilities?.schemaStatus==='REMINDER_EXTENSION_REQUIRED'" role="status">{{ t('REMINDER_EXTENSION_REQUIRED') }}</p>
    <button v-if="flow.state.capabilities?.allowedCommands?.includes('CREATE_VISIT')" type="button" :disabled="flow.locked() || !!draft" @click="beginCreate">{{ t('create') }}</button>
    <template v-if="!sourceId">
      <p v-if="flow.state.readPhase==='ready' && !flow.state.items.length">{{ t('empty') }}</p>
      <ul class="visit-cards"><li v-for="row in flow.state.items" :key="row.sourceId"><p>#{{ row.sourceId }} · {{ wall(row.visitedWall) }}</p><p>{{ t(row.nativeStatus) }} · {{ t(row.currentDisposition) }}</p><button type="button" :disabled="flow.locked() || !!draft" @click="open(row.sourceId,$event)">{{ t('open') }}</button></li></ul>
      <div class="visit-controls"><button type="button" :disabled="flow.locked() || !!draft" @click="flow.loadPage(false)">{{ t('first') }}</button><button v-if="flow.state.nextCursor" type="button" :disabled="flow.locked() || !!draft" @click="flow.loadPage(true)">{{ t('next') }}</button></div>
    </template>
    <!-- 已保存和未知原包独立于正文，读取503不能隐藏结果；撤权则由会话清空。 -->
    <p v-if="flow.state.phase==='succeeded'" ref="outcomeHeading" tabindex="-1" role="status">{{ t('saved') }} #{{ flow.state.receipt?.sourceId }} · v{{ flow.state.receipt?.version }}</p>
    <p v-if="flow.state.phase==='submitting'" role="status">{{ t('submitting') }}</p>
    <section v-if="['unknown','conflict','invalid'].includes(flow.state.phase)" class="visit-notice" role="alert">
      <p>{{ t(flow.state.phase) }}</p><p v-if="flow.state.error">{{ t(flow.state.error) }}</p>
      <div class="visit-controls"><button v-if="flow.state.phase==='unknown'" type="button" @click="retry">{{ t('retry') }}</button><button type="button" @click="flow.compare">{{ t('compare') }}</button><button v-if="flow.state.phase!=='unknown'" type="button" :disabled="flow.state.phase==='conflict' && (!flow.state.compared || !detail?.allowedCommands?.includes(flow.state.operation))" @click="flow.editAfterComparison">{{ t('editCompared') }}</button></div>
      <!-- 已核当前状态的确定冲突可明确放弃旧草稿；未知结果和提交中仍不得丢弃原包。 -->
      <button v-if="flow.state.phase==='conflict' && flow.state.compared && draft" type="button" :disabled="planEditorVisible || flow.locked()" @click="cancelDraft">放弃已核对的旧草稿</button>
      <details v-if="flow.state.submitted"><summary>{{ t('submitted') }}</summary><p>{{ t(flow.state.submitted.operation) }} · #{{ flow.state.submitted.sourceId || '—' }} · v{{ flow.state.submitted.expectedVersion }}</p><pre>{{ JSON.stringify(flow.state.submitted.body,null,2) }}</pre></details>
      <p v-if="flow.state.compared && detail">{{ t('latest') }}: #{{ detail.sourceId || '—' }} · v{{ detail.version }} · {{ t(detail.currentDisposition) }}</p>
    </section>
    <button v-if="flow.state.readPhase==='unavailable' && flow.hasSelection()" type="button" :disabled="flow.state.phase==='submitting'" @click="flow.reloadSelected">{{ t('reloadSource') }}</button>
    <article v-if="detail?.sourceId" class="visit-detail">
      <h3 ref="detailHeading" tabindex="-1">#{{ detail.sourceId }} · {{ t(detail.nativeStatus) }}</h3>
      <p>{{ t('clinician') }}: {{ detail.doctorName || `#${detail.doctorUserId}` }}</p><p>{{ t('originalWall') }}: {{ wall(detail.visitedWall) }}</p>
      <p v-if="detail.actualTimeKind==='KNOWN'">{{ t('actual') }}: {{ detail.actualAtUtc }} · {{ detail.actualOffset }}</p><p v-else>{{ t('unknownActual') }}</p>
      <dl><dt>{{ t('diagnosis') }}</dt><dd>{{ detail.diagnosisSummary || '—' }}</dd><dt>{{ t('treatment') }}</dt><dd>{{ detail.treatmentSummary || '—' }}</dd><dt>{{ t('advice') }}</dt><dd>{{ detail.followUpAdvice || '—' }}</dd><dt>{{ t('followUp') }}</dt><dd>{{ wall(detail.followUpWall) }}</dd></dl>
      <p v-if="detail.originAppointment?.available">{{ t('origin') }}: {{ t(detail.originAppointment.sourceKind) }} #{{ detail.originAppointment.sourceId }} · {{ wall(detail.originAppointment.startWall) }}</p><p v-else-if="detail.originAppointment">{{ t('origin') }}: {{ t('unavailable') }}</p>
      <h4>{{ t('decision') }}</h4><p>{{ t(detail.currentDisposition) }}</p>
      <section v-if="detail.decision"><p v-if="!detail.decision.current" class="visit-notice">{{ t('historical') }}: {{ t(detail.disposition) }}</p><p>{{ t('actor') }}: {{ detail.decision.actorName || `#${detail.decision.actorId}` }} · {{ detail.decision.recordedAt }}</p><p class="visit-text">{{ detail.decision.reason || '—' }}</p></section>
      <section v-if="detail.actions && (detail.actions.items?.length || !detail.actions.available)"><h4>{{ t('actions') }}</h4><p>{{ t(detail.actions.status) }}</p><p>{{ t('notClinicalOutcome') }}</p>
        <ol class="visit-action-list"><li v-for="action in detail.actions.items" :key="action.actionId"><h5>{{ t(action.kind) }} · #{{ action.actionId }} · {{ t(action.status) }}</h5><p class="visit-text">{{ action.instruction }}</p><p>{{ t('assigned') }}: {{ action.assignedName || `#${action.assignedUserId}` }} · {{ t('due') }}: {{ action.dueAt }}</p>
          <section v-for="field in ['submission','returned','confirmation']" :key="field"><template v-if="action[field]"><h6>{{ t(field) }}</h6><p>{{ t('actor') }}: {{ action[field].actorName || `#${action[field].actorId}` }}<template v-if="action[field].entryMode"> · {{ t(action[field].entryMode) }}</template></p><p v-if="action[field].occurredAt">{{ t('actual') }}: {{ action[field].occurredAt }}</p><p>{{ t('recordedAt') }}: {{ action[field].recordedAt }}</p><p class="visit-text">{{ action[field].note || (field==='confirmation'?t('confirmedNoNote'):'—') }}</p></template></section>
          <router-link :to="actionLink(action)">{{ t('openAction') }}</router-link>
        </li></ol>
      </section>
      <p v-if="!detail.allowedCommands?.length">{{ t('readOnly') }}</p><div v-if="!draft" class="visit-controls"><button v-for="operation in detail.allowedCommands" :key="operation" type="button" :disabled="flow.locked()" @click="begin(operation,$event)">{{ t(operation) }}</button></div>
      <button type="button" :disabled="flow.state.phase==='submitting'" @click="flow.loadEvents(false)">{{ t('history') }}</button><p v-if="flow.state.eventPhase==='unavailable'" role="alert">{{ t('REQUEST_FAILED') }}</p><ol><li v-for="event in flow.state.events" :key="event.id">{{ t(event.event_type) }} · {{ event.actor_name || `#${event.actor_id}` }} · {{ event.recorded_at }}<p class="visit-text">{{ event.reason }}</p></li></ol><button v-if="flow.state.eventCursor" type="button" @click="flow.loadEvents(true)">{{ t('next') }}</button>
    </article>
    <form v-if="draft" class="visit-form" @submit.prevent="submit"><h3 ref="formHeading" tabindex="-1">{{ t(flow.state.operation) }}</h3><p v-if="formError" role="alert">{{ t(formError) }}</p>
      <fieldset :disabled="planEditorVisible || flow.locked() || ['conflict','invalid'].includes(flow.state.phase)">
        <template v-if="flow.state.operation==='CREATE_VISIT'">
          <p>{{ t('actualHelp') }}</p><div class="visit-fields"><label>{{ t('actualWall') }}<input v-model="draft.actualWall" type="text" placeholder="2026-10-17T09:30:00.123456" autocomplete="off" required /></label><label>{{ t('offset') }}<input v-model="draft.actualOffset" type="text" placeholder="+08:00" autocomplete="off" required /></label></div>
          <label>{{ t('diagnosis') }}<textarea v-model="draft.diagnosisSummary" rows="3" /></label><label>{{ t('treatment') }}<textarea v-model="draft.treatmentSummary" rows="3" /></label><label>{{ t('advice') }}<textarea v-model="draft.followUpAdvice" rows="3" /></label><label>{{ t('followUp') }}<input v-model="draft.followUpAt" type="datetime-local" step="1" /></label>
          <fieldset class="visit-origin"><legend>{{ t('origin') }}</legend><p>{{ t('originHelp') }}</p><div class="visit-fields"><label>{{ t('originKind') }}<select v-model="draft.originKind" @change="clearOrigin"><option value="">{{ t('choose') }}</option><option v-for="kind in ['CARE_ITEM_APPOINTMENT','CARE_APPOINTMENT']" :key="kind" :value="kind">{{ t(kind) }}</option></select></label><label>{{ t('originId') }}<input v-model="draft.originId" inputmode="numeric" pattern="[1-9][0-9]*" @input="clearOrigin" /></label></div><button type="button" :disabled="!draft.originId || !draft.originKind" @click="loadOrigin">{{ t('loadOrigin') }}</button><p v-if="flow.state.originPhase==='unavailable'" role="alert">{{ t('REQUEST_FAILED') }}</p><p v-if="flow.state.target">{{ t(flow.state.target.sourceKind) }} #{{ flow.state.target.sourceId }} · v{{ flow.state.target.version }} · {{ wall(flow.state.target.startWall) }}</p><label v-if="draft.originId" class="visit-check"><input v-model="draft.confirmOrigin" type="checkbox" :disabled="!flow.state.target" required /><span>{{ t('confirmOrigin') }}</span></label></fieldset>
        </template>
        <p v-else-if="flow.state.operation==='PUBLISH_VISIT'">{{ t('publishHelp') }}</p>
        <template v-else><p>{{ t('decisionHelp') }}</p><label>{{ t('decision') }}<select v-model="draft.disposition" required @change="decisionChanged"><option value="">{{ t('choose') }}</option><option value="NO_FOLLOW_UP">{{ t('NO_FOLLOW_UP') }}</option><option value="ACTIONS_REQUIRED" :disabled="!detail?.planBindingAvailable">{{ t('ACTIONS_REQUIRED') }}</option></select></label>
          <label>{{ t('reason') }}<textarea v-model="draft.reason" rows="3" maxlength="1000" :required="flow.state.operation==='REVISE_DISPOSITION' || draft.disposition==='NO_FOLLOW_UP'" /></label>
          <section v-if="draft.disposition==='ACTIONS_REQUIRED'"><p>{{ t('newPlan') }}</p><button type="button" @click="planEditorVisible=true">{{ t('openPlans') }}</button><h4>{{ t('candidates') }}</h4><p>{{ t('maxLinks') }}</p><p v-if="flow.state.candidatePhase==='unavailable'" role="alert">{{ t('REQUEST_FAILED') }}</p><p v-if="flow.state.candidatePhase==='loading'" role="status">{{ t('loading') }}</p><p v-if="flow.state.candidatePhase==='ready' && !flow.state.candidates.length">{{ t('noCandidates') }}</p><ul class="visit-cards"><li v-for="candidate in flow.state.candidates" :key="candidate.actionId"><p>{{ t('plan') }} #{{ candidate.planId }} · {{ t('revision') }} #{{ candidate.revisionId }} · {{ t('action') }} #{{ candidate.actionId }}</p><p>{{ candidate.instruction }}</p><p>{{ t('assigned') }}: {{ candidate.assignedName || `#${candidate.assignedUserId}` }} · {{ candidate.dueAt }}</p><button type="button" :disabled="draft.links.length>=10 || draft.links.some(link=>link.actionId===candidate.actionId)" @click="add(candidate)">{{ t('add') }}</button></li></ul><div class="visit-controls"><button type="button" @click="flow.loadCandidates(false)">{{ t('first') }}</button><button v-if="flow.state.candidateCursor" type="button" @click="flow.loadCandidates(true)">{{ t('next') }}</button></div>
            <ol><li v-for="(link,index) in draft.links" :key="link.actionId"><p>{{ t('plan') }} #{{ link.planId }} · {{ t('revision') }} #{{ link.revisionId }} · {{ t('action') }} #{{ link.actionId }}</p><label>{{ t('kind') }}<select v-model="link.kind" required><option value="">{{ t('choose') }}</option><option v-for="kind in ['CHECK','REVISIT','REFERRAL']" :key="kind" :value="kind">{{ t(kind) }}</option></select></label><button type="button" @click="draft.links.splice(index,1)">{{ t('remove') }}</button></li></ol>
          </section>
        </template>
        <label class="visit-check"><input v-model="draft.confirm" type="checkbox" required /><span>{{ t('confirm') }}</span></label><div class="visit-controls"><button type="submit" class="visit-primary">{{ t('save') }}</button><button type="button" @click="cancelDraft">{{ t('cancelDraft') }}</button></div>
      </fieldset>
    </form>
    <p v-if="planSaved" role="status">{{ t('planSaved') }}</p><PlanEditor v-if="planEditorVisible" ref="planEditor" :patient-id="Number(patientId)" @saved="planSaved=true" @closed="planClosed" />
  </section>
</template>
<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router';
import { useVisitDisposition } from '@/composables/useVisitDisposition';
import { visitText as t } from '@/utils/visitDispositionCopy';
import PlanEditor from '@/components/care-plan/PlanEditor.vue';
import { visitBody } from '@/utils/visitDispositionForms';
const props=defineProps({patientId:[Number,String],sourceId:[Number,String],active:{type:Boolean,default:true}}),emit=defineEmits(['changed','capabilities']);
const flow=useVisitDisposition(computed(()=>props.patientId)),detail=computed(()=>flow.state.detail),draft=computed(()=>flow.state.draft),detailHeading=ref(null),formHeading=ref(null),outcomeHeading=ref(null),formError=ref(null);
const planEditorVisible=ref(false),planEditor=ref(null),planSaved=ref(false);
let returnFocus=null,focusVersion=0,disposed=false;
const focusEvents=['auth-session-cleared','auth-session-changed','storage','patient-changed'];
function retireReturnFocus(){focusVersion++;returnFocus=null;}
function restoreVisibleFocus(target){
  if(!target?.isConnected)return;
  target.focus();
  const topbar=document.querySelector('.workspace-topbar');
  if(!topbar||!['sticky','fixed'].includes(window.getComputedStyle(topbar).position))return;
  const bottom=topbar.getBoundingClientRect().bottom,clearance=bottom+16;
  if(bottom<=0||target.getBoundingClientRect().top>=clearance)return;
  const margin=target.style.getPropertyValue('scroll-margin-top'),priority=target.style.getPropertyPriority('scroll-margin-top');
  target.style.setProperty('scroll-margin-top',`${clearance}px`);
  target.scrollIntoView({behavior:'instant',block:'start'});
  if(margin)target.style.setProperty('scroll-margin-top',margin,priority);else target.style.removeProperty('scroll-margin-top');
}
const wall=value=>String(value||'—').replace('T',' ');
const defaults=()=>({patientId:Number(props.patientId),actualWall:'',actualOffset:'',diagnosisSummary:'',treatmentSummary:'',followUpAdvice:'',followUpAt:'',originKind:'',originId:'',confirmOrigin:false,disposition:'',reason:'',links:[],confirm:false});
async function refresh(){if(!props.active||!props.patientId)return;await flow.loadCapabilities();if(props.sourceId)await flow.open('VISIT_RECORD',props.sourceId,flow.locked());else if(flow.hasSelection())await flow.reloadSelected();else if(flow.state.capabilities?.visits)await flow.loadPage();}
async function open(id,event){returnFocus=event?.currentTarget;await flow.open('VISIT_RECORD',id);await nextTick();detailHeading.value?.focus();}
async function beginCreate(event){if(!flow.beginCreate(defaults()))return;returnFocus=event?.currentTarget;formError.value=null;await nextTick();formHeading.value?.focus();}
// 行入口只带确切现代预约身份，不自动纳管、保存或用排程填实际时间。
async function beginFromAppointment(row,event){if(Number(row?.patientId)!==Number(props.patientId)||row?.sourceKind!=='CARE_APPOINTMENT'||!Number.isSafeInteger(row.sourceId)||row.sourceId<1||flow.locked()||draft.value||planEditorVisible.value)return false;if(!flow.beginCreate({...defaults(),originKind:'CARE_APPOINTMENT',originId:row.sourceId}))return false;returnFocus=event?.currentTarget;formError.value=null;await nextTick();formHeading.value?.focus();return true;}
async function begin(operation,event){if(!flow.begin(operation,defaults()))return;returnFocus=event?.currentTarget;formError.value=null;await nextTick();formHeading.value?.focus();}
function clearOrigin(){flow.state.target=null;if(draft.value)draft.value.confirmOrigin=false;}
async function loadOrigin(){clearOrigin();await flow.selectOrigin(draft.value.originKind,draft.value.originId);}
async function decisionChanged(){draft.value.confirm=false;if(draft.value.disposition==='ACTIONS_REQUIRED')await flow.loadCandidates();}
function add(candidate){if(draft.value.links.length>=10||draft.value.links.some(link=>link.actionId===candidate.actionId))return;draft.value.links.push({planId:candidate.planId,revisionId:candidate.revisionId,actionId:candidate.actionId,kind:''});draft.value.confirm=false;}
async function submit(){formError.value=null;try{const result=await flow.submit(visitBody(flow.state.operation,draft.value,flow.state.target));if(result.phase==='succeeded'){emit('changed');await nextTick();outcomeHeading.value?.focus();}}catch(error){formError.value=error.message;await nextTick();formHeading.value?.focus();}}
async function retry(){const result=await flow.retry();if(result.phase==='succeeded'){emit('changed');await nextTick();outcomeHeading.value?.focus();}}
async function planClosed(){planEditorVisible.value=false;if(draft.value?.disposition==='ACTIONS_REQUIRED')await flow.loadCandidates();}
async function cancelDraft(){
  if(planEditorVisible.value||!flow.cancelDraft())return;
  const version=++focusVersion,selected=detail.value,opener=returnFocus;
  formError.value=null;
  await nextTick();
  if(disposed||version!==focusVersion||!props.active||selected!==detail.value||draft.value||flow.locked()||planEditorVisible.value)return;
  restoreVisibleFocus(opener?.isConnected?opener:detailHeading.value);
}
// 跳转携带完整患者/计划版本/行动身份，目标页再核当前版本，不能按序号找替代行动。
const actionLink=action=>({path:`/care-plans/${action.planId}`,query:{patientId:String(props.patientId),revisionId:String(action.revisionId),actionId:String(action.actionId),visitId:String(detail.value.sourceId)}});
watch(()=>[props.patientId,props.sourceId,props.active],retireReturnFocus,{flush:'sync'});
watch(()=>[props.patientId,props.sourceId,props.active],()=>{formError.value=null;planEditorVisible.value=false;planSaved.value=false;if(props.active)refresh();else if(!flow.locked()&&!draft.value&&!planEditorVisible.value)flow.clear();},{immediate:true});
watch(()=>flow.state.capabilities,value=>emit('capabilities',value));
function protect(event){if(flow.locked()||draft.value||planEditorVisible.value)event.preventDefault();}
function beforeUnload(event){if(flow.locked()||draft.value||planEditorVisible.value){event.preventDefault();event.returnValue='';}}
onMounted(()=>{window.addEventListener('care-plan-before-context-change',protect);window.addEventListener('beforeunload',beforeUnload);for(const name of focusEvents)window.addEventListener(name,retireReturnFocus);});
onUnmounted(()=>{disposed=true;retireReturnFocus();window.removeEventListener('care-plan-before-context-change',protect);window.removeEventListener('beforeunload',beforeUnload);for(const name of focusEvents)window.removeEventListener(name,retireReturnFocus);});
function beforeRouteChange(){const allowed=!flow.locked()&&!draft.value&&!planEditorVisible.value;if(allowed)retireReturnFocus();return allowed;}
onBeforeRouteLeave(beforeRouteChange);
onBeforeRouteUpdate(beforeRouteChange);
defineExpose({beginFromAppointment,protectNavigation:()=>!flow.locked()&&!draft.value&&!planEditorVisible.value,refresh});
</script>
<style scoped>
.visit-follow-up{min-width:0;padding:1rem;margin-block:1rem;border:1px solid #cbd5e1;border-radius:12px;background:#fff;color:#172b3a;overflow-wrap:anywhere}.visit-follow-up header,.visit-controls{display:flex;align-items:center;flex-wrap:wrap;gap:.65rem}.visit-follow-up header h2{flex:1 1 16rem}.visit-follow-up button,.visit-follow-up input,.visit-follow-up select,.visit-follow-up textarea{font:inherit;max-width:100%;box-sizing:border-box}.visit-follow-up button,.visit-follow-up a{min-height:44px;padding:.6rem .85rem;line-height:1.5;white-space:normal;border:1px solid #728497;border-radius:8px;background:#f8fafc;color:#17344b;display:inline-flex;align-items:center}.visit-follow-up button{cursor:pointer}.visit-follow-up button:disabled{opacity:.6;cursor:not-allowed}.visit-follow-up :focus-visible{outline:3px solid #1767ad;outline-offset:3px}.visit-cards{list-style:none;padding:0;display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,260px),1fr));gap:1rem}.visit-cards li,.visit-detail,.visit-notice,.visit-action-list>li{padding:1rem;border:1px solid #cbd5e1;border-radius:8px;margin-block:.75rem;min-width:0}.visit-notice{background:#fff8e6}.visit-fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,240px),1fr));gap:1rem}.visit-form fieldset{border:0;padding:0;margin:0;min-width:0}.visit-form label{display:flex;flex-direction:column;gap:.4rem;margin-block:.7rem;min-width:0}.visit-form input,.visit-form select,.visit-form textarea{width:100%;min-height:44px;border:1px solid #728497;border-radius:6px;padding:.6rem;background:#fff;color:#172b3a}.visit-form label.visit-check{flex-direction:row;align-items:flex-start}.visit-check input{width:24px;min-height:24px;flex:0 0 24px;margin-top:.2rem}.visit-primary{background:#174f76!important;color:#fff!important}.visit-follow-up dd{margin:.35rem 0 1rem}.visit-text,.visit-follow-up pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}.visit-follow-up h5,.visit-follow-up h6{font-size:1em;margin-block:.75rem}.visit-action-list{padding-inline-start:1.3rem}@media(max-width:480px){.visit-follow-up,.visit-detail{padding:.65rem}.visit-controls>*{flex:1 1 100%}}
</style>
