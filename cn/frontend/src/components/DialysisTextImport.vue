<template>
  <el-button @click="visible=true">粘贴文本导入</el-button>
  <el-dialog v-model="visible" title="通过模板导入透析记录" width="min(960px,96vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
    <p>每次透析请以日期开始，体重单位为 kg。超滤量、间隔天数和上次透后体重可留空，系统会根据已有记录推算。</p>
    <el-button :disabled="busy" @click="insertTemplate">插入模板</el-button><el-button :disabled="busy" @click="copyTemplate">复制模板</el-button>
    <el-input v-model="text" type="textarea" :rows="14" :disabled="busy" :maxlength="100000" show-word-limit placeholder="请在此粘贴填写完成的模板（支持中文或英文字段）" style="margin:14px 0"/>
    <el-button type="primary" :loading="busy" :disabled="!patientId" @click="preview">解析并预览</el-button><p v-if="!patientId">请先选择家庭成员。</p>
    <section v-if="result"><el-alert v-for="(error,i) in result.errors" :key="'e'+i" :title="error" type="error" :closable="false"/><el-alert v-for="(warning,i) in result.warnings" :key="'w'+i" :title="warning" type="warning" :closable="false"/>
      <p>为保护原始记录，已存在的日期将自动跳过。如需修正，请前往透析记录列表编辑原记录。</p>
      <el-table :data="result.rows"><el-table-column prop="recordDate" label="日期" width="115"/><el-table-column label="上次透后体重"><template #default="{row}">{{displayValue(row.lastOffWeight)}}<small v-if="isInferred(row,'lastOffWeight')"> 已推算</small></template></el-table-column><el-table-column prop="onWeight" label="透前体重"/><el-table-column prop="offWeight" label="透后体重"/><el-table-column label="超滤量"><template #default="{row}">{{displayValue(row.ufAmount)}}<small v-if="isInferred(row,'ufAmount')"> 已推算</small></template></el-table-column><el-table-column label="间隔天数"><template #default="{row}">{{displayValue(row.intervalDays)}}<small v-if="isInferred(row,'intervalDays')"> 已推算</small></template></el-table-column><el-table-column label="血压"><template #default="{row}">{{row.systolicBp==null?'未填写':row.systolicBp+'/'+row.diastolicBp}}</template></el-table-column><el-table-column prop="remark" label="备注"/><el-table-column label="处理方式"><template #default="{row}">{{result.existingDates.includes(row.recordDate)?'跳过已有记录':row.recordType==='INCOMPLETE'?'作为不完整记录添加':'添加'}}</template></el-table-column></el-table>
    </section>
    <template #footer><el-button :disabled="busy" @click="visible=false">关闭</el-button><el-button type="success" :loading="busy" :disabled="!canImport" @click="confirm">导入至当前家庭成员</el-button></template>
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
const template=`# 请替换示例日期；如需录入更多记录，可复制整段内容。
日期: 2026-09-14
上次透后体重:
透前体重:
透后体重:
超滤量:
间隔天数:
血压:
备注:

日期: 2026-09-16
状态: 缺失
缺失原因: 数据未保留`
let generation=0
watch(text,()=>{generation++;result.value=null})
watch(()=>props.patientId,()=>{generation++;result.value=null;visible.value=false})
function insertTemplate(){text.value=template}
async function copyTemplate(){try{await navigator.clipboard.writeText(template);ElMessage.success('模板已复制。')}catch{text.value=template;ElMessage.info('已插入模板，你可以手动复制。')}}
async function preview(){if(busy.value||!props.patientId)return;busy.value=true;const version=generation;const payload={patientId:props.patientId,text:text.value};try{const r=await request.post('/dialysis/text-import/preview',payload);if(version===generation){result.value=r.data;previewedText.value=payload.text;previewedPatientId.value=payload.patientId}}finally{busy.value=false}}
async function confirm(){if(busy.value||!canImport.value)return;busy.value=true;try{const r=await request.post('/dialysis/text-import/confirm',{patientId:props.patientId,text:text.value});ElMessage.success(`已导入 ${r.data.added} 条记录，跳过 ${r.data.skipped} 个已有日期。`);visible.value=false;result.value=null;emit('saved')}finally{busy.value=false}}
function isInferred(row,field){return result.value?.inferredFields?.[row.recordDate]?.includes(field)}
function displayValue(value){return value==null?'未填写':value}
</script>
<style scoped>small{color:#19806d;margin-left:4px}</style>
