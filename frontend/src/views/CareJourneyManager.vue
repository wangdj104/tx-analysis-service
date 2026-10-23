<template>
  <main class="journey-page">
    <header v-if="!focusedMode && !visitFocus.attempted" class="journey-hero">
      <div><span>CONTINUOUS CARE</span><h1>Care journey</h1><p>Measurements, appointments, consultation, recovery, emergency information, specialty care, privacy, and operations in one traceable workspace.</p></div>
      <el-tag v-if="tab === 'consultation' && isDoctor" effect="plain">Consultation inbox</el-tag>
      <el-tag v-else effect="plain">Patient {{ currentPatientName || currentPatientId || 'not selected' }}</el-tag>
    </header>

    <el-alert v-if="!focusedMode && !visitFocus.attempted && patientRequired && !currentPatientId" title="Select a patient from the workspace header before using this module." type="warning" show-icon :closable="false" />
    <section v-if="focusedMode" class="focused-source" aria-label="Linked measurement">
      <h1>Linked measurement</h1><p v-if="sourceFocus.locator.value">Patient #{{ sourceFocus.locator.value.patientId }} · #{{ sourceFocus.locator.value.sourceId }}</p>
      <p v-if="sourceFocus.state.loading" role="status">Checking source access…</p><p v-if="sourceFocus.state.error" role="alert">This linked record is restricted, unavailable, or its context changed.</p><button type="button" :disabled="sourceFocus.state.loading" @click="sourceFocus.reload">Recheck source</button>
      <el-table :data="sourceFocus.state.record ? [sourceFocus.state.record] : []" max-height="440"><el-table-column :label="ot('originalTime')" min-width="210"><template #default="{row}">{{recordedTime(row.measured_at)}}</template></el-table-column><el-table-column :label="ot('metric')" min-width="160"><template #default="{row}">{{journeyName(row)}}<small> #{{row.id}}</small></template></el-table-column><el-table-column label="Value"><template #default="{row}">{{journeyValues(row)}}</template></el-table-column><el-table-column v-if="measurementFlow.state.capabilityPhase==='disabled'" prop="status" :label="ot('historyStatus')" width="100"><template #default="{row}"><el-tag type="info">{{row.status}}</el-tag></template></el-table-column></el-table>
      <article v-for="note in sourceFocus.state.record?.annotations || []" :key="note.id"><p>{{note.doctor_name}} · {{note.created_at}}</p><p>{{note.annotation}}</p></article>
    </section>
    <section v-if="visitFocus.attempted"><p v-if="!visitFocus.value" role="alert">{{visitText('unavailable')}}</p><VisitDispositionPanel v-else ref="visitPanel" :patient-id="visitFocus.value.patientId" :source-id="visitFocus.value.sourceId" /></section>
    <el-tabs v-if="!focusedMode && !visitFocus.attempted" v-model="tab" class="journey-tabs" :before-leave="beforeTabLeave">
      <el-tab-pane label="Measurements" name="measurements">
        <TrustedMeasurementTrend v-if="tab==='measurements' && !focusedMode" ref="trustedTrend" :patient-id="currentPatientId" :patient-name="currentPatientName" initial-source-type="HEALTH_MEASUREMENT" @changed="loadMeasurements" />
        <FollowUpList v-if="tab==='measurements'" :patient-id="currentPatientId" />
        <MeasurementWorkflowStatus :flow="measurementFlow" source-type="HEALTH_MEASUREMENT" @saved="measurementChanged" />
        <section class="panel-grid two">
          <el-card><template #header><b>Record a health measurement</b></template><el-form class="measurement-entry-form" :disabled="busy || measurementLocked || (patientRequired && !currentPatientId)" label-position="top">
            <div class="form-grid"><el-form-item label="Metric"><el-select v-model="measurement.metricType"><el-option v-for="item in metricOptions" :key="item.value" v-bind="item" /></el-select></el-form-item><el-form-item label="Measured at"><el-date-picker v-model="measurement.measuredAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item><el-form-item label="Primary value"><el-input v-model="measurement.valuePrimary"  type="text" inputmode="decimal" /></el-form-item><el-form-item v-if="measurement.metricType==='BP'" label="Diastolic"><el-input v-model="measurement.valueSecondary"  type="text" inputmode="decimal" /></el-form-item><el-form-item label="Unit"><el-input v-model="measurement.unit" :placeholder="({BP:'mmHg',GLUCOSE:'mmol/L / mg/dL',SPO2:'%',WEIGHT:'kg',HEART_RATE:'bpm',TEMPERATURE:'°C'})[measurement.metricType] || awText('unit')" /></el-form-item><el-form-item label="Notes"><el-input v-model="measurement.remark" /></el-form-item></div>
            <el-form-item v-if="measurementFlow.state.capabilityPhase==='enabled'" :label="awText('offset')"><el-input v-model="measurementOffset" placeholder="+08:00" /><p>{{awText('offsetHelp')}}</p></el-form-item><el-button type="primary" :loading="busy" :disabled="measurementLocked || !['enabled','disabled'].includes(measurementFlow.state.capabilityPhase) || (measurementFlow.state.capabilityPhase==='enabled'&&!measurementFlow.state.capabilities?.canAdopt)" @click="recordMeasurement">Save and evaluate</el-button>
          </el-form></el-card>
          <el-card><div class="measurement-read-model"><button type="button" data-action="toggle-journey-original" :disabled="measurementLocked" :aria-expanded="rawOpen" @click="rawOpen=!rawOpen">{{ot(rawOpen?'close':'open')}}</button></div><template v-if="rawOpen"><OriginalMeasurementPageStatus :state="raw.state" :locked="measurementLocked" @refresh="loadMeasurements" @retry="raw.refresh({keepPosition:true})" @next="raw.next()" @previous="raw.previous()" /><p>{{ot('unassessed')}}</p><el-table :data="measurements" max-height="440"><el-table-column :label="ot('originalTime')" width="220"><template #default="{row}">{{recordedTime(row.measured_at)}}</template></el-table-column><el-table-column :label="ot('metric')" min-width="160"><template #default="{row}">{{journeyName(row)}}<small> #{{row.id}}</small></template></el-table-column><el-table-column label="Value"><template #default="{row}">{{journeyValues(row)}}</template></el-table-column><el-table-column v-if="measurementFlow.state.capabilityPhase==='disabled'" prop="status" :label="ot('historyStatus')" width="100"><template #default="{row}"><el-tag type="info">{{row.status}}</el-tag></template></el-table-column><el-table-column v-if="measurementFlow.state.capabilityPhase==='enabled'" :label="awText('source')" min-width="180"><template #default="{row}"><el-button link :disabled="measurementLocked" @click="inspectMeasurement(row)">{{awText('sourceOpen')}}</el-button></template></el-table-column><el-table-column v-if="isDoctor" label="Clinical note" width="130"><template #default="{row}"><el-button link @click="annotate(row)">Annotate</el-button></template></el-table-column></el-table></template></el-card>
        </section>
      <SourceMeasurementPanel v-if="measurementFlow.state.source||measurementFlow.state.receipt||measurementFlow.state.sourceReadPhase!=='idle'" :flow="measurementFlow" @changed="measurementChanged" />
      </el-tab-pane>

      <el-tab-pane label="Appointments & visits" name="appointments">
        <section ref="physicianSection" tabindex="-1" :aria-label="appointmentText('physicianRecords')"><VisitDispositionPanel ref="visitPanel" :patient-id="currentPatientId" :active="tab==='appointments' && !focusedMode" @capabilities="visitCapabilities" /></section>
        <section ref="attendanceSection" tabindex="-1" :aria-label="appointmentText('attendanceRecords')"><AppointmentFollowUpPanel ref="appointmentPanel" :patient-id="currentPatientId" :active="tab==='appointments' && !focusedMode" @changed="loadAppointments" /></section>
        <p v-if="appointmentReads.appointments==='unavailable'" role="alert">{{appointmentText('REQUEST_FAILED')}} <el-button @click="loadAppointments">{{appointmentText('refresh')}}</el-button></p>
        <ReconciliationPanel :patient-id="currentPatientId" :navigation-key="tab" />
        <section ref="accompanimentSection" tabindex="-1" :aria-label="appointmentText('accompanimentRecords')"><AssistancePanel :patient-id="currentPatientId" source-kind="CARE_APPOINTMENT" :active="tab==='appointments' && !focusedMode" /></section>
        <section class="panel-grid two">
          <el-card><template #header><b>Book or reschedule</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><div class="form-grid"><el-form-item label="Clinician"><el-select v-model="appointment.doctorUserId" @change="changeAppointmentDoctor"><el-option v-for="d in clinicians" :key="d.id" :label="d.real_name||d.username" :value="d.id"/></el-select></el-form-item><el-form-item label="Available session"><el-select v-model="appointment.scheduleId" clearable @change="selectSchedule"><el-option v-for="s in doctorSchedules" :key="s.id" :value="s.id" :label="`${s.work_date} ${s.start_time}-${s.end_time}`"/></el-select></el-form-item><el-form-item label="Mode"><el-select v-model="appointment.consultationMode"><el-option label="In person" value="IN_PERSON"/><el-option label="Text" value="TEXT"/><el-option label="Voice" value="VOICE"/><el-option label="Video" value="VIDEO"/></el-select></el-form-item><el-form-item label="Start"><el-date-picker v-model="appointment.startAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item><el-form-item label="End"><el-date-picker v-model="appointment.endAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item><el-form-item label="Repeat follow-up every (days)"><el-input-number v-model="appointment.recurrenceDays" :min="0"/></el-form-item><el-form-item label="Reason"><el-input v-model="appointment.reason"/></el-form-item></div><el-button type="primary" @click="bookAppointment">Save appointment</el-button></el-form></el-card>
          <el-card><template #header><b>Shared schedule</b></template><el-table :data="appointments" max-height="430"><el-table-column prop="start_at" label="Start" width="165"/><el-table-column prop="doctor_name" label="Clinician"/><el-table-column prop="consultation_mode" label="Mode"/><el-table-column label="Status"><template #default="{row}">{{appointmentStatusLabel(row.status)}}<span v-if="row.visit_follow_up_managed"> · {{appointmentText(row.visit_follow_up_attendance)}}</span></template></el-table-column><el-table-column label="Actions" width="150"><template #default="{row}"><el-button link @click="openAppointmentFollowUp(row)">{{appointmentText('open')}}</el-button><el-button v-if="visitCreateAvailable && Number(row.patient_id)===Number(currentPatientId)" link @click="visitPanel?.beginFromAppointment({patientId:Number(row.patient_id),sourceKind:'CARE_APPOINTMENT',sourceId:Number(row.id)},$event)">{{appointmentText('recordVisit')}}</el-button><router-link v-if="row.status!=='CANCELLED' && Number(row.patient_id)===Number(currentPatientId)" :to="questionLink(row.patient_id,'CARE_APPOINTMENT',row.id)">{{questionText('title')}}</router-link><el-button v-if="row.status==='BOOKED' && !row.visit_follow_up_managed" link @click="editAppointment(row)">Reschedule</el-button><el-button v-if="row.status==='BOOKED' && !row.visit_follow_up_managed" link type="danger" @click="cancel(row)">Cancel</el-button></template></el-table-column></el-table></el-card>
        </section>
        <el-card v-if="isDoctor" class="section-card"><template #header><b>Post-visit summary, prescription, and follow-up</b></template><el-form v-if="visitLegacyMode" :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><div class="form-grid"><el-form-item label="Diagnosis summary"><el-input v-model="visit.diagnosisSummary" type="textarea"/></el-form-item><el-form-item label="Treatment summary"><el-input v-model="visit.treatmentSummary" type="textarea"/></el-form-item><el-form-item label="Follow-up advice"><el-input v-model="visit.followUpAdvice" type="textarea"/></el-form-item><el-form-item label="Follow-up time"><el-date-picker v-model="visit.followUpAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item></div><el-button type="primary" @click="createVisit">Save summary</el-button></el-form><el-divider>Electronic prescription record</el-divider><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><div class="form-grid"><el-form-item label="Drug"><el-input v-model="prescription.drugName"/></el-form-item><el-form-item label="Dose"><el-input v-model="prescription.dosage" placeholder="e.g. 5 mg"/></el-form-item><el-form-item label="Frequency"><el-input v-model="prescription.frequency" placeholder="e.g. twice daily"/></el-form-item><el-form-item label="Route"><el-input v-model="prescription.administrationRoute" placeholder="oral / topical"/></el-form-item><el-form-item label="Duration days"><el-input-number v-model="prescription.durationDays" :min="1"/></el-form-item><el-form-item label="Instructions"><el-input v-model="prescription.instructions"/></el-form-item></div><el-button type="primary" @click="savePrescription">Publish prescription version</el-button></el-form></el-card>
        <el-card v-if="visitLegacyMode" class="section-card"><template #header><b>Visit timeline</b></template><p v-if="appointmentReads.visits==='unavailable'" role="alert">{{appointmentText('REQUEST_FAILED')}}</p><el-button @click="loadVisits">{{appointmentText('refresh')}}</el-button><el-timeline><el-timeline-item v-for="v in visits" :key="v.id" :timestamp="v.visited_at"><b>{{v.diagnosis_summary||'Visit summary'}}</b><p>{{v.treatment_summary}}</p><router-link v-if="v.status==='PUBLISHED' && Number(v.patient_id)===Number(currentPatientId)" :to="questionLink(v.patient_id,'VISIT_RECORD',v.id)">{{questionText('title')}}</router-link><el-button v-if="isDoctor&&v.status!=='PUBLISHED'&&!v.visit_follow_up_managed" link @click="publish(v)">Publish to patient and family</el-button></el-timeline-item></el-timeline></el-card>
        <el-card class="section-card"><template #header><b>Electronic prescriptions and medication instructions</b></template><p v-if="appointmentReads.prescriptions==='unavailable'" role="alert">{{appointmentText('REQUEST_FAILED')}}</p><el-button @click="loadPrescriptions">{{appointmentText('refresh')}}</el-button><el-empty v-if="appointmentReads.prescriptions==='ready' && !prescriptions.length" description="No published prescriptions yet" :image-size="60"/><article v-for="entry in prescriptions" :key="entry.id" class="prescription-version"><div class="card-head"><b>Version {{entry.version_no}}</b><el-tag :type="entry.status==='ACTIVE'?'success':'info'">{{entry.status==='ACTIVE'?'Current instructions':'Previous version'}}</el-tag></div><p>{{String(entry.published_at||'').replace('T',' ')}} · {{entry.instructions}}</p><el-table :data="entry.items||[]"><el-table-column prop="drug_name" label="Medicine" min-width="140"/><el-table-column prop="dosage" label="Dose" min-width="110"/><el-table-column prop="frequency" label="Frequency" min-width="110"/><el-table-column prop="administration_route" label="Route" min-width="100"/><el-table-column prop="duration_days" label="Days" width="105"/></el-table></article></el-card>
      </el-tab-pane>

      <el-tab-pane label="Remote consultation" name="consultation">
        <ConsultationWorkspace :patient-id="currentPatientId" :is-doctor="isDoctor" :enabled="tab === 'consultation'" locale="en" />
      </el-tab-pane>

      <el-tab-pane label="Inpatient & recovery" name="recovery">
        <SpecialtyCarePlans :patient-id="currentPatientId" plan-type="REHAB" />
        <section class="panel-grid two"><el-card v-if="isDoctor"><template #header><b>Treatment plan</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="Plan type"><el-select v-model="plan.planType"><el-option v-for="x in ['INPATIENT','SURGERY','REHAB','DISCHARGE']" :key="x" :value="x" :label="x"/></el-select></el-form-item><el-form-item label="Title"><el-input v-model="plan.title"/></el-form-item><el-form-item label="Structured plan"><el-input v-model="planText" type="textarea" :rows="6" placeholder="Checks, surgery time, medication adjustment, discharge instructions"/></el-form-item><el-button type="primary" @click="createPlan">Publish plan</el-button></el-form></el-card><el-card><template #header><b>Active treatment plans</b></template><el-collapse><el-collapse-item v-for="p in plans" :key="p.id" :title="p.title"><p>{{p.plan_type}} · {{p.status}}</p><pre>{{prettyJson(p.plan_json)}}</pre></el-collapse-item></el-collapse></el-card></section>
        <el-card class="section-card"><template #header><b>Rehabilitation, wound, drain, and symptom check-in</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><div class="form-grid"><el-form-item label="Record type"><el-select v-model="rehab.recordType"><el-option v-for="x in ['EXERCISE','WOUND','DRAIN','SYMPTOM']" :key="x" :value="x" :label="x"/></el-select></el-form-item><el-form-item label="Completion"><el-slider v-model="rehab.completionPercent"/></el-form-item><el-form-item label="Instruction/action video URL"><el-input v-model="rehab.videoUrl"/></el-form-item><el-form-item label="Structured observation"><el-input v-model="rehabText" type="textarea" placeholder="Wound appearance, drainage volume, symptom severity, or exercise notes"/></el-form-item><el-form-item label="Needs clinician attention"><el-switch v-model="rehab.abnormal"/></el-form-item></div><el-button type="primary" @click="checkinRehab">Save check-in</el-button></el-form><el-table :data="rehabRows"><el-table-column prop="recorded_at" label="Time"/><el-table-column prop="record_type" label="Type"/><el-table-column prop="completion_percent" label="Completion"/><el-table-column prop="abnormal" label="Alert"/></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane label="Emergency" name="emergency">
        <EmergencyRequestPanel :patient-id="emergencyPatientId" :emergency-event-id="route.query.emergencyEventId" :active="tab==='emergency'" />
        <el-card class="section-card"><template #header><div class="card-head"><b>{{emergencyText('offlineTitle')}}</b><el-button @click="loadEmergencyCard">{{emergencyText('refresh')}}</el-button></div></template><p>{{emergencyText('offlineHint')}}</p><div v-if="emergencyCard"><p v-if="emergencyCard.stale" role="alert">{{emergencyText('stale')}}</p><p>{{emergencyText('generated')}}: {{emergencyCard.generatedAt}}</p><h3>{{emergencyCard.patient?.name}}</h3><p><b>{{emergencyText('medicalHistory')}}:</b> {{emergencyCard.patient?.medical_history||'—'}}</p><p><b>{{emergencyText('allergies')}}:</b> {{emergencyCard.clinical?.allergy_drugs||'—'}}</p><p><b>{{emergencyText('bloodType')}}:</b> {{emergencyCard.clinical?.blood_type||'—'}}</p><p><b>{{emergencyText('medication')}}:</b> {{(emergencyCard.medications||[]).map(x=>x.drug_name).join(', ')||'—'}}</p></div></el-card>
      </el-tab-pane>

      <el-tab-pane label="Children & maternity" name="specialty">
        <SpecialtyCarePlans :patient-id="currentPatientId" plan-type="MATERNITY" />
        <section class="panel-grid three"><el-card><template #header><b>Growth curve</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="Date"><el-date-picker v-model="growth.recordDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="Height cm"><el-input-number v-model="growth.heightCm"/></el-form-item><el-form-item label="Weight kg"><el-input-number v-model="growth.weightKg" :precision="2"/></el-form-item><el-form-item label="Head circumference cm"><el-input-number v-model="growth.headCircumferenceCm" :precision="1"/></el-form-item><el-form-item label="Standard percentiles"><div class="inline"><el-input-number v-model="growth.heightPercentile" placeholder="Height %"/><el-input-number v-model="growth.weightPercentile" placeholder="Weight %"/></div></el-form-item><el-button type="primary" @click="saveSpecial('growth',growth)">Save</el-button></el-form></el-card><el-card><template #header><b>Vaccination plan</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="Vaccine"><el-input v-model="vaccine.vaccineName"/></el-form-item><el-form-item label="Dose"><el-input v-model="vaccine.doseNo"/></el-form-item><el-form-item label="Planned date"><el-date-picker v-model="vaccine.plannedDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="Reminder"><el-date-picker v-model="vaccine.remindAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item><el-button type="primary" @click="saveSpecial('vaccination',vaccine)">Save</el-button></el-form></el-card><el-card><template #header><b>Prenatal/postpartum timeline</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="Type"><el-select v-model="maternity.recordType"><el-option label="Prenatal" value="PRENATAL"/><el-option label="Delivery" value="DELIVERY"/><el-option label="Postpartum" value="POSTPARTUM"/></el-select></el-form-item><el-form-item label="Date"><el-date-picker v-model="maternity.recordDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="Title"><el-input v-model="maternity.title"/></el-form-item><el-form-item label="Details"><el-input v-model="maternityText" type="textarea"/></el-form-item><el-button type="primary" @click="saveMaternity">Save</el-button></el-form></el-card></section>
        <el-card class="section-card"><template #header><div class="card-head"><b>Specialty health records</b><el-button @click="loadSpecialty">Refresh</el-button></div></template><el-tabs><el-tab-pane label="Growth"><el-table :data="specialtyRows.growth"><el-table-column prop="record_date" label="Date"/><el-table-column prop="height_cm" label="Height (cm)"/><el-table-column prop="weight_kg" label="Weight (kg)"/><el-table-column prop="head_circumference_cm" label="Head circumference (cm)"/></el-table></el-tab-pane><el-tab-pane label="Vaccinations"><el-table :data="specialtyRows.vaccination"><el-table-column prop="vaccine_name" label="Vaccine"/><el-table-column prop="dose_no" label="Dose"/><el-table-column prop="planned_date" label="Planned date"/><el-table-column prop="remind_at" label="Reminder"/></el-table></el-tab-pane><el-tab-pane label="Maternity"><el-table :data="specialtyRows.maternity"><el-table-column prop="record_date" label="Date"/><el-table-column prop="title" label="Title"/><el-table-column label="Details"><template #default="{row}">{{specialtyDetail(row.data_json)}}</template></el-table-column></el-table></el-tab-pane></el-tabs></el-card>
      </el-tab-pane>

      <el-tab-pane label="Mental health" name="mental">
        <SpecialtyCarePlans :patient-id="currentPatientId" plan-type="MENTAL" />
        <section class="panel-grid two"><el-card><template #header><b>Private self-assessment</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="Scale"><el-select v-model="mental.scaleCode"><el-option label="PHQ-9" value="PHQ9"/><el-option label="GAD-7" value="GAD7"/><el-option label="WHO-5" value="WHO5"/></el-select></el-form-item><el-form-item label="Completed questionnaire scores (in question order)"><el-input v-model="mentalAnswers" :placeholder="mental.scaleCode==='PHQ9'?'0,1,2,1,0,1,0,0,0':mental.scaleCode==='GAD7'?'0,1,2,1,0,1,0':'3,4,3,4,3'"/></el-form-item><el-form-item label="Visible to family"><el-switch v-model="mentalVisible"/></el-form-item><el-button type="primary" @click="submitMental">Submit privately</el-button></el-form></el-card><el-card><template #header><b>Periodic delivery</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="Interval days"><el-input-number v-model="mentalSchedule.intervalDays" :min="1"/></el-form-item><el-form-item label="Next due"><el-date-picker v-model="mentalSchedule.nextDueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item><el-button type="primary" @click="scheduleMental">Schedule</el-button></el-form><el-table :data="mentalRows"><el-table-column prop="submitted_at" label="Submitted"/><el-table-column prop="scale_code" label="Scale"/><el-table-column prop="score" label="Score"/><el-table-column prop="severity" label="Screening result"><template #default="{row}">{{mentalSeverityLabel(row)}}</template></el-table-column></el-table></el-card></section>
        <el-card class="section-card"><template #header><b>Assessment reminder plans</b></template><el-table :data="mentalSchedules" empty-text="No reminder plans"><el-table-column prop="scale_code" label="Scale"/><el-table-column prop="interval_days" label="Interval (days)"/><el-table-column prop="next_due_at" label="Next due"/><el-table-column label="Status"><template #default="{row}">{{row.enabled===1?'Active':'Stopped'}}</template></el-table-column><el-table-column label="Action"><template #default="{row}"><el-button v-if="row.enabled===1" link type="danger" :disabled="busy" @click="disableMentalSchedule(row)">Stop reminders</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane label="Privacy & access" name="privacy">
        <el-card><template #header><b>Patient-controlled access</b></template>
          <el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top" class="inline-form care-plan-grant-form">
            <el-form-item label="Role"><el-select v-model="grant.granteeRole" @change="changeGrantRole"><el-option label="Doctor" value="DOCTOR"/><el-option v-if="carePlanGrantEnabled||measurementGrantEnabled" label="Nurse" value="NURSE"/><el-option label="Family" value="FAMILY"/><el-option label="Guardian" value="GUARDIAN"/></el-select></el-form-item>
            <el-form-item v-if="grant.granteeRole==='DOCTOR'" label="Doctor" class="grant-choice"><el-select v-model="grant.granteeUserId" filterable clearable placeholder="Search platform doctors"><el-option v-for="doctor in clinicians" :key="doctor.id" :value="doctor.id" :label="doctor.real_name && doctor.real_name!==doctor.username ? `${doctor.real_name} (${doctor.username})` : doctor.username"/></el-select></el-form-item>
            <el-form-item v-else-if="grant.granteeRole==='NURSE'" label="Currently assigned nurse" class="grant-choice grant-nurse-choice"><el-select v-model="grant.granteeUserId" clearable filterable><el-option v-for="nurse in nurseCandidates" :key="nurse.nurseUserId" :value="nurse.nurseUserId" :label="`${nurse.nurseName} (#${nurse.nurseUserId})`"/></el-select><p>Assignment and patient authorization are separate steps. Only active assignments for this patient are listed.</p></el-form-item><el-form-item v-else label="Recipient user ID"><el-input-number v-model="grant.granteeUserId"/></el-form-item>
            <el-form-item label="Level"><el-select v-model="grant.accessLevel"><el-option label="Read only" value="READ"/><el-option label="Can record" value="WRITE"/><el-option label="Can act for patient" value="PROXY"/></el-select></el-form-item>
            <el-form-item label="Visible modules" class="grant-choice"><el-select v-model="grantModules" multiple collapse-tags collapse-tags-tooltip clearable placeholder="Leave empty for all modules"><el-option v-for="module in availableGrantModules" :key="module.value" :label="module.label" :value="module.value"/></el-select></el-form-item>
            <el-form-item :label="awText('grantLocal')"><el-date-picker v-model="grant.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item><el-button type="primary" @click="createGrant">Authorize</el-button>
          </el-form>
          <el-table :data="grants"><el-table-column prop="real_name" label="Person"/><el-table-column prop="grantee_role" label="Role"/><el-table-column prop="access_level" label="Level"/><el-table-column prop="visible_modules" label="Visible modules"/><el-table-column prop="status" label="Status"/><el-table-column label=""><template #default="{row}"><el-button link type="danger" @click="revoke(row)">Revoke</el-button></template></el-table-column></el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane v-if="isDoctor" label="Operations" name="operations">
        <el-card class="appointment-inbox"><template #header><div class="card-head"><b>My appointment inbox</b><el-button :loading="appointmentInboxLoading" @click="loadAppointmentInbox">Refresh appointments</el-button></div></template><p class="inbox-description">Appointments addressed to you appear here without selecting a patient above. You can view or cancel this appointment; other medical records remain private.</p><el-table :data="appointmentInbox" max-height="420" empty-text="No appointments addressed to you"><el-table-column prop="patient_name" label="Patient" min-width="110"/><el-table-column label="Start" min-width="160"><template #default="{row}">{{String(row.start_at||'').replace('T',' ')}}</template></el-table-column><el-table-column label="End" min-width="160"><template #default="{row}">{{String(row.end_at||'').replace('T',' ')}}</template></el-table-column><el-table-column label="Mode" min-width="100"><template #default="{row}">{{appointmentModeLabel(row.consultation_mode)}}</template></el-table-column><el-table-column prop="reason" label="Reason" min-width="160"/><el-table-column label="Status" min-width="100"><template #default="{row}">{{appointmentStatusLabel(row.status)}}<span v-if="row.visit_follow_up_managed"> · {{appointmentText(row.visit_follow_up_attendance)}}</span></template></el-table-column><el-table-column label="Actions" width="180"><template #default="{row}"><el-button link @click="openAppointmentFollowUp(row)">{{appointmentText('open')}}</el-button><el-button v-if="row.status==='BOOKED' && !row.visit_follow_up_managed" link type="primary" :disabled="busy" @click="completeInboxAppointment(row)">Complete</el-button><el-button v-if="row.status==='BOOKED' && !row.visit_follow_up_managed" link type="danger" :disabled="busy" @click="cancelInboxAppointment(row)">Cancel</el-button></template></el-table-column></el-table></el-card>
        <section class="panel-grid two"><el-card><template #header><b>Clinician schedule</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><div class="form-grid"><el-form-item v-if="roles.includes('admin')" label="Doctor"><el-select v-model="schedule.doctorUserId" filterable placeholder="Choose a doctor"><el-option v-for="d in clinicians" :key="d.id" :label="d.real_name||d.username" :value="d.id"/></el-select></el-form-item><el-form-item label="Date"><el-date-picker v-model="schedule.workDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="Start"><el-time-picker v-model="schedule.startTime" value-format="HH:mm:ss"/></el-form-item><el-form-item label="End"><el-time-picker v-model="schedule.endTime" value-format="HH:mm:ss"/></el-form-item><el-form-item label="Slot minutes"><el-input-number v-model="schedule.slotMinutes" :min="10"/></el-form-item></div><el-button type="primary" @click="createSchedule">Add availability</el-button></el-form></el-card><el-card><template #header><b>Patient groups</b></template><div class="message-compose"><el-input v-model="group.groupName" placeholder="Group name"/><el-input v-model="group.description" placeholder="Description"/><el-button type="primary" @click="createGroup">Create</el-button></div><el-table :data="groups"><el-table-column prop="group_name" label="Group"/><el-table-column prop="patient_count" label="Patients"/><el-table-column label=""><template #default="{row}"><el-button link @click="addToGroup(row)">Add selected patient</el-button></template></el-table-column></el-table></el-card></section>
        <el-card class="section-card"><template #header><div class="card-head"><b>Internal care outcomes</b><el-button @click="loadOperations">Refresh</el-button></div></template><p>Administrative task closure, attendance reports, and notification delivery do not establish clinical follow-up completion.</p><p>{{appointmentText('recordsSeparate')}}</p><p v-if="!currentPatientId" role="status">{{appointmentText('chooseRecordPatient')}}</p><nav v-else class="operation-record-links" :aria-label="appointmentText('recordsSeparate')"><button type="button" @click="openOperationalRecord('attendance')">{{appointmentText('attendanceRecords')}}</button><button type="button" @click="openOperationalRecord('physician')">{{appointmentText('physicianRecords')}}</button><button type="button" @click="openOperationalRecord('accompaniment')">{{appointmentText('accompanimentRecords')}}</button><router-link :to="`/care-plans/reports?patientId=${currentPatientId}`">{{appointmentText('executionRecords')}}</router-link><router-link :to="`/care-plans/reports?patientId=${currentPatientId}`">{{appointmentText('reviewRecords')}}</router-link></nav><div v-if="operations" class="metric-cards"><article><span>{{appointmentText('CARE_ITEM_APPOINTMENT')}}</span><strong>{{operations.legacyAppointments?.open||0}} / {{operations.legacyAppointments?.total||0}}</strong><small>{{appointmentText('openArrangements')}}</small></article><article><span>{{appointmentText('CARE_APPOINTMENT')}}</span><strong>{{operations.appointments?.booked||0}} / {{operations.appointments?.total||0}}</strong><small>{{appointmentText('openArrangements')}}</small></article><article><span>{{appointmentText('administrativeQuestions')}}</span><strong>{{operations.questions?.available===true ? operations.questions.administrativeClosed : '—'}}</strong><small>{{appointmentText('notClinicalResult')}}</small></article><article><span>Medication adherence</span><strong>{{operations.medication?.adherence_rate||0}}%</strong></article><article><span>Indicators on target</span><strong>{{operations.measurements?.target_rate||0}}%</strong></article><article><span>Resolved alerts</span><strong>{{operations.alerts?.resolved||0}} / {{operations.alerts?.total||0}}</strong></article></div></el-card>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="appointmentDetailVisible" :title="appointmentText('title')" width="min(800px, 96vw)" :before-close="closeAppointmentDetail" destroy-on-close><AppointmentFollowUpPanel v-if="appointmentDetail" ref="appointmentDetailPanel" :patient-id="appointmentDetail.patientId" source-kind="CARE_APPOINTMENT" :source-id="appointmentDetail.sourceId" @changed="refreshAppointmentRows" /></el-dialog>
  </main>
