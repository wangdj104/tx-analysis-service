<template>
  <section class="care-center" aria-labelledby="care-heading" v-loading="loading && tab!=='questions' && tab!=='goals' && tab!=='appointments'">
    <header class="care-header">
      <div class="care-header__copy">
        <span class="eyebrow">FAMILY CARE</span>
        <h1 id="care-heading">{{ mode === 'PATIENT' ? 'Your care, today' : 'Care together, today' }}</h1>
        <p class="care-context"><span>Current record</span><strong>{{ selectedPatientName || (patientId ? 'Current patient record' : 'Select a patient above') }}</strong><span v-if="patientId" class="care-record-id">Record ID: {{ patientId }}</span></p>
      </div>
      <picture class="care-header-art"><source media="(max-width:800px)" :srcset="careMomentsSmall"/><img :src="careMoments" alt="" width="120" height="80" decoding="async"/></picture>
    </header>
    <div class="care-controls">
      <el-radio-group v-model="mode" aria-label="Care role"><el-radio-button value="PATIENT">My care</el-radio-button><el-radio-button value="FAMILY">Family care</el-radio-button></el-radio-group>
      <div class="care-controls__utilities">
        <el-switch v-model="senior" active-text="Large text" aria-label="Large text"/>
        <details class="care-options">
          <summary>Care options</summary>
          <div class="care-toolbar">
            <el-button @click="joinVisible=true">Join shared care</el-button>
            <el-button @click="selectCareTab('backup')">Backup & restore</el-button>
            <el-button :loading="loading" @click="reload">Refresh</el-button>
          </div>
        </details>
      </div>
    </div>
    <section v-if="mode==='FAMILY'" class="family-cards" aria-label="Family members">
      <button v-for="p in home" :key="p.patient.id" type="button" :class="{selected:p.patient.id===patientId}" :aria-pressed="p.patient.id===patientId" @click="selectCarePatient(p.patient.id)">
        <span class="family-card__heading"><strong>{{p.patient.name}}</strong><span v-if="p.patient.id===patientId" class="family-card__selected">Selected</span></span>
        <small class="family-card__record">Record ID: {{ p.patient.id }}</small><span>{{pendingCount(p)}} items need attention</span><small>{{nextAppointment(p)}}</small>
      </button>
    </section>
    <el-alert v-if="!patientId" title="Select a family member, or join shared care with an invitation code." :closable="false" type="info"/>
    <p v-if="patientId"><router-link class="medication-typed-link" :to="`/medication?patientId=${patientId}`">{{medicationText('intakeTitle')}}</router-link></p>
    <AppointmentSchedulePreview v-if="patientId && ['today','appointments'].includes(tab)" :patient-id="patientId" :inline="false" @open="row => appointmentPanel?.openSource(row)" />
    <AppointmentFollowUpPanel v-if="patientId && ['today','appointments'].includes(tab)" ref="appointmentPanel" :patient-id="patientId" @changed="reload" />
    <el-tabs v-model="tab" class="care-tabs" :before-leave="beforeCareTabLeave">
      <el-tab-pane label="Shared care goals" name="goals"><CommonGoalPanel ref="goalPanel" :patient-id="patientId" :patient-name="selectedPatientName" :active="tab==='goals'" :locator="goalLocator" /></el-tab-pane>
      <el-tab-pane label="Handover & accompaniment" name="assistance"><AssistancePanel ref="assistancePanel" :source-context="handoverSourceContext" :source-preview-only="handoverSourcePreviewOnly" @source-successor="prepareHandoverSuccessor" @source-freeze="handoverFrozenId=$event" @source-created="handoverCreated" @select-handover="selectHandoverRequest" :patient-id="patientId" :assistance-id="typedAssistanceMode ? assistanceLink?.assistanceId : null" :active="tab==='assistance'" /></el-tab-pane>
      <el-tab-pane label="Today" name="today" :disabled="!patientId">
        <template v-if="!typedNarrowMode">
        <section v-if="legacyAvailable||measurementFlow.state.capabilities?.canAdopt" class="quick-actions" aria-label="Record health"><el-button class="quick-actions__primary" type="primary" @click="openQuick"><span>Record health<small>Blood pressure or glucose</small></span></el-button><el-button v-if="legacyAvailable" @click="open('SYMPTOM')">Record a symptom</el-button><el-button v-if="legacyAvailable" @click="open('QUESTION')">Add a visit question</el-button><el-button v-if="legacyAvailable&&mode==='FAMILY'" @click="open('HANDOVER')">Leave a family handover</el-button></section>
        <MeasurementWorkflowStatus v-if="!quickVisible" :flow="measurementFlow" source-type="BP_SELF_MONITOR" @saved="quickReplaySaved" /><FollowUpList :patient-id="patientId" /><SourceMeasurementPanel v-if="measurementFlow.state.source||measurementFlow.state.receipt||measurementFlow.state.sourceReadPhase!=='idle'" :flow="measurementFlow" @changed="reload" /><PlanTaskList :patient-id="patientId" :mode="mode" />
        <p v-if="legacyError" data-testid="legacy-care-restricted" role="status">{{legacyError}}</p>
        <template v-if="legacyAvailable">

        <div class="care-columns"><section class="care-card"><div class="care-card__heading"><h2>Today's medications</h2><span class="care-count">{{pendingMedicationCount}} pending</span></div><el-empty v-if="!pendingIntakes.length" description="No pending medication tasks today"/><article v-for="i in pendingIntakes" :key="i.id" class="care-task"><div><strong>{{i.scheduledAt?.slice(11,16)}} {{i.drugName}}</strong><p>{{i.dosage}}</p><small v-if="lastActor(i.id)">{{lastActor(i.id)}}</small><small v-if="i.status==='SNOOZED'">Remind again at {{i.snoozeUntil}}</small></div><span class="medication-status" :data-medication-status="i.status"><svg v-if="i.status==='PENDING'" aria-hidden="true" viewBox="0 0 20 20" fill="none"><circle cx="10" cy="10" r="7.5"/><path d="M10 5.5V10l3 2"/></svg><svg v-else-if="i.status==='MISSED'" aria-hidden="true" viewBox="0 0 20 20" fill="none"><path d="M10 2.5 18 17H2Z M10 7v4 M10 14v.5"/></svg><svg v-else aria-hidden="true" viewBox="0 0 20 20" fill="none"><path d="M13 3a7.5 7.5 0 1 0 4 9 M12 3h6l-6 5h6"/></svg><span>{{i.nextAction ? medicationText(i.status) : statusText(i.status)}}</span></span><div v-if="!i.nextAction && ['PENDING','MISSED','SNOOZED'].includes(i.status)" class="task-actions"><el-button type="success" :disabled="busy" @click="openIntake(i)">{{mode==='FAMILY'?'Record as taken':'I took this dose'}}</el-button><el-button :disabled="busy" @click="intakeAction(i,'SNOOZED')">Remind in 15 minutes</el-button><el-button :disabled="busy" @click="skipIntake(i)">Skip</el-button></div><router-link class="medication-typed-link" v-if="i.nextAction" :to="`/medication?patientId=${patientId}${i.executionId ? '&executionId='+i.executionId : ''}`">{{medicationText('intakeTitle')}}</router-link></article></section>
          <section class="care-card"><div class="care-card__heading"><h2>Appointments & care tasks</h2><span class="care-count">{{dueTasks.length}} open</span></div><el-empty v-if="!dueTasks.length" description="Nothing needs attention soon"/><article v-for="item in dueTasks" :key="item.id" class="care-task"><div><strong>{{item.title}}</strong><p>{{item.eventAt || 'Time not set'}}</p><small>Assigned to: {{memberName(item.assignedUserId)}} · Recorded by: {{item.actorName||'Family member'}}</small><p>{{item.details?.note || item.details?.preparation}}</p></div><div class="task-actions"><template v-if="item.kind==='HANDOVER'"><p>{{handoverText(handoverLocked(item)?'sourceFrozen':'ordinaryUntracked')}}</p><el-button :disabled="busy || handoverLocked(item)" @click="arrangeHandover(item)">{{handoverText('arrangeHandover')}}</el-button></template><el-button v-if="!(item.kind==='APPOINTMENT' && item.visitFollowUpManaged)" :disabled="busy || handoverLocked(item)" type="primary" @click="action(item,'DONE')">Complete</el-button><el-button :disabled="handoverLocked(item)" @click="open(item.kind,item)">View</el-button></div></article></section></div>
        <section v-if="workflowIntakes.length" class="care-card"><h2>{{medicationText('intakeTitle')}}</h2><p>Collaborative follow-up is excluded from the legacy taken/missed summary. Unknown facts or ended follow-up do not establish execution or safety.</p><article v-for="i in workflowIntakes" :key="i.id" class="care-task"><strong>{{i.drugName}}</strong><span role="status">{{medicationText(i.followUpState)}} · {{medicationText(i.followUpKind)}} · {{medicationText(i.status)}}</span><router-link class="medication-typed-link" :to="`/medication?patientId=${patientId}${i.executionId ? '&executionId='+i.executionId : ''}`">{{medicationText('intakeTitle')}}</router-link></article></section>
        <details class="care-history" v-if="completedIntakes.length || completedCareTasks.length">
          <summary>Completed & history ({{ completedIntakes.length + completedCareTasks.length }})</summary>
          <section v-if="completedIntakes.length" class="care-card"><h2>Today’s medication history</h2><article v-for="i in completedIntakes" :key="i.id" class="care-task"><div><strong>{{i.scheduledAt?.slice(11,16)}} {{i.drugName}}</strong><p>{{i.dosage}}</p><small v-if="lastActor(i.id)">{{lastActor(i.id)}}</small><small v-if="i.status==='SNOOZED'">Remind again at {{i.snoozeUntil}}</small></div><el-tag>{{i.nextAction ? medicationText(i.status) : statusText(i.status)}}</el-tag><router-link class="medication-typed-link" v-if="i.nextAction" :to="`/medication?patientId=${patientId}${i.executionId ? '&executionId='+i.executionId : ''}`">{{medicationText('intakeTitle')}}</router-link><div v-if="!i.nextAction && ['PENDING','MISSED','SNOOZED'].includes(i.status)" class="task-actions"><el-button type="success" :disabled="busy" @click="openIntake(i)">{{mode==='FAMILY'?'Record as taken':'I took this dose'}}</el-button><el-button :disabled="busy" @click="intakeAction(i,'SNOOZED')">Remind in 15 minutes</el-button><el-button :disabled="busy" @click="skipIntake(i)">Skip</el-button></div></article></section>
          <section v-if="completedCareTasks.length" class="care-card"><h2>Appointment and care-task history</h2><article v-for="item in completedCareTasks" :key="item.id" class="care-task"><div><strong>{{ item.title }}</strong><p>{{ item.eventAt || 'Time not set' }}</p><small>Recorded by: {{ item.actorName || 'Family member' }}</small><p>{{ item.details?.note || item.details?.preparation }}</p></div><el-tag>{{ statusText(item.status) }}<span v-if="item.visitFollowUpManaged"> · {{appointmentText(item.visitFollowUpAttendance)}}</span></el-tag><div class="task-actions"><el-button @click="open(item.kind, item)">View</el-button></div></article></section>
        </details>
        <el-alert v-for="s in lowStocks" :key="s.id" :title="s.drugName + ' has ' + s.quantity + ' ' + s.unit + ' remaining' + (s.estimatedDays != null ? ' (about ' + s.estimatedDays + ' days). Please plan a refill.' : '. Please check the supply.')" type="warning" :closable="false"/>
      </template>
              </template>
      </el-tab-pane>
      <el-tab-pane label="Appointments" name="appointments" :disabled="!patientId"><template v-if="legacyAvailable">
        <div class="section-head"><h2>Appointments, follow-ups, and companions</h2><el-button type="primary" @click="open('APPOINTMENT')">Add appointment</el-button></div>
        <el-calendar v-model="calendarDate"><template #date-cell="{data}"><div class="calendar-cell"><span>{{data.day.slice(8)}}</span><button v-for="a in appointments.filter(x=>x.eventAt?.startsWith(data.day)&&x.status!=='CANCELLED')" :key="a.id" @click.stop="open('APPOINTMENT',a)">{{a.title}}</button></div></template></el-calendar>
        <el-table :data="appointments"><el-table-column label="Time" prop="eventAt" min-width="165"/><el-table-column label="Appointment" prop="title" min-width="150"/><el-table-column label="Hospital / Department" min-width="150"><template #default="{row}">{{row.details.hospital}} {{row.details.department}}</template></el-table-column><el-table-column label="Companion"><template #default="{row}">{{memberName(row.assignedUserId)}}</template></el-table-column><el-table-column label="Status"><template #default="{row}">{{statusText(row.status)}}<span v-if="row.visitFollowUpManaged"> · {{appointmentText(row.visitFollowUpAttendance)}}</span></template></el-table-column><el-table-column label="Actions" min-width="190"><template #default="{row}"><el-button link @click="open('APPOINTMENT',row)">Edit / link report</el-button><el-button v-if="row.status==='OPEN' && !row.visitFollowUpManaged" link type="success" :disabled="busy" @click="action(row,'DONE')">Complete</el-button><el-button v-if="row.status!=='CANCELLED' && !row.visitFollowUpManaged" link :disabled="busy" @click="action(row,'CANCELLED')">Cancel</el-button></template></el-table-column></el-table>
      </template></el-tab-pane>
      <el-tab-pane label="Prescriptions" name="orders" :disabled="!patientId || !legacyAvailable">
        <div class="section-head"><div><h2>Follow the current prescription and preserve every change</h2><p>Add the medication first, then enter the clinician's directions and effective date.</p></div><div><el-button @click="$router.push('/medication')">Medication list</el-button><el-button type="primary" @click="open('ORDER')">Add prescription</el-button></div></div>
        <el-empty v-if="!orders.length" description="No prescription versions yet"/>
        <article v-for="order in orders" :key="order.id" class="care-card order-card"><div class="section-head"><h3>{{order.title}}</h3><el-tag>{{statusText(order.status)}}</el-tag></div><p>{{drugName(order.details.medicationId)}} · Starts {{order.details.startDate}}{{order.details.endDate?', ends '+order.details.endDate:''}} · Clinician: {{order.details.doctor||'Not provided'}}</p><p v-if="order.details.action!=='STOP'">{{order.details.doses?.map(d=>d.time+' '+d.quantity+order.details.unit).join('; ')}} · {{daysText(order.details.repeatDays)}}</p><p v-else>This medication was stopped.</p><p>{{order.details.instructions}}</p><div class="attachment-links"><a v-for="(a,i) in order.details.attachments||[]" :key="i" :href="a.dataUrl" :download="a.name">{{a.name}}</a></div><small>Recorded by {{order.actorName}} · Version #{{order.id}}</small><div class="task-actions"><el-button @click="changeOrder(order,'CHANGE')">Add revised version</el-button><el-button v-if="order.status==='ACTIVE'" @click="changeOrder(order,'STOP')">Record stop order</el-button><el-button v-if="order.status==='SCHEDULED'" :disabled="busy" @click="action(order,'CANCELLED')">Cancel scheduled version</el-button></div></article>
      </el-tab-pane>
      <el-tab-pane label="Medication stock" name="stock" :disabled="!patientId">
        <StockWorkPanel ref="stockWorkPanel" @notification-review="stockNotificationPanel?.open($event)" :patient-id="patientId" :stock-id="typedStockMode ? stockLink?.stockId : null" :active="tab==='stock'" />
        <p class="stock-policy-help">{{ stockPolicyText('historical') }}</p>
        <template v-if="legacyAvailable">
        <div class="section-head"><h2>Remaining medication</h2><el-button type="primary" @click="openStock()">Set or correct stock</el-button></div><p>Legacy setup is only for untracked stock. Use the stock review above for tracked records, with explicit amounts and exact units.</p>
        <el-table :data="context.stocks||[]"><el-table-column :label="stockPolicyText('status')" min-width="220"><template #default="{row}"><StockNotificationStatus :policy="row.reminderPolicy" :stock-id="row.id" @review="stockNotificationPanel?.open($event)" /></template></el-table-column><el-table-column prop="drugName" label="Medication" min-width="150"/><el-table-column label="Remaining"><template #default="{row}">{{row.quantity==null?'—':row.quantity}} {{row.unit}} <el-tag v-if="row.low" type="warning">Low stock</el-tag></template></el-table-column><el-table-column label="Estimated days"><template #default="{row}">{{row.estimatedDays==null?'Unknown / not evaluable':row.estimatedDays+' days'}}</template></el-table-column><el-table-column label="Actions" min-width="180"><template #default="{row}"><el-button v-if="!row.workflow_version" link @click="purchase(row)">Restock</el-button><el-button v-if="!row.workflow_version" link @click="openStock(row)">Correct</el-button><el-button v-if="!row.workflow_version" link @click="showStockHistory(row)">History</el-button></template></el-table-column></el-table>
        </template>
      </el-tab-pane>
      <el-tab-pane label="Symptoms" name="symptoms" :disabled="!patientId || !legacyAvailable">
        <div class="section-head"><h2>Track when symptoms started and how they change</h2><el-button type="primary" @click="open('SYMPTOM')">Record symptom</el-button></div><el-select v-model="symptomFilter" clearable placeholder="Select a symptom to view its history"><el-option v-for="name in [...new Set(symptoms.map(s=>s.title))]" :key="name" :value="name"/></el-select>
        <article v-for="s in filteredSymptoms" :key="s.id" class="care-card"><div class="section-head"><h3>{{s.title}}</h3><span>{{s.eventAt}} · {{s.actorName}}</span></div><el-progress :percentage="Number(s.details.severity)*10" :format="()=>s.details.severity+'/10'"/><p>Duration: {{s.details.duration||'Not provided'}}; {{progressText(s.details.progress)}}; {{symptomChange(s)}}</p><p>{{s.details.context}}</p><p>{{s.details.response}}</p><el-button link @click="open('SYMPTOM',s)">Edit</el-button><el-button link @click="open('SYMPTOM',{...s,id:null,eventAt:null})">Record again</el-button><el-popconfirm title="Delete this symptom entry?" @confirm="removeItem(s)"><template #reference><el-button link type="danger">Delete</el-button></template></el-popconfirm></article><el-empty v-if="!filteredSymptoms.length" description="No symptom entries"/>
      </el-tab-pane>
      <el-tab-pane :label="questionText('title')" name="questions" :disabled="!patientId">
        <VisitQuestionPanel ref="questionPanel" :patient-id="patientId" :patient-name="selectedPatientName" :active="tab==='questions'" :locator="questionLocator" @ordinary="openOrdinaryQuestion" :can-handover="legacyAvailable" @handover="prepareQuestionHandover" />
        <section v-if="legacyAvailable && !typedNarrowMode" class="vq vq-card" aria-labelledby="ordinary-question-heading">
          <h3 id="ordinary-question-heading">{{ questionText('ordinaryContextTitle') }}</h3><p>{{ questionText('ordinaryContextHelp') }}</p>
          <p v-if="ordinaryPageError" role="alert">{{ questionText('ordinaryContextError') }}</p><p v-if="ordinaryPageLoading" role="status" aria-live="polite">{{ questionText('loading') }}</p>
          <p v-if="!questions.length">{{ questionText('empty') }}</p>
          <article v-for="row in questions" :key="row.id" class="vq-card"><h4>{{ row.title }}</h4><p>{{ row.details?.description }}</p><p>{{ questionText(row.questionProjection?.answerOrigin || 'LEGACY_UNVERIFIED') }}</p><p>{{ row.details?.answer }}</p><p>{{ questionText('followUp') }}: {{ row.details?.followUp || questionText('noNext') }}</p><p v-if="row.questionProjection?.projectionBasis==='PRESERVED_NATIVE_SNAPSHOT'">{{ questionText('ordinaryContextSnapshot') }}</p><button type="button" @click="openContextQuestion(row)">{{ questionText('ordinaryContextEdit') }}</button></article>
          <div class="vq-actions"><button v-if="context.questionContinuation" type="button" :disabled="ordinaryPageLoading" @click="loadOrdinaryQuestions(context.questionContinuation)">{{ questionText('more') }}</button><button type="button" :disabled="ordinaryPageLoading" @click="loadOrdinaryQuestions()">{{ questionText('first') }}</button></div>
        </section>
      </el-tab-pane>
      <el-tab-pane label="Family collaboration" name="family" :disabled="!patientId || !legacyAvailable">
        <div class="section-head"><h2>Shared caregivers</h2><el-button v-if="canManage" type="primary" @click="invite">Generate invitation code</el-button></div><p v-if="inviteCode" class="invite-code">Invitation code (valid for 7 days and one use): <strong>{{inviteCode}}</strong><el-button @click="copyInvite">Copy</el-button></p>
        <el-table :data="context.members||[]"><el-table-column prop="name" label="Name"/><el-table-column prop="username" label="Account"/><el-table-column prop="relationName" label="Relationship"/><el-table-column label="Actions"><template #default="{row}"><el-popconfirm v-if="canManage&&row.userId!==context.patient?.userId" title="Remove this account from shared care?" @confirm="removeMember(row)"><template #reference><el-button link type="danger">Remove</el-button></template></el-popconfirm></template></el-table-column></el-table>
        <div class="section-head"><h2>Care handovers</h2><el-button @click="open('HANDOVER')">Add handover</el-button></div><article v-for="h in handovers" :key="h.id" class="care-card"><strong>{{h.title}}</strong><p>{{h.details.note}}</p><small>{{h.actorName}} assigned this to {{memberName(h.assignedUserId)}} · {{h.eventAt||'No due time'}} · {{statusText(h.status)}}</small><p>{{handoverText(handoverLocked(h)?'sourceFrozen':'ordinaryUntracked')}}</p><div class="task-actions"><el-button :disabled="busy || handoverLocked(h)" type="primary" @click="arrangeHandover(h)">{{handoverText('arrangeHandover')}}</el-button><el-button :disabled="handoverLocked(h)" @click="open('HANDOVER',h)">Edit</el-button><el-button v-if="h.status==='OPEN'" :disabled="busy || handoverLocked(h)" @click="action(h,'DONE')">Complete</el-button></div></article>
        <h2>Recent activity</h2><el-table :data="activities.slice(0,50)"><el-table-column prop="eventAt" label="Time"/><el-table-column prop="actorName" label="Recorded by"/><el-table-column prop="title" label="Action"/></el-table>
      </el-tab-pane>
      <el-tab-pane label="Care settings" name="settings" :disabled="!patientId || !legacyAvailable">
        <el-form label-position="top" style="max-width:650px"><h2>{{context.patient?.name}}'s care settings</h2><el-form-item label="Notify a caregiver about overdue tasks"><el-select v-model="profile.escalationUserId" clearable placeholder="No additional follow-up notification"><el-option v-for="m in context.members||[]" :key="m.userId" :value="m.userId" :label="m.name"/></el-select></el-form-item><el-form-item label="Minutes overdue before caregiver follow-up"><el-input-number v-model="profile.escalationMinutes" :min="15" :step="15"/></el-form-item><el-button type="primary" :loading="busy" @click="saveProfile">Save settings</el-button><el-button @click="$router.push('/settings/notifications')">Notification channels</el-button></el-form>
      </el-tab-pane>
      <el-tab-pane label="Backup & restore" name="backup"><FamilyBackupPanel v-if="!typedNarrowMode" @restored="reload"/></el-tab-pane>
    </el-tabs>
    <StockNotificationPanel ref="stockNotificationPanel" :patient-id="patientId" :active="tab==='stock'" @changed="stockPolicyChanged" />
    <CareEntryDialog v-if="legacyAvailable || entryKind==='QUESTION'" v-model="entryVisible" :kind="entryKind" :row="entryRow" :patient-id="patientId" :patient-name="selectedPatientName" :members="context.members" :medications="context.medications" :reports="context.reports" :appointments="appointments" @saved="entrySaved"/>
    <el-dialog v-model="quickVisible" :close-on-click-modal="!measurementLocked" :close-on-press-escape="!measurementLocked" :show-close="!measurementLocked" title="Record blood pressure / glucose" width="min(460px,94vw)"><p class="care-context">Current patient: <strong>{{ selectedPatientName || '—' }}</strong> · Record ID: {{ patientId }}</p><MeasurementWorkflowStatus :flow="measurementFlow" source-type="BP_SELF_MONITOR" @saved="quickReplaySaved" /><el-form class="measurement-entry-form" label-position="top" :disabled="busy || measurementLocked"><el-form-item label="Systolic / diastolic (mmHg)"><el-input v-model="quick.systolicBp" type="text" inputmode="decimal" /> / <el-input v-model="quick.diastolicBp" type="text" inputmode="decimal" /></el-form-item><el-form-item label="Blood glucose (optional)"><el-input v-model="quick.bloodGlucose" type="text" inputmode="decimal" /></el-form-item><el-form-item :label="awText('unit')"><el-select v-model="quick.bgUnit" :placeholder="awText('chooseUnit')"><el-option label="mmol/L" value="mmol/L"/><el-option label="mg/dL" value="mg/dL"/></el-select></el-form-item><p v-if="previousPeriod==='After Meal'">{{awText('explicitPeriodHelp')}}</p><el-form-item label="Measurement period"><el-select v-model="quick.measurePeriod" :placeholder="awText('choosePeriod')"><el-option label="Fasting" value="Fasting"/><el-option label="Two hours after a meal" value="After Meal2h"/><el-option label="Random" value="Random"/></el-select></el-form-item><el-form-item label="Notes"><el-input v-model="quick.remark"/></el-form-item><p>{{awText('newMeasurementHelp')}}</p><el-form-item :label="awText('wallTime')"><el-input v-model="quickWall" type="datetime-local" /></el-form-item><template v-if="measurementFlow.state.capabilityPhase==='enabled'"><el-form-item :label="awText('offset')"><el-input v-model="quickOffset" placeholder="+08:00" /><p>{{awText('offsetHelp')}}</p></el-form-item></template></el-form><template #footer><el-button :loading="busy" :disabled="measurementLocked || !['enabled','disabled'].includes(measurementFlow.state.capabilityPhase) || (measurementFlow.state.capabilityPhase==='enabled'&&!measurementFlow.state.capabilities?.canAdopt)" class="measurement-save" type="primary" @click="submitQuick">Save reading</el-button></template></el-dialog>
    <el-dialog v-model="intakeVisible" :title="mode==='FAMILY'?'Record a dose for a family member':'Confirm this dose'" width="min(460px,94vw)"><p class="care-context">Current patient: <strong>{{ selectedPatientName || '—' }}</strong> · Record ID: {{ patientId }}</p><p>{{selectedIntake?.drugName}} · {{selectedIntake?.dosage}}</p><el-form label-position="top"><el-form-item label="Actual quantity (optional, in the inventory unit)"><el-input-number v-model="intakeQuantity" :min="0.001" :precision="3"/></el-form-item><p>If left blank, stock is deducted only when the prescribed dose and inventory use the same unit.</p></el-form><template #footer><el-button :loading="busy" type="success" @click="confirmIntake">Confirm taken</el-button></template></el-dialog>
    <el-dialog v-model="stockVisible" title="Set or correct inventory" width="min(480px,94vw)"><p class="care-context">Current patient: <strong>{{ selectedPatientName || '—' }}</strong> · Record ID: {{ patientId }}</p><el-form label-position="top"><el-form-item label="Medication"><el-select v-model="stockForm.medicationId" :disabled="!!stockForm.id"><el-option v-for="m in context.medications||[]" :key="m.id" :value="m.id" :label="m.drugName"/></el-select></el-form-item><el-form-item label="Actual quantity remaining"><el-input-number v-model="stockForm.quantity" :min="0" :precision="3"/></el-form-item><el-form-item label="Unit (must match the prescription)"><el-input v-model="stockForm.unit"/></el-form-item><el-form-item label="Warn when this many days remain"><el-input-number v-model="stockForm.warningDays" :min="0"/></el-form-item><el-form-item label="Or warn below this quantity"><el-input-number v-model="stockForm.warningQuantity" :min="0"/></el-form-item></el-form><template #footer><el-button :loading="busy" type="primary" @click="submitStock">Save</el-button></template></el-dialog>
    <el-dialog v-model="historyVisible" title="Inventory history" width="min(800px,94vw)"><el-table :data="stockHistory"><el-table-column prop="created_at" label="Time"/><el-table-column prop="quantity" label="Quantity change"/><el-table-column prop="reason" label="Reason"/><el-table-column prop="actor_name" label="Recorded by"/></el-table></el-dialog>
    <el-dialog v-model="joinVisible" title="Join shared care" width="min(460px,94vw)"><p>Ask the owner of this family member's record for an invitation code.</p><el-input v-model="joinCode" placeholder="Paste invitation code"/><el-input v-model="joinRelation" placeholder="Your relationship to the patient, for example: Daughter" style="margin-top:14px"/><template #footer><el-button type="primary" :loading="busy" @click="join">Join</el-button></template></el-dialog>
  </section>
