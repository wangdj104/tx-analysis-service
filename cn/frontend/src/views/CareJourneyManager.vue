<template>
  <main class="journey-page">
    <header class="journey-hero">
      <div><span>全程照护</span><h1>健康照护全流程</h1><p>在一个可追溯的工作台中统一管理健康指标、预约复诊、远程问诊、康复、急救信息、专项健康、隐私与运营。</p></div>
      <el-tag v-if="tab === 'consultation' && isDoctor" effect="plain">问诊收件箱</el-tag>
      <el-tag v-else effect="plain">患者：{{ currentPatientName || currentPatientId || '未选择' }}</el-tag>
    </header>

    <el-alert v-if="patientRequired && !currentPatientId" title="使用本模块前，请先在工作台顶部选择患者。" type="warning" show-icon :closable="false" />
    <el-tabs v-model="tab" class="journey-tabs" @tab-change="syncTab">
      <el-tab-pane label="健康指标" name="measurements">
        <section class="panel-grid two">
          <el-card><template #header><b>录入健康指标</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top">
            <div class="form-grid"><el-form-item label="指标"><el-select v-model="measurement.metricType"><el-option v-for="item in metricOptions" :key="item.value" v-bind="item" /></el-select></el-form-item><el-form-item label="测量时间"><el-date-picker v-model="measurement.measuredAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item><el-form-item label="主数值"><el-input-number v-model="measurement.valuePrimary" :precision="2" /></el-form-item><el-form-item v-if="measurement.metricType==='BP'" label="舒张压"><el-input-number v-model="measurement.valueSecondary" /></el-form-item><el-form-item label="单位"><el-input v-model="measurement.unit" /></el-form-item><el-form-item label="备注"><el-input v-model="measurement.remark" /></el-form-item></div>
            <el-button type="primary" :loading="busy" @click="recordMeasurement">保存并自动评估</el-button>
          </el-form></el-card>
          <el-card><template #header><div class="card-head"><b>趋势记录</b><el-button @click="loadMeasurements">刷新</el-button></div></template><el-table :data="measurements" max-height="440"><el-table-column prop="measured_at" label="时间" width="165"/><el-table-column prop="metric_type" label="指标" width="110"><template #default="{row}">{{ metricLabel(row.metric_type) }}</template></el-table-column><el-table-column label="数值"><template #default="{row}">{{row.value_primary}}{{row.value_secondary!=null?'/'+row.value_secondary:''}} {{row.unit}}</template></el-table-column><el-table-column prop="status" label="状态" width="100"><template #default="{row}"><el-tag :type="row.status==='NORMAL'?'success':'danger'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column><el-table-column v-if="isDoctor" label="医生标注" width="130"><template #default="{row}"><el-button link @click="annotate(row)">添加标注</el-button></template></el-table-column></el-table></el-card>
        </section>
      </el-tab-pane>

      <el-tab-pane label="预约与复诊" name="appointments">
        <section class="panel-grid two">
          <el-card><template #header><b>预约或改约</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><div class="form-grid"><el-form-item label="医生"><el-select v-model="appointment.doctorUserId" @change="changeAppointmentDoctor"><el-option v-for="d in clinicians" :key="d.id" :label="d.real_name||d.username" :value="d.id"/></el-select></el-form-item><el-form-item label="可预约时段"><el-select v-model="appointment.scheduleId" clearable @change="selectSchedule"><el-option v-for="s in doctorSchedules" :key="s.id" :value="s.id" :label="`${s.work_date} ${s.start_time}-${s.end_time}`"/></el-select></el-form-item><el-form-item label="方式"><el-select v-model="appointment.consultationMode"><el-option label="线下面诊" value="IN_PERSON"/><el-option label="图文" value="TEXT"/><el-option label="语音" value="VOICE"/><el-option label="视频" value="VIDEO"/></el-select></el-form-item><el-form-item label="开始时间"><el-date-picker v-model="appointment.startAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item><el-form-item label="结束时间"><el-date-picker v-model="appointment.endAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item><el-form-item label="复诊周期（天）"><el-input-number v-model="appointment.recurrenceDays" :min="0"/></el-form-item><el-form-item label="就诊原因"><el-input v-model="appointment.reason"/></el-form-item></div><el-button type="primary" @click="bookAppointment">保存预约</el-button></el-form></el-card>
          <el-card><template #header><b>三方共享日程</b></template><el-table :data="appointments" max-height="430"><el-table-column prop="start_at" label="开始时间" width="165"/><el-table-column prop="doctor_name" label="医生"/><el-table-column prop="consultation_mode" label="方式"><template #default="{row}">{{ modeLabel(row.consultation_mode) }}</template></el-table-column><el-table-column prop="status" label="状态"><template #default="{row}">{{ statusLabel(row.status) }}</template></el-table-column><el-table-column label="操作" width="150"><template #default="{row}"><el-button v-if="row.status==='BOOKED'" link @click="editAppointment(row)">改约</el-button><el-button v-if="row.status==='BOOKED'" link type="danger" @click="cancel(row)">取消</el-button></template></el-table-column></el-table></el-card>
        </section>
        <el-card v-if="isDoctor" class="section-card"><template #header><b>诊后小结、电子处方与复诊</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><div class="form-grid"><el-form-item label="诊断摘要"><el-input v-model="visit.diagnosisSummary" type="textarea"/></el-form-item><el-form-item label="治疗摘要"><el-input v-model="visit.treatmentSummary" type="textarea"/></el-form-item><el-form-item label="复诊建议"><el-input v-model="visit.followUpAdvice" type="textarea"/></el-form-item><el-form-item label="复诊时间"><el-date-picker v-model="visit.followUpAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item></div><el-button type="primary" @click="createVisit">保存小结</el-button></el-form><el-divider>电子处方留档</el-divider><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><div class="form-grid"><el-form-item label="药品"><el-input v-model="prescription.drugName"/></el-form-item><el-form-item label="剂量"><el-input v-model="prescription.dosage" placeholder="例如：5 mg"/></el-form-item><el-form-item label="频次"><el-input v-model="prescription.frequency" placeholder="例如：每日两次"/></el-form-item><el-form-item label="用法"><el-input v-model="prescription.administrationRoute" placeholder="例如：口服或外用"/></el-form-item><el-form-item label="疗程天数"><el-input-number v-model="prescription.durationDays" :min="1"/></el-form-item><el-form-item label="医嘱说明"><el-input v-model="prescription.instructions"/></el-form-item></div><el-button type="primary" @click="savePrescription">发布新处方版本</el-button></el-form></el-card>
        <el-card class="section-card"><template #header><b>历史就诊时间轴</b></template><el-timeline><el-timeline-item v-for="v in visits" :key="v.id" :timestamp="v.visited_at"><b>{{v.diagnosis_summary||'就诊小结'}}</b><p>{{v.treatment_summary}}</p><el-button v-if="isDoctor&&v.status!=='PUBLISHED'" link @click="publish(v)">推送给患者与家属</el-button></el-timeline-item></el-timeline></el-card>
        <el-card class="section-card"><template #header><b>电子处方与用药医嘱</b></template><el-empty v-if="!prescriptions.length" description="暂无医生发布的处方医嘱" :image-size="60"/><article v-for="entry in prescriptions" :key="entry.id" class="prescription-version"><div class="card-head"><b>版本 {{entry.version_no}}</b><el-tag :type="entry.status==='ACTIVE'?'success':'info'">{{entry.status==='ACTIVE'?'当前医嘱':'历史版本'}}</el-tag></div><p>{{String(entry.published_at||'').replace('T',' ')}} · {{entry.instructions}}</p><el-table :data="entry.items||[]"><el-table-column prop="drug_name" label="药品" min-width="140"/><el-table-column prop="dosage" label="剂量" min-width="110"/><el-table-column prop="frequency" label="频次" min-width="110"/><el-table-column prop="administration_route" label="用法" min-width="100"/><el-table-column prop="duration_days" label="疗程（天）" width="105"/></el-table></article></el-card>
      </el-tab-pane>

      <el-tab-pane label="远程问诊" name="consultation">
        <ConsultationWorkspace :patient-id="currentPatientId" :is-doctor="isDoctor" :enabled="tab === 'consultation'" locale="zh" />
      </el-tab-pane>

      <el-tab-pane label="住院与康复" name="recovery">
        <section class="panel-grid two"><el-card v-if="isDoctor"><template #header><b>治疗计划</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="计划类型"><el-select v-model="plan.planType"><el-option v-for="x in planTypes" :key="x.value" :value="x.value" :label="x.label"/></el-select></el-form-item><el-form-item label="标题"><el-input v-model="plan.title"/></el-form-item><el-form-item label="结构化计划"><el-input v-model="planText" type="textarea" :rows="6" placeholder="检查安排、手术时间、用药调整、出院医嘱"/></el-form-item><el-button type="primary" @click="createPlan">发布计划</el-button></el-form></el-card><el-card><template #header><b>当前治疗计划</b></template><el-collapse><el-collapse-item v-for="p in plans" :key="p.id" :title="p.title"><p>{{ planTypeLabel(p.plan_type) }} · {{ statusLabel(p.status) }}</p><pre>{{prettyJson(p.plan_json)}}</pre></el-collapse-item></el-collapse></el-card></section>
        <el-card class="section-card"><template #header><b>康复、伤口、引流与异常症状打卡</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><div class="form-grid"><el-form-item label="记录类型"><el-select v-model="rehab.recordType"><el-option v-for="x in rehabTypes" :key="x.value" :value="x.value" :label="x.label"/></el-select></el-form-item><el-form-item label="完成度"><el-slider v-model="rehab.completionPercent"/></el-form-item><el-form-item label="动作指导视频地址"><el-input v-model="rehab.videoUrl"/></el-form-item><el-form-item label="结构化观察记录"><el-input v-model="rehabText" type="textarea" placeholder="伤口情况、引流量、症状程度或训练记录"/></el-form-item><el-form-item label="需要医护关注"><el-switch v-model="rehab.abnormal"/></el-form-item></div><el-button type="primary" @click="checkinRehab">保存打卡</el-button></el-form><el-table :data="rehabRows"><el-table-column prop="recorded_at" label="时间"/><el-table-column prop="record_type" label="记录类型"><template #default="{row}">{{ rehabTypeLabel(row.record_type) }}</template></el-table-column><el-table-column prop="completion_percent" label="完成度"/><el-table-column prop="abnormal" label="预警"><template #default="{row}">{{ row.abnormal ? '需要关注' : '正常' }}</template></el-table-column></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane label="急诊与呼救" name="emergency">
        <section class="panel-grid two"><el-card class="emergency-card"><template #header><b>一键紧急呼救</b></template><p>记录位置与关键医疗信息，并尝试通知已绑定家属和医生。发送后请查看投递结果。</p><el-input v-model="emergency.locationText" placeholder="位置说明"/><el-button type="danger" size="large" :loading="busy" :disabled="!currentPatientId" @click="sos">发送紧急呼救</el-button></el-card><el-card><template #header><div class="card-head"><b>离线关键医疗信息卡</b><el-button @click="loadEmergencyCard">刷新</el-button></div></template><div v-if="emergencyCard"><h3>{{emergencyCard.patient?.name}}</h3><p><b>病史：</b> {{emergencyCard.patient?.medical_history||'-'}}</p><p><b>过敏史：</b> {{emergencyCard.clinical?.allergy_drugs||'-'}}</p><p><b>血型：</b> {{emergencyCard.clinical?.blood_type||'未记录'}}</p><p><b>当前用药：</b> {{(emergencyCard.medications||[]).map(x=>x.drug_name).join(', ')||'-'}}</p><el-tag>已缓存在本设备，可离线查看</el-tag></div></el-card></section>
        <el-card class="section-card"><template #header><div class="card-head"><b>紧急事件</b><el-button @click="loadEmergencyEvents">刷新</el-button></div></template>
          <el-table :data="emergencyEvents" empty-text="该患者暂无紧急事件"><el-table-column prop="triggered_at" label="时间" min-width="165"/><el-table-column prop="location_text" label="位置" min-width="160"/><el-table-column label="详情" width="110"><template #default="{row}"><el-button link @click="openEmergency(row)">查看</el-button></template></el-table-column></el-table>
          <div v-if="selectedEmergency" class="emergency-event-detail"><h3>紧急事件 #{{ selectedEmergency.id }}</h3><p>位置：{{selectedEmergency.location_text||'-'}} <span v-if="selectedEmergency.latitude!=null&&selectedEmergency.longitude!=null">({{selectedEmergency.latitude}}, {{selectedEmergency.longitude}})</span></p><p>病史：{{selectedEmergency.snapshot?.patient?.medical_history||'-'}}</p><p>过敏史：{{selectedEmergency.snapshot?.clinical?.allergy_drugs||'-'}}</p><p>当前用药：{{(selectedEmergency.snapshot?.medications||[]).map(x=>x.drug_name).join(', ')||'-'}}</p></div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="儿童与孕产" name="specialty">
        <section class="panel-grid three"><el-card><template #header><b>生长曲线</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="日期"><el-date-picker v-model="growth.recordDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="身高（厘米）"><el-input-number v-model="growth.heightCm"/></el-form-item><el-form-item label="体重（千克）"><el-input-number v-model="growth.weightKg" :precision="2"/></el-form-item><el-form-item label="头围（厘米）"><el-input-number v-model="growth.headCircumferenceCm" :precision="1"/></el-form-item><el-form-item label="标准百分位"><div class="inline"><el-input-number v-model="growth.heightPercentile" placeholder="身高百分位"/><el-input-number v-model="growth.weightPercentile" placeholder="体重百分位"/></div></el-form-item><el-button type="primary" @click="saveSpecial('growth',growth)">保存</el-button></el-form></el-card><el-card><template #header><b>疫苗接种计划</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="疫苗名称"><el-input v-model="vaccine.vaccineName"/></el-form-item><el-form-item label="剂次"><el-input v-model="vaccine.doseNo"/></el-form-item><el-form-item label="计划日期"><el-date-picker v-model="vaccine.plannedDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="提醒时间"><el-date-picker v-model="vaccine.remindAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item><el-button type="primary" @click="saveSpecial('vaccination',vaccine)">保存</el-button></el-form></el-card><el-card><template #header><b>产检与产后时间轴</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="记录类型"><el-select v-model="maternity.recordType"><el-option label="产检" value="PRENATAL"/><el-option label="分娩" value="DELIVERY"/><el-option label="产后" value="POSTPARTUM"/></el-select></el-form-item><el-form-item label="日期"><el-date-picker v-model="maternity.recordDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="标题"><el-input v-model="maternity.title"/></el-form-item><el-form-item label="详情"><el-input v-model="maternityText" type="textarea"/></el-form-item><el-button type="primary" @click="saveMaternity">保存</el-button></el-form></el-card></section>
        <el-card class="section-card"><template #header><div class="card-head"><b>专项健康记录</b><el-button @click="loadSpecialty">刷新</el-button></div></template><el-tabs><el-tab-pane label="生长记录"><el-table :data="specialtyRows.growth"><el-table-column prop="record_date" label="日期"/><el-table-column prop="height_cm" label="身高（cm）"/><el-table-column prop="weight_kg" label="体重（kg）"/><el-table-column prop="head_circumference_cm" label="头围（cm）"/></el-table></el-tab-pane><el-tab-pane label="疫苗计划"><el-table :data="specialtyRows.vaccination"><el-table-column prop="vaccine_name" label="疫苗"/><el-table-column prop="dose_no" label="剂次"/><el-table-column prop="planned_date" label="计划日期"/><el-table-column prop="remind_at" label="提醒时间"/></el-table></el-tab-pane><el-tab-pane label="孕产记录"><el-table :data="specialtyRows.maternity"><el-table-column prop="record_date" label="日期"/><el-table-column prop="title" label="标题"/><el-table-column label="详情"><template #default="{row}">{{specialtyDetail(row.data_json)}}</template></el-table-column></el-table></el-tab-pane></el-tabs></el-card>
      </el-tab-pane>

      <el-tab-pane label="心理健康" name="mental">
        <section class="panel-grid two"><el-card><template #header><b>私密自评</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="量表"><el-select v-model="mental.scaleCode"><el-option label="PHQ-9" value="PHQ9"/><el-option label="GAD-7" value="GAD7"/><el-option label="WHO-5" value="WHO5"/></el-select></el-form-item><el-form-item label="已完成问卷的各题得分（按题序输入）"><el-input v-model="mentalAnswers" :placeholder="mental.scaleCode==='PHQ9'?'0,1,2,1,0,1,0,0,0':mental.scaleCode==='GAD7'?'0,1,2,1,0,1,0':'3,4,3,4,3'"/></el-form-item><el-form-item label="允许家属查看"><el-switch v-model="mentalVisible"/></el-form-item><el-button type="primary" @click="submitMental">私密提交</el-button></el-form></el-card><el-card><template #header><b>定期推送</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><el-form-item label="间隔天数"><el-input-number v-model="mentalSchedule.intervalDays" :min="1"/></el-form-item><el-form-item label="下次推送"><el-date-picker v-model="mentalSchedule.nextDueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"/></el-form-item><el-button type="primary" @click="scheduleMental">设置计划</el-button></el-form><el-table :data="mentalRows"><el-table-column prop="submitted_at" label="提交时间"/><el-table-column prop="scale_code" label="量表"/><el-table-column prop="score" label="得分"/><el-table-column prop="severity" label="筛查提示"><template #default="{row}">{{ mentalSeverityLabel(row) }}</template></el-table-column></el-table></el-card></section>
        <el-card class="section-card"><template #header><b>量表推送计划</b></template><el-table :data="mentalSchedules" empty-text="暂无推送计划"><el-table-column prop="scale_code" label="量表"/><el-table-column prop="interval_days" label="间隔天数"/><el-table-column prop="next_due_at" label="下次推送"/><el-table-column label="状态"><template #default="{row}">{{row.enabled===1?'启用':'已停用'}}</template></el-table-column><el-table-column label="操作"><template #default="{row}"><el-button v-if="row.enabled===1" link type="danger" :disabled="busy" @click="disableMentalSchedule(row)">停止推送</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane label="隐私与授权" name="privacy">
        <el-card><template #header><b>患者自主授权</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top" class="inline-form"><el-form-item label="用户编号"><el-input-number v-model="grant.granteeUserId"/></el-form-item><el-form-item label="角色"><el-select v-model="grant.granteeRole"><el-option label="医生" value="DOCTOR"/><el-option label="家属" value="FAMILY"/><el-option label="监护人" value="GUARDIAN"/></el-select></el-form-item><el-form-item label="权限级别"><el-select v-model="grant.accessLevel"><el-option label="只读" value="READ"/><el-option label="可录入" value="WRITE"/><el-option label="可代办" value="PROXY"/></el-select></el-form-item><el-form-item label="可见模块"><el-input v-model="grant.visibleModules" placeholder="例如：健康指标、预约复诊；留空表示全部"/></el-form-item><el-button type="primary" @click="createGrant">授权</el-button></el-form><el-table :data="grants"><el-table-column prop="real_name" label="人员"/><el-table-column prop="grantee_role" label="角色"><template #default="{row}">{{ roleLabel(row.grantee_role) }}</template></el-table-column><el-table-column prop="access_level" label="权限级别"><template #default="{row}">{{ accessLabel(row.access_level) }}</template></el-table-column><el-table-column prop="visible_modules" label="可见模块"><template #default="{row}">{{ modulesLabel(row.visible_modules) }}</template></el-table-column><el-table-column prop="status" label="状态"><template #default="{row}">{{ statusLabel(row.status) }}</template></el-table-column><el-table-column label=""><template #default="{row}"><el-button link type="danger" @click="revoke(row)">撤销</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane v-if="isDoctor" label="运营管理" name="operations">
        <el-card class="appointment-inbox"><template #header><div class="card-head"><b>我的预约收件箱</b><el-button :loading="appointmentInboxLoading" @click="loadAppointmentInbox">刷新预约</el-button></div></template><p class="inbox-description">显示预约给您的患者，无需先在顶部选择患者。预约仅授予该条预约的查看与取消权限，不开放其他病历。</p><el-table :data="appointmentInbox" max-height="420" empty-text="暂无发给您的预约"><el-table-column prop="patient_name" label="患者" min-width="110"/><el-table-column label="开始时间" min-width="160"><template #default="{row}">{{String(row.start_at||'').replace('T',' ')}}</template></el-table-column><el-table-column label="结束时间" min-width="160"><template #default="{row}">{{String(row.end_at||'').replace('T',' ')}}</template></el-table-column><el-table-column label="方式" min-width="100"><template #default="{row}">{{appointmentModeLabel(row.consultation_mode)}}</template></el-table-column><el-table-column prop="reason" label="预约原因" min-width="160"/><el-table-column label="状态" min-width="100"><template #default="{row}">{{appointmentStatusLabel(row.status)}}</template></el-table-column><el-table-column label="操作" width="180"><template #default="{row}"><el-button v-if="row.status==='BOOKED'" link type="primary" :disabled="busy" @click="completeInboxAppointment(row)">完成就诊</el-button><el-button v-if="row.status==='BOOKED'" link type="danger" :disabled="busy" @click="cancelInboxAppointment(row)">取消预约</el-button></template></el-table-column></el-table></el-card>
        <section class="panel-grid two"><el-card><template #header><b>医生排班</b></template><el-form :disabled="busy || (patientRequired && !currentPatientId)" label-position="top"><div class="form-grid"><el-form-item v-if="roles.includes('admin')" label="接诊医生"><el-select v-model="schedule.doctorUserId" filterable placeholder="请选择排班医生"><el-option v-for="d in clinicians" :key="d.id" :label="d.real_name||d.username" :value="d.id"/></el-select></el-form-item><el-form-item label="日期"><el-date-picker v-model="schedule.workDate" value-format="YYYY-MM-DD"/></el-form-item><el-form-item label="开始时间"><el-time-picker v-model="schedule.startTime" value-format="HH:mm:ss"/></el-form-item><el-form-item label="结束时间"><el-time-picker v-model="schedule.endTime" value-format="HH:mm:ss"/></el-form-item><el-form-item label="时段分钟数"><el-input-number v-model="schedule.slotMinutes" :min="10"/></el-form-item></div><el-button type="primary" @click="createSchedule">新增可预约时段</el-button></el-form></el-card><el-card><template #header><b>患者分组</b></template><div class="message-compose"><el-input v-model="group.groupName" placeholder="分组名称"/><el-input v-model="group.description" placeholder="说明"/><el-button type="primary" @click="createGroup">创建</el-button></div><el-table :data="groups"><el-table-column prop="group_name" label="分组"/><el-table-column prop="patient_count" label="患者数"/><el-table-column label=""><template #default="{row}"><el-button link @click="addToGroup(row)">加入当前患者</el-button></template></el-table-column></el-table></el-card></section>
        <el-card class="section-card"><template #header><div class="card-head"><b>内部照护统计</b><el-button @click="loadOperations">刷新</el-button></div></template><div v-if="operations" class="metric-cards"><article><span>随访完成率</span><strong>{{operations.followUp?.completion_rate||0}}%</strong></article><article><span>服药依从率</span><strong>{{operations.medication?.adherence_rate||0}}%</strong></article><article><span>指标达标率</span><strong>{{operations.measurements?.target_rate||0}}%</strong></article><article><span>已处理预警</span><strong>{{operations.alerts?.resolved||0}} / {{operations.alerts?.total||0}}</strong></article></div></el-card>
      </el-tab-pane>
    </el-tabs>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useCurrentPatient } from '@/composables/useCurrentPatient'