</template>

<script setup>
import SpecialtyCarePlans from '@/components/care-plan/SpecialtyCarePlans.vue'
import {questionLink} from '@/api/visitQuestion'
import {questionText} from '@/utils/visitQuestionCopy'
import {measurementFits} from '@/utils/measurementNumbers';
import EmergencyRequestPanel from '@/components/emergency-assistance/EmergencyRequestPanel.vue';
import {createEmergencyOfflineCard} from '@/utils/emergencyOfflineCard';
import {t as emergencyText} from '@/utils/emergencyAssistanceCopy';

import VisitDispositionPanel from '@/components/visit-follow-up/VisitDispositionPanel.vue';
import {visitLocator} from '@/utils/visitDispositionNavigation';
import {visitText} from '@/utils/visitDispositionCopy';
import AppointmentFollowUpPanel from '@/components/visit-follow-up/AppointmentFollowUpPanel.vue';
import {appointmentText} from '@/utils/visitFollowUpCopy';
import AssistancePanel from '@/components/family-assistance/AssistancePanel.vue';
import ReconciliationPanel from '@/components/medication-execution/ReconciliationPanel.vue'
import {useAlertWorkflowNavigation} from '@/composables/useAlertWorkflowNavigation'
import OriginalMeasurementPageStatus from '@/components/measurement-read-model/OriginalMeasurementPageStatus.vue'
import {useOriginalMeasurementPage} from '@/composables/useOriginalMeasurementPage'
import {originalText as ot} from '@/utils/originalMeasurementCopy'
import {recordedTime,journeyValues,journeyName} from '@/utils/originalMeasurementPresentation'
import TrustedMeasurementTrend from '@/components/measurement-read-model/TrustedMeasurementTrend.vue'
import FollowUpList from '@/components/alert-workflow/FollowUpList.vue'
import SourceMeasurementPanel from '@/components/alert-workflow/SourceMeasurementPanel.vue'
import MeasurementWorkflowStatus from '@/components/alert-workflow/MeasurementWorkflowStatus.vue'
import {useAlertWorkflow} from '@/composables/useAlertWorkflow'
import {measurementInstant,localOffset} from '@/utils/alertWorkflowForms'
import {t as awText} from '@/utils/alertWorkflowCopy'
import * as alertApi from '@/api/alertWorkflow'
import { useFocusedCareSource } from '@/composables/useFocusedCareSource'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCurrentPatient } from '@/composables/useCurrentPatient'
import * as api from '@/api/careJourney'
import {getCarePlanCapabilities,listNurseAssignments} from '@/api/carePlan'
import {captureAuthSession,isAuthSessionCurrent,AUTH_STORAGE_KEYS} from '@/utils/authSession'
import request from '@/utils/request'
import ConsultationWorkspace from '@/components/ConsultationWorkspace.vue'

