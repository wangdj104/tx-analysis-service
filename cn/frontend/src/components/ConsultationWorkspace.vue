<template>
  <section class="consultation-workspace">
    <el-alert v-if="isDoctor" :title="copy.inboxHint" type="info" show-icon :closable="false" class="consultation-notice" />
    <section :class="['consultation-grid', { 'consultation-grid--single': isDoctor || !patientId }]">
      <el-card v-if="!isDoctor && patientId" class="consultation-start">
        <template #header><b>{{ copy.startTitle }}</b></template>
        <el-form label-position="top" @submit.prevent="beginConsultation">
          <div class="consultation-form-grid">
            <el-form-item :label="copy.mode">
              <el-select v-model="form.mode"><el-option v-for="item in modeOptions" :key="item.value" v-bind="item" /></el-select>
            </el-form-item>
            <el-form-item :label="copy.clinician" required>
              <el-select v-model="form.doctorUserId" filterable :placeholder="copy.chooseDoctor" :loading="doctorsLoading">
                <el-option v-for="doctor in clinicians" :key="doctor.id" :label="doctor.real_name || doctor.username" :value="doctor.id" />
              </el-select>
            </el-form-item>
            <el-form-item :label="copy.symptom" required class="form-wide">
              <el-input v-model="form.symptom" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" maxlength="500" show-word-limit :placeholder="copy.symptomHint" />
            </el-form-item>
            <el-form-item :label="copy.duration"><el-input v-model="form.durationText" maxlength="100" :placeholder="copy.durationHint" /></el-form-item>
            <el-form-item :label="copy.visibility"><el-switch v-model="visibleToFamily" :active-text="copy.visible" :inactive-text="copy.private" /></el-form-item>
            <el-form-item v-if="visibleToFamily && invitees.length" :label="copy.inviteFamily" class="form-wide">
              <el-select v-model="participantUserIds" multiple clearable :placeholder="copy.inviteHint">
                <el-option v-for="member in invitees" :key="member.user_id" :value="member.user_id" :label="[member.real_name, member.relation_name].filter(Boolean).join(' · ')" />
              </el-select>
            </el-form-item>
            <el-form-item :label="copy.history" class="form-wide"><el-input v-model="form.medicalHistory" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" maxlength="4000" show-word-limit :placeholder="copy.historyHint" /></el-form-item>
          </div>
          <p class="consultation-help">{{ copy.startHint }}</p>
          <el-button type="primary" native-type="submit" :loading="starting" :disabled="!canStart">{{ copy.start }}</el-button>
        </el-form>
      </el-card>
      <el-card class="consultation-history">
        <template #header>
          <div class="consultation-card-head"><div><b>{{ isDoctor ? copy.inbox : copy.consultationHistory }}</b><p class="consultation-help">{{ isDoctor ? copy.inboxScope : copy.historyScope }}</p></div><el-button :loading="listLoading" @click="retrySync">{{ copy.refresh }}</el-button></div>
        </template>
        <el-alert v-if="listError" :title="copy.listError" type="warning" :closable="false" show-icon />
        <el-empty v-else-if="!listLoading && !consultations.length" :description="isDoctor ? copy.emptyInbox : patientId ? copy.emptyHistory : copy.selectPatient" :image-size="70" />
        <el-table v-else :data="consultations" max-height="420" row-key="id" :row-class-name="consultationRowClass">
          <el-table-column :label="isDoctor ? copy.patient : copy.clinician" min-width="110"><template #default="{ row }">{{ isDoctor ? row.patient_name || '—' : row.doctor_name || '—' }}</template></el-table-column>
          <el-table-column :label="copy.symptom" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.symptom }}</template></el-table-column>
          <el-table-column :label="copy.status" width="100"><template #default="{ row }"><el-tag :type="row.status === 'OPEN' ? 'success' : 'info'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
          <el-table-column :label="copy.updated" min-width="145"><template #default="{ row }">{{ dateLabel(row.last_message_at || row.started_at) }}</template></el-table-column>
          <el-table-column :label="copy.actions" width="95" fixed="right"><template #default="{ row }"><el-button link type="primary" :loading="openingId === row.id" @click="openConsultation(row.id)">{{ row.status === 'OPEN' ? copy.reply : copy.view }}</el-button></template></el-table-column>
        </el-table>
      </el-card>
    </section>

    <el-card v-if="activeConsultation" class="consultation-room">
      <template #header>
        <div class="consultation-card-head">
          <div>
            <h3>{{ activeConsultation.patient_name || copy.patient }} · {{ modeLabel(activeConsultation.mode) }}</h3>
            <p class="consultation-help">{{ copy.clinician }}: {{ activeConsultation.doctor_name || '—' }} · {{ statusLabel(activeConsultation.status) }}</p>
            <span v-if="activeConsultation.status === 'OPEN'" :class="['consultation-sync', { 'consultation-sync--error': syncError }]"><i />{{ syncError ? copy.syncError : chatSyncing ? copy.syncing : copy.live }}</span>
          </div>
          <div class="consultation-room-actions">
            <el-button v-if="syncError" @click="retrySync">{{ copy.retry }}</el-button>
            <el-button v-if="canMessage && activeConsultation.mode !== 'TEXT'" :type="callActive ? 'danger' : 'primary'" :loading="callStarting" :disabled="!callActive && !mediaSupported" @click="callActive ? stopCall() : startCall()">{{ callActive ? copy.leaveCall : activeConsultation.mode === 'VIDEO' ? copy.joinVideo : copy.joinVoice }}</el-button>
            <el-button v-if="canClose" :loading="closing" @click="finishConsultation">{{ copy.archive }}</el-button>
          </div>
        </div>
      </template>
      <div class="consultation-context"><p><b>{{ copy.symptom }}: </b>{{ activeConsultation.symptom }}</p><p v-if="activeConsultation.duration_text"><b>{{ copy.duration }}: </b>{{ activeConsultation.duration_text }}</p><p v-if="activeConsultation.medical_history"><b>{{ copy.history }}: </b>{{ activeConsultation.medical_history }}</p></div>
      <el-alert v-if="activeConsultation.mode !== 'TEXT' && !mediaSupported && activeConsultation.status === 'OPEN'" :title="copy.httpsRequired" type="info" show-icon :closable="false" class="consultation-notice" />
      <div v-if="callActive" class="consultation-media">
        <video ref="localVideo" autoplay muted playsinline />
        <video v-for="entry in remoteEntries" :key="entry[0]" :ref="element => attachRemoteVideo(entry[0], element)" autoplay playsinline />
        <p v-if="!remoteEntries.length">{{ copy.waiting }}</p>
      </div>
      <div ref="messageList" class="consultation-messages" role="log" aria-live="polite" :aria-label="copy.messages">
        <el-empty v-if="!activeConsultation.messages?.length" :description="copy.firstMessage" :image-size="60" />
        <article v-for="item in activeConsultation.messages || []" :key="item.id" :class="['consultation-message', { 'consultation-message--own': Number(item.sender_user_id) === currentUserId }]">
          <header><b>{{ item.sender_name || copy.participant }}</b><time>{{ dateLabel(item.sent_at) }}</time></header>
          <p>{{ item.content }}</p>
          <div v-if="item.attachment_record" class="consultation-attachment">
            <b>{{ copy.sharedRecord }} · {{ recordLabel(item.attachment_record) }}</b>
            <span class="consultation-source-status" data-testid="consultation-reference-status">{{ copy.referenceStatus }} {{ humanSourceStatus(item.attachment_record.referenceStatus) }}</span>
            <div v-if="item.attachment_record.attachments?.length" class="consultation-attachment-files">
              <el-button v-for="file in item.attachment_record.attachments" :key="file.id" link type="primary" :loading="downloadingId === file.id" @click="downloadAttachment(file)">{{ copy.download }} {{ file.file_name || copy.attachment }}</el-button>
            </div>
            <span v-else class="consultation-help">{{ copy.noFile }}</span>
          </div>
          <span v-else-if="item.attachment_record_id != null" class="consultation-source-status" data-testid="consultation-reference-status">{{ copy.referenceStatus }} {{ humanSourceStatus(null) }}</span>
        </article>
      </div>
      <div v-if="canMessage" class="consultation-compose">
        <div class="consultation-compose-toolbar">
          <el-radio-group v-model="draft.messageType" :disabled="sending" :aria-label="copy.messageType">
            <el-radio-button value="TEXT">{{ copy.text }}</el-radio-button>
            <el-radio-button v-if="recordOptions.length" value="IMAGE">{{ copy.image }}</el-radio-button>
            <el-radio-button v-if="recordOptions.length" value="FILE">{{ copy.file }}</el-radio-button>
          </el-radio-group>
          <span class="consultation-help">{{ copy.keyboardHint }}</span>
        </div>
        <el-select v-if="draft.messageType !== 'TEXT'" v-model="draft.attachmentRecordId" filterable :placeholder="copy.chooseRecord" :disabled="sending" :loading="recordsLoading" class="consultation-record-select" fit-input-width>
          <el-option class="consultation-record-option" v-for="record in recordOptions" :key="record.id" :value="record.id" :label="recordLabel(record)"><span>{{ recordLabel(record) }}</span><span class="consultation-source-status" data-testid="consultation-choice-status">{{ copy.sourceStatus }} {{ humanSourceStatus(record.sourceStatus) }}</span></el-option>
        </el-select>
        <el-input v-model="draft.content" class="consultation-editor" type="textarea" :autosize="{ minRows: 4, maxRows: 10 }" maxlength="4000" show-word-limit :disabled="sending" :placeholder="draft.messageType === 'TEXT' ? copy.messageHint : copy.attachmentHint" :aria-label="copy.messageHint" @keydown="onComposerKeydown" />
        <div class="consultation-compose-footer"><span class="consultation-help">{{ copy.messageHintShort }}</span><el-button type="primary" :loading="sending" :disabled="!canSend" @click="sendMessage">{{ copy.send }}</el-button></div>
      </div>
      <el-alert v-else :title="activeConsultation.status === 'CLOSED' ? copy.closedHint : copy.readOnlyHint" type="info" :closable="false" show-icon class="consultation-notice" />
    </el-card>
    <el-empty v-else-if="consultations.length && !openingId" :description="copy.openHint" :image-size="75" />
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/careJourney'
import { AUTH_STORAGE_KEYS, captureAuthSession, isAuthSessionCurrent } from '@/utils/authSession'

