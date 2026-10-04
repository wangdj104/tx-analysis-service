<template>
  <section ref="reportElement" class="execution-report" aria-labelledby="execution-report-heading" :aria-busy="busy">
    <header><h2 id="execution-report-heading">{{ t('Care execution & visit preparation','照护执行与就诊准备') }}</h2><p>{{ t('Patient','患者') }} #{{ patientId }} · {{ label('scope',planId ? 'SINGLE_PLAN' : 'ALL_PLANS') }}<template v-if="planId"> #{{ planId }}</template></p></header>
    <form class="report-controls" @submit.prevent="generate">
      <label>{{ t('From date','开始日期') }}<input v-model="form.fromDate" type="date" required :max="today" /></label>
      <label>{{ t('To date','结束日期') }}<input v-model="form.toDate" type="date" required :max="today" /></label>
      <label>{{ t('Timezone (IANA)','时区（IANA）') }}<input v-model.trim="form.timeZone" required placeholder="Asia/Shanghai" list="report-timezones" /></label>
      <datalist id="report-timezones"><option value="UTC"/><option value="Asia/Shanghai"/><option value="America/New_York"/><option value="Europe/London"/></datalist>
      <label>{{ t('Report language','报告语言') }}<select v-model="form.language"><option value="en">English</option><option value="zh-CN">中文</option></select></label>
      <button type="button" @click="resetDates" :disabled="busy">{{ t('Use last 30 calendar days','使用最近30个日历日') }}</button>
      <button type="submit" data-testid="preview-report" :disabled="busy || !valid">{{ t('Generate / refresh preview','生成 / 刷新预览') }}</button>
      <button v-for="format in formats" :key="format" type="button" :data-testid="`download-${format}`" :disabled="busy || !valid" @click="download(format)">{{ label('format',format) }}</button>
    </form>
    <p v-if="!valid" class="report-controls" role="alert">{{ t('Choose a valid patient, IANA timezone and 1–366 calendar dates ending no later than today.','请选择有效患者、IANA时区及不超过今天的1至366个日历日。') }}</p>
    <p class="report-controls">{{ t('Every download is generated again with current authorization. Saved copies cannot be recalled. Original clinical text is not translated; CSV text is escaped for spreadsheet safety.','每次下载均重新生成并检查当前权限。已保存的副本无法收回。原临床文本不翻译；CSV文本经过电子表格安全转义。') }}</p>
    <p v-if="busy" role="status">{{ t('Generating authorized material…','正在生成已授权材料…') }}</p>
    <p v-else-if="stale && !state.report && !state.error" role="status">{{ t('The previous preview has expired. Generate again for this context.','旧预览已失效，请按当前范围重新生成。') }}</p>
    <p v-else-if="!state.report && !state.error && !state.lastExport" role="status">{{ t('Choose a range and generate a report. Current difficulties and review waits are not filtered by these dates.','选择期间并生成报告。当前困难和待复核事项不受日期筛选影响。') }}</p>
    <section v-if="state.error" role="alert" class="report-notice"><p>{{ errorText }}</p><p v-if="state.error.limitKind">{{ state.error.limitKind }} · {{ t('Limit','上限') }} {{ state.error.limit }}</p><p v-if="state.error.errorCode==='REPORT_LIMIT_EXCEEDED'">{{ t('Shorten the activity period, open a single plan, or download either CSV independently. Questions are excluded from single-plan reports and CSV.','可缩短活动期间、打开单个计划，或独立下载任一种CSV。单计划报告及CSV不包含问题。') }}</p></section>
    <section v-if="state.lastExport" role="status" class="report-notice" data-testid="last-export">
      <h3>{{ label('format',state.lastExport.format) }}</h3>
      <p v-if="state.lastExport.isEmpty">{{ t('0 rows · header only. This empty CSV does not contain offline scope metadata.','0条 · 仅表头。此空CSV不包含离线范围元数据。') }}</p>
      <p v-else-if="state.lastExport.rowCount != null">{{ state.lastExport.rowCount }} {{ t('rows','条') }}</p>
      <p>{{ label('scope',state.lastExport.scope.label) }} · {{ t('Patient','患者') }} #{{ state.lastExport.scope.patientId }}<template v-if="state.lastExport.scope.planId"> · {{ t('Plan','计划') }} #{{ state.lastExport.scope.planId }}</template></p>
      <p>{{ state.lastExport.rangeKnown ? `${state.lastExport.fromDate} — ${state.lastExport.toDate}` : t('Range not available','期间不可用') }} · {{ state.lastExport.timeZone }} · {{ state.lastExport.language }} · {{ t('Report schema','报告schema') }} {{ state.lastExport.reportSchemaVersion }}</p>
      <p>{{ t('Generated','生成时间') }}: {{ state.lastExport.generatedAt ? instant(state.lastExport.generatedAt,state.lastExport.timeZone) : t('Not available from server filename','服务器文件名未提供可验证时间') }}</p>
      <p>{{ t('The browser received the file. This does not mean a clinician reviewed it or an institution received it.','浏览器已获得文件，不表示医生已复核或外部机构已接收。') }}</p>
    </section>
    <p v-if="state.report && !report" role="alert">{{ t('The response does not match this patient scope or the supported complete report format. Generate again.','报告响应与当前患者范围或支持的完整报告格式不符，请重新生成。') }}</p>
    <article v-if="report" class="report-body" data-testid="execution-report-body">
      <header><h2>{{ report.patient.displayName }} · {{ label('scope',report.scope.label) }}</h2><p>{{ t('Patient','患者') }} #{{ report.patient.id }}<template v-if="report.scope.planId"> · {{ t('Plan','计划') }} #{{ report.scope.planId }}</template></p></header>
      <p>{{ t('Current status as of this generation; activity covers the selected period.','当前状态截至本次生成；活动范围为所选期间。') }}</p>
      <p>{{ t('Current as of','当前状态截至') }}: {{ instant(report.metadata.currentAsOf) }}<br/>{{ t('Generated','生成时间') }}: {{ instant(report.metadata.generatedAt) }}</p>
      <p>{{ report.metadata.fromDate }} — {{ report.metadata.toDate }} · {{ report.metadata.timeZone }} · {{ report.metadata.language }} · {{ t('Report schema','报告schema') }} {{ report.reportSchemaVersion }}</p>
      <p>{{ t('UTC interval, end excluded','UTC期间，不含结束瞬时') }}: {{ report.metadata.rangeStartAt }} — {{ report.metadata.rangeEndExclusiveAt }}</p>
      <section aria-labelledby="current-report-heading"><h3 id="current-report-heading">{{ t('Current status','当前状态') }}</h3>
        <p>{{ t('N is all current actions in active published plans in this scope. It is not the number of events or actions due during the period.','N为本范围内有效已发布计划的全部当前事项，不是期间事件数或期间到期事项数。') }}</p>
        <p v-if="!report.currentSummary.total">{{ t('No current active-plan actions · Not applicable','无当前有效计划事项 · 比例不适用') }}</p>
        <dl v-else class="report-counts"><template v-for="[key,code] in statusCounts" :key="key"><dt>{{ label('status',code) }}</dt><dd>{{ report.currentSummary[key] }} / {{ report.currentSummary.total }}</dd></template></dl>
        <p>{{ t('Overdue subset','逾期子集') }}: {{ report.currentSummary.overdue }} · {{ t('Needs supplement subset','待补充子集') }}: {{ report.currentSummary.needsSupplement }}</p>
        <h4>{{ t('Current attention, independent of activity dates','当前待处理，不受活动期间影响') }}</h4><p v-if="!report.currentAttention.length">{{ t('No current attention items','无当前待处理事项') }}</p>
        <ul><li v-for="action in report.currentAttention" :key="action.actionId"><button type="button" class="report-jump" :data-testid="`jump-action-${action.actionId}`" @click="jumpToAction(action.actionId)">#{{ action.actionId }} {{ action.instruction }}</button> · {{ label('status',action.status) }}</li></ul>
        <h4>{{ t('All current actions and sources','全部当前事项与来源') }}</h4>
        <article v-for="action in report.currentActions" :id="`report-action-${action.actionId}`" tabindex="-1" :key="action.actionId" class="report-record" data-testid="report-current-action">
          <h4>{{ action.planTitle }} · {{ t('Version','版本') }} {{ action.revisionNo }} · #{{ action.actionId }}</h4><p class="original">{{ action.instructions }}</p><p class="original">{{ action.instruction }}</p>
          <p>{{ label('status',action.status) }}<strong v-if="action.overdue"> · {{ t('Patient overdue','患者逾期') }}</strong><strong v-if="action.needsSupplement"> · {{ t('Needs supplement','待补充') }}</strong></p>
          <p>{{ t('Responsible account','负责人账号') }} #{{ action.assignedUserId }}<span v-if="!action.assigneeAvailable"> · {{ t('Assignee unavailable','协助人已不可用') }}</span> · {{ t('Due','期限') }} {{ instant(action.dueAt) }}</p>
          <p v-if="action.reviewWaitingSince">{{ t('Waiting for doctor review since','等待医生复核起点') }} {{ instant(action.reviewWaitingSince) }} · {{ t('Review waiting is not patient delay','等待复核不是患者延迟') }}</p>
          <p v-if="action.reviewWaitingSince">{{ t('Review wait duration (seconds)','本次待复核时长（秒）') }}: {{ reportReviewWaitSeconds(action.reviewWaitingSince,report.metadata.currentAsOf) ?? t('Not available','不可用') }}</p>
          <p>CARE_PLAN · {{ t('Plan / revision / action','计划 / 修订 / 事项') }} {{ action.planId }} / {{ action.revisionId }} / {{ action.actionId }}</p>
          <ul><li v-for="(e,index) in action.evidence" :key="index"><router-link v-if="evidencePath(e)" :to="evidencePath(e)">{{ e.title }} · {{ e.sourceType }} #{{ e.sourceId }}</router-link><span v-else>{{ t('A restricted or unavailable linked record exists','存在受限或不可用的关联记录') }}</span></li></ul>
          <section v-for="event in [action.latestReceipt,action.latestReturn,action.latestReview,action.latestHelp,action.latestFollowUp].filter(Boolean)" :key="event.eventId" class="report-event">
            <h5>{{ label('eventType',event.eventType) }} · #{{ event.eventId }}</h5><p>{{ event.actorName }} #{{ event.actorId }} · {{ label('actorRole',event.actorRole) }} ({{ event.actorRole }}) · {{ event.actorRelation }} · {{ event.entryMode ? label('entryMode',event.entryMode) : t('Entry mode: not applicable','记录方式：不适用') }}</p>
            <p v-if="event.followUpKind">{{ label('followUpKind',event.followUpKind) }}</p><p class="original">{{ event.note }}</p><p>{{ t('Recorded','记录时间') }} {{ instant(event.recordedAt) }}<template v-if="event.occurredAt"><br/>{{ t('Occurred','实际执行时间') }} {{ instant(event.occurredAt) }}</template></p>
            <ul><li v-for="(e,index) in event.evidence" :key="index"><router-link v-if="evidencePath(e)" :to="evidencePath(e)">{{ e.title }} · {{ e.sourceType }} #{{ e.sourceId }}</router-link><span v-else>{{ t('A restricted or unavailable linked record exists','存在受限或不可用的关联记录') }}</span></li></ul>
          </section>
          <router-link :to="`/care-plans/${action.planId}`">{{ t('Open authorized plan details','打开已授权计划详情') }}</router-link>
        </article>
      </section>
      <section aria-labelledby="period-report-heading"><h3 id="period-report-heading">{{ t('Period activity','期间活动') }}</h3><p>{{ t('Filtered only by recorded time. Event counts are separate from current N; categories may overlap.','仅按记录时间筛选。事件数与当前N分开；分类涉及事项可能重叠。') }}</p>
        <p>{{ report.activitySummary.eventCount }} {{ t('events','次事件') }} · {{ report.activitySummary.distinctActionCount }} {{ t('distinct actions','个去重事项') }}</p>
        <ul><li v-for="(count,code) in report.activitySummary.eventTypeCounts" :key="code">{{ label('eventType',code) }}: {{ count }}</li></ul><p v-if="!report.periodEvents.length">{{ t('No public activity in this period','期间无公开活动') }}</p>
        <article v-for="event in report.periodEvents" :key="event.eventId" class="report-record">
          <h4>{{ label('eventType',event.eventType) }} · #{{ event.eventId }}</h4><p>{{ event.planTitle }} · {{ t('Version','版本') }} {{ event.revisionNo }}</p><p class="original">{{ event.instructions }}</p><p class="original">{{ event.actionInstruction }}</p><p class="original">{{ event.note }}</p>
          <p>CARE_PLAN · {{ t('Plan / revision / action','计划 / 修订 / 事项') }} {{ event.planId }} / {{ event.revisionId }} / {{ event.actionId ?? t('Not applicable','不适用') }}</p>
          <p v-if="event.actionStatusAfterEvent">{{ t('Historical status after this event','本事件后的历史状态') }}: {{ label('status',event.actionStatusAfterEvent) }}</p>
          <p>{{ t('Plan lifecycle at generation','生成时计划生命周期') }}: {{ event.planLifecycleAtGeneration }} · {{ t('Current version at generation','生成时是否当前版本') }}: {{ event.revisionIsCurrentAtGeneration ? t('Yes','是') : t('No','否') }}</p>
          <p>{{ event.actorName }} #{{ event.actorId }} · {{ label('actorRole',event.actorRole) }} ({{ event.actorRole }}) · {{ event.actorRelation }} · {{ event.entryMode ? label('entryMode',event.entryMode) : t('Entry mode: not applicable','记录方式：不适用') }}</p><p v-if="event.followUpKind">{{ label('followUpKind',event.followUpKind) }}</p>
          <p>{{ t('Recorded','记录时间') }} {{ instant(event.recordedAt) }}<template v-if="event.occurredAt"><br/>{{ t('Occurred','实际执行时间') }} {{ instant(event.occurredAt) }}</template></p>
          <ul><li v-for="(e,index) in event.evidence" :key="index"><router-link v-if="evidencePath(e)" :to="evidencePath(e)">{{ e.title }} · {{ e.sourceType }} #{{ e.sourceId }}</router-link><span v-else>{{ t('A restricted or unavailable linked record exists','存在受限或不可用的关联记录') }}</span></li></ul>
        </article>
      </section>
      <section><h3>{{ t('Current visit questions','当前就诊问题') }}</h3><p>{{ label('questionsAvailability',report.questionsAvailability) }}</p>
        <template v-if="report.questionsAvailability==='AVAILABLE'"><p>{{ t('Current editable legacy content; full historical authorship is not established. Dates do not filter current questions.','这是可编辑旧记录的当前内容，不承诺完整历史作者链。当前问题不受日期筛选影响。') }}</p><p v-if="!report.questions.length">{{ t('No current questions','当前无问题') }}</p>
          <article v-for="question in report.questions" :key="question.id" class="report-record"><h4>{{ question.title }} · #{{ question.id }}</h4><p>{{ label('questionStatus',question.status) }}</p><p class="original">{{ question.description }}</p><p v-if="question.answer" class="original">{{ t('Recorded answer','记录的答复') }}: {{ question.answer }}</p><p v-if="question.followUp" class="original">{{ t('Follow-up','后续安排') }}: {{ question.followUp }}</p><p>{{ question.actorName }}<template v-if="question.actorId"> #{{ question.actorId }}</template></p><p v-for="key in ['createdAtLocal','updatedAtLocal','eventAtLocal']" :key="key"><template v-if="question[key]">{{ key }}: {{ legacy(question[key]) }}</template></p></article>
        </template>
      </section>
      <footer><p>{{ t('Complete for this scope and authorized sections. Source links recheck original-module permission when opened. This material is not a diagnosis or proof of treatment effectiveness; doctor review means the record was reviewed. Self-reported and assisted records remain declarations.','完整性限于本范围及已授权区域。来源链接打开时重新检查原模块权限。本材料不是诊断或治疗有效证明；医生复核仅表示记录已处理，自报与代录仍是声明。') }}</p><p>{{ t('The report is read-only. Administrative follow-up does not resolve difficulty or confirm a receipt. Viewing or exporting does not change care states or send notifications.','本报告只读。行政跟进不自动解决困难或确认回执。查看或导出不改变照护状态，不发送通知。') }}</p></footer>
    </article>
  </section>