const route=useRoute(),router=useRouter(),{currentPatientId,currentPatientName}=useCurrentPatient()
const emergencyPatientId=computed(()=>{if(!route.query.emergencyEventId)return currentPatientId.value;const raw=String(route.query.patientId||''),n=Number(raw);return /^[1-9][0-9]*$/.test(raw)&&Number.isSafeInteger(n)?n:null;});
const sourceFocus=useFocusedCareSource(route,currentPatientId,(source,options)=>api.listMeasurements({patientId:source.patientId,measurementId:source.sourceId},options),'MEASUREMENT')
const focusedMode=sourceFocus.attempted
const measurementFlow=useAlertWorkflow(currentPatientId),measurementOffset=ref(localOffset()),trustedTrend=ref(null)
useAlertWorkflowNavigation(measurementFlow)
const measurementLocked=computed(()=>['submitting','uncertain'].includes(measurementFlow.state.sourcePhase))
watch(currentPatientId,()=>{measurementOffset.value=localOffset();measurementFlow.loadCapabilities()},{immediate:true})
async function inspectMeasurement(row){if(measurementLocked.value)return;originalSourceOwner={patientId:currentPatientId.value,generation:raw.state.generation};await measurementFlow.readSource('HEALTH_MEASUREMENT',row.id)}
const roles=JSON.parse(localStorage.getItem('userRoleCodes')||'[]'),isDoctor=computed(()=>roles.includes('doctor')||roles.includes('admin'))
const allowedTabs=['measurements','appointments','consultation','recovery','emergency','specialty','mental','privacy',...(isDoctor.value?['operations']:[])]
// Only a committed route selects content; rejected navigation cannot disable an active draft.
const tab=computed({get:()=>allowedTabs.includes(route.query.tab)?route.query.tab:'measurements',set:name=>{void syncTab(name)}}),busy=ref(false)
const rawOpen=ref(false)
let originalSourceOwner=null
// 只按已知开关变化重验原值权限，不把可信读取失败变成原值拒绝。
const originalMode=ref(['enabled','disabled'].includes(measurementFlow.state.capabilityPhase)?measurementFlow.state.capabilityPhase:null)
const raw=useOriginalMeasurementPage(currentPatientId,{sourceType:'HEALTH_MEASUREMENT'},computed(()=>rawOpen.value && tab.value==='measurements' && !focusedMode.value),{contextKey:originalMode})
const patientRequired=computed(()=>!['operations','consultation'].includes(tab.value))
const visitPanel=ref(null),visitCreateAvailable=ref(false),visitLegacyMode=ref(false),visitFocus=computed(()=>visitLocator(route.query));
function visitCapabilities(value){visitCreateAvailable.value=value?.allowedCommands?.includes('CREATE_VISIT')===true;visitLegacyMode.value=value?.legacyVisitsAllowed===true;if(visitLegacyMode.value && !visitFocus.value.attempted)loadVisits();}
const appointmentPanel=ref(null),appointmentDetailPanel=ref(null),appointmentDetail=ref(null),appointmentDetailVisible=ref(false)
const attendanceSection=ref(null),physicianSection=ref(null),accompanimentSection=ref(null);
// 仅导航到当前患者已有领域组件；目的组件独立核权，不预取其他模块详情。
async function openOperationalRecord(kind){const patient=currentPatientId.value,auth=captureAuthSession();if(!patient||!await beforeTabLeave('appointments'))return;await nextTick();if(patient!==currentPatientId.value||!isAuthSessionCurrent(auth)||tab.value!=='appointments')return;({attendance:attendanceSection,physician:physicianSection,accompaniment:accompanimentSection}[kind])?.value?.focus();}
function openAppointmentFollowUp(row){if(visitPanel.value?.protectNavigation()===false||appointmentPanel.value?.protectNavigation()===false||appointmentDetailPanel.value?.protectNavigation()===false)return;appointmentDetail.value={patientId:Number(row.patient_id),sourceId:Number(row.id)};appointmentDetailVisible.value=true}
function closeAppointmentDetail(done){if(appointmentDetailPanel.value?.protectNavigation()===false)return;appointmentDetailVisible.value=false;appointmentDetail.value=null;if(typeof done==='function')done()}
async function refreshAppointmentRows(){await Promise.allSettled([loadAppointments(),loadAppointmentInbox()])}
const clinicians=ref([]),doctorSchedules=ref([]),measurements=ref([]),appointments=ref([]),visits=ref([]),prescriptions=ref([]),specialtyRows=reactive({growth:[],vaccination:[],maternity:[]}),plans=ref([]),rehabRows=ref([]),emergencyCard=ref(null),emergencyEvents=ref([]),selectedEmergency=ref(null),mentalRows=ref([]),mentalSchedules=ref([]),grants=ref([]),groups=ref([]),operations=ref(null)
watch(()=>[raw.state.phase,raw.state.items],()=>{measurements.value=raw.state.items},{flush:'sync'})
watch(()=>raw.state.generation,()=>{
  originalSourceOwner=null;
  // 在途来源读取必须失效，但不能丢弃提交中或结果未明的写命令。
  if(!measurementLocked.value && (measurementFlow.state.source || measurementFlow.state.receipt || measurementFlow.state.sourceLocator || measurementFlow.state.sourceReadPhase==='loading')){measurementFlow.reset();measurementFlow.loadCapabilities()}
},{flush:'sync'})
watch(()=>measurementFlow.state.capabilityPhase,phase=>{
  if(['enabled','disabled'].includes(phase))originalMode.value=phase;
  if(phase==='denied'&&originalSourceOwner?.patientId===currentPatientId.value&&originalSourceOwner.generation===raw.state.generation)raw.deny()
},{flush:'sync'})
watch([currentPatientId,tab,focusedMode],()=>{rawOpen.value=false;raw.clear()},{flush:'sync'})
const now=()=>{const date=new Date();return new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,19)},today=()=>now().slice(0,10)
const measurement=reactive({metricType:'BP',valuePrimary:null,valueSecondary:null,unit:'',measuredAt:now(),remark:''})
const appointment=reactive({id:null,doctorUserId:null,scheduleId:null,consultationMode:'IN_PERSON',startAt:'',endAt:'',recurrenceDays:0,reason:''})
const visit=reactive({diagnosisSummary:'',treatmentSummary:'',followUpAdvice:'',followUpAt:''})
const prescription=reactive({drugName:'',dosage:'',frequency:'',administrationRoute:'',durationDays:7,instructions:''})
const plan=reactive({planType:'INPATIENT',title:''}),planText=ref(''),rehab=reactive({recordType:'EXERCISE',completionPercent:0,videoUrl:'',abnormal:false}),rehabText=ref('')
const emergency=reactive({locationText:'',latitude:null,longitude:null})
const growth=reactive({recordDate:today(),heightCm:null,weightKg:null,headCircumferenceCm:null,heightPercentile:null,weightPercentile:null}),vaccine=reactive({vaccineName:'',doseNo:'',plannedDate:today(),remindAt:''}),maternity=reactive({recordType:'PRENATAL',recordDate:today(),title:''}),maternityText=ref('')
const mental=reactive({scaleCode:'PHQ9'}),mentalAnswers=ref(''),mentalVisible=ref(false),mentalSchedule=reactive({scaleCode:'PHQ9',intervalDays:14,nextDueAt:now()})
const grant=reactive({granteeUserId:null,granteeRole:'DOCTOR',accessLevel:'READ',expiresAt:''}),grantModules=ref([]),schedule=reactive({doctorUserId:null,workDate:today(),startTime:'09:00:00',endTime:'12:00:00',slotMinutes:30}),group=reactive({groupName:'',description:''})
const carePlanGrantEnabled=ref(false),measurementGrantEnabled=ref(false),nurseCandidates=ref([])
let nurseCandidateGeneration=0,nurseController=null,grantContextEpoch=0,carePlanGrantController=null
const availableGrantModules=computed(()=>grant.granteeRole==='NURSE'?[...(carePlanGrantEnabled.value?[{value:'CARE_PLAN',label:'Collaboration care plans'}]:[]),...(measurementGrantEnabled.value?[{value:'MEASUREMENTS',label:awText('title')}]:[])]:grantModuleOptions.filter(m=>m.value!=='CARE_PLAN'||carePlanGrantEnabled.value))
function changeGrantRole(){grant.granteeUserId=null;if(grant.granteeRole==='NURSE')grantModules.value=measurementGrantEnabled.value?['MEASUREMENTS']:['CARE_PLAN']}
function clearNurseCandidates(){nurseCandidateGeneration++;nurseController?.abort();nurseController=null;nurseCandidates.value=[];carePlanGrantEnabled.value=false;measurementGrantEnabled.value=false;if(grant.granteeRole==='NURSE')grant.granteeUserId=null}
async function loadNurseCandidates(){clearNurseCandidates();const id=currentPatientId.value;if(!id)return;const generation=nurseCandidateGeneration,auth=captureAuthSession(),actor=localStorage.getItem('userId');nurseController=new AbortController();const options={expectedAuth:{...auth,actorId:actor},signal:nurseController.signal};const owns=()=>generation===nurseCandidateGeneration&&id===currentPatientId.value&&isAuthSessionCurrent(auth)&&actor===localStorage.getItem('userId');const results=await Promise.allSettled([getCarePlanCapabilities(options),alertApi.capabilities(id,options)]);if(!owns())return;carePlanGrantEnabled.value=results[0].status==='fulfilled'&&results[0].value.data?.enabled===true;measurementGrantEnabled.value=results[1].status==='fulfilled'&&results[1].value.data?.canGrantMeasurementsToAssignedNurse===true;if(!carePlanGrantEnabled.value&&!measurementGrantEnabled.value)return;try{const result=await(measurementGrantEnabled.value?alertApi.nurseAssignments(id,options):listNurseAssignments(id,options));if(owns())nurseCandidates.value=(result.data||[]).filter(n=>n.patientId===id&&n.status==='ACTIVE'&&(!n.expiresAt||Date.parse(n.expiresAt)>Date.now()))}catch{if(owns())nurseCandidates.value=[]}}

