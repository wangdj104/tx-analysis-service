<template>
  <section class="facts-timeline" aria-live="polite">
    <p>{{t('scope')}}</p><p>{{t('semantics')}}</p>
    <div class="facts-toolbar">
      <label class="facts-filter">{{t('source')}} <select v-model="source"><option value="">{{t('all')}}</option><option v-for="kind in sources" :key="kind" :value="kind">{{t(kind)}}</option></select></label>
      <span class="facts-count">{{visible.length}} {{t(visible.length===1?'record':'records')}}</span>
    </div>
    <ol class="facts-list">
      <li v-for="event in visible" :key="`${timelineSource(event)}:${event.sourceId??event.id}`">
        <article>
          <time>{{event.occurredAt ? `${event.occurredAt.replace('T',' ').replace('Z','')} UTC` : `${event.eventDate??'—'} ${event.eventTime||''}`}}</time>
          <h3>{{event.title}}</h3>
          <p>{{t('source')}}: {{t(timelineSource(event))}} · #{{event.sourceRecordId??event.carePlanId??event.sourceId??event.id}}</p>
          <p v-if="event.eventType && event.eventType!==event.sourceType">{{t('event')}}: {{eventLabel(event)}}</p>
          <p v-if="event.summary||event.remark" class="original">{{event.summary||event.remark}}</p>
          <p v-if="event.currentState">{{t('current')}}: {{stateLabel(event)}}</p>
          <div class="facts-actions">
            <router-link v-if="timelineSourceLink(event,patientId)" :to="timelineSourceLink(event,patientId)">{{t('open')}}</router-link>
            <router-link v-if="timelineRelatedLink(event,patientId)" :to="timelineRelatedLink(event,patientId)">{{t('related')}}</router-link>
            <slot v-if="timelineSource(event)==='MANUAL'" name="actions" :event="event" />
          </div>
        </article>
      </li>
    </ol>
    <div v-if="!visible.length" class="facts-empty" role="status"><p>{{t('empty')}}</p><p>{{t('emptyHint')}}</p></div>
  </section>
</template>
<script setup>
import {computed,ref,watch} from 'vue'
import {timelineText,timelineSource,timelineSourceLink,timelineRelatedLink} from '@/utils/healthTimeline'
import {medicationText} from '@/utils/medicationExecutionCopy'
import {t as familyText} from '@/utils/familyAssistanceCopy'
import {t as emergencyText} from '@/utils/emergencyAssistanceCopy'
import {t as alertText} from '@/utils/alertWorkflowCopy'
const props=defineProps({events:{type:Array,default:()=>[]},patientId:Number,locale:{type:String,default:'en'}})
const source=ref('')
const t=key=>timelineText(key,props.locale)
const sources=computed(()=>[...new Set(props.events.map(timelineSource))])
const visible=computed(()=>props.events.filter(event=>event.patientId===props.patientId&&(!source.value||timelineSource(event)===source.value)))
watch(()=>props.patientId,()=>{source.value=''},{flush:'sync'})
function moduleText(event,key){
  switch(event.sourceType){case 'ALERT_EVENT':return alertText(key);case 'MEDICATION_EXECUTION_EVENT':case 'INTAKE':return medicationText(key);case 'FAMILY_ASSISTANCE_EVENT':return familyText(key);case 'EMERGENCY_ASSISTANCE_EVENT':return emergencyText(key);default:return t(key)}
}
const eventLabel=event=>moduleText(event,event.eventType)
const stateLabel=event=>moduleText(event,event.currentState)
</script>
<style scoped>
.facts-toolbar{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:.75rem}.facts-count{color:var(--el-text-color-regular);font-size:.9em}.facts-empty{margin-top:1rem;padding:1rem;border:1px dashed var(--el-border-color);border-radius:12px;background:var(--surface-subtle)}.facts-empty p{margin:.25rem 0}
.facts-timeline{min-width:0;line-height:1.65}.facts-timeline p,.facts-timeline h3{overflow-wrap:anywhere}.facts-filter{display:flex;align-items:center;gap:.75rem;flex-wrap:wrap}.facts-filter select{font:inherit;max-width:100%;min-height:44px;border:1px solid var(--el-border-color);border-radius:6px;background:var(--el-bg-color);color:var(--el-text-color-primary);padding:.4rem}.facts-list{list-style:none;padding:0;display:grid;gap:1rem}.facts-list article{border:1px solid var(--el-border-color);border-radius:12px;padding:1rem;background:var(--el-bg-color)}.facts-list h3{margin:.4rem 0}.facts-list time{font-size:.9em;color:var(--el-text-color-regular)}.facts-actions{display:flex;align-items:center;gap:.75rem;flex-wrap:wrap}.facts-actions a{min-height:44px;display:inline-flex;align-items:center;color:var(--el-color-primary);overflow-wrap:anywhere}.facts-actions a:focus-visible,.facts-filter select:focus-visible{outline:3px solid var(--el-color-primary);outline-offset:3px}.original{white-space:pre-wrap}@media(max-width:600px){.facts-list article{padding:.75rem}.facts-actions{align-items:stretch;flex-direction:column}}
</style>
