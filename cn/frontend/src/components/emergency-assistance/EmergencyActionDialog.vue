<template>
    <section v-if="flow.state.dialog&&flow.state.draft" class="emergency-dialog" role="dialog" aria-modal="false" :aria-label="t(flow.state.operation)">
        <h3>
            {{t(flow.state.operation)}}
        </h3>
        <p>
            {{t('intro')}}
        </p>
        <form @submit.prevent="submit">
            <label v-if="'locationText' in flow.state.draft">
                {{t('location')}}
                <textarea v-model="flow.state.draft.locationText" maxlength="500" :disabled="busy" />
            </label>
            <label v-if="'successorUserId' in flow.state.draft">
                {{t('successor')}}
                <input v-model="flow.state.draft.successorUserId" inputmode="numeric" required :disabled="busy">
            </label>
            <label v-if="'actualAt' in flow.state.draft">
                {{t('actualAt')}}
                <input v-model="flow.state.draft.actualAt" placeholder="2026-10-14T12:00:00Z" required :disabled="busy">
            </label>
            <label v-if="'note' in flow.state.draft">
                {{t('note')}}
                <textarea v-model="flow.state.draft.note" maxlength="1000" required :disabled="busy" />
            </label>
            <label v-if="'outcomeCode' in flow.state.draft">
                {{t('outcome')}}
                <select v-model="flow.state.draft.outcomeCode" required :disabled="busy">
                    <option disabled value="">
                        {{t('outcome')}}
                    </option>
                    <option v-for="code in outcomes" :key="code" :value="code">
                        {{t(code)}}
                    </option>
                </select>
            </label>
            <label v-if="'reason' in flow.state.draft">
                {{t('reason')}}
                <textarea v-model="flow.state.draft.reason" maxlength="1000" required :disabled="busy" />
            </label>
            <button type="submit" :disabled="busy||['invalid','conflict'].includes(flow.state.phase)">
                {{t('save')}}
            </button>
            <button v-if="['invalid','conflict'].includes(flow.state.phase)" type="button" @click="edit">
                {{t('edit')}}
            </button>
            <button type="button" @click="flow.close">
                {{t('close')}}
            </button>
        </form>
    </section>
</template>
<script setup>
import {computed} from 'vue';
import {t} from '@/utils/emergencyAssistanceCopy';
const props=defineProps({
    flow:{
        type:Object,required:true}
}
),outcomes=['CONTACTED_HELP','CONTACTED_REQUESTER','HANDED_TO_SERVICES','UNABLE_TO_CONTACT','OTHER_ACTUAL_OUTCOME'];
const busy=computed(()=>['unknown','submitting'].includes(props.flow.state.phase));
async function submit(){
    const body={
        ...props.flow.state.draft}
    ;
    if('successorUserId' in body){
        const n=Number(body.successorUserId);
        if(!/^[1-9][0-9]*$/.test(String(body.successorUserId))||!Number.isSafeInteger(n)){
            props.flow.state.error='INVALID_REQUEST';
            return;
        }
        body.successorUserId=n;
    }
    if(body.locationText==='')delete body.locationText;
    await props.flow.submit(body);
}
async function edit(){
    const flow=props.flow;
    if(flow.state.detail)await flow.open(flow.state.detail.emergencyEventId,true);
    flow.editAfterError();
}
</script>
<style scoped>
.emergency-dialog{margin:1rem 0;padding:1rem;border:2px solid var(--el-border-color);border-radius:12px;background:var(--el-bg-color);overflow-wrap:anywhere}form{display:grid;gap:.6rem}label{display:grid;gap:.3rem;min-height:44px}input,select,textarea,button{box-sizing:border-box;min-height:44px;min-width:44px;width:100%;padding:.5rem;border:1px solid var(--el-border-color);border-radius:6px;background:var(--el-bg-color);color:var(--el-text-color-primary)}button:disabled{opacity:.55}button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}
</style>