function invalidateCarePlanGrants(){grantContextEpoch++;carePlanGrantController?.abort();carePlanGrantController=null}
function nurseAuthCleared(){invalidateCarePlanGrants();clearNurseCandidates();measurements.value=[];Object.assign(measurement,{metricType:'BP',valuePrimary:null,valueSecondary:null,unit:'',measuredAt:now(),remark:''});measurementOffset.value=''}
function nurseStorageChanged(event){if(event.key==null||AUTH_STORAGE_KEYS.includes(event.key))nurseAuthCleared()}
const grantModuleOptions=[{value:'CARE_PLAN',label:'Collaboration care plans'},{value:'MEASUREMENTS',label:'Measurements'},{value:'APPOINTMENTS',label:'Appointments'},{value:'VISITS',label:'Visit summaries'},{value:'MEDICATION',label:'Medication'},{value:'CONSULTATION',label:'Consultations'},{value:'TREATMENT',label:'Treatment plans'},{value:'REHAB',label:'Recovery'},{value:'EMERGENCY',label:'Emergency'},{value:'SPECIALTY',label:'Child and maternity'},{value:'MENTAL',label:'Mental health'},{value:'MEDICAL',label:'Medical records'},{value:'DIALYSIS',label:'Dialysis'}]
const metricOptions=[{label:'Blood pressure',value:'BP'},{label:'Blood glucose',value:'GLUCOSE'},{label:'Blood oxygen',value:'SPO2'},{label:'Weight',value:'WEIGHT'},{label:'Heart rate',value:'HEART_RATE'},{label:'Temperature',value:'TEMPERATURE'},{label:'Custom',value:'CUSTOM'}]
function syncTab(name){if(!allowedTabs.includes(name))return;const query={...route.query,tab:name};if(name!=='consultation')delete query.consultationId;if(JSON.stringify(query)===JSON.stringify(route.query))return;return router.replace({query})}
async function beforeTabLeave(name){if(visitPanel.value?.protectNavigation()===false||appointmentPanel.value?.protectNavigation()===false||appointmentDetailPanel.value?.protectNavigation()===false)return false;if(name===tab.value)return true;const failure=await syncTab(name);return !failure&&tab.value===name}
function pid(){if(!currentPatientId.value)throw new Error('Select a patient first.');return currentPatientId.value}
async function safely(action,message='Saved',isCurrent=null){if(busy.value)return;try{busy.value=true;const result=await action();if(!isCurrent||(isCurrent()&&result!==false))ElMessage.success(message)}catch(e){if(isCurrent&&!isCurrent())return;if(e.validation)ElMessage.warning(e.message);else if(e.message==='Select a patient first.')ElMessage.warning(e.message)}finally{busy.value=false}}
let editorEpoch=0,editorOperation=null
function invalidatePatientEditor(kind){
  if(kind&&editorOperation?.kind!==kind)return
  editorEpoch++
  if(editorOperation){editorOperation=null;busy.value=false}
}
// Watch cleanup invalidates pending editors on context changes and component disposal.
watch(tab,()=>{for(const key of ['appointments','visits','prescriptions'])loadVersions.set(key,(loadVersions.get(key)||0)+1)},{flush:'sync'})
watch([currentPatientId,tab],(_value,_previous,onCleanup)=>{
  onCleanup(invalidatePatientEditor)
},{immediate:true,flush:'sync'})
async function savePatientEditor(kind,readDraft,save,reset,refresh,message='Saved'){
  if(busy.value)return
  const epoch=editorEpoch,selected=currentPatientId.value,operation={kind}
  const data=readDraft()
  let draft=JSON.stringify(data)
  const current=()=>editorOperation===operation&&epoch===editorEpoch&&selected===currentPatientId.value
  editorOperation=operation;busy.value=true
  try{
    await save(data,pid())
    if(!current()||JSON.stringify(readDraft())!==draft)return
    reset()
    draft=JSON.stringify(readDraft())
    await refresh()
    if(current()&&JSON.stringify(readDraft())===draft)ElMessage.success(message)
  }catch(error){
    if(current()&&JSON.stringify(readDraft())===draft&&(error.validation||error.message==='Select a patient first.'))ElMessage.warning(error.message)
  }finally{if(editorOperation===operation){editorOperation=null;busy.value=false}}
}
async function loadMeasurements(){if(focusedMode.value){await sourceFocus.reload();return;}return raw.refresh()}
// 成功更正或录入只重读当前测量页，不重放写入；证据聚焦仍沿原精确读取。
async function measurementChanged(){trustedTrend.value?.refresh();await loadMeasurements()}
async function recordMeasurement(){
  if(busy.value||measurementLocked.value)return
  try{const presentNumber=value=>value!==null&&value!==undefined&&String(value).trim()!==''&&Number.isFinite(Number(value));validate(presentNumber(measurement.valuePrimary)&&String(measurement.unit??'').trim(),awText('inputInvalid'));const fits=value=>measurementFits(value,12,3);validate(fits(measurement.valuePrimary),awText('inputInvalid'));if(measurement.valueSecondary!=null&&String(measurement.valueSecondary).trim()!=='')validate(fits(measurement.valueSecondary),awText('inputInvalid'));if(measurement.metricType==='SPO2')validate(Number(measurement.valuePrimary)>=0&&Number(measurement.valuePrimary)<=100,awText('inputInvalid'));if(measurement.metricType==='GLUCOSE')validate(Number(measurement.valuePrimary)>0,awText('inputInvalid'));if(measurement.metricType==='BP')validate(fits(measurement.valueSecondary)&&Number(measurement.valuePrimary)>=40&&Number(measurement.valuePrimary)<=300&&Number(measurement.valueSecondary)>=20&&Number(measurement.valueSecondary)<=200,awText('inputInvalid'));measurementInstant(measurement.measuredAt,null,{localOnly:true});const enabled=measurementFlow.state.capabilityPhase==='enabled';const payload={...measurement,valuePrimary:Number(measurement.valuePrimary),valueSecondary:presentNumber(measurement.valueSecondary)?Number(measurement.valueSecondary):null,...(enabled?{occurredAt:measurementInstant(measurement.measuredAt,measurementOffset.value)}:{})};const result=await measurementFlow.saveSource('HEALTH_MEASUREMENT',payload);if(result.status==='succeeded'){ElMessage.success(awText('saved'));await measurementChanged()}else if(result.status==='capability-required')ElMessage.warning(awText('capabilityUnknown'))}catch{ElMessage.error(awText('inputInvalid'))}
}
async function annotate(row){const {value}=await ElMessageBox.prompt('Add a clinician note to this exact trend point','Clinical annotation');await safely(async()=>{await api.annotateMeasurement(row.id,value);await loadMeasurements()})}
const appointmentReads=reactive({appointments:'idle',visits:'idle',prescriptions:'idle'})
function appointmentAuthCleared(){visitLegacyMode.value=false;visitCreateAvailable.value=false;for(const key of ['appointments','visits','prescriptions']){loadVersions.set(key,(loadVersions.get(key)||0)+1);appointmentReads[key]='idle'}appointments.value=[];visits.value=[];prescriptions.value=[];appointmentInboxVersion++;appointmentInbox.value=[];appointmentDetailVisible.value=false;appointmentDetail.value=null;}
function appointmentStorageChanged(event){if(event.key==null||AUTH_STORAGE_KEYS.includes(event.key))appointmentAuthCleared()}
// 三个授权模块独立读取；患者、账号及标签页代次必须同时匹配，A→B→A也不接受旧响应。
async function loadAppointmentPart(key,fetcher,target){
  const selected=currentPatientId.value,view=tab.value,auth=captureAuthSession(),actor=localStorage.getItem('userId'),version=(loadVersions.get(key)||0)+1;
  loadVersions.set(key,version);target.value=[];appointmentReads[key]='loading';
  if(!selected){appointmentReads[key]='idle';return;}
  const owns=()=>selected===currentPatientId.value&&view===tab.value&&version===loadVersions.get(key)&&isAuthSessionCurrent(auth)&&actor===localStorage.getItem('userId');
  try{const response=await fetcher(selected,{expectedAuth:{...auth,actorId:actor}});if(owns()){target.value=response.data||[];appointmentReads[key]='ready'}}catch{if(owns())appointmentReads[key]='unavailable'}
}
async function loadAppointments(){return loadAppointmentPart('appointments',api.listAppointments,appointments)}
async function loadVisits(){if(!visitLegacyMode.value || visitFocus.value.attempted)return;return loadAppointmentPart('visits',api.listVisits,visits)}
async function loadPrescriptions(){return loadAppointmentPart('prescriptions',api.listPrescriptions,prescriptions)}
async function loadAppointmentTab(){await Promise.allSettled([loadAppointments(),loadVisits(),loadPrescriptions()])}

