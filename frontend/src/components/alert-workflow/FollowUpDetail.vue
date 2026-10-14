<template>
 <section class="aw">
  <div class="aw-controls"><button type="button" :disabled="state.opening||locked" @click="refresh">{{t('refresh')}}</button></div>
  <p v-if="state.opening" role="status">{{t('loading')}}</p><p v-if="state.commandPhase==='denied'" role="alert">{{t('denied')}}</p><p v-if="state.error" role="alert">{{t(state.error)}}</p>
  <p v-if="state.capabilityPhase==='disabled'" role="status">{{t('featureOff')}}</p><p v-if="state.capabilityPhase==='unavailable'" role="alert">{{t('capabilityUnknown')}}</p>
  <template v-if="detail&&state.capabilityPhase==='enabled'">
   <router-link v-if="selectedPatientId===patientId" class="aw-link" to="/bp-self-monitor">{{t('retest')}}</router-link><p v-else class="aw-notice">{{t('retestPatientRequired')}}</p>
   <h1 ref="heading" tabindex="-1">{{t('title')}} #{{detail.alertId}}</h1><p>{{t('patient')}} #{{detail.patient.id}} · {{t('privacy')}}</p>
   <dl><dt>{{t('mode')}}</dt><dd>{{t(detail.mode)}}</dd><dt>{{t('state')}}</dt><dd>{{t(detail.state)}}</dd><dt>{{t('cycle')}}</dt><dd>{{detail.cycleNo}}</dd><dt>{{t('assignee')}}</dt><dd>{{detail.responsibleUserId?`${assigneeName} (#${detail.responsibleUserId})`:t('noAssignee')}}</dd><dt>{{t('due')}}</dt><dd>{{detail.dueAt?formatWorkflowTime(detail.dueAt):t('noDeadline')}}</dd><dt>{{t('next')}}</dt><dd>{{nextStep}}</dd><dt>{{t('policy')}}</dt><dd>{{detail.classification?.policyKey||t('unknown')}} · {{t(detail.classification?.status)}}</dd></dl>
   <p v-if="detail.blockers?.length" class="aw-notice">{{t('blockers')}}: {{detail.blockers.map(t).join(' · ')}}</p>
   <p v-if="detail.state==='CLOSED'">{{t(detail.closureKind)}} · {{formatWorkflowTime(detail.closedAt)}} · {{detail.restricted?t('restricted'):detail.closureReason}}</p>
   <p v-if="detail.restricted" class="aw-notice">{{t('restricted')}}</p>
   <div class="aw-controls"><button v-for="action in detail.allowedActions" :key="action" type="button" :disabled="locked" @click="start(action,$event)">{{t(action)}}</button></div><p v-if="!detail.allowedActions?.length">{{t('noActions')}}</p>
   <SourceMeasurementPanel :flow="flow" @changed="refresh" /><NurseAssignments v-if="state.capabilities?.canGrantMeasurementsToAssignedNurse||state.capabilities?.canSetClinicalDeadline" :patient-id="patientId" :history-allowed="true" />
   <section class="aw-panel"><h2>{{t('evidence')}}</h2><p>{{t('referenceHelp')}}</p><article v-for="reference in detail.evidence||[]" :key="reference.id"><p v-if="reference.restricted">#{{reference.id}} · {{t('restricted')}}</p><p v-else>{{t(reference.sourceType)}} #{{reference.sourceId}} · {{t('sourceRevision')}} {{reference.sourceRevision??'—'}} · {{t(reference.dependencyState)}}</p></article></section>
   <section class="aw-panel"><h2>{{t('delivery')}}</h2><p>{{t('deliveryMeaning')}}</p><p v-if="state.deliveryPhase==='uncertain'" role="status">{{t('uncertain')}}</p><p v-if="state.deliveryPhase==='conflict'" role="status">{{t('conflict')}}</p><button type="button" @click="flow.loadDeliveries()">{{t('refresh')}}</button><button v-if="state.deliveryPhase==='uncertain'" type="button" @click="flow.retryDelivery(null,false,{retry:true})">{{t('retryOriginal')}}</button><button v-if="state.deliveryPhase==='conflict'" type="button" :disabled="!state.deliveryDecisionReady" @click="flow.newDecision('delivery')">{{t('newDecision')}}</button>
    <article v-for="delivery in state.deliveries" :key="delivery.id"><h3>#{{delivery.id}} · {{t(delivery.status)}}</h3><p>{{t('attempts')}}: {{delivery.attemptCount}} · {{t('nextAttempt')}}: {{formatWorkflowTime(delivery.nextAttemptAt)}}</p><p>{{formatWorkflowTime(delivery.deliveredAt||delivery.updatedAt)}}</p><p v-if="delivery.lastResult">{{t('lastResult')}}: {{t(delivery.lastResult)}}</p><p v-if="delivery.status==='NO_CHANNEL'">{{t('noChannel')}}</p><template v-if="delivery.canRetry"><label v-if="delivery.requiresDuplicateRiskAcknowledgement" class="aw-check"><input v-model="acknowledged[delivery.id]" type="checkbox" />{{t('duplicateRisk')}}</label><button type="button" :disabled="['submitting','uncertain','conflict'].includes(state.deliveryPhase)||(delivery.requiresDuplicateRiskAcknowledgement&&!acknowledged[delivery.id])" @click="flow.retryDelivery(delivery,!!acknowledged[delivery.id])">{{t('retryDelivery')}}</button></template></article><button v-if="state.deliveryCursor" type="button" @click="flow.loadDeliveries(state.deliveryCursor)">{{t('more')}}</button>
   </section>
   <section class="aw-panel"><h2>{{t('history')}}</h2><button type="button" @click="flow.loadEvents()">{{t('refresh')}}</button><article v-for="event in state.events" :key="event.id"><h3>{{t(event.eventType)}} · {{t('cycle')}} {{event.cycleNo}}</h3><p>{{event.actorRole==='SYSTEM'&&event.actorRelation==='SCHEDULER'?t('automated'):`${t('recorder')}: ${event.actorName||'#'+event.actorId} · ${t(event.actorRole)} · ${t(event.actorRelation)}`}}<template v-if="event.entryMode"> · {{t(event.entryMode)}}</template></p><p>{{t('occurred')}}: {{formatWorkflowTime(event.occurredAt)}}<br />{{t('captured')}}: {{formatWorkflowTime(event.recordedAt)}}</p><p>{{event.fromState?t(event.fromState):'—'}} → {{t(event.toState)}}</p><p v-if="event.restricted" class="aw-notice">{{t('restricted')}}</p><template v-else><p v-if="event.note">{{event.note}}</p><p v-if="event.payload?.reason">{{event.payload.reason}}</p><p v-if="event.payload?.outcome">{{t(event.payload.outcome)}}</p><p v-if="event.payload?.contact">{{t('contact')}}: {{event.payload.contact.relationship}} · {{event.payload.contact.outcome}}</p><p v-if="event.payload?.dueKey">{{t('dueIdentity')}}: {{event.payload.dueKey}}</p><p v-if="event.payload?.dueAt">{{t('due')}}: {{formatWorkflowTime(event.payload.dueAt)}}</p></template></article><button v-if="state.eventCursor" type="button" @click="flow.loadEvents(state.eventCursor)">{{t('more')}}</button></section>
  </template>
  <FollowUpActionDialog :flow="flow" @changed="changed" @closed="restoreFocus" />
 </section>
