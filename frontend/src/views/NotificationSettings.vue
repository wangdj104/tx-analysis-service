<template>
  <main class="settings-page">
    <header>
      <div><h1>Notification Settings</h1><p>Configure medication, appointment, stock, and care reminders. Webhooks are delivered by the background service.</p></div>
      <el-button type="primary" @click="openCreate">Add channel</el-button>
    </header>
    <section class="browser-card">
      <div><b>Browser notifications</b><p>While the app is open and a patient is selected, due medication tasks are checked every 30 seconds.</p></div>
      <el-button @click="enableBrowser">{{ browserStatus }}</el-button>
    </section>
    <section class="channel-list" v-loading="loading">
      <el-empty v-if="!rows.length" description="No notification channels configured" />
      <article v-for="row in rows" :key="row.id" class="channel-card">
        <div><b>{{ row.channelName || channelTypeName(row.channelType) }}</b><p>{{ channelTypeName(row.channelType) }} · {{ row.webhookConfigured ? 'Webhook securely stored' : 'Webhook URL missing' }}</p><small>Last test: {{ row.lastTestResult || 'Not tested' }}</small></div>
        <el-switch :disabled="saving || pendingIds.has(row.id)" :model-value="row.enabled === 1" @change="toggle(row)" />
        <div><el-button link :loading="pendingIds.has(row.id)" :disabled="saving" @click="test(row)">Test</el-button><el-button link type="primary" :disabled="saving || pendingIds.has(row.id)" @click="edit(row)">Edit</el-button><el-popconfirm title="Delete this notification channel?" @confirm="remove(row)"><template #reference><el-button link type="danger" :disabled="saving || pendingIds.has(row.id)">Delete</el-button></template></el-popconfirm></div>
      </article>
    </section>
    <el-dialog v-model="visible" :title="form.id ? 'Edit notification channel' : 'Add notification channel'" width="min(540px, 94vw)">
      <el-form :disabled="saving" :model="form" label-width="110px">
        <el-form-item label="Channel type"><el-select v-model="form.channelType" style="width:100%"><el-option label="DingTalk bot" value="DINGTALK_WEBHOOK"/><el-option label="WeCom webhook" value="WECHAT_WEBHOOK"/><el-option label="Generic webhook" value="WEBHOOK"/></el-select></el-form-item>
        <el-form-item label="Channel name"><el-input v-model="form.channelName"/></el-form-item>
        <el-form-item label="Webhook URL"><el-input v-model="form.webhookUrl" type="textarea" :rows="3" :placeholder="form.id && form.webhookConfigured ? 'Already configured; leave blank to keep it unchanged' : 'Enter the complete webhook URL'"/></el-form-item>
        <template v-if="form.channelType === 'DINGTALK_WEBHOOK'">
          <el-form-item label="Signing secret"><el-input v-model="form.robotSecret" show-password :placeholder="form.id && form.robotSecretConfigured ? 'Already configured; leave blank to keep it unchanged' : 'Enter the SEC-prefixed secret when signing is enabled'"/></el-form-item>
          <el-form-item label="Keyword"><el-input v-model="form.robotKeyword" placeholder="Optional keyword prepended to each message"/></el-form-item>
          <p class="form-note">Save the channel, then send a test. Care handovers and reminders can be created in Family Care.</p>
        </template>
        <el-form-item label="Enabled"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">Cancel</el-button><el-button type="primary" :loading="saving" @click="submit">Save</el-button></template>
    </el-dialog>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deleteNotificationChannel, listNotificationChannels, saveNotificationChannel, testNotificationChannel } from '@/api/notificationChannel'