function changeAppointmentDoctor(){appointment.scheduleId=null;appointment.startAt='';appointment.endAt='';doctorSchedules.value=[];loadDoctorSchedules()}
async function loadDoctorSchedules(){const doctor=appointment.doctorUserId,selected=currentPatientId.value;if(!doctor){doctorSchedules.value=[];return}try{const response=await api.listDoctorSchedules({doctorUserId:doctor});if(doctor===appointment.doctorUserId&&selected===currentPatientId.value)doctorSchedules.value=response.data||[]}catch{}}
function selectSchedule(id){const s=doctorSchedules.value.find(x=>x.id===id);if(!s)return;appointment.startAt=`${s.work_date}T${String(s.start_time).slice(0,8)}`;const endMinutes=Number(String(s.start_time).slice(0,2))*60+Number(String(s.start_time).slice(3,5))+Number(s.slot_minutes||30);appointment.endAt=`${s.work_date}T${String(Math.floor(endMinutes/60)%24).padStart(2,'0')}:${String(endMinutes%60).padStart(2,'0')}:00`}
async function bookAppointment(){
  await savePatientEditor('appointment',()=>({...appointment}),async(data,patientId)=>{
    validate(data.doctorUserId && data.startAt && data.endAt && new Date(data.endAt)>new Date(data.startAt),'Choose a doctor and an appointment ending after its start time.')
    await api.saveAppointment({...data,patientId})
  },()=>Object.assign(appointment,{id:null,startAt:'',endAt:'',reason:''}),loadAppointments)
}
function editAppointment(row){if(row.visit_follow_up_managed){openAppointmentFollowUp(row);return;}if(row.status!=='BOOKED')return;invalidatePatientEditor('appointment');Object.assign(appointment,{id:row.id,doctorUserId:row.doctor_user_id,scheduleId:row.schedule_id,consultationMode:row.consultation_mode,startAt:String(row.start_at).replace(' ','T'),endAt:String(row.end_at).replace(' ','T'),recurrenceDays:row.recurrence_days||0,reason:row.reason||''});loadDoctorSchedules();window.scrollTo({top:0,behavior:'smooth'})}
async function cancel(row){if(row.visit_follow_up_managed){openAppointmentFollowUp(row);return;}await ElMessageBox.confirm('Cancel this appointment and notify the shared care team?','Cancel appointment');await safely(async()=>{await api.cancelAppointment(row.id,'Cancelled from shared schedule');await loadAppointments()},'Appointment cancelled')}
async function createVisit(){if(!visitLegacyMode.value)return;await safely(async()=>{validate(visit.diagnosisSummary.trim() || visit.treatmentSummary.trim(),'Enter a diagnosis or treatment summary.');await api.saveVisit({...visit,patientId:pid(),visitedAt:now()});await loadVisits()})}
async function publish(row){if(!visitLegacyMode.value)return;await safely(async()=>{await api.publishVisit(row.id);await loadVisits()},'Summary published')}
async function savePrescription(){
  await savePatientEditor('prescription',()=>({...prescription}),async(data,patientId)=>{
    validate(data.drugName.trim() && data.dosage.trim() && data.frequency.trim(),'Enter the medicine, dose and frequency.')
    await api.savePrescription({patientId,instructions:data.instructions,items:[{drugName:data.drugName,dosage:data.dosage,frequency:data.frequency,administrationRoute:data.administrationRoute,durationDays:data.durationDays}]})
  },()=>Object.assign(prescription,{drugName:'',dosage:'',frequency:'',administrationRoute:'',durationDays:7,instructions:''}),loadPrescriptions,'Prescription version published; review its notification status')
}
async function loadRecovery(){await loadPatientData('recovery',id=>Promise.all([api.listTreatmentPlans(id),api.listRehabCheckins(id)]),responses=>{plans.value=responses[0].data||[];rehabRows.value=responses[1].data||[]})}
async function createPlan(){
  await savePatientEditor('plan',()=>({...plan,plan:{instructions:planText.value}}),async(data,patientId)=>{
    validate(data.title.trim() && data.plan.instructions.trim(),'Enter a plan title and instructions.')
    await api.saveTreatmentPlan({...data,patientId})
  },()=>{plan.title='';planText.value=''},loadRecovery)
}
async function checkinRehab(){
  await savePatientEditor('rehab',()=>({...rehab,data:{observation:rehabText.value}}),async(data,patientId)=>{
    validate(data.data.observation.trim(),'Enter the rehabilitation or symptom observation.')
    await api.saveRehabCheckin({...data,patientId})
  },()=>{rehabText.value=''},loadRecovery)
}
function specialtyDetail(value){try{return (typeof value==='string'?JSON.parse(value):value)?.details||'—'}catch{return value}}
function prettyJson(value){try{return JSON.stringify(typeof value==='string'?JSON.parse(value):value,null,2)}catch{return value}}
const emergencyCache=createEmergencyOfflineCard({storage:localStorage,owner:()=>({...captureAuthSession(),actorId:localStorage.getItem('userId'),patientId:Number(emergencyPatientId.value)})});
// 当前身份改变或在线拒绝访问时清除卡片；离线卡不能继承其他身份的权限。
function clearEmergencyCard() {
  emergencyCache.invalidate();
  emergencyCard.value = null;
}
function emergencyStorageChanged(event) {
  if (event.key == null || AUTH_STORAGE_KEYS.includes(event.key)) clearEmergencyCard();
}
async function loadEmergencyCard(){emergencyCard.value=null;try{emergencyCard.value=await emergencyCache.load(()=>api.getEmergencyCard(emergencyPatientId.value));}catch{emergencyCard.value=null;}}
async function loadEmergencyEvents(){await loadPatientData('emergencyEvents',id=>api.listEmergencies(id),response=>{emergencyEvents.value=response.data||[]})}
async function loadEmergencyTab(){if(!route.query.emergencyEventId)await loadEmergencyCard()}
async function openEmergency(row) {
  selectedEmergency.value = null
  await loadPatientData('emergencyDetail', () => api.getEmergency(row.id), (response, patientId) => {
    if (Number(response.data?.patient_id) === Number(patientId)) selectedEmergency.value = response.data
  })
}

