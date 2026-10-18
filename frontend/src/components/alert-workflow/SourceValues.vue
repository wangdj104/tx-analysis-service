<template>
 <dl v-if="source">
  <template v-for="field in fields" :key="field[0]"><template v-if="present(source[field[0]])"><dt>{{t(field[1])}}</dt><dd>{{['metricType','measureType','verificationStatus'].includes(field[0])?t(source[field[0]]):source[field[0]]}}</dd></template></template>
  <template v-if="journey"><dt>{{t('unit')}}</dt><dd>{{measurementUnit(source.unit)}}</dd><dt>{{t('measurementTime')}}</dt><dd>{{present(source.measuredAt)?source.measuredAt:mt('noTime')}}</dd></template>
  <template v-if="self"><dt>{{t('recordDate')}}</dt><dd>{{present(source.recordDate)?source.recordDate:mt('noDate')}}</dd><dt>{{t('recordTime')}}</dt><dd>{{present(source.recordTime)?source.recordTime:mt('noTime')}}</dd>
   <template v-if="selfBp"><dt>{{t('originalBpUnit')}}</dt><dd>mmHg</dd></template>
   <template v-if="selfGlucose"><dt>{{t('unit')}}</dt><dd>{{measurementUnit(source.bgUnit)}}</dd><dt>{{t('period')}}</dt><dd>{{present(source.measurePeriod)?source.measurePeriod:mt('UNKNOWN_PERIOD')}}</dd></template>
  </template>
  <template v-if="self || journey"><dt>{{mt('originalTime')}}</dt><dd>{{mt('timeBasis')}}</dd></template>
 </dl>
</template>
<script setup>
import {computed} from 'vue'
import {t} from '@/utils/alertWorkflowCopy'
import {measurementText as mt} from '@/utils/measurementReadModelCopy'
import {measurementUnit} from '@/utils/measurementReadModelPresentation'
const props=defineProps({source:{type:Object,default:null}})
const present=value=>value!==null&&value!==undefined&&String(value).trim()!==''
const journey=computed(()=>props.source?.sourceType==='HEALTH_MEASUREMENT'||Object.hasOwn(props.source||{},'metricType'))
const self=computed(()=>props.source?.sourceType==='BP_SELF_MONITOR'||Object.hasOwn(props.source||{},'measureType'))
// 历史快照没有判定数组；原自测血压字段仍有自己的mmHg单位契约。
const selfBp=computed(()=>self.value&&(['BP','BP_BG','BOTH'].includes(props.source?.measureType)||present(props.source?.systolicBp)||present(props.source?.diastolicBp)))
const selfGlucose=computed(()=>self.value&&(['BG','BP_BG','BOTH'].includes(props.source?.measureType)||present(props.source?.bloodGlucose)))
const fields=[['metricType','metric'],['measureType','measureType'],['valuePrimary','primary'],['valueSecondary','secondary'],['systolicBp','systolic'],['diastolicBp','diastolic'],['bloodGlucose','glucose'],['sourceKind','sourceType'],['recordedBy','recorder'],['userId','recorder'],['verificationStatus','state']]
</script>
