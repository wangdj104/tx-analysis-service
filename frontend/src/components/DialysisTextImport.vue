<template>
  <el-button @click="visible=true">Paste text import</el-button>
  <el-dialog v-model="visible" title="Import dialysis records from a template" width="min(960px,96vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
    <p>Start each session with a date. Weights are in kg. Fluid removed, interval days, and previous post-dialysis weight may be left blank and inferred from available records.</p>
    <el-button :disabled="busy" @click="insertTemplate">Insert template</el-button><el-button :disabled="busy" @click="copyTemplate">Copy template</el-button>
    <el-input v-model="text" type="textarea" :rows="14" :disabled="busy" :maxlength="100000" show-word-limit placeholder="Paste the completed template here (English or Chinese fields are supported)" style="margin:14px 0"/>
    <el-button type="primary" :loading="busy" :disabled="!patientId" @click="preview">Parse and preview</el-button><p v-if="!patientId">Select a family member first.</p>
    <section v-if="result"><el-alert v-for="(error,i) in result.errors" :key="'e'+i" :title="error" type="error" :closable="false"/><el-alert v-for="(warning,i) in result.warnings" :key="'w'+i" :title="warning" type="warning" :closable="false"/>
      <p>Existing dates are skipped to protect the original record. Edit an existing record from the dialysis list if it needs correction.</p>
      <el-table :data="result.rows"><el-table-column prop="recordDate" label="Date" width="115"/><el-table-column label="Previous post-dialysis"><template #default="{row}">{{displayValue(row.lastOffWeight)}}<small v-if="isInferred(row,'lastOffWeight')"> Inferred</small></template></el-table-column><el-table-column prop="onWeight" label="Pre-dialysis"/><el-table-column prop="offWeight" label="Post-dialysis"/><el-table-column label="Fluid removed"><template #default="{row}">{{displayValue(row.ufAmount)}}<small v-if="isInferred(row,'ufAmount')"> Inferred</small></template></el-table-column><el-table-column label="Interval days"><template #default="{row}">{{displayValue(row.intervalDays)}}<small v-if="isInferred(row,'intervalDays')"> Inferred</small></template></el-table-column><el-table-column label="Blood pressure"><template #default="{row}">{{row.systolicBp==null?'Not provided':row.systolicBp+'/'+row.diastolicBp}}</template></el-table-column><el-table-column prop="remark" label="Notes"/><el-table-column label="Action"><template #default="{row}">{{result.existingDates.includes(row.recordDate)?'Skip existing':row.recordType==='INCOMPLETE'?'Add as incomplete':'Add'}}</template></el-table-column></el-table>
    </section>
    <template #footer><el-button :disabled="busy" @click="visible=false">Close</el-button><el-button type="success" :loading="busy" :disabled="!canImport" @click="confirm">Import for selected family member</el-button></template>
  </el-dialog>
</template>
<script setup>
import{computed,ref,watch}from'vue'
import{ElMessage}from'element-plus'
import request from '@/utils/request'
const props=defineProps({patientId:Number}),emit=defineEmits(['saved'])
const visible=ref(false),text=ref(''),result=ref(null),busy=ref(false)
const previewedText=ref(''),previewedPatientId=ref(null)
const canImport=computed(()=>!!props.patientId&&previewedPatientId.value===props.patientId&&previewedText.value===text.value&&result.value&&!result.value.errors.length&&result.value.rows.some(row=>!result.value.existingDates.includes(row.recordDate)))
const template=`# Replace the sample dates. Copy the section for additional sessions.
Date: 2026-09-14
Previous post-dialysis weight:
Pre-dialysis weight:
Post-dialysis weight:
Fluid removed:
Interval days:
Blood pressure:
Notes:

Date: 2026-09-16
Status: Missing
Missing reason: Data was not retained`
let generation=0
watch(text,()=>{generation++;result.value=null})
watch(()=>props.patientId,()=>{generation++;result.value=null;visible.value=false})
function insertTemplate(){text.value=template}
async function copyTemplate(){try{await navigator.clipboard.writeText(template);ElMessage.success('Template copied.')}catch{text.value=template;ElMessage.info('The template was inserted so you can copy it manually.')}}
async function preview(){if(busy.value||!props.patientId)return;busy.value=true;const version=generation;const payload={patientId:props.patientId,text:text.value};try{const r=await request.post('/dialysis/text-import/preview',payload);if(version===generation){result.value=r.data;previewedText.value=payload.text;previewedPatientId.value=payload.patientId}}finally{busy.value=false}}
async function confirm(){if(busy.value||!canImport.value)return;busy.value=true;try{const r=await request.post('/dialysis/text-import/confirm',{patientId:props.patientId,text:text.value});ElMessage.success(`Imported ${r.data.added} records; skipped ${r.data.skipped} existing dates.`);visible.value=false;result.value=null;emit('saved')}finally{busy.value=false}}
function isInferred(row,field){return result.value?.inferredFields?.[row.recordDate]?.includes(field)}
function displayValue(value){return value==null?'Not provided':value}
</script>
<style scoped>small{color:#19806d;margin-left:4px}</style>