async function saveSpecial(type,data){await safely(async()=>{if(type==='growth')validate(data.recordDate && [data.heightCm,data.weightKg,data.headCircumferenceCm].some(value=>Number(value)>0),'Enter a date and at least one positive growth measurement.');if(type==='vaccination')validate(data.vaccineName?.trim() && data.plannedDate,'Enter the vaccine name and planned date.');if(type==='maternity')validate(data.recordDate && data.title?.trim(),'Enter a record date and title.');await api.saveSpecialty(type,{...data,patientId:pid()});await loadSpecialty()})}
async function saveMaternity(){await saveSpecial('maternity',{...maternity,data:{details:maternityText.value}})}
async function loadMental(){await loadPatientData('mental',id=>Promise.allSettled([api.listMentalAssessments(id),api.listMentalSchedules(id)]),responses=>{if(responses[0].status==='fulfilled')mentalRows.value=responses[0].value.data||[];if(responses[1].status==='fulfilled')mentalSchedules.value=responses[1].value.data||[]})}
async function submitMental(){await safely(async()=>{const values=mentalAnswers.value.split(/[,，]/).map(value=>value.trim()),count={PHQ9:9,GAD7:7,WHO5:5}[mental.scaleCode],maximum=mental.scaleCode==='WHO5'?5:3;validate(values.length===count && values.every(value=>/^\d+$/.test(value) && Number(value)<=maximum),`Enter ${count} integer scores from 0 to ${maximum}, separated by commas.`);const answers=values.map(Number);await api.saveMentalAssessment({...mental,patientId:pid(),answers,familyVisibility:mentalVisible.value?'VISIBLE':'PRIVATE'});mentalAnswers.value='';await loadMental()})}
async function scheduleMental(){await safely(async()=>{validate(mentalSchedule.nextDueAt && Number(mentalSchedule.intervalDays)>0,'Enter the next delivery time and a positive interval.');await api.saveMentalSchedule({...mentalSchedule,scaleCode:mental.scaleCode,patientId:pid(),familyVisibility:mentalVisible.value?'VISIBLE':'PRIVATE'});await loadMental()},'Assessment scheduled')}
async function disableMentalSchedule(row){if(!row||row.enabled!==1)return;await safely(async()=>{await api.disableMentalSchedule(row.id);await loadMental()},'Assessment schedule disabled')}
async function loadGrants(){await Promise.all([loadPatientData('grants',id=>api.listAccessGrants(id),response=>{grants.value=response.data||[]}),loadNurseCandidates()])}
async function createGrant(){
  const patient=currentPatientId.value,epoch=grantContextEpoch,auth=captureAuthSession(),actor=localStorage.getItem('userId')
  let guarded=false
  const owns=()=>!guarded||(epoch===grantContextEpoch&&patient===currentPatientId.value&&isAuthSessionCurrent(auth)&&actor===localStorage.getItem('userId'))
  await safely(async()=>{
    pid()
    const body={...grant},modules=[...grantModules.value]
    guarded=modules.includes('CARE_PLAN')||body.granteeRole==='NURSE'
    validate(Number(body.granteeUserId)>0,'Select a recipient.')
    if(body.granteeRole==='DOCTOR')validate(clinicians.value.some(doctor=>Number(doctor.id)===Number(body.granteeUserId)),'Select a doctor from the list.')
    if(body.granteeRole==='NURSE'){
      await loadNurseCandidates()
      if(!owns()||grant.granteeRole!==body.granteeRole||grant.accessLevel!==body.accessLevel||JSON.stringify(grantModules.value)!==JSON.stringify(modules)||(grant.granteeUserId!=null&&grant.granteeUserId!==body.granteeUserId))return false
      grant.granteeUserId=body.granteeUserId
      validate((carePlanGrantEnabled.value||measurementGrantEnabled.value)&&nurseCandidates.value.some(n=>n.nurseUserId===Number(body.granteeUserId)),'Select a currently assigned nurse for this patient.')
      validate(modules.length>0&&modules.every(module=>module==='CARE_PLAN'?carePlanGrantEnabled.value:module==='MEASUREMENTS'&&measurementGrantEnabled.value),'Select explicit currently available nursing modules.')
    }
    if(!owns())return false
    const data={...body,visibleModules:modules.join(','),patientId:patient}
    if(guarded){
      validate((!modules.includes('CARE_PLAN')||carePlanGrantEnabled.value)&&(body.granteeRole!=='NURSE'||!modules.includes('MEASUREMENTS')||measurementGrantEnabled.value),'The selected collaboration modules are unavailable in this session.')
      carePlanGrantController?.abort();const controller=new AbortController();carePlanGrantController=controller
      await request({url:'/care-journey/access-grants',method:'post',data,expectedAuth:{...auth,actorId:actor},signal:controller.signal})
    }else await api.saveAccessGrant(data)
    if(!owns())return false
    await loadGrants()
    return owns()
  },'Access authorized',owns)
}
async function revoke(row){
  const modules=String(row.visible_modules??row.visibleModules??'').split(',').map(value=>value.trim())
  if(!modules.includes('CARE_PLAN')&&row.grantee_role!=='NURSE'){await safely(async()=>{await api.revokeAccessGrant(row.id);await loadGrants()},'Access revoked');return}
  const patient=currentPatientId.value,epoch=grantContextEpoch,auth=captureAuthSession(),actor=localStorage.getItem('userId'),id=row.id
  const owns=()=>epoch===grantContextEpoch&&patient===currentPatientId.value&&isAuthSessionCurrent(auth)&&actor===localStorage.getItem('userId')
  await safely(async()=>{
    if(!owns()||!Number.isSafeInteger(id)||id<=0||(row.patient_id??row.patientId)!==patient||!grants.value.some(item=>item.id===id&&(item.patient_id??item.patientId)===patient))return false
    carePlanGrantController?.abort();const controller=new AbortController();carePlanGrantController=controller
    await request({url:`/care-journey/access-grants/${id}`,method:'delete',expectedAuth:{...auth,actorId:actor},signal:controller.signal})
    if(!owns())return false
    await loadGrants()
    return owns()
  },'Access revoked',owns)
}
async function createSchedule(){await safely(async()=>{validate(!roles.includes('admin') || schedule.doctorUserId,'Choose the doctor whose schedule you are creating.');validate(schedule.workDate && schedule.startTime && schedule.endTime && schedule.endTime>schedule.startTime,'Enter a date and a schedule ending after its start time.');await api.saveDoctorSchedule({...schedule})},'Availability added')}
async function createGroup(){await safely(async()=>{validate(group.groupName.trim(),'Enter the patient group name.');await api.savePatientGroup({...group});group.groupName='';group.description='';groups.value=(await api.listPatientGroups()).data||[]})}
async function addToGroup(row){await safely(async()=>{await api.addPatientToGroup(row.id,pid());groups.value=(await api.listPatientGroups()).data||[]},'Patient added')}
const appointmentInbox=ref([]),appointmentInboxLoading=ref(false)
let appointmentInboxVersion=0
const appointmentModeLabel=value=>({IN_PERSON:'In person',TEXT:'Text',VOICE:'Voice',VIDEO:'Video'})[value]||value
const appointmentStatusLabel=value=>({NO_SHOW:appointmentText('NO_SHOW'),BOOKED:'Booked',CANCELLED:'Cancelled',COMPLETED:'Completed'})[value]||value
async function loadAppointmentInbox(){
  if(!isDoctor.value)return
  const version=++appointmentInboxVersion,userId=localStorage.getItem('userId'),auth=captureAuthSession()
  appointmentInboxLoading.value=true
  try{const response=await api.listAppointmentInbox();if(version===appointmentInboxVersion&&userId===localStorage.getItem('userId')&&isAuthSessionCurrent(auth))appointmentInbox.value=response.data||[]}
  catch{if(version===appointmentInboxVersion)appointmentInbox.value=[]}
  finally{if(version===appointmentInboxVersion)appointmentInboxLoading.value=false}
}
async function completeInboxAppointment(row){
  if(row.visit_follow_up_managed){openAppointmentFollowUp(row);return;}
  if(busy.value||row.status!=='BOOKED')return
  busy.value=true
  try{
    await ElMessageBox.confirm('Mark this visit as completed? This does not create a visit summary or prescription.','Complete appointment')
    await api.completeAppointment(row.id)
    ElMessage.success('Appointment completed')
    await loadAppointmentInbox()
  }catch{}finally{busy.value=false}
}
async function cancelInboxAppointment(row){
  if(row.visit_follow_up_managed){openAppointmentFollowUp(row);return;}
  if(busy.value||row.status!=='BOOKED')return
  busy.value=true
  try{
    await ElMessageBox.confirm('Cancel this appointment and notify the patient and care team?','Cancel appointment')
    await api.cancelAppointment(row.id,'Cancelled by the doctor from appointment inbox')
    ElMessage.success('Appointment cancelled')
    await loadAppointmentInbox()
  }catch{}finally{busy.value=false}
}
async function loadOperations(){await Promise.allSettled([loadAppointmentInbox(),(async()=>{operations.value=(await api.getOperationsReport({})).data})(),(async()=>{groups.value=(await api.listPatientGroups()).data||[]})()])}
async function loadTab(){if(focusedMode.value || visitFocus.value.attempted)return;const current=tab.value;const loaders={measurements:loadMeasurements,appointments:loadAppointmentTab,recovery:loadRecovery,specialty:loadSpecialty,emergency:loadEmergencyTab,mental:loadMental,privacy:loadGrants,operations:loadOperations};if(['appointments','operations','privacy'].includes(current)&&!clinicians.value.length){try{clinicians.value=(await api.listClinicians()).data||[]}catch{clinicians.value=[]}}try{if(loaders[current])await loaders[current]()}catch{}}
const mentalSeverityLabel=row=>({MINIMAL:'Below review threshold',MILD:'Mild',MODERATE:'Moderate',MODERATELY_SEVERE:'Moderately severe',SEVERE:'Severe',REVIEW_REQUIRED:'Further assessment suggested'})[row.severity]||row.severity||'—'
function validate(valid,text){if(!valid){const error=new Error(text);error.validation=true;throw error}}
async function loadSpecialty(){await loadPatientData('specialty',id=>Promise.all(['growth','vaccination','maternity'].map(type=>api.listSpecialty(type,id))),responses=>{['growth','vaccination','maternity'].forEach((type,index)=>{specialtyRows[type]=responses[index].data||[]})})}
watch(()=>measurement.metricType,()=>{measurement.unit='';measurement.valuePrimary=null;measurement.valueSecondary=null})
const loadVersions=new Map()
const patientForms=[measurement,appointment,visit,prescription,plan,rehab,emergency,growth,vaccine,maternity,mental,mentalSchedule,grant]
const formDefaults=patientForms.map(model=>JSON.parse(JSON.stringify(model)))
async function loadPatientData(key,fetcher,apply){
  const selected=currentPatientId.value
  if(!selected)return
  const version=(loadVersions.get(key)||0)+1
  loadVersions.set(key,version)
  const response=await fetcher(selected)
  if(currentPatientId.value===selected&&loadVersions.get(key)===version)apply(response,selected)
}
watch(()=>[route.query.emergencyEventId,route.query.patientId],()=>{emergencyCache.invalidate();emergencyCard.value=null;});
watch(currentPatientId,()=>{
  visitLegacyMode.value=false;visitCreateAvailable.value=false;
  invalidateCarePlanGrants();clearNurseCandidates()
  for(const [key,version]of loadVersions)loadVersions.set(key,version+1)
  emergencyCache.invalidate();measurements.value=[];appointments.value=[];visits.value=[];prescriptions.value=[];Object.keys(specialtyRows).forEach(key=>{specialtyRows[key]=[]});plans.value=[];rehabRows.value=[];emergencyCard.value=null;emergencyEvents.value=[];selectedEmergency.value=null;mentalRows.value=[];mentalSchedules.value=[];grants.value=[];doctorSchedules.value=[]
  patientForms.forEach((model,index)=>Object.assign(model,formDefaults[index]))
  planText.value='';rehabText.value='';maternityText.value='';mentalAnswers.value='';mentalVisible.value=false
  measurement.measuredAt=now();mentalSchedule.nextDueAt=now();growth.recordDate=today();vaccine.plannedDate=today();maternity.recordDate=today()
  loadTab()
},{flush:'sync'})
watch([()=>route.query.tab,focusedMode],([value,focused],[,wasFocused])=>{if(focused||(wasFocused&&route.fullPath?.split('?')[0]!=='/care-journey'))return;const next=allowedTabs.includes(value)?value:'measurements';if(value&&value!==next){syncTab(next);return}loadTab()})
onMounted(()=>{window.addEventListener('auth-session-cleared',appointmentAuthCleared);window.addEventListener('auth-session-changed',appointmentAuthCleared);window.addEventListener('storage',appointmentStorageChanged);window.addEventListener('auth-session-cleared',nurseAuthCleared);window.addEventListener('auth-session-changed',nurseAuthCleared);window.addEventListener('auth-session-cleared',clearEmergencyCard);window.addEventListener('auth-session-changed',clearEmergencyCard);window.addEventListener('emergency-access-denied',clearEmergencyCard);window.addEventListener('storage',emergencyStorageChanged);window.addEventListener('storage',nurseStorageChanged);if(route.query.tab&&!allowedTabs.includes(route.query.tab))syncTab(tab.value);else loadTab()})
onUnmounted(()=>{appointmentAuthCleared();window.removeEventListener('auth-session-cleared',appointmentAuthCleared);window.removeEventListener('auth-session-changed',appointmentAuthCleared);window.removeEventListener('storage',appointmentStorageChanged);nurseAuthCleared();window.removeEventListener('auth-session-cleared',nurseAuthCleared);window.removeEventListener('auth-session-changed',nurseAuthCleared);clearEmergencyCard();window.removeEventListener('auth-session-cleared',clearEmergencyCard);window.removeEventListener('auth-session-changed',clearEmergencyCard);window.removeEventListener('emergency-access-denied',clearEmergencyCard);window.removeEventListener('storage',emergencyStorageChanged);window.removeEventListener('storage',nurseStorageChanged)})
</script>

