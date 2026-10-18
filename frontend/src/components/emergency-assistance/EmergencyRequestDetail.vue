<template>
    <article class="emergency-detail">
        <h3>
            #{{d.emergencyEventId}} · {{t(d.state)}}
        </h3>
        <p role="status">
            {{t(d.responseStatus)}}
        </p>
        <p>
            {{t('requester')}} {{d.requesterId}} · {{t('lead')}} {{d.currentLeadId||t('none')}} · {{t('pending')}} {{d.pendingSuccessorId||t('none')}}
        </p>
        <p v-if="d.currentLeadId && d.leadCurrent===false" role="alert">
            {{t('leadLost')}}
        </p>
        <p v-if="d.pendingSuccessorId" role="status">
            {{t('handoffPending')}}
        </p>
        <p>
            {{d.acceptedAt?t('accepted')+' '+d.acceptedAt:t('noEvidence')}}
        </p>
        <p v-if="d.lastProgressAt">
            {{t('progress')}} {{d.lastProgressAt}}
        </p>
        <p>
            {{t('location')}}: {{d.locationText||'—'}}
        </p>
        <p v-if="d.outcomeCode">
            {{t('result')}}: {{t(d.outcomeCode)}} · {{d.outcomeReason}}
        </p>
        <details>
            <summary>
                {{t('snapshot')}}
            </summary>
            <p>
                {{d.snapshot?.patient?.medical_history||'—'}}
            </p>
            <p>
                {{d.snapshot?.clinical?.allergy_drugs||'—'}}
            </p>
            <p>
                {{(d.snapshot?.medications||[]).map(m=>m.drug_name).join(', ')}}
            </p>
            <p>
                {{t('generated')}} {{d.snapshot?.generatedAt}}
            </p>
        </details>
        <div>
            <button v-for="op in d.allowedCommands" :key="op" type="button" :disabled="busy" @click="$emit('command',op)">
                {{t(op)}}
            </button>
        </div>
        <button type="button" @click="flow.loadEvents()">
            {{t('history')}}
        </button>
        <ol>
            <li v-for="e in flow.state.events" :key="e.id">
                {{t(e.type)}} · {{e.occurred_at}}
                <p v-if="e.actualAt">
                    {{t('actualAt')}} {{e.actualAt}}
                </p>
                <p v-if="e.note">
                    {{e.note}}
                </p>
                <p v-if="e.reason">
                    {{e.reason}}
                </p>
            </li>
        </ol>
        <button v-if="flow.state.eventCursor" type="button" @click="flow.loadEvents(true)">
            {{t('moreEvents')}}
        </button>
        <button type="button" :disabled="flow.state.deliveryReviewing||flow.state.deliveryPhase==='submitting'" @click="reviewDeliveries">
            {{t('deliveries')}}
        </button>
        <p v-if="flow.state.deliveryPhase==='unknown'" role="alert">{{t('retryUnknown')}}</p>
        <p v-else-if="['conflict','invalid'].includes(flow.state.deliveryPhase)" role="alert">{{t('retryConflict')}}</p>
        <p v-else-if="flow.state.deliveryPhase==='unavailable'" role="alert">{{t('retryAdmissionFailed')}}</p>
        <p v-else-if="flow.state.deliveryPhase==='submitting'" role="status">{{t('retrySubmitting')}}</p>
        <button v-if="['unknown','conflict','invalid','unavailable'].includes(flow.state.deliveryPhase)" type="button" :disabled="flow.state.deliveryReviewing||flow.state.deliveryPhase==='submitting'" @click="reviewDeliveries">
            {{t('refreshDeliveryStatus')}}
        </button>
        <ul>
            <li v-for="row in flow.state.deliveries" :key="row.id">
                #{{row.id}} · {{t(row.status)}}
                <p v-if="row.blockedReason">
                    {{t(row.blockedReason)}}
                </p>
                <label v-if="row.requiresDuplicateRiskAcknowledgement">
                    <input v-model="ack[row.id+':'+row.retryToken]" type="checkbox">
                    {{t('duplicate')}}
                </label>
                <button v-if="row.canRetry" type="button" :disabled="flow.state.deliveryReviewing||['submitting','conflict','invalid'].includes(flow.state.deliveryPhase)||(flow.state.deliveryPhase==='unknown'&&flow.state.deliveryRetryId!==row.id)||(row.requiresDuplicateRiskAcknowledgement&&!ack[row.id+':'+row.retryToken])" @click="flow.retryDelivery(row,!!ack[row.id+':'+row.retryToken])">
                    {{t(flow.state.deliveryPhase==='unknown'?'retryIdenticalDelivery':'retryDelivery')}}
                </button>
            </li>
        </ul>
        <button v-if="flow.state.deliveryCursor" type="button" @click="flow.loadDeliveries(true)">
            {{t('moreDeliveries')}}
        </button>
        <button type="button" @click="flow.close">
            {{t('close')}}
        </button>
    </article>
</template>
<script setup>
import {computed,reactive} from 'vue';
import {t} from '@/utils/emergencyAssistanceCopy';
const props=defineProps({
    flow:{
        type:Object,required:true}
}
);
defineEmits(['command']);
const d=computed(()=>props.flow.state.detail),busy=computed(()=>['unknown','submitting'].includes(props.flow.state.phase)),
ack=reactive({
}
);
async function reviewDeliveries() {
    const result = await props.flow.reviewDeliveries();
    if (result.phase === 'succeeded') for (const key of Object.keys(ack)) delete ack[key];
}
</script>
<style scoped>
.emergency-detail{margin-top:1rem;border-top:1px solid var(--el-border-color);padding-top:.7rem;overflow-wrap:anywhere}button,label,summary{min-height:44px;min-width:44px;padding:.5rem;margin:.25rem;border:1px solid var(--el-border-color);border-radius:8px;color:inherit;background:var(--el-fill-color-light)}label{display:flex;align-items:center;gap:.5rem}button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}@media(max-width:600px){button{width:100%;margin:.25rem 0}}
</style>