</template>
<script setup>
import CommonGoalPanel from '@/components/common-care-goal/CommonGoalPanel.vue'
import { parseGoalRoute } from '@/utils/commonCareGoal'
import {measurementFits} from '@/utils/measurementNumbers';
import AssistancePanel from '@/components/family-assistance/AssistancePanel.vue';
import { useRoute, useRouter } from 'vue-router';
import { t as handoverText } from '@/utils/familyAssistanceCopy';
import { handoverId, typedHandoverSource, isOrdinaryHandover } from '@/utils/familyHandover';
import { captureAssistanceLoginRedirect } from '@/utils/assistanceLogin';
import VisitQuestionPanel from '@/components/visit-question/VisitQuestionPanel.vue'
import { parseQuestionRoute } from '@/api/visitQuestion'
import { questionText } from '@/utils/visitQuestionCopy'
import { parseStockRoute, parseAssistanceRoute } from '@/utils/workspaceAccess';
import {medicationText} from '@/utils/medicationExecutionCopy';
import StockWorkPanel from '@/components/stock-workflow/StockWorkPanel.vue';
import StockNotificationStatus from '@/components/stock-notification/StockNotificationStatus.vue';
import StockNotificationPanel from '@/components/stock-notification/StockNotificationPanel.vue';
import { stockNotificationText as stockPolicyText } from '@/utils/archiveStockNotificationCopy';
import {AUTH_STORAGE_KEYS,captureAuthSession,isAuthSessionCurrent} from '@/utils/authSession'
import {useAlertWorkflowNavigation} from '@/composables/useAlertWorkflowNavigation'
import {ref,reactive,computed,watch,onMounted,onUnmounted,nextTick} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {useCurrentPatient} from '@/composables/useCurrentPatient'
import * as api from '@/api/care'
import AppointmentFollowUpPanel from '@/components/visit-follow-up/AppointmentFollowUpPanel.vue'
import {appointmentText} from '@/utils/visitFollowUpCopy'
import AppointmentSchedulePreview from '@/components/visit-follow-up/AppointmentSchedulePreview.vue'
import CareEntryDialog from '@/components/CareEntryDialog.vue'
import FamilyBackupPanel from '@/components/FamilyBackupPanel.vue'
import FollowUpList from '@/components/alert-workflow/FollowUpList.vue'
import SourceMeasurementPanel from '@/components/alert-workflow/SourceMeasurementPanel.vue'
import MeasurementWorkflowStatus from '@/components/alert-workflow/MeasurementWorkflowStatus.vue'
import {useAlertWorkflow} from '@/composables/useAlertWorkflow'
import {measurementInstant,localOffset,currentWall} from '@/utils/alertWorkflowForms'
import {t as awText} from '@/utils/alertWorkflowCopy'
import PlanTaskList from '@/components/care-plan/PlanTaskList.vue'
import {localDateKey} from '@/utils/familyHealth'
import careMoments from '@/assets/illustrations/care-moments.webp'
import careMomentsSmall from '@/assets/illustrations/care-moments-small.webp'
const stockNotificationPanel=ref(null),stockWorkPanel=ref(null);
function stockPolicyChanged(update){if(Number(patientId.value)!==update.patientId||stockNotificationPanel.value?.ownsProjection(update)!==true)return;for(const row of context.value.stocks||[])if(Number(row.id)===update.stockId)row.reminderPolicy=update.policy;stockWorkPanel.value?.updateReminderPolicy(update)}
const router=useRouter(),assistancePanel=ref(null),handoverSourceContext=ref(null),handoverSourcePreviewOnly=ref(false),handoverFrozenId=ref(null);
const stockRoute=useRoute(),typedStockMode=ref(!!parseStockRoute(stockRoute.fullPath));
let handoverViewEpoch=0,handoverRouteEpoch=0;
let pendingHandoverSelection=null,removeHandoverSelectionGuard=null;
watch(()=>stockRoute.fullPath,()=>{handoverRouteEpoch++},{flush:'sync'});
const typedAssistanceMode=ref(!!parseAssistanceRoute(stockRoute.fullPath)),assistanceLink=computed(()=>parseAssistanceRoute(stockRoute.fullPath));
const typedQuestionMode=ref(!!parseQuestionRoute(stockRoute.fullPath)),questionLocator=computed(()=>parseQuestionRoute(stockRoute.fullPath)),questionPanel=ref(null);
const typedGoalMode=ref(!!parseGoalRoute(stockRoute.fullPath)),goalLocator=computed(()=>parseGoalRoute(stockRoute.fullPath)),goalPanel=ref(null);
const typedNarrowMode=computed(()=>typedStockMode.value||typedAssistanceMode.value||typedQuestionMode.value||typedGoalMode.value);
const stockLink=computed(()=>parseStockRoute(stockRoute.fullPath));
let hostReady=false;
const {currentPatientId:patientId,currentPatientName,setPatientList,patientList}=useCurrentPatient()
const measurementFlow=useAlertWorkflow(patientId),quickWall=ref(currentWall()),quickOffset=ref(localOffset())
useAlertWorkflowNavigation(measurementFlow)
const measurementLocked=computed(()=>['submitting','uncertain'].includes(measurementFlow.state.sourcePhase))
watch(patientId,()=>{quickWall.value=currentWall();quickOffset.value=localOffset();if(!typedNarrowMode.value)measurementFlow.loadCapabilities()},{immediate:true})
function readCarePreference(key, fallback = '') {
  try { return localStorage.getItem(key) || fallback } catch { return fallback }
}
const mode=ref(readCarePreference('care-mode','PATIENT')),senior=ref(readCarePreference('care-senior')==='true'),tab=ref(typedGoalMode.value?'goals':typedQuestionMode.value?'questions':typedAssistanceMode.value?'assistance':typedStockMode.value?'stock':'today'),home=ref([]),context=ref({}),legacyAvailable=ref(false),legacyError=ref(''),loading=ref(false),busy=ref(false)
watch(() => stockRoute.fullPath, path => {
  const goal = parseGoalRoute(path)
  if (goal) {
    // 先建立独立读取模式，不能让挂载或患者 watcher 短暂进入旧混载接口。
    typedGoalMode.value = true
    typedQuestionMode.value = false
    typedAssistanceMode.value = false
    typedStockMode.value = false
    if (hostReady) clearLegacy()
    tab.value = 'goals'
    return
  }
  const question = parseQuestionRoute(path)
  if (question) {
    typedQuestionMode.value = true
    typedAssistanceMode.value = false
    typedStockMode.value = false
    if (hostReady) clearLegacy()
    tab.value = 'questions'
    if (!question.invalid) patientId.value = question.patientId
    return
  }
  typedQuestionMode.value = false
  const assistance = parseAssistanceRoute(path)
  if (assistance) {
    handoverSourceContext.value = null
    typedAssistanceMode.value = true
    typedStockMode.value = false
    if (hostReady) clearLegacy()
    tab.value = 'assistance'
    patientId.value = assistance.patientId
    return
  }
  const link = parseStockRoute(path)
  if (link) {
    typedStockMode.value = true
    typedAssistanceMode.value = false
    if (hostReady) clearLegacy()
    tab.value = 'stock'
    patientId.value = link.patientId
    return
  }
  if (typedGoalMode.value && tab.value === 'goals') tab.value = 'today'
}, { immediate: true })
watch(handoverSourceContext,value=>{handoverViewEpoch++;if(!value)handoverSourcePreviewOnly.value=false},{flush:'sync'});
watch(tab, next => {
  handoverViewEpoch++;
  if(next!=='assistance')handoverSourceContext.value=null;
  if(next==='goals') {
    typedGoalMode.value = true
    typedQuestionMode.value = false
    typedAssistanceMode.value = false
    typedStockMode.value = false
    if (hostReady) clearLegacy()
    return
  }
  if (typedGoalMode.value) {
    typedGoalMode.value = false
    // 另一个独立入口已接管时，不先回落到旧首页读取。
    if (!typedNarrowMode.value) { measurementFlow.loadCapabilities(); reload() }
  }
  if (next !== 'questions' && typedQuestionMode.value) { typedQuestionMode.value = false; measurementFlow.loadCapabilities(); reload() }
  if (next !== 'assistance' && typedAssistanceMode.value) { typedAssistanceMode.value = false; measurementFlow.loadCapabilities(); reload() }
  if (next !== 'stock' && typedStockMode.value) { typedStockMode.value = false; measurementFlow.loadCapabilities(); reload() }
}, { flush: 'sync' })
const entryNeedsLegacy=ref(true),ordinaryPageLoading=ref(false),ordinaryPageError=ref(false)
const appointmentPanel=ref(null)
const entryVisible=ref(false),entryKind=ref('APPOINTMENT'),entryRow=ref(null),calendarDate=ref(new Date()),symptomFilter=ref('')
// 编辑器关闭或自身权限失效后，不在父页面保留已复制的正文。
watch(entryVisible, visible=>{if(!visible)entryRow.value=null},{flush:'sync'})
const previousPeriod=readCarePreference('care-measure-period');
const quickVisible=ref(false),quick=reactive({systolicBp:undefined,diastolicBp:undefined,bloodGlucose:undefined,bgUnit:'',measurePeriod:'',remark:''})
const intakeVisible=ref(false),selectedIntake=ref(null),intakeQuantity=ref(undefined),stockVisible=ref(false),stockForm=reactive({}),historyVisible=ref(false),stockHistory=ref([])
const joinVisible=ref(false),joinCode=ref(''),joinRelation=ref('family caregiver'),inviteCode=ref(''),profile=reactive({escalationUserId:null,escalationMinutes:60})
const selectedPatientName=computed(()=>{
  if(!patientId.value)return ''
  return currentPatientName.value || home.value.find(p=>p.patient.id===patientId.value)?.patient.name
    || (context.value.patient?.id===patientId.value ? context.value.patient.name : '') || ''
})
const pendingMedicationCount=computed(()=>(context.value.intakes||[]).filter(i=>!i.nextAction && ['PENDING','MISSED','SNOOZED'].includes(i.status)).length)
const items=computed(()=>context.value.items||[]),byKind=kind=>computed(()=>items.value.filter(i=>i.kind===kind))
const appointments=byKind('APPOINTMENT'),orders=byKind('ORDER'),symptoms=byKind('SYMPTOM'),questions=byKind('QUESTION'),handovers=byKind('HANDOVER'),activities=byKind('ACTIVITY')
const appointmentById=computed(()=>{
  const index=new Map()
  for(const appointment of appointments.value){
    const id=appointment.id
    // Keep find's first strict match; Map would otherwise also match NaN.
    if(id===id&&!index.has(id))index.set(id,appointment)
  }
  return index
})
const activeIntakes=computed(()=>(context.value.intakes||[]).filter(i=>!i.nextAction && i.status!=='CANCELLED')),lowStocks=computed(()=>(context.value.stocks||[]).filter(s=>s.low))
const workflowIntakes=computed(()=>(context.value.intakes||[]).filter(i=>i.nextAction))
const pendingIntakes=computed(()=>(context.value.intakes||[]).filter(i=>!i.nextAction && ['PENDING','MISSED','SNOOZED'].includes(i.status)))
const completedIntakes=computed(()=>(context.value.intakes||[]).filter(i=>!i.nextAction && !['PENDING','MISSED','SNOOZED'].includes(i.status)))
const completedCareTasks=computed(()=>items.value.filter(i=>['APPOINTMENT','HANDOVER'].includes(i.kind)&&i.status!=='OPEN'))
const filteredSymptoms=computed(()=>symptoms.value.filter(s=>!symptomFilter.value||s.title===symptomFilter.value))
const dueTasks=computed(()=>items.value.filter(i=>['APPOINTMENT','HANDOVER'].includes(i.kind)&&i.status==='OPEN'&&(!i.eventAt||new Date(i.eventAt.replace(' ','T')).getTime()<Date.now()+7*86400000)))
const canManage=computed(()=>Number(localStorage.getItem('userId'))===context.value.patient?.userId||JSON.parse(localStorage.getItem('userRoleCodes')||'[]').includes('admin'))
watch(mode,v=>{try{localStorage.setItem('care-mode',v)}catch{/* Keep the live selection when storage is unavailable. */}})
watch(senior,v=>{
  document.body.classList.toggle('care-senior',v)
  try{localStorage.setItem('care-senior',v)}catch{/* Large text still applies for this visit. */}
},{immediate:true})
let generation=0, patientEpoch=0, historyRequest=0, inviteRequest=0, timer
let editorOperation=null, disposed=false
const editorVersions={quick:0,stock:0}
function invalidateEditor(kind) {
  editorVersions[kind]++
  if(editorOperation?.kind===kind){editorOperation=null;busy.value=false}
}
watch(quickVisible,()=>invalidateEditor('quick'),{flush:'sync'})
watch([quickWall,quickOffset],()=>invalidateEditor('quick'),{flush:'sync'})
watch(stockVisible,()=>invalidateEditor('stock'),{flush:'sync'})
async function reload(){if(typedNarrowMode.value)return;const v=++generation;loading.value=true;legacyError.value='';try{
  try{const r=await api.getCareHome();if(v!==generation)return;home.value=r.data||[];if(home.value.length&&!patientList.value.length)setPatientList(home.value.map(x=>({id:x.patient.id,patientName:x.patient.name})));if(!patientId.value&&home.value.length){patientId.value=home.value[0].patient.id;return}}catch(error){if(v!==generation)return;home.value=[]}
  if(patientId.value){const id=patientId.value;try{const c=await api.getCareContext(id);if(v!==generation||id!==patientId.value)return;context.value=c.data||{};legacyAvailable.value=true;const p=items.value.find(x=>x.kind==='PROFILE');Object.assign(profile,{escalationUserId:null,escalationMinutes:60},p?.details||{})}catch(error){if(v!==generation||id!==patientId.value)return;context.value={};legacyAvailable.value=false;if(entryVisible.value && entryNeedsLegacy.value){entryVisible.value=false;entryRow.value=null}if(measurementFlow.state.capabilityPhase!=='enabled'){quickVisible.value=false;resetQuick()}legacyError.value=Number(error?.response?.status||error?.code)===403?'Other care modules are restricted. Doctor plan tasks remain available independently.':'Other care modules could not be loaded. Doctor plan tasks load independently.'}}
}finally{if(v===generation)loading.value=false}}