const rows = ref([])
const loading = ref(false), saving = ref(false), pendingIds = reactive(new Set())
const browserPermission = ref('Notification' in window ? Notification.permission : 'unsupported')
const visible = ref(false)
const form = reactive({ id: null, channelType: 'WECHAT_WEBHOOK', channelName: '', webhookUrl: '', robotSecret: '', robotKeyword: '', enabled: 1 })
const browserStatus = computed(() => !('Notification' in window) ? 'Not supported' : browserPermission.value === 'granted' ? 'Enabled' : 'Enable notifications')
const emptyForm = () => ({ id: null, channelType: 'WECHAT_WEBHOOK', channelName: '', webhookUrl: '', robotSecret: '', robotKeyword: '', webhookConfigured: false, robotSecretConfigured: false, enabled: 1 })
const channelTypeName = type => ({ DINGTALK_WEBHOOK: 'DingTalk bot', WECHAT_WEBHOOK: 'WeCom webhook', WEBHOOK: 'Generic webhook' }[type] || type)

async function load(){ loading.value=true; try { rows.value=(await listNotificationChannels()).data||[] } finally { loading.value=false } }
function openCreate(){ if(saving.value)return;Object.assign(form,emptyForm()); visible.value=true }
function edit(row){ if(saving.value||pendingIds.has(row.id))return;Object.assign(form,emptyForm(),row); visible.value=true }
async function submit(){if(saving.value)return;const url=form.webhookUrl?.trim();if(!url&&!form.webhookConfigured){ElMessage.warning('Enter the complete webhook URL.');return}if(url){try{const parsed=new URL(url);if(!['http:','https:'].includes(parsed.protocol))throw new Error()}catch{ElMessage.warning('Use an http:// or https:// webhook URL.');return}}saving.value=true;try{await saveNotificationChannel({...form,webhookUrl:url});ElMessage.success('Notification channel saved.');visible.value=false;await load()}catch{}finally{saving.value=false}}
async function withPending(row,action){if(saving.value||pendingIds.has(row.id))return;pendingIds.add(row.id);try{await action()}catch{}finally{pendingIds.delete(row.id)}}
async function toggle(row){await withPending(row,async()=>{await saveNotificationChannel({...row,enabled:row.enabled===1?0:1});await load()})}
async function test(row){await withPending(row,async()=>{try{const result=await testNotificationChannel(row.id);ElMessage.success(result.data||'Test request completed.')}finally{await load()}})}
async function remove(row){await withPending(row,async()=>{await deleteNotificationChannel(row.id);ElMessage.success('Notification channel deleted.');await load()})}
async function enableBrowser(){ if(!('Notification' in window)){ ElMessage.warning('This browser does not support notifications.'); return } const permission=await Notification.requestPermission();browserPermission.value=permission; ElMessage[permission==='granted'?'success':'warning'](permission==='granted'?'Browser notifications enabled.':'Browser notification permission was not granted.') }
onMounted(load)
</script>

<style scoped>.settings-page{max-width:1100px;margin:0 auto;padding:32px}.settings-page>header,.browser-card,.channel-card{display:flex;align-items:center;justify-content:space-between;gap:16px}.settings-page h1{margin:0 0 6px}.settings-page p{margin:0;color:#64748b;line-height:1.55}.browser-card,.channel-list{margin-top:18px;padding:20px;background:#fff;border:1px solid #e2e8f0;border-radius:16px}.channel-card{padding:16px;border-bottom:1px solid #e2e8f0}.channel-card>div:first-child{flex:1;min-width:0}.channel-card>div:last-child{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:4px 10px}.channel-card .el-button+.el-button{margin-left:0}.channel-card:last-child{border-bottom:0}.channel-card small{color:#94a3b8}.form-note{margin:-4px 0 18px 110px;color:#64748b}@media(max-width:700px){.settings-page{padding:20px 14px}.settings-page>header,.browser-card{align-items:flex-start;flex-direction:column}.settings-page>header>.el-button,.browser-card>.el-button{width:100%}.channel-list{padding:8px 14px}.channel-card{display:grid;grid-template-columns:1fr auto;align-items:start;padding:16px 0}.channel-card>div:last-child{grid-column:1/-1;justify-content:flex-start}.form-note{margin:-4px 0 18px}.channel-card :deep(.el-button){min-height:40px}}</style>
