<template>
  <main class="settings-page">
    <header>
      <div><h1>通知设置</h1><p>配置用药、预约、库存和照护提醒，Webhook 消息由后台服务发送。</p></div>
      <el-button type="primary" @click="openCreate">新增渠道</el-button>
    </header>
    <section class="browser-card">
      <div><b>浏览器通知</b><p>应用保持打开且已选择患者时，系统每 30 秒检查一次到期用药任务。</p></div>
      <el-button @click="enableBrowser">{{ browserStatus }}</el-button>
    </section>
    <section class="channel-list" v-loading="loading">
      <el-empty v-if="!rows.length" description="尚未配置通知渠道" />
      <article v-for="row in rows" :key="row.id" class="channel-card">
        <div><b>{{ row.channelName || channelTypeName(row.channelType) }}</b><p>{{ channelTypeName(row.channelType) }} · {{ row.webhookConfigured ? 'Webhook 已安全保存' : '未填写 Webhook 地址' }}</p><small>最近测试：{{ row.lastTestResult || '尚未测试' }}</small></div>
        <el-switch :disabled="saving || pendingIds.has(row.id)" :model-value="row.enabled === 1" @change="toggle(row)" />
        <div><el-button link :loading="pendingIds.has(row.id)" :disabled="saving" @click="test(row)">测试</el-button><el-button link type="primary" :disabled="saving || pendingIds.has(row.id)" @click="edit(row)">编辑</el-button><el-popconfirm title="确认删除该通知渠道吗？" @confirm="remove(row)"><template #reference><el-button link type="danger" :disabled="saving || pendingIds.has(row.id)">删除</el-button></template></el-popconfirm></div>
      </article>
    </section>
    <el-dialog v-model="visible" :title="form.id ? '编辑通知渠道' : '新增通知渠道'" width="min(540px, 94vw)">
      <el-form :disabled="saving" :model="form" label-width="110px">
        <el-form-item label="渠道类型"><el-select v-model="form.channelType" style="width:100%"><el-option label="钉钉机器人" value="DINGTALK_WEBHOOK"/><el-option label="企业微信 Webhook" value="WECHAT_WEBHOOK"/><el-option label="通用 Webhook" value="WEBHOOK"/></el-select></el-form-item>
        <el-form-item label="渠道名称"><el-input v-model="form.channelName"/></el-form-item>
        <el-form-item label="Webhook 地址"><el-input v-model="form.webhookUrl" type="textarea" :rows="3" :placeholder="form.id && form.webhookConfigured ? '已配置，留空则保持不变' : '请输入完整的 Webhook 地址'"/></el-form-item>
        <template v-if="form.channelType === 'DINGTALK_WEBHOOK'">
          <el-form-item label="签名密钥"><el-input v-model="form.robotSecret" show-password :placeholder="form.id && form.robotSecretConfigured ? '已配置，留空则保持不变' : '启用签名时请输入以 SEC 开头的密钥'"/></el-form-item>
          <el-form-item label="关键词"><el-input v-model="form.robotKeyword" placeholder="可选，将添加到每条消息开头"/></el-form-item>
          <p class="form-note">保存渠道后请发送测试消息。照护交接和提醒可在家庭照护中创建。</p>
        </template>
        <el-form-item label="启用"><el-switch v-model="form.enabled" :active-value="1" :inactive-value="0"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">保存</el-button></template>
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
const browserStatus = computed(() => !('Notification' in window) ? '不支持' : browserPermission.value === 'granted' ? '已启用' : '启用通知')
const emptyForm = () => ({ id: null, channelType: 'WECHAT_WEBHOOK', channelName: '', webhookUrl: '', robotSecret: '', robotKeyword: '', webhookConfigured: false, robotSecretConfigured: false, enabled: 1 })
const channelTypeName = type => ({ DINGTALK_WEBHOOK: '钉钉机器人', WECHAT_WEBHOOK: '企业微信 Webhook', WEBHOOK: '通用 Webhook' }[type] || type)

async function load(){ loading.value=true; try { rows.value=(await listNotificationChannels()).data||[] } finally { loading.value=false } }
function openCreate(){ if(saving.value)return;Object.assign(form,emptyForm()); visible.value=true }
function edit(row){ if(saving.value||pendingIds.has(row.id))return;Object.assign(form,emptyForm(),row); visible.value=true }
async function submit(){if(saving.value)return;const url=form.webhookUrl?.trim();if(!url&&!form.webhookConfigured){ElMessage.warning('请填写完整的 Webhook 地址。');return}if(url){try{const parsed=new URL(url);if(!['http:','https:'].includes(parsed.protocol))throw new Error()}catch{ElMessage.warning('Webhook 地址必须以 http:// 或 https:// 开头。');return}}saving.value=true;try{await saveNotificationChannel({...form,webhookUrl:url});ElMessage.success('通知渠道已保存。');visible.value=false;await load()}catch{}finally{saving.value=false}}
async function withPending(row,action){if(saving.value||pendingIds.has(row.id))return;pendingIds.add(row.id);try{await action()}catch{}finally{pendingIds.delete(row.id)}}
async function toggle(row){await withPending(row,async()=>{await saveNotificationChannel({...row,enabled:row.enabled===1?0:1});await load()})}
async function test(row){await withPending(row,async()=>{try{const result=await testNotificationChannel(row.id);ElMessage.success(result.data||'测试请求已完成。')}finally{await load()}})}
async function remove(row){await withPending(row,async()=>{await deleteNotificationChannel(row.id);ElMessage.success('通知渠道已删除。');await load()})}
async function enableBrowser(){ if(!('Notification' in window)){ ElMessage.warning('当前浏览器不支持通知。'); return } const permission=await Notification.requestPermission();browserPermission.value=permission; ElMessage[permission==='granted'?'success':'warning'](permission==='granted'?'浏览器通知已启用。':'未获得浏览器通知权限。') }
onMounted(load)
</script>

<style scoped>.settings-page{max-width:1100px;margin:0 auto;padding:32px}.settings-page>header,.browser-card,.channel-card{display:flex;align-items:center;justify-content:space-between;gap:16px}.settings-page h1{margin:0 0 6px}.settings-page p{margin:0;color:#64748b;line-height:1.55}.browser-card,.channel-list{margin-top:18px;padding:20px;background:#fff;border:1px solid #e2e8f0;border-radius:16px}.channel-card{padding:16px;border-bottom:1px solid #e2e8f0}.channel-card>div:first-child{flex:1;min-width:0}.channel-card>div:last-child{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:4px 10px}.channel-card .el-button+.el-button{margin-left:0}.channel-card:last-child{border-bottom:0}.channel-card small{color:#94a3b8}.form-note{margin:-4px 0 18px 110px;color:#64748b}@media(max-width:700px){.settings-page{padding:20px 14px}.settings-page>header,.browser-card{align-items:flex-start;flex-direction:column}.settings-page>header>.el-button,.browser-card>.el-button{width:100%}.channel-list{padding:8px 14px}.channel-card{display:grid;grid-template-columns:1fr auto;align-items:start;padding:16px 0}.channel-card>div:last-child{grid-column:1/-1;justify-content:flex-start}.form-note{margin:-4px 0 18px}.channel-card :deep(.el-button){min-height:40px}}</style>