function selectCarePatient(id){const event=new Event('care-plan-before-context-change',{cancelable:true});window.dispatchEvent(event);if(!event.defaultPrevented)patientId.value=id}
function openQuick(){if(!measurementLocked.value){quickWall.value=currentWall();quickOffset.value=localOffset()}quickVisible.value=true}
async function quickReplaySaved(){resetQuick();quickVisible.value=false;await reload()}
function resetQuick() {
  Object.assign(quick, { systolicBp: undefined, diastolicBp: undefined, bloodGlucose: undefined, bgUnit: '', measurePeriod: '', remark: '' })
}
watch(patientId, () => {
  handoverSourceContext.value=null;handoverFrozenId.value=null
  generation++; patientEpoch++; historyRequest++; inviteRequest++
  invalidateEditor('quick'); invalidateEditor('stock')
  context.value = {}; ordinaryPageLoading.value=false;ordinaryPageError.value=false; legacyAvailable.value=false;legacyError.value=''; entryRow.value = null; selectedIntake.value = null
  entryVisible.value = false; quickVisible.value = false; intakeVisible.value = false; stockVisible.value = false
  historyVisible.value = false; stockHistory.value = []; inviteCode.value = ''; symptomFilter.value = ''
  Object.assign(profile, { escalationUserId: null, escalationMinutes: 60 })
  resetQuick()
  reload()
}, { flush: 'sync' })
function clearLegacy(){handoverSourceContext.value=null;handoverFrozenId.value=null;ordinaryPageLoading.value=false;ordinaryPageError.value=false;generation++;patientEpoch++;historyRequest++;inviteRequest++;invalidateEditor('quick');invalidateEditor('stock');home.value=[];context.value={};legacyAvailable.value=false;legacyError.value='';loading.value=false;entryVisible.value=false;entryRow.value=null;quickVisible.value=false;intakeVisible.value=false;selectedIntake.value=null;stockVisible.value=false;historyVisible.value=false;stockHistory.value=[];inviteCode.value='';resetQuick();Object.keys(stockForm).forEach(key=>delete stockForm[key])}
function measurementAuthStorage(event){if(event.key==null||AUTH_STORAGE_KEYS.includes(event.key))clearLegacy()}
hostReady=true;
onMounted(()=>{window.addEventListener('storage',measurementAuthStorage);window.addEventListener('auth-session-cleared',clearLegacy);window.addEventListener('auth-session-changed',clearLegacy);reload();timer=setInterval(()=>{if(!busy.value&&!entryVisible.value&&!stockVisible.value&&!quickVisible.value&&!intakeVisible.value&&tab.value==='today')reload()},60000)})
onUnmounted(()=>{window.removeEventListener('storage',measurementAuthStorage);window.removeEventListener('auth-session-cleared',clearLegacy);window.removeEventListener('auth-session-changed',clearLegacy);disposed=true;generation++;patientEpoch++;historyRequest++;inviteRequest++;invalidateEditor('quick');invalidateEditor('stock');clearInterval(timer)})
async function perform(operation){if(busy.value)return;busy.value=true;try{await operation();ElMessage.success('Saved successfully.');await reload()}finally{busy.value=false}}
function beforeCareTabLeave(next, previous){if(stockNotificationPanel.value?.protectNavigation()===false)return false;if(appointmentPanel.value?.protectNavigation()===false)return false;if(previous==='assistance'&&assistancePanel.value?.protectNavigation()===false)return false;if(previous==='goals'&&goalPanel.value?.protectNavigation()===false)return false;return previous!=='questions'||questionPanel.value?.protectNavigation()!==false}
function selectCareTab(next){if(beforeCareTabLeave(next,tab.value))tab.value=next}
// 只从旧接口当前可见、已保存的普通行进入；不借 B 写权限新增来源目录。
function handoverLocked(row){return row?.kind==='HANDOVER' && Number(row.id)===Number(handoverFrozenId.value)}
// 页面与子视图各自保留代次，离开再回来也不能重新接管已退役的跳转。
function captureHandoverView(){const flow=assistancePanel.value?.flow;return {epoch:handoverViewEpoch,routeEpoch:handoverRouteEpoch,flow,childEpoch:flow?.captureView()}}
function ownsHandoverView(view){return !disposed && view.epoch===handoverViewEpoch && view.flow===assistancePanel.value?.flow && (!view.flow || view.flow.ownsView(view.childEpoch))}
function ownsHandoverSelection(selection){
  return pendingHandoverSelection===selection && ownsHandoverView(selection.view) && selection.view.routeEpoch===handoverRouteEpoch && tab.value==='assistance' && selection.patientId===handoverId(patientId.value) && isAuthSessionCurrent(selection.auth) && selection.auth.actorId===localStorage.getItem('userId') && selection.view.flow.protectNavigation();
}
async function selectHandoverRequest(value){
  const id=handoverId(value?.assistanceId),pid=handoverId(value?.patientId),flow=assistancePanel.value?.flow;
  if(!id || !pid || pid!==handoverId(patientId.value) || disposed || tab.value!=='assistance' || !flow || !flow.protectNavigation())return;
  handoverViewEpoch++;
  const target=`/care?patientId=${pid}&assistanceId=${id}`;
  if(stockRoute.fullPath===target)return flow.open(id);
  const selection={target,patientId:pid,view:captureHandoverView(),navigationEpoch:captureAssistanceLoginRedirect(stockRoute.fullPath).epoch,auth:{...captureAuthSession(),actorId:localStorage.getItem('userId')}};
  pendingHandoverSelection=selection;
  // 全局菜单守卫可能等待权限读取；提交路由前再核所属视图，关闭或新选择后不能跳回。
  // 全局首步会递增导航代次；只拦本次旧跳转，不让卸载实例否决后来同址的新选择。
  if(!removeHandoverSelectionGuard)removeHandoverSelectionGuard=router.beforeResolve(to=>{const current=pendingHandoverSelection;if(current && to.fullPath===current.target && captureAssistanceLoginRedirect(stockRoute.fullPath).epoch===current.navigationEpoch+1 && !ownsHandoverSelection(current))return false;});
  try{return await router.replace(target);}
  finally{if(pendingHandoverSelection===selection)pendingHandoverSelection=null;if(!pendingHandoverSelection && removeHandoverSelectionGuard){removeHandoverSelectionGuard();removeHandoverSelectionGuard=null;}}
}
// 后继仍从已授权的当前普通详情进入同一来源上下文，不把子组件草稿当作新的权限。
function prepareHandoverSuccessor(value){
  const flow=assistancePanel.value?.flow,d=flow?.state.detail,id=handoverId(value?.assistanceId),careItemId=handoverId(value?.careItemId);
  if(!id || !careItemId || !handoverId(patientId.value) || disposed || tab.value!=='assistance' || !flow || !flow.protectNavigation() || !isOrdinaryHandover(d) || d.currentSource!==true || !['COMPLETED','CANCELLED','CLOSED_UNRESOLVED'].includes(d.state) || !flow.state.capabilities?.create || id!==handoverId(d.assistanceId) || careItemId!==handoverId(d.careItemId) || handoverId(d.patientId)!==handoverId(patientId.value))return;
  const source=typedHandoverSource({sourceKind:'CARE_ITEM',careItemId:d.careItemId,expectedNativeKind:'HANDOVER'});
  if(!source)return;
  handoverSourcePreviewOnly.value=true;handoverSourceContext.value=source;
}
async function arrangeHandover(row){
  if(!legacyAvailable.value || typedNarrowMode.value || !items.value.includes(row) || row.kind!=='HANDOVER' || Number(row.patientId)!==Number(patientId.value) || !handoverId(row.id) || handoverLocked(row))return
  if(!beforeCareTabLeave('assistance',tab.value))return
  handoverViewEpoch++;
  const view=captureHandoverView(),pid=patientId.value,auth={...captureAuthSession(),actorId:localStorage.getItem('userId')},source=typedHandoverSource({sourceKind:'CARE_ITEM',careItemId:row.id,expectedNativeKind:'HANDOVER'})
  // 本地来源草稿不写 URL；规范 /care 可通过异步菜单及 workspace guard。
  const canonical=stockRoute.fullPath!=='/care'
  if(canonical && await router.replace('/care'))return
  if(!ownsHandoverView(view) || handoverRouteEpoch!==view.routeEpoch+(canonical?1:0) || stockRoute.fullPath!=='/care' || pid!==patientId.value || !isAuthSessionCurrent(auth) || auth.actorId!==localStorage.getItem('userId') || !legacyAvailable.value || !items.value.includes(row))return
  entryVisible.value=false;entryRow.value=null;handoverSourcePreviewOnly.value=false;handoverSourceContext.value=source;tab.value='assistance'
}
async function handoverCreated(result){
  const view=captureHandoverView(),pid=patientId.value,auth={...captureAuthSession(),actorId:localStorage.getItem('userId')}
  // 成功后先撤掉旧修改入口，再重新读取旧列表；未知结果由 frozenId 保持禁用。
  context.value={...context.value,items:items.value.filter(row=>Number(row.id)!==Number(result.careItemId))}
  home.value=home.value.map(group=>({...group,items:(group.items||[]).filter(row=>Number(row.id)!==Number(result.careItemId))}))
  if(entryRow.value?.id===result.careItemId){entryVisible.value=false;entryRow.value=null}
  await reload()
  // 成功事实与旧行刷新保留；只有仍属于当前来源视图的回调可以更新路由。
  if(!ownsHandoverView(view) || view.routeEpoch!==handoverRouteEpoch || tab.value!=='assistance' || Number(handoverSourceContext.value?.careItemId)!==Number(result.careItemId) || pid!==patientId.value || !isAuthSessionCurrent(auth) || auth.actorId!==localStorage.getItem('userId'))return
  if(handoverId(result.assistanceId))await selectHandoverRequest({patientId:pid,assistanceId:result.assistanceId})
}
async function entrySaved(){await reload();if(entryKind.value==='QUESTION')await questionPanel.value?.refresh()}
function prepareQuestionHandover(row){if(legacyAvailable.value)open('HANDOVER',{title:row.followUp,details:{note:row.recordedAnswer||''},kind:'HANDOVER'})}
function openOrdinaryQuestion(row){
  if(!patientId.value)return
  entryKind.value='QUESTION';entryNeedsLegacy.value=false
  entryRow.value=row ? {id:row.questionId,patientId:row.patientId,kind:'QUESTION',title:row.title,questionProjection:row.managed ? {questionVersion:row.version,answerOrigin:row.answerOrigin,projectionBasis:'CURRENT_ORDINARY_NOTE'} : undefined,details:{description:row.description||'',answer:row.recordedAnswer||'',followUp:row.followUp||'',appointmentId:row.legacyAppointmentId??null}} : null
  entryVisible.value=true
}
async function open(kind,row){
  if(!patientId.value || handoverLocked(row))return
  if(kind==='QUESTION'){
    tab.value='questions';await nextTick()
    if(row?.id){openContextQuestion(row)}
    else { await questionPanel.value?.flow.loadCapabilities();if(questionPanel.value?.flow.state.availability==='READY')questionPanel.value.flow.begin('CREATE');else if(legacyAvailable.value||['DISABLED','ABSENT'].includes(questionPanel.value?.flow.state.availability))openOrdinaryQuestion() }
    return
  }
  entryNeedsLegacy.value=true;entryKind.value=kind;entryRow.value=row||null;entryVisible.value=true
}
// 普通投影已有独立服务器授权，不把 Q 权限拒绝当成此分支的授权或撤权。
function openContextQuestion(row) {
  if(typedNarrowMode.value || !legacyAvailable.value || !questions.value.includes(row) || Number(row.patientId)!==Number(patientId.value))return
  entryNeedsLegacy.value=true;entryKind.value='QUESTION';entryRow.value=row;entryVisible.value=true
}
async function loadOrdinaryQuestions(cursor=null) {
  if(typedNarrowMode.value || !legacyAvailable.value || ordinaryPageLoading.value)return
  const epoch=generation,pid=patientId.value,auth={...captureAuthSession(),actorId:localStorage.getItem('userId')}
  const current=()=>epoch===generation && pid===patientId.value && isAuthSessionCurrent(auth) && auth.actorId===localStorage.getItem('userId') && !typedNarrowMode.value
  ordinaryPageLoading.value=true;ordinaryPageError.value=false
  try {
    const result=await api.getCareContext(pid,{questionLimit:25,...(cursor?{questionCursor:cursor}:{})},{expectedAuth:auth,visitQuestion:true})
    if(!current())return
    const data=result.data||{},rows=(data.items||[]).filter(row=>row.kind==='QUESTION')
    if(Number(data.patient?.id)!==Number(pid) || rows.some(row=>Number(row.patientId)!==Number(pid)))throw new Error('QUESTION_IDENTITY_INVALID')
    context.value={...context.value,items:[...items.value.filter(row=>row.kind!=='QUESTION'),...rows],questionContinuation:data.questionContinuation,questionPreviews:data.questionPreviews}
  }catch(error){if(!current())return;ordinaryPageError.value=true;if([401,403].includes(Number(error?.response?.status||error?.code))){context.value={...context.value,items:items.value.filter(row=>row.kind!=='QUESTION'),questionContinuation:null};if(entryNeedsLegacy.value&&entryKind.value==='QUESTION'){entryVisible.value=false;entryRow.value=null}}}
  finally{if(current())ordinaryPageLoading.value=false}
}
function changeOrder(row,action){const data=JSON.parse(JSON.stringify(row));delete data.id;data.details.action=action;data.details.startDate=localDateKey();data.details.endDate=null;data.title=`${drugName(data.details.medicationId)}${action==='STOP'?'stop medication':'medication adjustment'}`;open('ORDER',data)}
async function action(row,status){if(row.kind==='APPOINTMENT'&&row.visitFollowUpManaged){await open('APPOINTMENT',row);return;}if(handoverLocked(row))return;await perform(()=>api.careAction(row.id,status))}
async function removeItem(row){if(handoverLocked(row))return;await perform(()=>api.deleteCareItem(row.id))}
async function saveEditor(kind, form, save, finish) {
  if(busy.value||!patientId.value||disposed)return
  const id=patientId.value,epoch=patientEpoch,editor=editorVersions[kind]
  const data={...form,patientId:id},draft=JSON.stringify(form),operation={kind}
  const current=()=>!disposed&&editorOperation===operation&&epoch===patientEpoch&&id===patientId.value&&editor===editorVersions[kind]
  editorOperation=operation;busy.value=true
  try{
    await save(data)
    if(!current()||JSON.stringify(form)!==draft)return
    finish(data)
    ElMessage.success('Saved successfully.')
    await reload()
  }catch(error){if(current()&&JSON.stringify(form)===draft)throw error}
  finally{if(editorOperation===operation){editorOperation=null;busy.value=false}}
}
async function submitQuick() {
  if(busy.value||measurementLocked.value)return
  if(!['enabled','disabled'].includes(measurementFlow.state.capabilityPhase)){ElMessage.warning(awText('capabilityUnknown'));return}
  try{
    const present=value=>value!==null&&value!==undefined&&String(value).trim()!=='',number=value=>present(value)&&Number.isFinite(Number(value));
    const bp=present(quick.systolicBp)||present(quick.diastolicBp),bg=present(quick.bloodGlucose);
    if((!bp&&!bg)||(bp&&(!number(quick.systolicBp)||!number(quick.diastolicBp)||Number(quick.systolicBp)<=0||Number(quick.diastolicBp)<=0))||(bg&&(!number(quick.bloodGlucose)||Number(quick.bloodGlucose)<=0||!['mmol/L','mg/dL'].includes(quick.bgUnit)||!['Fasting','After Meal2h','Random'].includes(quick.measurePeriod))))throw new Error('INPUT_REQUIRED');
    // 先校验用户输入，不让控件夹值或让数据库替它舍入。
    if(bp&&(!measurementFits(quick.systolicBp,10,0)||!measurementFits(quick.diastolicBp,10,0)||Number(quick.systolicBp)<40||Number(quick.systolicBp)>300||Number(quick.diastolicBp)<20||Number(quick.diastolicBp)>200))throw new Error('INPUT_REQUIRED');
    if(bg&&!measurementFits(quick.bloodGlucose,5,2))throw new Error('INPUT_REQUIRED');
    measurementInstant(quickWall.value,null,{localOnly:true});
    const data={...quick,measureType:bp?(bg?'BP_BG':'BP'):'BG',systolicBp:bp?Number(quick.systolicBp):null,diastolicBp:bp?Number(quick.diastolicBp):null,bloodGlucose:bg?Number(quick.bloodGlucose):null,bgUnit:bg?quick.bgUnit:null,measurePeriod:bg?quick.measurePeriod:null,recordDate:quickWall.value.slice(0,10),recordTime:quickWall.value.slice(11)};
    if(measurementFlow.state.capabilityPhase==='disabled'){await saveEditor('quick',quick,payload=>api.quickVitals({...data,patientId:payload.patientId}),()=>{resetQuick();quickVisible.value=false});return}
    data.occurredAt=measurementInstant(quickWall.value,quickOffset.value);
    const result=await measurementFlow.saveSource('BP_SELF_MONITOR',data);
    if(result.status==='succeeded'){ElMessage.success(awText('saved'));resetQuick();quickVisible.value=false;await reload()}
  }catch{ElMessage.error(awText('inputInvalid'))}
}

