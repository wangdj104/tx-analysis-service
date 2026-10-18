<template>
    <section class="emergency-panel" :aria-label="t('title')">
        <header>
            <h2>
                {{t('title')}}
            </h2>
            <button type="button" :disabled="flow.state.loading" @click="refresh">
                {{t('refresh')}}
            </button>
        </header>
        <p role="note">
            {{t('intro')}}
        </p>
        <p v-if="flow.state.phase==='forbidden'" role="alert">
            {{t('forbidden')}}
        </p>
        <p v-else-if="flow.state.error" role="alert">
            {{t(flow.state.error==='AUDIENCE_LIMIT'||flow.state.error==='CHANNEL_LIMIT'?(flow.state.operation==='CREATE_REQUEST'?flow.state.error:'capacityAction'):'error')}}
        </p>
        <p v-if="flow.state.capabilities && !flow.state.capabilities.enabled && !flow.state.capabilities.legacyAvailable">
            {{t('disabled')}}
        </p>
        <p v-if="flow.state.capabilities && flow.state.capabilities.schemaStatus!=='STRUCTURALLY_READY' && !flow.state.capabilities.legacyAvailable">
            {{t('notReady')}}
        </p>
        <section v-if="flow.state.capabilities?.legacyAvailable">
            <label>
                {{t('location')}}
                <textarea v-model="flow.state.legacyLocation" maxlength="500" />
            </label>
            <button v-if="flow.state.capabilities.legacyCanWrite" type="button" :disabled="['submitting','unknown'].includes(flow.state.legacyPhase)" @click="flow.createLegacy">
                {{t('legacyCreate')}}
            </button>
            <p v-if="flow.state.legacyPhase==='recorded'" role="status">
                {{t('legacyRecorded')}}
            </p>
            <p v-if="flow.state.legacyPhase==='unknown'" role="alert">
                {{t('legacyUnknown')}}
            </p>
            <ul>
                <li v-for="row in flow.state.legacyItems" :key="row.id">
                    <button type="button" @click="flow.openLegacy(row.id)">
                        #{{row.id}} · {{row.triggered_at}}
                    </button>
                </li>
            </ul>
            <div v-if="flow.state.legacyDetail">
                <!-- 既有事件只显示该次冻结快照；不能用当前离线卡替换历史。 -->
                <p>{{t('location')}}: {{flow.state.legacyDetail.location_text||'—'}}</p>
                <p v-if="flow.state.legacyDetail.latitude!=null && flow.state.legacyDetail.longitude!=null">
                    {{t('coordinates')}}: {{flow.state.legacyDetail.latitude}}, {{flow.state.legacyDetail.longitude}}
                </p>
                <p>{{t('medicalHistory')}}: {{flow.state.legacyDetail.snapshot?.patient?.medical_history||'—'}}</p>
                <p>{{t('allergies')}}: {{flow.state.legacyDetail.snapshot?.clinical?.allergy_drugs||'—'}}</p>
                <p>{{t('medication')}}: {{(flow.state.legacyDetail.snapshot?.medications||[]).map(item=>item.drug_name).join(', ')||'—'}}</p>
            </div>
        </section>
        <button v-if="flow.state.capabilities?.create" type="button" :disabled="busy" @click="create">
            {{t('new')}}
        </button>
        <ul>
            <li v-for="row in flow.state.items" :key="row.emergencyEventId">
                <button type="button" @click="flow.open(row.emergencyEventId)">
                    #{{row.emergencyEventId}} · {{t(row.state)}} · {{t(row.responseStatus)}}
                </button>
            </li>
        </ul>
        <button v-if="flow.state.nextCursor" type="button" @click="flow.loadList(true)">
            {{t('more')}}
        </button>
        <EmergencyRequestDetail v-if="flow.state.detail" :flow="flow" @command="begin" />
        <p v-if="busy" role="status">
            {{t(flow.state.phase==='unknown'?'unknown':'saving')}}
        </p>
        <button v-if="flow.state.phase==='unknown'" type="button" @click="flow.reconcile">
            {{t('receipt')}}
        </button>
        <button v-if="flow.state.phase==='unknown'" type="button" @click="flow.retry">
            {{t('identical')}}
        </button>
        <EmergencyActionDialog :flow="flow" />
    </section>
</template>
<script setup>
import {toRef,watch,onBeforeUnmount,computed} from 'vue';
import {useEmergencyAssistance} from '@/composables/useEmergencyAssistance';
import {t} from '@/utils/emergencyAssistanceCopy';
import EmergencyRequestDetail from './EmergencyRequestDetail.vue';
import EmergencyActionDialog from './EmergencyActionDialog.vue';
const props=defineProps({
    patientId:[Number,String],emergencyEventId:[Number,String],active:{
        type:Boolean,default:true}
}
);
const flow=useEmergencyAssistance(toRef(props,'patientId'));
const busy=computed(()=>['unknown','submitting'].includes(flow.state.phase));
function create(){
    flow.openDraft('CREATE_REQUEST',{
        locationText:''}
    );
}
function begin(operation){
    const draft={
    }
    ;
    if(operation==='PROGRESS'){
        draft.actualAt='';
        draft.note='';
    }
    if(['HANDOFF','RESOLVE','CANCEL_MISTAKE'].includes(operation))draft.reason='';
    if(operation==='HANDOFF')draft.successorUserId='';
    if(operation==='RESOLVE')draft.outcomeCode='';
    flow.openDraft(operation,draft);
}
function refresh(){
    return flow.load(props.emergencyEventId?Number(props.emergencyEventId):null);
}
watch(()=>[props.patientId,props.emergencyEventId,props.active],()=>{
    flow.invalidate();
    if(props.active&&props.patientId)refresh();
}
,{
    immediate:true}
);
onBeforeUnmount(()=>flow.dispose());
</script>
<style scoped>
textarea{box-sizing:border-box;min-height:44px;width:100%;padding:.5rem;border:1px solid var(--el-border-color);background:var(--el-bg-color);color:inherit}label{display:block;min-height:44px}.emergency-panel{padding:1rem;border:1px solid var(--el-border-color);border-radius:12px;background:var(--el-bg-color);color:var(--el-text-color-primary);overflow-wrap:anywhere}header{display:flex;flex-wrap:wrap;justify-content:space-between;gap:.5rem}button{min-height:44px;min-width:44px;padding:.5rem .8rem;background:var(--el-fill-color-light);color:inherit;border:1px solid var(--el-border-color);border-radius:8px;cursor:pointer}button:disabled{opacity:.55;cursor:wait}li{margin:.4rem 0}button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}@media(max-width:600px){header,button{width:100%}textarea{box-sizing:border-box;min-height:44px;width:100%;padding:.5rem;border:1px solid var(--el-border-color);background:var(--el-bg-color);color:inherit}label{display:block;min-height:44px}.emergency-panel{padding:.65rem}}
</style>
