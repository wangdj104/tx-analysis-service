<template>
  <section class="backup-panel">
    <h2>completebackupcopy and restore</h2><p>backupcopyyoucancare has Family Member, includehealthrecord, prescription, Inventoryhistory, ExaminationReportandSaved successfully. Attachmentoriginalfile. </p>
    <div class="backup-actions"><el-button type="primary" :loading="busy" @click="download">Downloadcompletebackupcopy</el-button><label class="file-label">selectbackupcopyfile <input type="file" accept=".zip" :disabled="busy" @change="preview"/></label></div>
    <el-alert title="restorewillCreateindependent Family Membersecondarythis , not covercurrentdata. for avoidduplicateReminder, restoresecondarythis  Medication Reminderstemporarilynot Enabled, carecompletememberneedneedagaininvitePlease . " type="info" :closable="false"/>
    <section v-if="inspection" class="backup-preview"><h3>backupcopyPreview</h3><p>Created At: {{inspection.createdAt}}</p><p>Family Member: {{inspection.patients?.map(p=>p.name).join(', ')}}</p>
      <el-table :data="Object.entries(inspection.counts||{}).map(([table,count])=>({table,count}))"><el-table-column prop="table" label="recordtype"/><el-table-column prop="count" label="quantity"/></el-table>
      <el-alert v-for="(w,i) in inspection.warnings||[]" :key="i" :title="w" type="warning" :closable="false"/>
      <el-checkbox v-model="confirmed">already ConfirmthisYesneedrestore backupcopy, restorefor independentsecondarythis </el-checkbox><el-button type="primary" :disabled="!confirmed" :loading="busy" @click="restore">startrestore</el-button>
    </section>
    <el-alert v-if="result" :title="result" type="success" :closable="false"/>
  </section>
</template>
<script setup>
import {ref} from 'vue'
import {ElMessage} from 'element-plus'
import {downloadBackup,previewBackup,restoreBackup} from '@/api/care'
const emit=defineEmits(['restored']),busy=ref(false),inspection=ref(null),confirmed=ref(false),result=ref('');let selected
async function download(){busy.value=true;try{const blob=await downloadBackup();const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=`familyhealthbackupcopy-${new Date().toISOString().slice(0,10)}.zip`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);ElMessage.success('backupcopyalready Download, Please Savein convenientfindreturn set')}finally{busy.value=false}}
async function preview(e){selected=e.target.files?.[0];inspection.value=null;confirmed.value=false;result.value='';if(!selected)return;busy.value=true;try{inspection.value=(await previewBackup(selected)).data}finally{busy.value=false;e.target.value=''}}
async function restore(){if(!selected||!confirmed.value)return;busy.value=true;try{const r=await restoreBackup(selected);result.value=r.data.message;inspection.value=null;confirmed.value=false;selected=null;emit('restored');window.dispatchEvent(new Event('care-patients-changed'))}finally{busy.value=false}}
</script>
<style scoped>.backup-actions{display:flex;flex-wrap:wrap;gap:20px;align-items:center;margin:20px 0}.backup-preview{max-width:700px;margin-top:24px}.backup-preview .el-checkbox{display:flex;margin:20px 0}.backup-panel p{line-height:1.7}.file-label{display:flex;gap:10px;flex-wrap:wrap}</style>