function openIntake(i){selectedIntake.value=i;intakeQuantity.value=undefined;intakeVisible.value=true}
async function confirmIntake(){await intakeAction(selectedIntake.value,'TAKEN','',intakeQuantity.value);intakeVisible.value=false}
async function intakeAction(i,status,reason='',quantity){await perform(()=>api.recordIntake(i.id,status,reason,quantity,mode.value==='FAMILY'?'FAMILY':'SELF'))}
async function skipIntake(i){const r=await ElMessageBox.prompt('Reason for skipping','Skip this dose').catch(()=>null);if(r)await intakeAction(i,'SKIPPED',r.value)}
function openStock(row){invalidateEditor('stock');Object.assign(stockForm,{id:null,medicationId:null,quantity:0,unit:'tablet',warningDays:7,warningQuantity:0},row?{id:row.id,medicationId:row.medication_id,quantity:Number(row.quantity),unit:row.unit,warningDays:row.warning_days,warningQuantity:Number(row.warning_quantity)}:{});stockVisible.value=true}
async function submitStock(){await saveEditor('stock',stockForm,data=>api.saveStock(data),()=>{stockVisible.value=false})}
async function purchase(row){const pid=patientId.value,r=await ElMessageBox.prompt(`How many ${row.unit} should be added?`,row.drugName,{inputPattern:/^\d+(\.\d{1,3})?$/,inputErrorMessage:'Enter a valid number'}).catch(()=>null);if(r&&pid===patientId.value)await perform(()=>api.purchaseStock({patientId:pid,medicationId:row.medication_id,quantity:Number(r.value),reason:'Restock'}))}
async function showStockHistory(row) {
  if (!patientId.value) return
  const epoch = patientEpoch, request = ++historyRequest
  const result = await api.getStockHistory(patientId.value, row.medication_id)
  if (epoch !== patientEpoch || request !== historyRequest) return
  stockHistory.value = result.data || []
  historyVisible.value = true
}
async function invite() {
  if (!patientId.value) return
  const epoch = patientEpoch, request = ++inviteRequest
  const result = await api.createCareInvite(patientId.value)
  if (epoch !== patientEpoch || request !== inviteRequest) return
  inviteCode.value = result.data
}
async function copyInvite(){try{await navigator.clipboard.writeText(inviteCode.value);ElMessage.success('Invitation code copied.')}catch{ElMessage.info('Select and copy the invitation code manually.')}}
async function join(){await perform(async()=>{const r=await api.joinCare(joinCode.value.trim(),joinRelation.value);joinVisible.value=false;patientId.value=r.data;window.dispatchEvent(new Event('care-patients-changed'))})}
async function removeMember(row){await perform(()=>api.removeCareMember(patientId.value,row.userId))}
async function saveProfile(){await perform(()=>api.saveCareItem({patientId:patientId.value,kind:'PROFILE',title:'Care settings',details:{...profile}}))}
function memberName(id){return context.value.members?.find(m=>m.userId===id)?.name||'not specified'}
function drugName(id){return context.value.medications?.find(m=>m.id===id)?.drugName||'Medication'}
function lastActor(id){const a=context.value.actions?.find(x=>x.intake_id===id);return a?`${a.actor_name||'Family member'} ${a.recorded_for==='FAMILY'?'recorded for the patient':'recorded'}: ${statusText(a.status)}`:''}
function pendingCount(p){return(p.intakes||[]).filter(i=>!i.nextAction && ['PENDING','MISSED','SNOOZED'].includes(i.status)).length+(p.items||[]).filter(i=>['APPOINTMENT','HANDOVER'].includes(i.kind)&&i.status==='OPEN').length}
function nextAppointment(){return appointmentText('selectSchedulePatient')}
function symptomChange(s){const previous=symptoms.value.filter(x=>x.title===s.title&&x.id!==s.id&&(x.eventAt||'')<(s.eventAt||'')).sort((a,b)=>(b.eventAt||'').localeCompare(a.eventAt||''))[0];if(!previous)return'First entry';const diff=Number(s.details.severity)-Number(previous.details.severity);return diff===0?'Same score as the previous entry':`${Math.abs(diff)} point${Math.abs(diff)===1?'':'s'} ${diff>0?'higher':'lower'} than the previous entry`}
const progressText=s=>({ONGOING:'Symptoms continue',IMPROVED:'Improved',RESOLVED:'Resolved'}[s]||'Not provided')
const statusText=s=>({NO_SHOW:appointmentText('NO_SHOW'),OPEN:'Open',DONE:'Completed',CANCELLED:'Cancelled',ACTIVE:'Active',SCHEDULED:'Scheduled',STOPPED:'Stopped',ANSWERED:'Answered',RESOLVED:'Resolved',PENDING:'Due',MISSED:'Missed',SNOOZED:'Snoozed',TAKEN:'Taken',SKIPPED:'Skipped'}[s]||s)
const daysText=s=>String(s||'').split(',').map(x=>['','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'][Number(x)]).join(', ')
</script>
<style scoped>
.medication-status{display:inline-flex;align-items:center;gap:6px;max-width:100%;box-sizing:border-box;padding:5px 8px;border:1px solid currentColor;border-radius:7px;font-size:1em;font-weight:600;line-height:1.5;overflow-wrap:anywhere}
.medication-status svg{width:18px;height:18px;flex:0 0 18px;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
.medication-status[data-medication-status="PENDING"]{color:var(--info);background:var(--info-soft)}
.medication-status[data-medication-status="MISSED"]{color:var(--danger);background:var(--danger-soft)}
.medication-status[data-medication-status="SNOOZED"]{color:var(--warning);background:var(--warning-soft)}

.care-history{margin-bottom:20px;border:1px solid var(--line);border-radius:12px;padding:14px}.care-history>summary{min-height:44px;display:flex;align-items:center;gap:8px;cursor:pointer;font-weight:600;color:var(--care-700);overflow-wrap:anywhere}.care-history>summary:before{content:"+"}.care-history[open]>summary:before{content:"−"}.care-history>summary:focus-visible{outline:2px solid var(--care-700);outline-offset:3px}.care-history>.care-card{margin-top:12px}

.care-center { max-width: 1400px; margin: auto; padding: 24px 28px 32px; }
.care-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 18px; }
.care-header__copy { min-width: 0; }
.eyebrow { color: var(--care-700); font-size: 12px; font-weight: 700; letter-spacing: .08em; }
.care-header h1 { margin: 5px 0 8px; font-size: 28px; line-height: 1.25; letter-spacing: -.025em; }
.care-context { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; margin: 0; color: var(--ink-500); font-size: 14px; }
.care-context strong { color: var(--ink-950); font-size: 16px; overflow-wrap: anywhere; }
.care-header-art { width: 120px; height: 80px; flex: 0 0 auto; }
.care-header-art img { display: block; width: 100%; height: 100%; object-fit: contain; }
.care-controls, .care-controls__utilities { display: flex; align-items: center; flex-wrap: wrap; gap: 16px; }
.care-controls { justify-content: space-between; margin-bottom: 20px; }
.care-controls :deep(.el-radio-button__inner) { min-height: 42px; display: inline-flex; align-items: center; padding: 10px 18px; font-size: 14px; }
.care-options { position: relative; }
.care-options summary { display: flex; align-items: center; gap: 8px; min-height: 44px; padding: 0 10px; color: var(--care-700); font-size: 14px; font-weight: 600; cursor: pointer; border-radius: 8px; list-style: none; }
.care-options summary::-webkit-details-marker { display: none; }
.care-options summary::after { content: '+'; font-size: 18px; }
.care-options[open] summary::after { content: '−'; }
.care-options summary:hover { background: var(--care-50); }
.care-options summary:focus-visible { outline: 2px solid var(--care-700); outline-offset: 2px; }
.care-toolbar { position: absolute; right: 0; top: calc(100% + 4px); z-index: 20; width: max-content; max-width: min(300px, 85vw); display: grid; gap: 8px; padding: 12px; background: var(--paper); border: 1px solid var(--line-strong); border-radius: 12px; box-shadow: 0 8px 24px #20342c14; }
.care-toolbar :deep(.el-button) { margin: 0; }
.family-cards { display: grid; grid-template-columns: repeat(auto-fit,minmax(220px,1fr)); gap: 12px; margin: 0 0 20px; }
.family-cards button { min-width: 0; display: flex; flex-direction: column; gap: 8px; padding: 16px; text-align: left; font: inherit; color: var(--ink-700); background: var(--paper); border: 1px solid var(--line-strong); border-radius: 12px; }
.family-cards button.selected { border-color: var(--care-700); box-shadow: inset 0 0 0 1px var(--care-700); background: var(--care-50); }
.family-card__heading { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; width: 100%; }
.family-cards strong { color: var(--ink-950); font-size: 18px; overflow-wrap: anywhere; }
.family-card__selected { margin-left: auto; font-size: 12px; color: var(--care-700); font-weight: 700; }
.care-center small { color: var(--ink-500); font-size: 14px; line-height: 1.55; }
.care-tabs { min-width: 0; background: var(--paper); border: 1px solid var(--line); border-radius: 16px; padding: 0 20px 20px; }
.care-tabs :deep(.el-tabs__header) { margin-bottom: 0; }
.care-tabs :deep(.el-tabs__item) { height: 54px; padding-inline: 16px; font-size: 15px; }
.care-tabs :deep(.el-tabs__nav-next), .care-tabs :deep(.el-tabs__nav-prev) { line-height: 54px; }
.care-tabs :deep(.el-tabs__content) { padding-top: 20px; }
.quick-actions { display: flex; flex-wrap: wrap; gap: 10px; margin: 0 0 20px; }
.quick-actions :deep(.el-button) { flex: 1; min-width: 150px; min-height: 56px; height: auto; margin: 0; padding: 12px; white-space: normal; line-height: 1.4; }
.quick-actions :deep(.el-button > span) { white-space: normal; }
.quick-actions__primary small { display: block; color: inherit; font-size: 13px; font-weight: 400; }
.care-columns { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 16px; align-items: start; }
.care-card { min-width: 0; padding: 18px; border: 1px solid var(--line); border-radius: 12px; margin-bottom: 16px; background: var(--paper); }
.care-card__heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; padding-bottom: 14px; border-bottom: 1px solid var(--line); }
.care-card h2, .care-card h3 { margin: 0; font-size: 18px; line-height: 1.4; }
.care-count { padding: 4px 8px; border-radius: 6px; background: var(--care-50); color: var(--care-700); font-size: 13px; font-weight: 600; white-space: nowrap; }
.care-card p { margin: 6px 0; line-height: 1.65; }
.care-task { display: flex; flex-wrap: wrap; gap: 10px; align-items: flex-start; padding: 16px 0; border-bottom: 1px solid var(--line-soft); }
.care-task:last-child { padding-bottom: 0; border-bottom: 0; }
.care-task > div:first-child { flex: 1; min-width: min(180px,100%); }
.care-task strong { font-size: 16px; line-height: 1.5; overflow-wrap: anywhere; }
.care-task small { display: block; }
.task-actions, .attachment-links { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 12px; }
.care-task > .task-actions { flex: 0 0 100%; margin-top: 0; }
.task-actions :deep(.el-button) { min-height: 44px; height: auto; margin: 0; padding: 10px 12px; white-space: normal; }
.task-actions :deep(.el-button > span) { white-space: normal; }
.section-head { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 20px; }
.calendar-cell button { display: block; border: 0; background: var(--care-50); color: var(--care-700); padding: 3px; text-align: left; width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; border-radius: 4px; margin-top: 4px; }
.invite-code { padding: 16px; background: var(--care-50); overflow-wrap: anywhere; }
.attachment-links { margin-block: 14px; }
.order-card small { display: block; }
@media (max-width: 1100px) { .care-columns { grid-template-columns: 1fr; } }
@media (max-width: 768px) {
  .care-center { padding: 18px 14px 24px; }
  .care-header { margin-bottom: 14px; gap: 10px; }
  .care-header h1 { font-size: 25px; }
  .care-header-art { width: 84px; height: 64px; }
  .care-controls { gap: 10px; margin-bottom: 16px; }
  .care-controls__utilities { gap: 12px; }
  .care-controls :deep(.el-radio-button__inner) { padding-inline: 14px; }
  .family-cards { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 10px; }
  .family-cards button { padding: 12px; }
  .family-card__selected { margin-left: 0; }
  .care-tabs { padding: 0 12px 12px; }
  .care-tabs :deep(.el-tabs__item) { height: 48px; }
  .care-tabs :deep(.el-tabs__nav-next), .care-tabs :deep(.el-tabs__nav-prev) { line-height: 48px; }
  .care-tabs :deep(.el-tabs__content) { padding-top: 14px; }
  .quick-actions { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 8px; margin-bottom: 16px; }
  .quick-actions :deep(.el-button) { min-width: 0; width: 100%; min-height: 48px; }
  .quick-actions__primary { grid-column: 1 / -1; }
  .care-card { padding: 14px; }
  .care-card h2 { font-size: 17px; }
  .section-head { align-items: flex-start; flex-direction: column; }
  .care-tabs :deep(.el-calendar__body) { padding: 0; }
  .care-tabs :deep(.el-calendar-day) { height: 85px; padding: 4px; }
}
@media (max-width: 480px) {
  .care-header-art { display: none; }
  .care-controls__utilities { width: 100%; justify-content: space-between; }
  .family-cards { grid-template-columns: 1fr; }
}
.medication-typed-link{display:inline-flex;align-items:center;min-height:44px;max-width:100%;white-space:normal;overflow-wrap:anywhere;padding:4px 8px;box-sizing:border-box}
</style>

<style scoped>.measurement-save{min-height:44px}.measurement-entry-form :deep(.el-input__wrapper),.measurement-entry-form :deep(.el-select__wrapper),.measurement-entry-form :deep(.el-button){min-height:44px}.measurement-entry-form :deep(.el-input-number){max-width:100%;min-height:44px}.measurement-entry-form :deep(.el-form-item__label){height:auto;white-space:normal;overflow-wrap:anywhere}</style>