</template>
<script setup>
import { reactive, computed, ref, watch, inject, onScopeDispose } from 'vue'
import { useCareExecutionReport } from '@/composables/useCareExecutionReport'
import { defaultReportDates, reportReviewWaitSeconds, reportLabel, formatReportInstant, formatLegacyQuestionTime, safeEvidencePath } from '@/utils/careExecutionReport'
const props = defineProps({ patientId: { type: Number, required: true }, planId: { type: Number, default: null } })
const reportElement = ref(null)
function jumpToAction(id) { if(!Number.isSafeInteger(id)||id<=0)return; const target=reportElement.value?.querySelector(`#report-action-${id}`);target?.focus({preventScroll:true});target?.scrollIntoView({block:'start'}) }
const language = inject('reportLanguage', 'zh-CN')
const form = reactive({ timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', language, fromDate: '', toDate: '' })
const context = computed(() => ({ patientId: props.patientId, planId: props.planId, ...form }))
const client = useCareExecutionReport(context), { state } = client
const generation = ref(0)
function clear() { generation.value++; client.clear() }
const report = computed(() => state.report?.reportSchemaVersion === 1 && state.report?.completeness === 'COMPLETE' && state.report?.patient?.id === props.patientId && (state.report?.scope?.planId ?? null) === props.planId ? state.report : null)
const stale = ref(false), busy = computed(() => ['previewing','downloading'].includes(state.phase))
const formats = ['html','pdf','actions_csv','events_csv'], statusCounts = [['open','OPEN'],['needsHelp','NEEDS_HELP'],['submitted','SUBMITTED'],['confirmed','CONFIRMED']]
const t = (en, zh) => form.language === 'zh-CN' ? zh : en
const label = (group, code) => reportLabel(group, code, form.language)
const today = computed(() => { try { return defaultReportDates(form.timeZone).toDate } catch { return '' } })
const valid = computed(() => {
  const date = value => /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(`${value}T00:00:00Z`)) && new Date(`${value}T00:00:00Z`).toISOString().slice(0,10) === value
  return Number.isSafeInteger(props.patientId) && props.patientId > 0 && (props.planId == null || (Number.isSafeInteger(props.planId) && props.planId > 0)) && today.value && date(form.fromDate) && date(form.toDate) && form.fromDate <= form.toDate && form.toDate <= today.value && (Date.parse(form.toDate) - Date.parse(form.fromDate)) / 86400000 < 366
})
const errorText = computed(() => state.error?.errorCode === 'REPORT_RENDER_UNAVAILABLE' ? t('Generation resources are unavailable. Retry; for a PDF failure, HTML may be available.','生成资源暂不可用，请重试；PDF失败时可尝试HTML。') : state.error?.errorCode ? label('error',state.error.errorCode) : t('The request failed. Check your connection and retry.','请求失败，请检查连接后重试。'))
function resetDates() { try { Object.assign(form, defaultReportDates(form.timeZone)) } catch { /* validation is visible beside the form */ } }
resetDates()
watch(context, () => { generation.value++; stale.value = true }, { flush: 'sync' })
function selectionChanged() { clear(); stale.value = true }
if(typeof window !== 'undefined') {
  window.addEventListener('care-report-selection-changed', selectionChanged)
  onScopeDispose(() => window.removeEventListener('care-report-selection-changed', selectionChanged))
}
function instant(value, zone = state.report?.metadata.timeZone || form.timeZone) { try { return formatReportInstant(value, zone, form.language) } catch { return t('Time unavailable','时间不可用') } }
const legacy = value => formatLegacyQuestionTime(value,form.language)
const evidencePath = evidence => safeEvidencePath(evidence,state.report?.patient.id)
async function generate() { if (!valid.value || busy.value) return {status:'stale'}; generation.value++; const result = await client.preview(); if(result.status==='succeeded')stale.value=false; return result }
async function download(format) { if (valid.value && !busy.value) { generation.value++; return client.download(format) } }
defineExpose({ refresh: generate, state, context, generation, clear })
</script>
<style scoped>
.execution-report{min-width:0;color:var(--ink-800);line-height:1.6}.report-controls{display:flex;flex-wrap:wrap;gap:12px;margin:16px 0}.report-controls label{display:flex;flex:1 1 180px;flex-direction:column;min-width:0}.report-jump,.report-controls input,.report-controls select,.report-controls button,.execution-report a{min-height:44px;box-sizing:border-box;font:inherit;padding:10px;max-width:100%}.report-jump,.report-controls button{cursor:pointer;border:1px solid var(--care-600);border-radius:8px;background:var(--paper);color:var(--care-800)}.execution-report a{display:inline-flex;align-items:center;color:var(--care-800)}button:disabled{opacity:.55;cursor:default}input:focus-visible,select:focus-visible,button:focus-visible,a:focus-visible{outline:3px solid var(--care-600);outline-offset:3px}.report-body,.report-notice{border:1px solid var(--line);border-radius:12px;padding:16px;margin:16px 0}.report-record{border-top:1px solid var(--line);padding:14px 0}.report-event{border-left:3px solid var(--care-600);padding-left:12px}.original{white-space:pre-wrap}.execution-report p,.execution-report h2,.execution-report h3,.execution-report h4,.execution-report h5,.execution-report li{overflow-wrap:anywhere}.report-counts{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:8px}.report-counts dd{margin:0}.execution-report ul{padding-left:22px}@media(max-width:600px){.report-controls label,.report-controls button{flex-basis:100%;width:100%}.report-body{padding:12px}}@media print{.report-controls{display:none}.report-body{border:0;padding:0}.report-record{break-inside:auto}h2,h3,h4,h5{break-after:avoid}a{color:inherit;text-decoration:none}p{orphans:3;widows:3}}
</style>