const props = defineProps({ patientId: { type: [Number, String], default: null }, isDoctor: Boolean, enabled: Boolean, locale: { type: String, default: 'en' } })
const route = useRoute(), router = useRouter()
const english = {
  inboxHint: 'Patients who choose you appear in this inbox. Open a consultation to reply; you do not need to select them in the workspace header.',
  inboxScope: 'Only consultations you participate in are listed. Consultation access does not grant access to the full medical record.',
  startTitle: 'Start a consultation', mode: 'Consultation mode', clinician: 'Doctor', chooseDoctor: 'Choose your doctor', symptom: 'Symptoms or question',
  symptomHint: 'Describe how you feel and what you would like to ask.', duration: 'How long has this lasted?', durationHint: 'For example, since yesterday',
  visibility: 'Family participation', visible: 'Visible to authorized family', private: 'Private', history: 'Relevant medical history',
  inviteFamily: 'Invite family members to this chat', inviteHint: 'Optional: choose who can send messages and join calls',
  historyHint: 'Previous conditions, current medication, allergies or other information you wish to share.',
  startHint: 'Your selected doctor receives this consultation in their inbox. You can send text in every mode.',
  start: 'Start consultation', inbox: 'Consultation inbox', consultationHistory: 'Your consultations', historyScope: 'Open a conversation to continue messaging or read its archive.',
  refresh: 'Refresh', listError: 'The consultation list could not be updated. Please retry.', emptyInbox: 'No consultations yet. New consultations addressed to you will appear here automatically.',
  emptyHistory: 'No consultations yet. Choose a doctor and describe your symptoms to begin.', selectPatient: 'Select a patient in the workspace header to begin.',
  patient: 'Patient', status: 'Status', updated: 'Latest activity', actions: 'Actions', reply: 'Open chat', view: 'View', retry: 'Reconnect',
  syncing: 'Checking for messages…', live: 'Messages update automatically', syncError: 'Updates paused; reconnect to continue',
  leaveCall: 'Leave call', joinVideo: 'Join video call', joinVoice: 'Join voice call', archive: 'Close and archive',
  httpsRequired: 'Voice and video require a secure HTTPS connection and camera/microphone access. Text messaging is available on this page.',
  waiting: 'Waiting for other participants to join…', messages: 'Consultation messages', firstMessage: 'The conversation is ready. Send the first message below.',
  referenceStatus: 'Reference status at last server read:', sourceStatus: 'Source status at last server read:',
  participant: 'Participant', sharedRecord: 'Shared medical record', download: 'Download', attachment: 'attachment', noFile: 'Record reference shared; this record has no attached file.',
  messageType: 'Message type', text: 'Text', image: 'Image / lab report', file: 'Medical file', keyboardHint: 'Enter for a new line · Ctrl/⌘ + Enter to send',
  chooseRecord: 'Choose a medical record to share', messageHint: 'Type your message here…', attachmentHint: 'Add a description of the record you are sharing…',
  messageHintShort: 'Only the participants in this consultation can read these messages.', send: 'Send message', closedHint: 'This consultation has been archived. Messages can be reviewed but no longer sent.',
  readOnlyHint: 'You can view this consultation but do not have permission to send messages.', openHint: 'Select a consultation above to open the conversation.',
  textMode: 'Text and images', voiceMode: 'Voice + text', videoMode: 'Video + text', open: 'In progress', closed: 'Archived',
  started: 'Consultation started. Your doctor can now see it in their inbox.', archivePrompt: 'Optional referral or specialist advice', archiveTitle: 'Close this consultation?',
  confirmArchive: 'Close and archive', cancel: 'Cancel', archived: 'Consultation archived', unavailableFile: 'This attachment is unavailable for download.',
  mediaError: 'Unable to start the camera or microphone. Check your browser permissions.', callError: 'The call connection was interrupted. You can join again.',
  attachmentLoadError: 'The attachment could not be opened.', noDoctor: 'No doctors are currently available.',
  draftTitle: 'Unsaved consultation draft', draftWarning: 'Keep editing to retain your unsent text for this patient and consultation, or discard it to continue. A message already sent may still complete.', keepEditing: 'Keep editing', discardDraft: 'Discard draft', roomLoadError: 'This consultation could not be opened. Your previous conversation and draft are still available.', roomDraftChanged: 'Your draft changed while the other consultation loaded. Your current conversation and text are kept; choose the other conversation again when ready.',
}
const chinese = {
  inboxHint: '患者选择您后，问诊会自动出现在这里。点击“进入聊天”即可回复，无需在顶部先选择患者。',
  inboxScope: '这里只展示您参与的问诊。问诊授权仅限本次会话，不会自动开放患者的全部病历。',
  startTitle: '发起问诊', mode: '问诊方式', clinician: '医生', chooseDoctor: '请选择接诊医生', symptom: '症状或咨询问题',
  symptomHint: '请描述您的不适、症状和想咨询的问题。', duration: '持续时间', durationHint: '例如：从昨天开始，持续约一天',
  visibility: '家属参与', visible: '已授权家属可见', private: '私密', history: '相关既往史',
  inviteFamily: '邀请家属一起问诊', inviteHint: '可选：选择能发送消息和加入通话的家属',
  historyHint: '填写既往疾病、当前用药、过敏史等希望向医生补充的信息。',
  startHint: '所选医生会在问诊收件箱收到本次问诊。所有问诊方式均支持文字交流。',
  start: '开始问诊', inbox: '问诊收件箱', consultationHistory: '我的问诊', historyScope: '打开问诊即可继续聊天，或查看已归档的问诊记录。',
  refresh: '刷新', listError: '问诊列表更新失败，请重试。', emptyInbox: '暂时没有问诊。患者向您发起问诊后，会自动显示在这里。',
  emptyHistory: '暂无问诊，请选择医生并填写症状后开始。', selectPatient: '请先在工作台顶部选择患者，再发起问诊。',
  patient: '患者', status: '状态', updated: '最近活动', actions: '操作', reply: '进入聊天', view: '查看', retry: '重新连接',
  syncing: '正在检查新消息…', live: '消息自动更新中', syncError: '更新已暂停，请重新连接',
  leaveCall: '退出通话', joinVideo: '加入视频通话', joinVoice: '加入语音通话', archive: '结束并归档',
  httpsRequired: '语音和视频需要通过 HTTPS 安全连接访问，并允许摄像头或麦克风权限。当前页面可以正常使用文字聊天。',
  waiting: '等待其他参与者加入通话…', messages: '问诊消息', firstMessage: '会话已建立，请在下方输入并发送第一条消息。',
  referenceStatus: '引用状态（上次服务器读取）：', sourceStatus: '来源状态（上次服务器读取）：',
  participant: '参与者', sharedRecord: '共享医疗记录', download: '下载', attachment: '附件', noFile: '已共享该记录信息，这条记录暂无文件附件。',
  messageType: '消息类型', text: '文字', image: '图片／化验单', file: '医疗文件', keyboardHint: '回车换行 · Ctrl／⌘ + 回车发送',
  chooseRecord: '请选择要共享的医疗记录', messageHint: '在这里输入消息…', attachmentHint: '请补充要共享的医疗记录说明…',
  messageHintShort: '消息仅向本次问诊参与者开放。', send: '发送消息', closedHint: '本次问诊已结束并归档，可以查看历史消息，不能继续发送。',
  readOnlyHint: '您可以查看本次问诊，但暂无发送消息的权限。', openHint: '在上方选择一条问诊，打开聊天窗口。',
  textMode: '文字／图文', voiceMode: '语音＋文字', videoMode: '视频＋文字', open: '进行中', closed: '已归档',
  started: '问诊已发起，医生现在可以在收件箱中查看并回复。', archivePrompt: '可选：转科或专科建议', archiveTitle: '结束本次问诊？',
  confirmArchive: '结束并归档', cancel: '取消', archived: '问诊已归档', unavailableFile: '暂时无法下载此附件。',
  mediaError: '无法启动摄像头或麦克风，请检查浏览器权限。', callError: '通话连接已中断，请重新加入。',
  attachmentLoadError: '无法打开该附件。', noDoctor: '当前暂无可接诊医生。',
  draftTitle: '问诊草稿尚未提交', draftWarning: '继续编辑可保留当前患者和问诊的未发送内容；放弃草稿后可继续操作。已发送的消息请求可能已经完成。', keepEditing: '继续编辑', discardDraft: '放弃草稿', roomLoadError: '未能打开这次问诊。原会话和草稿仍然保留。', roomDraftChanged: '另一问诊加载期间，您修改了草稿。当前会话和内容已保留；准备好后请重新选择另一问诊。',
}
const copy = computed(() => props.locale === 'zh' ? chinese : english)
const modeOptions = computed(() => [{ value: 'TEXT', label: copy.value.textMode }, { value: 'VOICE', label: copy.value.voiceMode }, { value: 'VIDEO', label: copy.value.videoMode }])
const modeLabel = value => modeOptions.value.find(item => item.value === value)?.label || value
const statusLabel = value => value === 'OPEN' ? copy.value.open : copy.value.closed
const dateLabel = value => value ? String(value).replace('T', ' ').slice(0, 16) : '—'
const recordTypes = { BLOOD: ['Blood test', '血液检查'], URINE: ['Urine test', '尿液检查'], LIVER: ['Liver function', '肝功能'], KIDNEY: ['Kidney function', '肾功能'], BONE: ['Bone metabolism', '骨代谢'], IRON: ['Iron metabolism', '铁代谢'], IMAGE: ['Imaging report', '影像报告'], OTHER: ['Other record', '其他记录'] }
const recordLabel = record => {
  const type = record.record_type || record.recordType
  return [record.record_date || record.recordDate, record.hospital_name || record.hospitalName, recordTypes[type]?.[props.locale === 'zh' ? 1 : 0] || type].filter(Boolean).join(' · ') || copy.value.sharedRecord
}
const currentUserId = ref(Number(localStorage.getItem('userId')))
const humanSourceLabels = {"CURRENT": ["Current", "当前"], "UNTRACKED": ["Legacy source; untracked", "旧版来源，未跟踪"], "UNKNOWN_NEEDS_RECHECK": ["Reference needs rechecking", "引用需重新核对"], "SOURCE_REVIEW_REQUIRED": ["Source verification required", "来源需核对"], "SOURCE_CHANGED": ["Source changed; recheck reference", "来源已变化，需重新核对引用"], "SOURCE_INVALIDATED": ["Source invalidated", "来源已作废"], "SOURCE_UNAVAILABLE": ["Source status unavailable", "来源状态不可用"]}
function humanSourceStatus(value) {
  const key = !syncError.value && typeof value === 'string' && Object.hasOwn(humanSourceLabels, value) ? value : 'SOURCE_UNAVAILABLE'
  return humanSourceLabels[key][props.locale === 'zh' ? 1 : 0]
}
let contextOwner = null
function currentAuthorization() { return !!contextOwner && isAuthSessionCurrent(contextOwner.auth) && contextOwner.actor === localStorage.getItem('userId') }
const clinicians = ref([]), invitees = ref([]), participantUserIds = ref([]), doctorsLoading = ref(false), consultations = ref([]), listLoading = ref(false), listError = ref(false)
const activeConsultation = ref(null), openingId = ref(null), starting = ref(false), sending = ref(false), closing = ref(false)
const chatSyncing = ref(false), syncError = ref(false), recordOptions = ref([]), recordsLoading = ref(false), downloadingId = ref(null)
const form = reactive({ mode: 'TEXT', doctorUserId: null, symptom: '', durationText: '', medicalHistory: '' }), visibleToFamily = ref(true)
const draft = reactive({ messageType: 'TEXT', content: '', attachmentRecordId: null }), messageList = ref(null)
const callActive = ref(false), callStarting = ref(false), localVideo = ref(null), remoteStreams = ref({})
const remoteEntries = computed(() => Object.entries(remoteStreams.value))
const mediaSupported = computed(() => Boolean(window.isSecureContext && navigator.mediaDevices?.getUserMedia && window.RTCPeerConnection))
const canStart = computed(() => Boolean(props.patientId && form.doctorUserId && form.symptom.trim() && !starting.value))
const canMessage = computed(() => activeConsultation.value?.status === 'OPEN' && activeConsultation.value?.can_message === true)
const canClose = computed(() => activeConsultation.value?.status === 'OPEN' && activeConsultation.value?.can_close === true)
const canSend = computed(() => canMessage.value && !openingId.value && !sending.value && !closing.value && Boolean(draft.content.trim()) && (draft.messageType === 'TEXT' || recordOptions.value.some(record => Number(record.id) === Number(draft.attachmentRecordId))))
let disposed = false, contextVersion = 0, roomVersion = 0, navigationVersion = 0, listRequest = 0, listTimer = null, messageTimer = null, signalTimer = null
let callOwner = null
let localStream = null, callVersion = 0, lastSignalId = 0, signalBusy = false
const peers = new Map(), pendingIce = new Map()
function currentContext(version) { return !disposed && props.enabled && currentAuthorization() && version === contextVersion }
function currentRoom(version, id) { return !disposed && props.enabled && currentAuthorization() && version === roomVersion && Number(activeConsultation.value?.id) === Number(id) }
function consultationRowClass({ row }) { return Number(row.id) === Number(activeConsultation.value?.id) ? 'consultation-selected-row' : '' }
let discardPrompt = null, routeWrite = null, removeRouteGuard = null, draftRevision = 0
watch(draft, () => { draftRevision++ }, { deep: true, flush: 'sync' })
const messageDraftDirty = () => Boolean(draft.content.trim() || draft.attachmentRecordId || draft.messageType !== 'TEXT' || sending.value)
const startSnapshot = () => JSON.stringify({ form, visibleToFamily: visibleToFamily.value, participants: participantUserIds.value })
let startBaseline = startSnapshot()
const startDraftDirty = () => starting.value || startSnapshot() !== startBaseline
const unsavedDraft = () => props.enabled && (messageDraftDirty() || (!props.isDoctor && startDraftDirty()))
const draftSnapshot = () => JSON.stringify({ draft, form, visibleToFamily: visibleToFamily.value, participants: participantUserIds.value })
async function confirmDiscard() {
  if (discardPrompt) return discardPrompt
  const context = contextVersion, original = draftSnapshot()
  discardPrompt = ElMessageBox.confirm(copy.value.draftWarning, copy.value.draftTitle, {
    confirmButtonText: copy.value.discardDraft, cancelButtonText: copy.value.keepEditing,
    distinguishCancelAndClose: true, closeOnClickModal: false, type: 'warning'
  }).then(() => currentContext(context) && draftSnapshot() === original, () => false)
  try { return await discardPrompt } finally { discardPrompt = null }
}
function clearUnsavedDrafts() {
  clearDraft(); Object.assign(form, { mode: 'TEXT', doctorUserId: null, symptom: '', durationText: '', medicalHistory: '' })
  participantUserIds.value = []; visibleToFamily.value = true; startBaseline = startSnapshot()
}
async function protectRouteChange(to, from) {
  if (routeWrite && to?.path === routeWrite.path && routeQueryKey(to.query) === routeQueryKey(routeWrite.query)) return true
  const target = Number(to?.query?.consultationId)
  if (to?.path === from?.path && to?.query?.tab === 'consultation' && Number.isSafeInteger(target) && target > 0 && target !== Number(activeConsultation.value?.id)) {
    // Commit a linked-room route only after its target loads; failed reads retain source input.
    return await openConsultation(target, false) === true
  }
  if (!unsavedDraft()) return true
  if (!(await confirmDiscard())) return false
  clearUnsavedDrafts(); return true
}
function protectPatientChange(event) {
  if (event.defaultPrevented || !unsavedDraft()) return
  event.preventDefault()
  void confirmDiscard().then(discard => { if (discard) clearUnsavedDrafts() })
}
function protectPageUnload(event) { if (unsavedDraft()) { event.preventDefault(); event.returnValue = '' } }
function sessionCleared() { resetContext() }
function sessionStorageChanged(event) { if (event.key == null || AUTH_STORAGE_KEYS.includes(event.key)) sessionCleared() }
const routeQueryKey = query => JSON.stringify(Object.keys(query || {}).sort().map(key => [key, query[key]]))
async function replaceRoomRoute(target) {
  const write = { path: target.path || route.path, query: { ...target.query } }
  routeWrite = write
  try { return await router.replace(target) } finally { if (routeWrite === write) routeWrite = null }
}
function clearDraft() { Object.assign(draft, { messageType: 'TEXT', content: '', attachmentRecordId: null }) }
function stopListSync() { clearTimeout(listTimer); listTimer = null }
function stopMessageSync() { clearTimeout(messageTimer); messageTimer = null }
function scheduleListSync() { stopListSync(); if (props.enabled && !disposed && !listError.value) listTimer = window.setTimeout(() => loadConsultations(), 6000) }
function scheduleMessageSync() { stopMessageSync(); if (props.enabled && !disposed && activeConsultation.value?.status === 'OPEN' && !syncError.value) messageTimer = window.setTimeout(refreshActiveConsultation, 1800) }
async function loadConsultations() {
  if (!props.enabled || disposed || !currentAuthorization()) return
  if (!props.isDoctor && !props.patientId) { consultations.value = []; return }
  const context = contextVersion, requestId = ++listRequest, patientId = props.patientId
  listLoading.value = true
  try {
    const response = await (props.isDoctor ? api.listConsultationInbox() : api.listConsultations(patientId))
    if (!currentContext(context) || requestId !== listRequest) return
    consultations.value = response.data || []
    listError.value = false
  } catch {
    if (currentContext(context) && requestId === listRequest) listError.value = true
  } finally {
    if (currentContext(context) && requestId === listRequest) { listLoading.value = false; scheduleListSync() }
  }
}
async function loadClinicians() {
  if (props.isDoctor || !currentAuthorization()) return
  const context = contextVersion
  doctorsLoading.value = true
  try { const response = await api.listClinicians(); if (currentContext(context)) clinicians.value = response.data || [] } catch {} finally { if (currentContext(context)) doctorsLoading.value = false }
}
async function loadRecordOptions(version, id) {
  if (!currentRoom(version, id)) return
  recordsLoading.value = true
  try { const response = await api.listConsultationRecordOptions(id); if (currentRoom(version, id)) recordOptions.value = response.data || [] } catch {} finally { if (currentRoom(version, id)) recordsLoading.value = false }
}
async function loadInvitees() {
  if (props.isDoctor || !props.patientId || !currentAuthorization()) return
  const context = contextVersion
  try { const response = await api.listConsultationInvitees(props.patientId); if (currentContext(context)) invitees.value = response.data || [] } catch {}
}
async function scrollMessages(force = false) {
  const element = messageList.value
  const nearBottom = !element || element.scrollHeight - element.scrollTop - element.clientHeight < 100
  await nextTick()
  if (messageList.value && (force || nearBottom)) messageList.value.scrollTop = messageList.value.scrollHeight
}
async function activateConsultation(detail) {
  stopMessageSync()
  stopCall()
  const version = ++roomVersion
  activeConsultation.value = detail
  syncError.value = false; chatSyncing.value = false; recordOptions.value = []
  sending.value = false; closing.value = false; downloadingId.value = null; clearDraft()
  await scrollMessages(true)
  if (!currentRoom(version, detail.id)) return
  scheduleMessageSync()
  void loadRecordOptions(version, detail.id)
}
async function openConsultation(id, updateRoute = true) {
  if (!id || disposed || !props.enabled || !currentAuthorization() || Number(id) === Number(activeConsultation.value?.id)) return
  const context = contextVersion, navigation = ++navigationVersion
  if (messageDraftDirty() && !(await confirmDiscard())) {
    if (currentContext(context) && navigation === navigationVersion) { openingId.value = null; scheduleMessageSync() }
    return false
  }
  if (!currentContext(context) || navigation !== navigationVersion) return false
  // Approval belongs to this exact source-room and draft revision, including clean drafts.
  const sourceRoom = roomVersion, approvedRevision = draftRevision
  // Keep the source room and draft until the target is successfully authorized and loaded.
  stopMessageSync(); openingId.value = id
  try {
    const response = await api.getConsultation(id)
    if (!currentContext(context) || navigation !== navigationVersion || sourceRoom !== roomVersion) return false
    if (approvedRevision !== draftRevision) { ElMessage.warning(copy.value.roomDraftChanged); return false }
    await activateConsultation(response.data)
    if (updateRoute && currentContext(context) && navigation === navigationVersion && Number(activeConsultation.value?.id) === Number(id)) await replaceRoomRoute({ query: { ...route.query, tab: 'consultation', consultationId: String(id) } })
    return currentContext(context) && navigation === navigationVersion && Number(activeConsultation.value?.id) === Number(id)
  } catch {
    if (currentContext(context) && navigation === navigationVersion) { ElMessage.error(copy.value.roomLoadError); scheduleMessageSync() }
  } finally { if (currentContext(context) && navigation === navigationVersion) { openingId.value = null; if (sourceRoom === roomVersion) scheduleMessageSync() } }
}
async function beginConsultation() {
  if (!canStart.value || !currentAuthorization()) return
  if (messageDraftDirty() && !(await confirmDiscard())) return
  if (!canStart.value || !currentAuthorization()) return
  const context = contextVersion, navigation = ++navigationVersion, patientId = props.patientId
  starting.value = true; openingId.value = null
  try {
    const response = await api.startConsultation({ ...form, symptom: form.symptom.trim(), patientId, familyVisibility: visibleToFamily.value ? 'VISIBLE' : 'PRIVATE', participantUserIds: visibleToFamily.value ? [...participantUserIds.value] : [] })
    if (!currentContext(context) || navigation !== navigationVersion) return
    form.symptom = ''; form.durationText = ''; form.medicalHistory = ''; startBaseline = startSnapshot()
    const version = roomVersion + 1
    await activateConsultation(response.data)
    if (!currentContext(context) || navigation !== navigationVersion || !currentRoom(version, response.data.id)) return
    await replaceRoomRoute({ query: { ...route.query, tab: 'consultation', consultationId: String(response.data.id) } })
    if (!currentContext(context) || navigation !== navigationVersion || !currentRoom(version, response.data.id)) return
    ElMessage.success(copy.value.started)
    await loadConsultations()
  } catch {} finally { if (currentContext(context)) starting.value = false }
}
async function refreshActiveConsultation() {
  if (!activeConsultation.value || chatSyncing.value || !props.enabled || disposed || !currentAuthorization()) return
  const version = roomVersion, id = activeConsultation.value.id, previous = (activeConsultation.value.messages || []).at(-1)?.id
  chatSyncing.value = true
  try {
    const response = await api.getConsultation(id)
    if (!currentRoom(version, id)) return
    activeConsultation.value = response.data
    syncError.value = false
    if ((response.data.messages || []).at(-1)?.id !== previous) await scrollMessages()
    if (!currentRoom(version, id)) return
    if (response.data.status !== 'OPEN' || response.data.can_message !== true) { stopCall(); clearDraft() }
  } catch (error) { if (currentRoom(version, id)) { if ([401, 403].includes(Number(error?.response?.status || error?.code))) resetContext(); else syncError.value = true } } finally {
    if (currentRoom(version, id)) { chatSyncing.value = false; scheduleMessageSync() }
  }
}
async function retrySync() {
  if (!currentAuthorization()) { resetContext(); await initialize(false); return }
  listError.value = false
  await Promise.all([loadConsultations(), activeConsultation.value ? refreshActiveConsultation() : Promise.resolve()])
}
async function sendMessage() {
  if (!canSend.value || !currentAuthorization()) return
  const version = roomVersion, id = activeConsultation.value.id
  const payload = { ...draft, content: draft.content.trim(), attachmentRecordId: draft.messageType === 'TEXT' ? null : draft.attachmentRecordId }
  sending.value = true
  try {
    await api.sendConsultationMessage(id, payload)
    if (!currentRoom(version, id)) return
    clearDraft()
    await refreshActiveConsultation()
    if (!currentRoom(version, id)) return
    await scrollMessages(true)
    if (!currentRoom(version, id)) return
    void loadConsultations()
  } catch (error) { if (currentRoom(version, id) && [401, 403].includes(Number(error?.response?.status || error?.code))) resetContext() } finally { if (currentRoom(version, id)) sending.value = false }
}
function onComposerKeydown(event) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) { event.preventDefault(); void sendMessage() }
}
async function finishConsultation() {
  if (!canClose.value || closing.value || !currentAuthorization()) return
  const version = roomVersion, id = activeConsultation.value.id
  closing.value = true
  try {
    let value
    const options = { confirmButtonText: copy.value.confirmArchive, cancelButtonText: copy.value.cancel }
    if (props.isDoctor) ({ value } = await ElMessageBox.prompt(copy.value.archivePrompt, copy.value.archiveTitle, { ...options, inputType: 'textarea' }))
    else await ElMessageBox.confirm(copy.value.closedHint, copy.value.archiveTitle, options)
    if (!currentRoom(version, id) || !canClose.value) return
    closing.value = true
    const response = await api.closeConsultation(id, value)
    if (!currentRoom(version, id)) return
    stopMessageSync(); stopCall(); roomVersion++; chatSyncing.value = false; closing.value = false
    activeConsultation.value = response.data; recordOptions.value = []; recordsLoading.value = false; clearDraft()
    ElMessage.success(copy.value.archived)
    await loadConsultations()
  } catch {} finally { if (currentRoom(version, id)) closing.value = false }
}
async function downloadAttachment(file) {
  const version = roomVersion, id = activeConsultation.value?.id
  if (!id || downloadingId.value || !currentRoom(version, id)) return
  downloadingId.value = file.id
  try {
    const response = await api.getConsultationAttachment(id, file.id)
    if (!currentRoom(version, id)) return
    const attachment = response.data
    if (!attachment?.file_content) { ElMessage.warning(copy.value.unavailableFile); return }
    const encoded = String(attachment.file_content).replace(/^data:[^;]+;base64,/, '')
    const bytes = Uint8Array.from(atob(encoded), character => character.charCodeAt(0))
    const url = URL.createObjectURL(new Blob([bytes], { type: attachment.file_type === 'PDF' ? 'application/pdf' : 'application/octet-stream' }))
    const link = document.createElement('a')
    link.href = url; link.download = String(attachment.file_name || 'attachment').replace(/[\\/]/g, '_')
    link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch { if (currentRoom(version, id)) ElMessage.error(copy.value.attachmentLoadError) } finally { if (currentRoom(version, id)) downloadingId.value = null }
}
function attachRemoteVideo(id, element) { if (element && remoteStreams.value[id] && element.srcObject !== remoteStreams.value[id]) element.srcObject = remoteStreams.value[id] }
function peerFor(target, consultationId) {
  if (peers.has(target)) return peers.get(target)
  const configured = String(import.meta.env.VITE_WEBRTC_ICE_SERVERS || '').split(',').map(value => value.trim()).filter(Boolean).map(urls => ({ urls }))
  const pc = new RTCPeerConnection({ iceServers: configured })
  if (localStream) localStream.getTracks().forEach(track => pc.addTrack(track, localStream))
  pc.onicecandidate = event => { if (event.candidate && callActive.value) api.sendConsultationSignal(consultationId, { targetUserId: target, signalType: 'ICE', payload: event.candidate.toJSON() }).catch(() => {}) }
  pc.ontrack = event => { remoteStreams.value = { ...remoteStreams.value, [target]: event.streams[0] } }
  pc.onconnectionstatechange = () => { if (['failed', 'closed'].includes(pc.connectionState)) { const next = { ...remoteStreams.value }; delete next[target]; remoteStreams.value = next } }
  peers.set(target, pc)
  return pc
}
async function startCall() {
  if (!canMessage.value || !mediaSupported.value || callStarting.value || callActive.value || !currentAuthorization()) return
  const version = roomVersion, id = activeConsultation.value.id, call = ++callVersion
  callOwner = { auth: { ...contextOwner.auth }, actor: contextOwner.actor, roomId: id }
  callStarting.value = true
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: activeConsultation.value.mode === 'VIDEO' })
    if (!currentRoom(version, id) || call !== callVersion || !canMessage.value) { stream.getTracks().forEach(track => track.stop()); return }
    localStream = stream; callActive.value = true
    await nextTick()
    if (localVideo.value) localVideo.value.srcObject = localStream
    const others = (activeConsultation.value.participants || []).map(item => Number(item.user_id)).filter(userId => userId !== currentUserId.value)
    for (const target of others) {
      // One offerer per pair prevents simultaneous offers when both participants join.
      if (currentUserId.value > target) continue
      const pc = peerFor(target, id), offer = await pc.createOffer()
      await pc.setLocalDescription(offer)
      await api.sendConsultationSignal(id, { targetUserId: target, signalType: 'OFFER', payload: offer })
    }
    if (currentRoom(version, id) && call === callVersion) { signalTimer = window.setInterval(pollSignals, 1400); await pollSignals() }
  } catch { if (currentRoom(version, id) && call === callVersion) { stopCall(); ElMessage.error(copy.value.mediaError) } } finally { if (currentRoom(version, id)) callStarting.value = false }
}
async function pollSignals() {
  if (!callActive.value || !activeConsultation.value || signalBusy) return
  const version = roomVersion, id = activeConsultation.value.id, call = callVersion
  signalBusy = true
  try {
    const rows = (await api.listConsultationSignals(id, lastSignalId)).data || []
    if (!currentRoom(version, id) || call !== callVersion) return
    for (const row of rows) {
      if (!callActive.value || call !== callVersion) break
      const sender = Number(row.sender_user_id), payload = typeof row.payload_json === 'string' ? JSON.parse(row.payload_json || '{}') : row.payload_json
      if (row.signal_type === 'HANGUP') { peers.get(sender)?.close(); peers.delete(sender); pendingIce.delete(sender); lastSignalId = Math.max(lastSignalId, Number(row.id)); continue }
      const pc = peerFor(sender, id)
      if (row.signal_type === 'OFFER') {
        if (pc.signalingState !== 'stable') await pc.setLocalDescription({ type: 'rollback' })
        await pc.setRemoteDescription(payload)
        for (const ice of pendingIce.get(sender) || []) await pc.addIceCandidate(ice)
        pendingIce.delete(sender)
        const answer = await pc.createAnswer(); await pc.setLocalDescription(answer)
        await api.sendConsultationSignal(id, { targetUserId: sender, signalType: 'ANSWER', payload: answer })
      } else if (row.signal_type === 'ANSWER' && pc.signalingState === 'have-local-offer') {
        await pc.setRemoteDescription(payload)
        for (const ice of pendingIce.get(sender) || []) await pc.addIceCandidate(ice)
        pendingIce.delete(sender)
      } else if (row.signal_type === 'ICE') {
        if (pc.remoteDescription) await pc.addIceCandidate(payload)
        else pendingIce.set(sender, [...(pendingIce.get(sender) || []), payload])
      }
      lastSignalId = Math.max(lastSignalId, Number(row.id))
    }
  } catch { if (currentRoom(version, id) && call === callVersion) { stopCall(); ElMessage.warning(copy.value.callError) } } finally { signalBusy = false }
}
function stopCall() {
  callVersion++
  clearInterval(signalTimer); signalTimer = null
  const owner = callOwner, id = activeConsultation.value?.id
  if (id && callActive.value && owner && Number(owner.roomId) === Number(id) && isAuthSessionCurrent(owner.auth) && owner.actor === localStorage.getItem('userId')) for (const target of peers.keys()) api.sendConsultationSignal(owner.roomId, { targetUserId: target, signalType: 'HANGUP', payload: {} }).catch(() => {})
  callOwner = null
  peers.forEach(pc => pc.close()); peers.clear(); pendingIce.clear()
  localStream?.getTracks().forEach(track => track.stop())
  localStream = null; remoteStreams.value = {}; callActive.value = false; callStarting.value = false; lastSignalId = 0
}
function resetContext() {
  contextVersion++; roomVersion++; navigationVersion++; listRequest++
  stopListSync(); stopMessageSync(); stopCall(); contextOwner = null
  activeConsultation.value = null; consultations.value = []; recordOptions.value = []; openingId.value = null
  chatSyncing.value = false; listLoading.value = false; recordsLoading.value = false; starting.value = false; sending.value = false; closing.value = false; downloadingId.value = null
  listError.value = false; syncError.value = false; clearDraft()
  Object.assign(form, { mode: 'TEXT', doctorUserId: null, symptom: '', durationText: '', medicalHistory: '' })
  visibleToFamily.value = true; invitees.value = []; participantUserIds.value = []; startBaseline = startSnapshot()
}
async function initialize(allowLinkedConsultation = true) {
  if (!props.enabled || disposed) return
  contextOwner = { auth: captureAuthSession(), actor: localStorage.getItem('userId') }
  currentUserId.value = Number(contextOwner.actor)
  if (!currentAuthorization()) return
  const context = contextVersion
  await Promise.all([loadConsultations(), loadClinicians(), loadInvitees()])
  if (allowLinkedConsultation && currentContext(context) && route.query.consultationId) await openConsultation(route.query.consultationId, false)
}
watch(() => props.patientId, () => {
  resetContext()
  if (route.query.consultationId) { const query = { ...route.query }; delete query.consultationId; void replaceRoomRoute({ query }) }
  void initialize(false)
}, { flush: 'sync' })
watch(() => props.enabled, enabled => { resetContext(); if (enabled) void initialize() }, { flush: 'sync' })
watch(() => route.query.consultationId, id => { if (props.enabled && id && Number(id) !== Number(activeConsultation.value?.id)) void openConsultation(id, false) })
onMounted(() => {
  removeRouteGuard = router.beforeEach(protectRouteChange)
  window.addEventListener('care-plan-before-context-change', protectPatientChange)
  window.addEventListener('beforeunload', protectPageUnload)
  window.addEventListener('auth-session-cleared', sessionCleared)
  window.addEventListener('auth-session-changed', sessionCleared)
  window.addEventListener('storage', sessionStorageChanged)
  void initialize()
})
onBeforeUnmount(() => {
  removeRouteGuard?.(); window.removeEventListener('care-plan-before-context-change', protectPatientChange)
  window.removeEventListener('beforeunload', protectPageUnload); window.removeEventListener('auth-session-cleared', sessionCleared); window.removeEventListener('auth-session-changed', sessionCleared); window.removeEventListener('storage', sessionStorageChanged)
  disposed = true; resetContext()
})
</script>

