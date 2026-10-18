<template>
 <div class="measurement-read-model" data-testid="original-page-status">
  <p>{{t('pageScope')}}</p><p>{{t('timeBasis')}}</p>
  <p v-if="state.phase==='loading'" role="status">{{t('loading')}}</p>
  <p v-else-if="!['idle','ready'].includes(state.phase)" role="alert">{{t(state.phase)}}</p>
  <template v-if="state.phase==='ready'"><p>{{t('page')}} {{state.page}} · {{state.items.length}} {{t('rows')}}</p><p>{{t(state.data?.coverage?.unknownDateExcluded?'rangeDates':'allDates')}} <span v-if="state.data?.coverage?.from">{{state.data.coverage.from}} – {{state.data.coverage.to}}</span></p><p>{{t('nonSnapshot')}}</p><p>{{t(state.nextCursor!=null?'more':'last')}}</p><p v-if="!state.items.length">{{t('empty')}}</p></template>
  <div class="mr-controls"><button type="button" data-action="original-refresh" :disabled="locked||state.phase==='loading'" @click="emit('refresh')">{{t('refresh')}}</button><button v-if="!['idle','ready','loading'].includes(state.phase)" type="button" data-action="original-retry" :disabled="locked" @click="emit('retry')">{{t('retry')}}</button><button type="button" data-action="original-previous" :disabled="locked||state.phase==='loading'||!state.previous.length" @click="emit('previous')">{{t('previous')}}</button><button type="button" data-action="original-next" :disabled="locked||state.phase!=='ready'||state.nextCursor==null" @click="emit('next')">{{t('next')}}</button></div>
 </div>
</template>
<script setup>
import {originalText as t} from '@/utils/originalMeasurementCopy';
import './measurement-read-model.css';
defineProps({state:{type:Object,required:true},locked:{type:Boolean,default:false}});
const emit=defineEmits(['refresh','retry','next','previous']);
</script>