import * as api from '@/api/careJourney'
import ConsultationWorkspace from '@/components/ConsultationWorkspace.vue'

const route=useRoute(),router=useRouter(),{currentPatientId,currentPatientName}=useCurrentPatient()
const roles=JSON.parse(localStorage.getItem('userRoleCodes')||'[]'),isDoctor=computed(()=>roles.includes('doctor')||roles.includes('admin'))
const allowedTabs=['measurements','appointments','consultation','recovery','emergency','specialty','mental','privacy',...(isDoctor.value?['operations']:[])]
const tab=ref(allowedTabs.includes(route.query.tab)?route.query.tab:'measurements'),busy=ref(false)
const patientRequired=computed(()=>!['operations','consultation'].includes(tab.value))
const clinicians=ref([]),doctorSchedules=ref([]),measurements=ref([]),appointments=ref([]),visits=ref([]),prescriptions=ref([]),specialtyRows=reactive({growth:[],vaccination:[],maternity:[]}),plans=ref([]),rehabRows=ref([]),emergencyCard=ref(null),emergencyEvents=ref([]),selectedEmergency=ref(null),mentalRows=ref([]),mentalSchedules=ref([]),grants=ref([]),groups=ref([]),operations=ref(null)
const now=()=>{const date=new Date();return new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,19)},today=()=>now().slice(0,10)
const measurement=reactive({metricType:'BP',valuePrimary:120,valueSecondary:80,unit:'mmHg',measuredAt:now(),remark:''})
const appointment=reactive({id:null,doctorUserId:null,scheduleId:null,consultationMode:'IN_PERSON',startAt:'',endAt:'',recurrenceDays:0,reason:''})
const visit=reactive({diagnosisSummary:'',treatmentSummary:'',followUpAdvice:'',followUpAt:''})
const prescription=reactive({drugName:'',dosage:'',frequency:'',administrationRoute:'',durationDays:7,instructions:''})
const plan=reactive({planType:'INPATIENT',title:''}),planText=ref(''),rehab=reactive({recordType:'EXERCISE',completionPercent:0,videoUrl:'',abnormal:false}),rehabText=ref('')
const emergency=reactive({locationText:'',latitude:null,longitude:null})
const growth=reactive({recordDate:today(),heightCm:null,weightKg:null,headCircumferenceCm:null,heightPercentile:null,weightPercentile:null}),vaccine=reactive({vaccineName:'',doseNo:'',plannedDate:today(),remindAt:''}),maternity=reactive({recordType:'PRENATAL',recordDate:today(),title:''}),maternityText=ref('')
const mental=reactive({scaleCode:'PHQ9'}),mentalAnswers=ref(''),mentalVisible=ref(false),mentalSchedule=reactive({scaleCode:'PHQ9',intervalDays:14,nextDueAt:now()})
const grant=reactive({granteeUserId:null,granteeRole:'FAMILY',accessLevel:'READ',visibleModules:''}),schedule=reactive({doctorUserId:null,workDate:today(),startTime:'09:00:00',endTime:'12:00:00',slotMinutes:30}),group=reactive({groupName:'',description:''})
const metricOptions=[{label:'血压',value:'BP'},{label:'血糖',value:'GLUCOSE'},{label:'血氧',value:'SPO2'},{label:'体重',value:'WEIGHT'},{label:'心率',value:'HEART_RATE'},{label:'体温',value:'TEMPERATURE'},{label:'自定义指标',value:'CUSTOM'}]
const planTypes=[{value:'INPATIENT',label:'住院治疗'},{value:'SURGERY',label:'手术计划'},{value:'REHAB',label:'康复计划'},{value:'DISCHARGE',label:'出院计划'}]
const rehabTypes=[{value:'EXERCISE',label:'康复训练'},{value:'WOUND',label:'伤口记录'},{value:'DRAIN',label:'引流记录'},{value:'SYMPTOM',label:'异常症状'}]
const statusLabels={NORMAL:'正常',ABNORMAL:'异常',OPEN:'进行中',CLOSED:'已结束',BOOKED:'已预约',CANCELLED:'已取消',PUBLISHED:'已发布',DRAFT:'草稿',ACTIVE:'生效中',INACTIVE:'未启用',REVOKED:'已撤销',COMPLETED:'已完成',PLANNED:'已计划',PENDING:'待处理',APPROVED:'已通过',REJECTED:'已驳回'}
const modeLabels={IN_PERSON:'线下面诊',TEXT:'图文',VOICE:'语音',VIDEO:'视频'}
const roleLabels={DOCTOR:'医生',FAMILY:'家属',GUARDIAN:'监护人',PATIENT:'患者'}
const accessLabels={READ:'只读',WRITE:'可录入',PROXY:'可代办'}
const severityLabels={NONE:'无明显风险',NORMAL:'正常',MILD:'轻度',MODERATE:'中度',SEVERE:'重度',LOW:'低风险',MEDIUM:'中风险',HIGH:'高风险',CRITICAL:'危急'}
const moduleLabels={MEASUREMENTS:'健康指标',APPOINTMENTS:'预约复诊',CONSULTATIONS:'远程问诊',MEDICATIONS:'用药管理',RECORDS:'医疗记录',DIALYSIS:'透析管理'}
const statusLabel=value=>statusLabels[value]||value||'—'
const modeLabel=value=>modeLabels[value]||value||'—'
const metricLabel=value=>metricOptions.find(item=>item.value===value)?.label||value||'—'
const planTypeLabel=value=>planTypes.find(item=>item.value===value)?.label||value||'—'
const rehabTypeLabel=value=>rehabTypes.find(item=>item.value===value)?.label||value||'—'
const roleLabel=value=>roleLabels[value]||value||'—'
const accessLabel=value=>accessLabels[value]||value||'—'
const severityLabel=value=>severityLabels[value]||value||'—'
const modulesLabel=value=>!value?'全部模块':String(value).split(',').map(item=>moduleLabels[item.trim()]||item.trim()).join('、')
function syncTab(name){const query={...route.query,tab:name};if(name!=='consultation')delete query.consultationId;router.replace({query})}
function pid(){if(!currentPatientId.value)throw new Error('请先选择患者。');return currentPatientId.value}
async function safely(action,message='已保存'){if(busy.value)return;try{busy.value=true;await action();ElMessage.success(message)}catch(e){if(e.validation)ElMessage.warning(e.message);else if(e.message==='请先选择患者。')ElMessage.warning(e.message)}finally{busy.value=false}}
async function loadMeasurements(){await loadPatientData('measurements',id=>api.listMeasurements({patientId:id}),response=>{measurements.value=response.data||[]})}
async function recordMeasurement(){await safely(async()=>{validate(measurement.measuredAt && Number(measurement.valuePrimary)>0, '请填写测量时间和有效的正数指标值。');if(measurement.metricType==='BP')validate(Number(measurement.valueSecondary)>0,'请填写舒张压。');await api.saveMeasurement({...measurement,patientId:pid()});await loadMeasurements()})}
async function annotate(row){const {value}=await ElMessageBox.prompt('为该趋势点添加医生备注','临床标注');await safely(async()=>{await api.annotateMeasurement(row.id,value);await loadMeasurements()})}
async function loadAppointments(){await loadPatientData('appointments',id=>Promise.all([api.listAppointments(id),api.listVisits(id),api.listPrescriptions(id)]),responses=>{appointments.value=responses[0].data||[];visits.value=responses[1].data||[];prescriptions.value=responses[2].data||[]})}
function changeAppointmentDoctor(){appointment.scheduleId=null;appointment.startAt='';appointment.endAt='';doctorSchedules.value=[];loadDoctorSchedules()}
async function loadDoctorSchedules(){const doctor=appointment.doctorUserId,selected=currentPatientId.value;if(!doctor){doctorSchedules.value=[];return}try{const response=await api.listDoctorSchedules({doctorUserId:doctor});if(doctor===appointment.doctorUserId&&selected===currentPatientId.value)doctorSchedules.value=response.data||[]}catch{}}
function selectSchedule(id){const s=doctorSchedules.value.find(x=>x.id===id);if(!s)return;appointment.startAt=`${s.work_date}T${String(s.start_time).slice(0,8)}`;const endMinutes=Number(String(s.start_time).slice(0,2))*60+Number(String(s.start_time).slice(3,5))+Number(s.slot_minutes||30);appointment.endAt=`${s.work_date}T${String(Math.floor(endMinutes/60)%24).padStart(2,'0')}:${String(endMinutes%60).padStart(2,'0')}:00`}
async function bookAppointment(){await safely(async()=>{validate(appointment.doctorUserId && appointment.startAt && appointment.endAt && new Date(appointment.endAt)>new Date(appointment.startAt),'请选择医生，并填写结束时间晚于开始时间的预约时段。');await api.saveAppointment({...appointment,patientId:pid()});Object.assign(appointment,{id:null,startAt:'',endAt:'',reason:''});await loadAppointments()})}
function editAppointment(row){if(row.status!=='BOOKED')return;Object.assign(appointment,{id:row.id,doctorUserId:row.doctor_user_id,scheduleId:row.schedule_id,consultationMode:row.consultation_mode,startAt:String(row.start_at).replace(' ','T'),endAt:String(row.end_at).replace(' ','T'),recurrenceDays:row.recurrence_days||0,reason:row.reason||''});loadDoctorSchedules();window.scrollTo({top:0,behavior:'smooth'})}
async function cancel(row){await ElMessageBox.confirm('取消该预约并通知共享照护团队？','取消预约');await safely(async()=>{await api.cancelAppointment(row.id,'用户从共享日程中取消预约');await loadAppointments()},'预约已取消')}
async function createVisit(){await safely(async()=>{validate(visit.diagnosisSummary.trim() || visit.treatmentSummary.trim(),'请填写诊断或治疗小结。');await api.saveVisit({...visit,patientId:pid(),visitedAt:now()});await loadAppointments()})}
async function publish(row){await safely(async()=>{await api.publishVisit(row.id);await loadAppointments()},'小结已推送')}
async function savePrescription(){await safely(async()=>{validate(prescription.drugName.trim() && prescription.dosage.trim() && prescription.frequency.trim(),'请填写药品名称、剂量和用药频次。');await api.savePrescription({patientId:pid(),instructions:prescription.instructions,items:[{drugName:prescription.drugName,dosage:prescription.dosage,frequency:prescription.frequency,administrationRoute:prescription.administrationRoute,durationDays:prescription.durationDays}]});Object.assign(prescription,{drugName:'',dosage:'',frequency:'',administrationRoute:'',durationDays:7,instructions:''});await loadAppointments()},'处方已发布并同步')}
async function loadRecovery(){await loadPatientData('recovery',id=>Promise.all([api.listTreatmentPlans(id),api.listRehabCheckins(id)]),responses=>{plans.value=responses[0].data||[];rehabRows.value=responses[1].data||[]})}
async function createPlan(){await safely(async()=>{validate(plan.title.trim() && planText.value.trim(),'请填写计划标题和内容。');await api.saveTreatmentPlan({...plan,patientId:pid(),plan:{instructions:planText.value}});plan.title='';planText.value='';await loadRecovery()})}
async function checkinRehab(){await safely(async()=>{validate(rehabText.value.trim(),'请填写本次康复或症状观察记录。');await api.saveRehabCheckin({...rehab,patientId:pid(),data:{observation:rehabText.value}});rehabText.value='';await loadRecovery()})}
function specialtyDetail(value){try{return (typeof value==='string'?JSON.parse(value):value)?.details||'—'}catch{return value}}
function prettyJson(value){try{return JSON.stringify(typeof value==='string'?JSON.parse(value):value,null,2)}catch{return value}}
async function loadEmergencyCard(){
  const selected=currentPatientId.value,userId=localStorage.getItem('userId')
  emergencyCard.value=null
  if(!selected||!userId)return
  const key=`offlineEmergencyCard:${userId}:${selected}`,version=(loadVersions.get('emergency')||0)+1
  loadVersions.set('emergency',version)
  const isCurrent=()=>currentPatientId.value===selected&&localStorage.getItem('userId')===userId&&loadVersions.get('emergency')===version
  try{
    const response=await api.getEmergencyCard(selected)
    if(!isCurrent())return
    if(response.code&&response.code!==200)throw Object.assign(new Error(response.msg||'Request failed'),{code:response.code})
    emergencyCard.value=response.data
    try{localStorage.setItem(key,JSON.stringify(response.data))}catch{}
  }catch(error){
    if(!isCurrent())return
    const networkFailure=!error.response&&!Number(error.code)&&(['ERR_NETWORK','ECONNABORTED','ETIMEDOUT'].includes(error.code)||(typeof navigator!=='undefined'&&navigator.onLine===false))
    if(networkFailure){
      try{emergencyCard.value=JSON.parse(localStorage.getItem(key)||'null')}catch{emergencyCard.value=null}
    }else{
      emergencyCard.value=null
      localStorage.removeItem(key)
    }
  }
}
async function loadEmergencyEvents(){await loadPatientData('emergencyEvents',id=>api.listEmergencies(id),response=>{emergencyEvents.value=response.data||[]})}
async function loadEmergencyTab(){await Promise.allSettled([loadEmergencyCard(),loadEmergencyEvents()])}
async function openEmergency(row){const patientId=currentPatientId.value;selectedEmergency.value=null;const response=await api.getEmergency(row.id);if(patientId===currentPatientId.value&&Number(response.data?.patient_id)===Number(patientId))selectedEmergency.value=response.data}
async function sos(){if(busy.value||!currentPatientId.value)return;const selected=pid();busy.value=true;try{await ElMessageBox.confirm('系统会记录紧急事件并尝试通知已绑定的照护联系人，是否继续？','紧急呼救',{confirmButtonText:'立即发送',type:'warning'})}catch{busy.value=false;return}if(currentPatientId.value!==selected){busy.value=false;return}try{emergency.latitude=null;emergency.longitude=null;if(navigator.geolocation)await new Promise(resolve=>navigator.geolocation.getCurrentPosition(p=>{emergency.latitude=p.coords.latitude;emergency.longitude=p.coords.longitude;resolve()},()=>resolve(),{timeout:5000}));if(currentPatientId.value!==selected)return;const response=await api.triggerEmergency({...emergency,patientId:selected});if(currentPatientId.value!==selected)return;const {deliveryCount=0,recipientCount=0,id}=response.data||{};if(deliveryCount===0)ElMessage.warning(`紧急事件 #${id} 已记录，但没有联系人收到通知。请立即通过其他方式求助。`);else if(deliveryCount<recipientCount)ElMessage.warning(`紧急事件 #${id} 已记录，${recipientCount} 位联系人中有 ${deliveryCount} 位收到通知。`);else ElMessage.success(`紧急事件 #${id} 已记录，${deliveryCount} 位联系人收到通知。`);await loadEmergencyEvents()}catch{}finally{busy.value=false}}
async function saveSpecial(type,data){await safely(async()=>{if(type==='growth')validate(data.recordDate && [data.heightCm,data.weightKg,data.headCircumferenceCm].some(value=>Number(value)>0),'请填写日期及至少一项有效的身高、体重或头围。');if(type==='vaccination')validate(data.vaccineName?.trim() && data.plannedDate,'请填写疫苗名称和计划接种日期。');if(type==='maternity')validate(data.recordDate && data.title?.trim(),'请填写记录日期和标题。');await api.saveSpecialty(type,{...data,patientId:pid()});await loadSpecialty()})}
async function saveMaternity(){await saveSpecial('maternity',{...maternity,data:{details:maternityText.value}})}
async function loadMental(){await loadPatientData('mental',id=>Promise.allSettled([api.listMentalAssessments(id),api.listMentalSchedules(id)]),responses=>{if(responses[0].status==='fulfilled')mentalRows.value=responses[0].value.data||[];if(responses[1].status==='fulfilled')mentalSchedules.value=responses[1].value.data||[]})}
async function submitMental(){await safely(async()=>{const values=mentalAnswers.value.split(/[,，]/).map(value=>value.trim()),count={PHQ9:9,GAD7:7,WHO5:5}[mental.scaleCode],maximum=mental.scaleCode==='WHO5'?5:3;validate(values.length===count && values.every(value=>/^\d+$/.test(value) && Number(value)<=maximum),`请按题目顺序填写 ${count} 个 0～${maximum} 的整数得分，用逗号分隔。`);const answers=values.map(Number);await api.saveMentalAssessment({...mental,patientId:pid(),answers,familyVisibility:mentalVisible.value?'VISIBLE':'PRIVATE'});mentalAnswers.value='';await loadMental()})}
async function scheduleMental(){await safely(async()=>{validate(mentalSchedule.nextDueAt && Number(mentalSchedule.intervalDays)>0,'请填写下次推送时间和有效间隔天数。');await api.saveMentalSchedule({...mentalSchedule,scaleCode:mental.scaleCode,patientId:pid(),familyVisibility:mentalVisible.value?'VISIBLE':'PRIVATE'});await loadMental()},'量表推送计划已设置')}
async function disableMentalSchedule(row){if(!row||row.enabled!==1)return;await safely(async()=>{await api.disableMentalSchedule(row.id);await loadMental()},'量表推送计划已停用')}
async function loadGrants(){await loadPatientData('grants',id=>api.listAccessGrants(id),response=>{grants.value=response.data||[]})}
async function createGrant(){await safely(async()=>{validate(Number(grant.granteeUserId)>0,'请填写有效的授权对象用户编号。');await api.saveAccessGrant({...grant,patientId:pid()});await loadGrants()},'授权已生效')}
async function revoke(row){await safely(async()=>{await api.revokeAccessGrant(row.id);await loadGrants()},'授权已撤销')}
async function createSchedule(){await safely(async()=>{validate(!roles.includes('admin') || schedule.doctorUserId,'请选择需要排班的医生。');validate(schedule.workDate && schedule.startTime && schedule.endTime && schedule.endTime>schedule.startTime,'请填写排班日期，且结束时间应晚于开始时间。');await api.saveDoctorSchedule({...schedule})},'可预约时段已添加')}
async function createGroup(){await safely(async()=>{validate(group.groupName.trim(),'请填写患者分组名称。');await api.savePatientGroup({...group});group.groupName='';group.description='';groups.value=(await api.listPatientGroups()).data||[]})}
async function addToGroup(row){await safely(async()=>{await api.addPatientToGroup(row.id,pid());groups.value=(await api.listPatientGroups()).data||[]},'患者已加入分组')}
const appointmentInbox=ref([]),appointmentInboxLoading=ref(false)
let appointmentInboxVersion=0
const appointmentModeLabel=value=>({IN_PERSON:'现场门诊',TEXT:'文字问诊',VOICE:'语音问诊',VIDEO:'视频问诊'})[value]||value
const appointmentStatusLabel=value=>({BOOKED:'已预约',CANCELLED:'已取消',COMPLETED:'已完成'})[value]||value
async function loadAppointmentInbox(){
  if(!isDoctor.value)return
  const version=++appointmentInboxVersion,userId=localStorage.getItem('userId')
  appointmentInboxLoading.value=true
  try{const response=await api.listAppointmentInbox();if(version===appointmentInboxVersion&&userId===localStorage.getItem('userId'))appointmentInbox.value=response.data||[]}
  catch{if(version===appointmentInboxVersion)appointmentInbox.value=[]}
  finally{if(version===appointmentInboxVersion)appointmentInboxLoading.value=false}
}
async function completeInboxAppointment(row){
  if(busy.value||row.status!=='BOOKED')return
  busy.value=true
  try{
    await ElMessageBox.confirm('确认该次就诊已完成？此操作不会代替诊后小结或处方记录。','完成就诊')
    await api.completeAppointment(row.id)
    ElMessage.success('已标记完成就诊')
    await loadAppointmentInbox()
  }catch{}finally{busy.value=false}
}
async function cancelInboxAppointment(row){
  if(busy.value||row.status!=='BOOKED')return
  busy.value=true
  try{
    await ElMessageBox.confirm('取消这条预约并通知患者及照护团队？','取消预约')
    await api.cancelAppointment(row.id,'医生从预约收件箱取消预约')
    ElMessage.success('预约已取消')
    await loadAppointmentInbox()
  }catch{}finally{busy.value=false}
}
async function loadOperations(){await Promise.allSettled([loadAppointmentInbox(),(async()=>{operations.value=(await api.getOperationsReport({})).data})(),(async()=>{groups.value=(await api.listPatientGroups()).data||[]})()])}
async function loadTab(){const current=tab.value;const loaders={measurements:loadMeasurements,appointments:loadAppointments,recovery:loadRecovery,specialty:loadSpecialty,emergency:loadEmergencyTab,mental:loadMental,privacy:loadGrants,operations:loadOperations};if(['appointments','operations'].includes(current)&&!clinicians.value.length){try{clinicians.value=(await api.listClinicians()).data||[]}catch{clinicians.value=[]}}try{if(loaders[current])await loaders[current]()}catch{}}
const mentalSeverityLabel=row=>({MINIMAL:'未达评估提醒阈值',MILD:'轻度',MODERATE:'中度',MODERATELY_SEVERE:'中重度',SEVERE:'重度',REVIEW_REQUIRED:'建议进一步评估'})[row.severity]||row.severity||'—'
function validate(valid,text){if(!valid){const error=new Error(text);error.validation=true;throw error}}
async function loadSpecialty(){await loadPatientData('specialty',id=>Promise.all(['growth','vaccination','maternity'].map(type=>api.listSpecialty(type,id))),responses=>{['growth','vaccination','maternity'].forEach((type,index)=>{specialtyRows[type]=responses[index].data||[]})})}
watch(()=>measurement.metricType,type=>{measurement.unit=({BP:'mmHg',GLUCOSE:'mmol/L',SPO2:'%',WEIGHT:'kg',HEART_RATE:'bpm',TEMPERATURE:'°C',CUSTOM:''})[type];measurement.valuePrimary=null;measurement.valueSecondary=type==='BP'?80:null})
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
watch(currentPatientId,()=>{
  for(const [key,version]of loadVersions)loadVersions.set(key,version+1)
  measurements.value=[];appointments.value=[];visits.value=[];prescriptions.value=[];Object.keys(specialtyRows).forEach(key=>{specialtyRows[key]=[]});plans.value=[];rehabRows.value=[];emergencyCard.value=null;emergencyEvents.value=[];selectedEmergency.value=null;mentalRows.value=[];mentalSchedules.value=[];grants.value=[];doctorSchedules.value=[]
  patientForms.forEach((model,index)=>Object.assign(model,formDefaults[index]))
  planText.value='';rehabText.value='';maternityText.value='';mentalAnswers.value='';mentalVisible.value=false
  measurement.measuredAt=now();mentalSchedule.nextDueAt=now();growth.recordDate=today();vaccine.plannedDate=today();maternity.recordDate=today()
  loadTab()
})
watch(()=>route.query.tab,value=>{const next=allowedTabs.includes(value)?value:'measurements';if(tab.value!==next)tab.value=next;if(value&&value!==next){syncTab(next);return}loadTab()})
onMounted(()=>{if(route.query.tab&&!allowedTabs.includes(route.query.tab))syncTab(tab.value);else loadTab()})
</script>