</template>
<script setup>
import {formatWorkflowTime} from '@/utils/alertWorkflowTime'
import {computed,toRef,watch,reactive,nextTick,ref} from 'vue'
import {useAlertWorkflow} from '@/composables/useAlertWorkflow'
import {initialActionForm,responsibleDisplayName} from '@/utils/alertWorkflowForms'
import {t} from '@/utils/alertWorkflowCopy'
import FollowUpActionDialog from './FollowUpActionDialog.vue'
import SourceMeasurementPanel from './SourceMeasurementPanel.vue'
import NurseAssignments from './NurseAssignments.vue'
import './workflow.css'
const props=defineProps({patientId:{type:Number,required:true},alertId:{type:Number,required:true},selectedPatientId:{type:Number,default:null}}),flow=useAlertWorkflow(toRef(props,'patientId')),state=flow.state,detail=computed(()=>state.detail),acknowledged=reactive({}),heading=ref(null)
const locked=computed(()=>['submitting','uncertain'].includes(state.commandPhase)||['submitting','uncertain'].includes(state.sourcePhase)||['submitting','uncertain'].includes(state.deliveryPhase))
const assigneeName=computed(()=>responsibleDisplayName(detail.value,state.assignees,state.events)||t('unknown'))
const nextStep=computed(()=>{const d=detail.value;if(!d)return '';if(d.blockers?.includes('AWAITING_CLINICIAN'))return t('AWAITING_CLINICIAN');if(d.blockers?.includes('UNASSIGNED'))return t('ASSIGN');if(d.blockers?.some(b=>['SOURCE_CHANGED','REFERENCE_CHANGED','REASSIGNMENT_REQUIRED'].includes(b)))return d.blockers.filter(b=>b!=='OVERDUE').map(t).join(' · ');return ({OPEN:t('ACCEPT'),ACCEPTED:t('SUBMIT'),IN_PROGRESS:t('SUBMIT'),WAITING_FEEDBACK:t('FEEDBACK'),READY_FOR_REVIEW:t('WAITING_REVIEW'),CLOSED:t(d.closureKind),CANCELLED:t('CANCELLED')})[d.state]||t('unknown')})
let opener=null,focusEpoch=0
async function initialize(){focusEpoch++;Object.keys(acknowledged).forEach(k=>delete acknowledged[k]);const cap=await flow.loadCapabilities();if(cap.status!=='enabled')return;const result=await flow.open(props.alertId);if(result.status==='succeeded')await supplemental()}
async function supplemental(){const d=state.detail;if(!d)return;await Promise.all([flow.loadEvents(),flow.loadDeliveries(),flow.readSource(d.sourceType,d.sourceId)])}
async function refresh(){if(locked.value)return;const cap=await flow.loadCapabilities();if(cap.status==='enabled'){const result=await flow.refreshDetail(props.alertId);if(result.status==='succeeded')await supplemental()}}
async function changed(){const result=await flow.refreshDetail(props.alertId);if(result.status==='succeeded')await supplemental()}
async function start(action,event){opener=event.currentTarget;focusEpoch++;if(flow.openDialog(action,initialActionForm(detail.value,action))&&['ASSIGN','ESCALATE'].includes(action))await flow.loadAssignees(action==='ESCALATE'?'CLINICAL':detail.value.mode)}
async function restoreFocus(){const epoch=focusEpoch,target=opener;await nextTick();if(epoch===focusEpoch){if(target?.isConnected&&!target.disabled)target.focus();else heading.value?.focus()}}
function guardLeave(){return !locked.value}
defineExpose({guardLeave})
watch(()=>[props.patientId,props.alertId],initialize,{immediate:true})
</script>