<style scoped>
.operation-record-links{display:flex;flex-wrap:wrap;gap:.6rem;margin-block:.8rem}.operation-record-links button,.operation-record-links a{font:inherit;min-height:44px;padding:.6rem .8rem;box-sizing:border-box;white-space:normal;overflow-wrap:anywhere;border:1px solid #728497;border-radius:7px;background:#f8fafc;color:#174f76;text-decoration:none}.operation-record-links :focus-visible{outline:3px solid #1767ad;outline-offset:3px}
.care-plan-grant-form :deep(.el-select__wrapper),.care-plan-grant-form :deep(.el-button){min-height:44px}

.inline-form .grant-choice{flex:1 1 260px;min-width:min(260px,100%)}
.appointment-inbox{margin-bottom:18px;min-width:0}.inbox-description{margin:0 0 16px;color:var(--ink-500);line-height:1.7}
.prescription-version+.prescription-version{margin-top:22px;padding-top:18px;border-top:1px solid var(--line)}.prescription-version p{color:var(--ink-500);white-space:pre-wrap}.form-grid{min-width:0}.form-grid :deep(.el-form-item__content){min-width:0}.form-grid :deep(.el-date-editor){max-width:100%}.card-head{flex-wrap:wrap}

.journey-page{max-width:1500px;margin:auto;padding:26px}.journey-hero{display:flex;justify-content:space-between;gap:24px;align-items:center;padding:28px;border:1px solid var(--line);border-radius:20px;background:linear-gradient(120deg,var(--care-50),var(--paper));margin-bottom:22px}.journey-hero span{font-size:11px;letter-spacing:2px;color:var(--care-700);font-weight:700}.journey-hero h1{margin:8px 0;font-size:32px}.journey-hero p{margin:0;max-width:820px;color:var(--ink-500)}.journey-tabs{background:var(--paper);border:1px solid var(--line);border-radius:18px;padding:18px}.panel-grid{display:grid;gap:18px}.panel-grid.two{grid-template-columns:repeat(2,minmax(0,1fr))}.panel-grid.three{grid-template-columns:repeat(3,minmax(0,1fr))}.section-card{margin-top:18px}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 14px}.card-head,.message-compose,.inline{display:flex;align-items:center;justify-content:space-between;gap:12px}.realtime-state{display:inline-flex;align-items:center;gap:6px;margin-left:12px;color:var(--care-700);font-size:11px;font-weight:600}.realtime-state i{width:8px;height:8px;border-radius:50%;background:var(--success);box-shadow:0 0 0 4px #31af7d20}.message-list{display:grid;gap:10px;max-height:360px;overflow:auto;scroll-behavior:smooth}.message-list article{padding:12px;border:1px solid var(--line);border-radius:12px}.message-list article span{float:right;color:var(--ink-500);font-size:12px}.message-list article p{margin:6px 0 0}.message-compose{margin-top:14px}.message-compose .el-input{flex:1}.media-room{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;margin-bottom:18px;padding:12px;border-radius:14px;background:#173a36}.media-room video{width:100%;min-height:160px;max-height:320px;object-fit:cover;border-radius:10px;background:#173a36}.media-room p{color:#cbd5e1;padding:20px}.emergency-card{border-color:var(--danger-soft)}.emergency-card .el-input{margin:14px 0}.metric-cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.metric-cards article{padding:20px;border-radius:14px;background:var(--surface-subtle)}.metric-cards span,.metric-cards strong{display:block}.metric-cards strong{font-size:26px;margin-top:8px}.inline-form{display:flex;align-items:end;gap:12px;flex-wrap:wrap}.inline-form .el-form-item{min-width:180px;margin-bottom:10px}pre{white-space:pre-wrap}.el-select,.el-date-editor{width:100%}@media(max-width:900px){.panel-grid.two,.panel-grid.three,.metric-cards{grid-template-columns:1fr 1fr}}@media(max-width:650px){.journey-page{padding:14px}.journey-hero{align-items:flex-start;flex-direction:column;padding:20px}.journey-tabs{padding:10px}.panel-grid.two,.panel-grid.three,.metric-cards,.form-grid{grid-template-columns:1fr}.message-compose{align-items:stretch;flex-direction:column}.inline{align-items:stretch;flex-direction:column}.media-room{grid-template-columns:1fr}}
</style>

<style scoped>
.operation-record-links{display:flex;flex-wrap:wrap;gap:.6rem;margin-block:.8rem}.operation-record-links button,.operation-record-links a{font:inherit;min-height:44px;padding:.6rem .8rem;box-sizing:border-box;white-space:normal;overflow-wrap:anywhere;border:1px solid #728497;border-radius:7px;background:#f8fafc;color:#174f76;text-decoration:none}.operation-record-links :focus-visible{outline:3px solid #1767ad;outline-offset:3px}.focused-source{min-width:0;overflow-wrap:anywhere}.focused-source button{min-height:44px;padding:10px 16px;font:inherit}.focused-source button:focus-visible{outline:3px solid var(--care-600)}</style>

<style scoped>
.operation-record-links{display:flex;flex-wrap:wrap;gap:.6rem;margin-block:.8rem}.operation-record-links button,.operation-record-links a{font:inherit;min-height:44px;padding:.6rem .8rem;box-sizing:border-box;white-space:normal;overflow-wrap:anywhere;border:1px solid #728497;border-radius:7px;background:#f8fafc;color:#174f76;text-decoration:none}.operation-record-links :focus-visible{outline:3px solid #1767ad;outline-offset:3px}.measurement-entry-form :deep(.el-input__wrapper),.measurement-entry-form :deep(.el-select__wrapper),.measurement-entry-form :deep(.el-button){min-height:44px}.measurement-entry-form :deep(.el-input-number){max-width:100%;min-height:44px}.measurement-entry-form :deep(.el-form-item__label){height:auto;white-space:normal;overflow-wrap:anywhere}</style>

<style scoped>
.operation-record-links{display:flex;flex-wrap:wrap;gap:.6rem;margin-block:.8rem}.operation-record-links button,.operation-record-links a{font:inherit;min-height:44px;padding:.6rem .8rem;box-sizing:border-box;white-space:normal;overflow-wrap:anywhere;border:1px solid #728497;border-radius:7px;background:#f8fafc;color:#174f76;text-decoration:none}.operation-record-links :focus-visible{outline:3px solid #1767ad;outline-offset:3px}
.care-plan-grant-form {
  --control-row-size: 44px;
  --el-component-size: var(--control-row-size);
}
body.care-senior .care-plan-grant-form {
  --control-row-size: 48px;
}
.inline-form.care-plan-grant-form .el-form-item {
  margin-bottom: 0;
  min-width: min(180px, 100%);
  max-width: 100%;
}
.inline-form.care-plan-grant-form .grant-nurse-choice {
  flex-basis: 100%;
}
.care-plan-grant-form :deep(.el-input__wrapper),
.care-plan-grant-form :deep(.el-select__wrapper),
.care-plan-grant-form :deep(.el-input-number),
.care-plan-grant-form :deep(.el-button) {
  box-sizing: border-box;
  min-height: var(--control-row-size);
}
.care-plan-grant-form :deep(.el-date-editor.el-input) {
  height: var(--control-row-size);
  max-width: 100%;
}
.care-plan-grant-form :deep(.el-button) {
  height: auto;
}
</style>
