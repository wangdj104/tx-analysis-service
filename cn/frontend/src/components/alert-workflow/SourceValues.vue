<template><dl v-if="source"><template v-for="field in fields" :key="field[0]"><template v-if="source[field[0]]!==null&&source[field[0]]!==undefined&&source[field[0]]!==''"><dt>{{t(field[1])}}</dt><dd>{{['metricType','measureType','verificationStatus'].includes(field[0])?t(source[field[0]]):source[field[0]]}}</dd></template></template><template v-if="source.sourceType==='BP_SELF_MONITOR'&&source.assessments?.find(item=>item.metric==='BP')?.sourceUnit"><dt>{{t('originalBpUnit')}}</dt><dd>{{source.assessments.find(item=>item.metric==='BP').sourceUnit}}</dd></template></dl></template>
<script setup>
import {t} from '@/utils/alertWorkflowCopy'
defineProps({source:{type:Object,default:null}})
const fields=[['metricType','metric'],['measureType','measureType'],['valuePrimary','primary'],['valueSecondary','secondary'],['unit','unit'],['measuredAt','measurementTime'],['recordDate','recordDate'],['recordTime','recordTime'],['systolicBp','systolic'],['diastolicBp','diastolic'],['bloodGlucose','glucose'],['bgUnit','unit'],['measurePeriod','period'],['sourceKind','sourceType'],['recordedBy','recorder'],['userId','recorder'],['verificationStatus','state']]
</script>