<style scoped>
.appointment-inbox{margin-bottom:18px;min-width:0}.inbox-description{margin:0 0 16px;color:#637a75;line-height:1.7}
.prescription-version+.prescription-version{margin-top:22px;padding-top:18px;border-top:1px solid #e4ece8}.prescription-version p{color:#6a7e79;white-space:pre-wrap}.form-grid{min-width:0}.form-grid :deep(.el-form-item__content){min-width:0}.form-grid :deep(.el-date-editor){max-width:100%}.card-head{flex-wrap:wrap}

.journey-page{max-width:1500px;margin:auto;padding:26px}.journey-hero{display:flex;justify-content:space-between;gap:24px;align-items:center;padding:28px;border:1px solid #dce9e4;border-radius:20px;background:linear-gradient(120deg,#eef7f3,#fff);margin-bottom:22px}.journey-hero span{font-size:11px;letter-spacing:2px;color:#247568;font-weight:700}.journey-hero h1{margin:8px 0;font-size:32px}.journey-hero p{margin:0;max-width:820px;color:#637a75}.journey-tabs{background:#fff;border:1px solid #e1ebe7;border-radius:18px;padding:18px}.panel-grid{display:grid;gap:18px}.panel-grid.two{grid-template-columns:repeat(2,minmax(0,1fr))}.panel-grid.three{grid-template-columns:repeat(3,minmax(0,1fr))}.section-card{margin-top:18px}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 14px}.card-head,.message-compose,.inline{display:flex;align-items:center;justify-content:space-between;gap:12px}.realtime-state{display:inline-flex;align-items:center;gap:6px;margin-left:12px;color:#238066;font-size:11px;font-weight:600}.realtime-state i{width:8px;height:8px;border-radius:50%;background:#31af7d;box-shadow:0 0 0 4px #31af7d20}.message-list{display:grid;gap:10px;max-height:360px;overflow:auto;scroll-behavior:smooth}.message-list article{padding:12px;border:1px solid #e4ece9;border-radius:12px}.message-list article span{float:right;color:#81928d;font-size:12px}.message-list article p{margin:6px 0 0}.message-compose{margin-top:14px}.message-compose .el-input{flex:1}.media-room{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;margin-bottom:18px;padding:12px;border-radius:14px;background:#173a36}.media-room video{width:100%;min-height:160px;max-height:320px;object-fit:cover;border-radius:10px;background:#0e2421}.media-room p{color:#d7e6e1;padding:20px}.emergency-card{border-color:#efb4aa}.emergency-card .el-input{margin:14px 0}.metric-cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.metric-cards article{padding:20px;border-radius:14px;background:#f2f7f5}.metric-cards span,.metric-cards strong{display:block}.metric-cards strong{font-size:26px;margin-top:8px}.inline-form{display:flex;align-items:end;gap:12px;flex-wrap:wrap}.inline-form .el-form-item{min-width:180px;margin-bottom:10px}pre{white-space:pre-wrap}.el-select,.el-date-editor{width:100%}@media(max-width:900px){.panel-grid.two,.panel-grid.three,.metric-cards{grid-template-columns:1fr 1fr}}@media(max-width:650px){.journey-page{padding:14px}.journey-hero{align-items:flex-start;flex-direction:column;padding:20px}.journey-tabs{padding:10px}.panel-grid.two,.panel-grid.three,.metric-cards,.form-grid{grid-template-columns:1fr}.message-compose{align-items:stretch;flex-direction:column}.inline{align-items:stretch;flex-direction:column}.media-room{grid-template-columns:1fr}}
</style>