<style scoped>
.consultation-workspace{min-width:0}.consultation-notice{margin-bottom:18px}.consultation-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:20px}.consultation-grid--single{grid-template-columns:minmax(0,1fr)}.consultation-grid>*{min-width:0}.consultation-form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}.form-wide{grid-column:1/-1}.consultation-form-grid .el-select{width:100%}.consultation-card-head{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap}.consultation-card-head h3{font-size:18px;margin:0 0 6px}.consultation-help{color:var(--ink-500);font-size:12px;line-height:1.6;margin:6px 0 12px}.consultation-card-head .consultation-help{margin:4px 0 0}.consultation-room{margin-top:22px;min-width:0}.consultation-room-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.consultation-room-actions .el-button+.el-button{margin-left:0}.consultation-sync{display:inline-flex;align-items:center;gap:7px;margin-top:8px;color:var(--care-700);font-size:12px}.consultation-sync i{width:7px;height:7px;background:var(--success);border-radius:50%;box-shadow:0 0 0 3px #2ca88018}.consultation-sync--error{color:var(--warning)}.consultation-sync--error i{background:var(--warning)}.consultation-context{padding:14px 16px;background:var(--surface-subtle);border-radius:12px;margin-bottom:18px;line-height:1.7;overflow-wrap:anywhere}.consultation-context p{margin:6px 0 0}.consultation-messages{display:flex;flex-direction:column;align-items:flex-start;gap:14px;min-height:180px;max-height:460px;overflow-y:auto;padding:4px 8px 18px 0;scrollbar-gutter:stable}.consultation-message{max-width:88%;min-width:min(260px,100%);padding:14px 16px;border:1px solid var(--line);border-radius:4px 16px 16px;background:var(--paper);overflow-wrap:anywhere}.consultation-message--own{align-self:flex-end;background:var(--care-50);border-color:var(--care-100);border-radius:16px 4px 16px 16px}.consultation-message header{display:flex;gap:20px;align-items:baseline;justify-content:space-between;flex-wrap:wrap}.consultation-message time{color:var(--ink-500);font-size:11px}.consultation-message p{margin:8px 0 0;white-space:pre-wrap;line-height:1.75}.consultation-attachment{display:grid;gap:6px;margin-top:12px;padding-top:10px;border-top:1px solid var(--line);font-size:13px}.consultation-attachment-files{display:flex;flex-direction:column;align-items:flex-start;gap:6px}.consultation-attachment-files .el-button{max-width:100%;height:auto;white-space:normal;text-align:left;margin-left:0}.consultation-compose{display:grid;gap:12px;padding-top:18px;border-top:1px solid var(--line)}.consultation-compose-toolbar,.consultation-compose-footer{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}.consultation-compose .consultation-help{margin:0}.consultation-record-select,.consultation-editor{width:100%;min-width:0}.consultation-editor :deep(.el-textarea__inner){min-height:130px!important;line-height:1.8;padding:14px 16px 26px;resize:vertical}.consultation-compose-footer .el-button{min-width:114px}.consultation-media{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;margin-bottom:18px;padding:12px;border-radius:14px;background:#173a36}.consultation-media video{width:100%;min-height:160px;max-height:320px;object-fit:cover;border-radius:10px;background:#173a36}.consultation-media p{color:#cbd5e1;padding:20px}:deep(.consultation-selected-row){--el-table-tr-bg-color:var(--care-50)}@media(max-width:1100px){.consultation-grid{grid-template-columns:minmax(0,1fr)}}@media(max-width:650px){.consultation-form-grid{grid-template-columns:1fr}.consultation-message{max-width:96%;min-width:0}.consultation-message header{gap:5px}.consultation-room-actions{width:100%}.consultation-compose-toolbar{align-items:flex-start;flex-direction:column}.consultation-compose-toolbar :deep(.el-radio-button__inner){padding:9px}.consultation-compose-footer{align-items:flex-start}.consultation-compose-footer .consultation-help{max-width:65%}.consultation-media{grid-template-columns:1fr}}
</style>

<style scoped>
.consultation-source-status{display:block;max-width:100%;white-space:normal;overflow-wrap:anywhere;line-height:1.6}
.consultation-record-option{height:auto;min-height:34px;line-height:1.5;white-space:normal;overflow-wrap:anywhere;padding-top:8px;padding-bottom:8px}
</style>
