/* Browser-only demo domain model. No network, storage, or production data. */
(() => {
  const day = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  const clock = date => `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  const bi = (en, zh) => ({ en, zh });
  function createState(now = new Date()) {
    const patients = [
      { id: 1, name: bi('Aihua Zhang', '张爱华'), relation: bi('Mother', '母亲'), age: 65, risk: 'critical', condition: bi('Hypertension · CKD monitoring', '高血压 · 慢性肾病随访'), stocks: [28, 8] },
      { id: 2, name: bi('Mingyuan Li', '李明远'), relation: bi('Father', '父亲'), age: 68, risk: 'watch', condition: bi('Diabetes · nutrition follow-up', '糖尿病 · 营养随访'), stocks: [18, 16] },
      { id: 3, name: bi('Ning Zhou', '周宁'), relation: bi('Spouse', '配偶'), age: 57, risk: 'stable', condition: bi('Post-discharge recovery', '出院后康复随访'), stocks: [22, 20] }
    ].map((person, patientIndex) => ({ ...person,
      records: Array.from({ length: 30 }, (_, i) => { const date = new Date(now); date.setDate(date.getDate() - 29 + i); return { id: i + 1, date: day(date), time: '08:00', systolic: 121 + (i * 7 + patientIndex * 5) % 18, diastolic: 72 + (i * 3 + patientIndex) % 11 }; }),
      tasks: [
        { id: patientIndex * 10 + 1, time: '08:00', title: bi('Morning medication', '晨间用药'), detail: bi('Demo Medication A · 1 tablet', '演示药品 A · 1 片'), type: 'medication', done: true },
        { id: patientIndex * 10 + 2, time: '14:00', title: bi('Afternoon medication', '午后用药'), detail: bi('Demo Medication B · 1 tablet', '演示药品 B · 1 片'), type: 'medication', done: false },
        { id: patientIndex * 10 + 3, time: '19:00', title: bi('Evening blood pressure', '晚间血压'), detail: bi('Record and share with the care team', '记录后同步给照护团队'), type: 'vital', done: false },
        { id: patientIndex * 10 + 4, time: '20:00', title: bi('Prepare follow-up questions', '准备复诊问题'), detail: bi('Bring recent reports and medication list', '携带近期报告和用药清单'), type: 'care', done: false }
      ]
    }));
    return {
      role: 'doctor', patientId: 1, patients,
      carePlanSequence: 1000, carePlans: [], carePlanRevisions: [], carePlanActions: [], carePlanEvents: [],
      nurseAssignments: [{ patientId: 1, nurseUserId: 4, assignedBy: 5, startsAt: `${now.toISOString().slice(0,10)}T00:00:00Z`, expiresAt: null, status: 'ACTIVE' }],
      carePlanGrants: [{ patientId: 1, role: 'family', permission: 'PROXY', status: 'ACTIVE', expiresAt: null }, { patientId: 1, role: 'nurse', permission: 'WRITE', status: 'ACTIVE', expiresAt: null }],
      branding: { platformName: bi('Chengxin Health', '澄心健康'), organizationName: bi('Chengxin Health', '澄心健康'), logo: 'assets/logo.svg', pageBackground: '#f7faf8', ownershipText: bi('© 2026 Chengxin Health. All rights reserved.', '© 2026 澄心健康 版权所有') },
      reviews: [
        { id: 101, patientId: 1, kind: 'record', title: bi('Imported metabolic panel', '导入的生化检验报告'), date: day(now), confidence: 96, status: 'pending' },
        { id: 102, patientId: 2, kind: 'ai', title: bi('AI-assisted risk summary', 'AI 辅助风险摘要'), date: day(now), confidence: 88, status: 'pending' },
        { id: 103, patientId: 1, kind: 'record', title: bi('Discharge medication list', '出院用药清单'), date: day(now), confidence: 91, status: 'pending' }
      ],
      plans: [
        { id: 201, patientId: 1, title: bi('Seven-day blood-pressure review', '七日血压复核计划'), owner: bi('Dr. Sarah Chen', '陈医生'), target: day(new Date(now.getTime() + 7 * 86400000)), status: 'active' },
        { id: 202, patientId: 2, title: bi('Nutrition and glucose follow-up', '营养与血糖随访'), owner: bi('Dr. Sarah Chen', '陈医生'), target: day(new Date(now.getTime() + 14 * 86400000)), status: 'active' }
      ],
      notes: [{ id: 301, patientId: 1, author: bi('Dr. Sarah Chen', '陈医生'), text: bi('Monitor morning readings; contact the clinic if symptoms change.', '继续观察晨间读数，如症状变化请联系门诊。'), visibility: 'patient', date: day(now) }],
      handovers: [{ id: 401, patientId: 1, author: bi('Wei Zhang', '张伟'), text: bi('Friday reports are in the shared record folder.', '周五复诊材料已放入共享档案。'), time: '09:20' }],
      alerts: [{ id: 501, patientId: 1, level: 'critical', title: bi('Blood pressure needs clinician review', '血压读数需要医生复核'), status: 'open' }],
      appointments: [{ id: 601, patientId: 1, date: day(new Date(now.getTime() + 3 * 86400000)), time: '09:00', clinic: bi('Nephrology follow-up', '肾内科复诊') }],
      consultations: [{ id: 701, patientId: 1, status: 'open', mode: 'TEXT', symptom: bi('Dizziness after morning medication', '晨间服药后头晕'), participants: [bi('Dr. Sarah Chen', '陈医生'), bi('Aihua Zhang', '张爱华'), bi('Wei Zhang', '张伟')], messages: [
        { id: 1, sender: 'patient', name: bi('Aihua Zhang', '张爱华'), text: bi('I felt dizzy for about ten minutes after breakfast.', '早餐后头晕了大约十分钟。'), time: '09:06' },
        { id: 2, sender: 'family', name: bi('Wei Zhang', '张伟'), text: bi('Her blood pressure was 108/68 and she is resting now.', '她的血压是 108/68，现在正在休息。'), time: '09:08' },
        { id: 3, sender: 'doctor', name: bi('Dr. Sarah Chen', '陈医生'), text: bi('Thank you. Please recheck in 20 minutes and tell me whether the dizziness returns.', '收到。请 20 分钟后复测血压，并告诉我是否再次头晕。'), time: '09:09' }
      ]}],
      featureRuns: {},
      audit: [
        { time: clock(now), actor: bi('Dr. Sarah Chen', '陈医生'), action: bi('Reviewed imported medical record', '复核导入的医疗记录'), result: 'success' },
        { time: '08:42', actor: bi('System automation', '系统自动化'), action: bi('Generated health alert candidates', '生成健康预警候选项'), result: 'success' }
      ],
      users: [
        { id: 1, name: bi('Dr. Sarah Chen', '陈医生'), role: bi('Doctor', '医生'), active: true },
        { id: 2, name: bi('Aihua Zhang', '张爱华'), role: bi('Patient', '患者'), active: true },
        { id: 3, name: bi('Wei Zhang', '张伟'), role: bi('Family caregiver', '家属照护者'), active: true },
        { id: 4, name: bi('Nurse Lin', '林护士'), role: bi('Nurse', '护理人员'), active: true }
      ]
    };
  }
  const current = state => state.patients.find(item => item.id === Number(state.patientId));
  function recordAudit(state, action, targetType, targetId, patientId=state.patientId, now=new Date()) {
    const patient = state.patients.find(item => item.id === Number(patientId));
    const actors = { doctor: bi('Dr. Sarah Chen','陈医生'), patient: current(state).name, family: bi('Wei Zhang','张伟'), nurse: bi('Nurse Lin','林护士'), admin: bi('Platform administrator','平台管理员') };
    state.audit.unshift({ time: clock(now), actor: actors[state.role],
      action: bi(`${action.en}${patient ? ' · '+patient.name.en : ''}`, `${action.zh}${patient ? ' · '+patient.name.zh : ''}`),
      patientId: Number(patientId), targetType, targetId, result: 'success' });
  }
  function complete(state, id) { const task = current(state).tasks.find(item => item.id === Number(id)); if (!task || task.done) return false; task.done = true; if (task.type === 'medication') { const index = task.detail.en.includes(' B ') ? 1 : 0; current(state).stocks[index] = Math.max(0, current(state).stocks[index] - 1); } recordAudit(state,bi('Completed care task','完成照护任务'),'task',task.id); return true; }
  function addVital(state, systolic, diastolic, now = new Date()) { if (!Number.isInteger(systolic) || !Number.isInteger(diastolic) || systolic < 50 || systolic > 260 || diastolic < 30 || diastolic > 160 || systolic <= diastolic) throw new Error('invalid-vital'); const patient = current(state); patient.records.push({ id: Date.now(), date: day(now), time: clock(now), systolic, diastolic }); patient.tasks.filter(t => t.type === 'vital').forEach(t => { t.done = true; }); recordAudit(state,bi('Recorded blood pressure','记录血压'),'vital',patient.records.at(-1).id,patient.id,now); }
  function review(state, id, decision) { const item = state.reviews.find(row => row.id === Number(id)); if (!item || item.status !== 'pending' || !['approved', 'rejected'].includes(decision)) return false; item.status = decision; recordAudit(state,decision==='approved'?bi('Approved clinical item','通过临床复核'):bi('Rejected clinical item','驳回临床项目'),'review',item.id,item.patientId); return true; }
  function addPlan(state, title, target) { const clean = String(title || '').trim(); if (!clean || !target) throw new Error('invalid-plan'); state.plans.unshift({ id: Date.now(), patientId: state.patientId, title: bi(clean, clean), owner: bi('Dr. Sarah Chen', '陈医生'), target, status: 'active' }); recordAudit(state,bi('Created care plan','创建照护计划'),'plan',state.plans[0].id); }
  function addHandover(state, text) { const clean = String(text || '').trim(); if (!clean) throw new Error('invalid-handover'); state.handovers.unshift({ id: Date.now(), patientId: state.patientId, author: bi('Demo family member', '演示家属'), text: bi(clean, clean), time: clock(new Date()) }); recordAudit(state,bi('Added family handover','新增家庭交接'),'handover',state.handovers[0].id); }
  function runFeature(state, key) { const clean=String(key||'').trim(); if(!clean) return false; state.featureRuns[clean]=(state.featureRuns[clean]||0)+1; state.audit.unshift({time:clock(new Date()),actor:bi('Demo operator','演示操作者'),action:bi(`Ran ${clean}`,`执行 ${clean}`),result:'success'}); return true; }
  function toggleUser(state, id) { const user=state.users.find(item=>item.id===Number(id)); if(!user)return false; user.active=!user.active; state.audit.unshift({time:clock(new Date()),actor:bi('Platform administrator','平台管理员'),action:bi(`${user.active?'Enabled':'Disabled'} ${user.name.en}`,`${user.active?'启用':'停用'} ${user.name.zh}`),result:'success'}); return true; }
  function updateBranding(state, input) { const color=String(input.pageBackground||'').trim(), name=String(input.platformName||'').trim(), org=String(input.organizationName||'').trim(), owner=String(input.ownershipText||'').trim(), logo=String(input.logo||'').trim(); if(!name||name.length>80||org.length>120||!/^#[0-9a-f]{6}$/i.test(color)||!owner||owner.length>240||!(/^(assets\/|data:image\/(png|jpeg|webp|svg\+xml);base64,)/i.test(logo))) throw new Error('invalid-branding'); state.branding={platformName:bi(name,name),organizationName:bi(org,org),logo,pageBackground:color.toLowerCase(),ownershipText:bi(owner,owner)}; return true; }
  function currentConsultation(state, patientId = state.patientId) { return state.consultations.find(item => item.patientId === Number(patientId)); }
  function sendChatMessage(state, text, role = state.role, now = new Date()) { const chat=currentConsultation(state); if(!chat)throw new Error('no-consultation'); const clean=String(text||'').trim(); if(!clean)throw new Error('empty-message'); const names={doctor:bi('Dr. Sarah Chen','陈医生'),patient:current(state).name,family:bi('Wei Zhang','张伟'),admin:bi('Platform administrator','平台管理员')}; chat.messages.push({id:Date.now(),sender:role,name:names[role]||names.patient,text:bi(clean,clean),time:clock(now)}); state.audit.unshift({time:clock(now),actor:names[role]||names.patient,action:bi('Sent consultation message','发送问诊消息'),result:'success'}); return chat.messages.at(-1); }
  function addDemoReply(state, language = 'en', now = new Date(), patientId = state.patientId) { const chat=currentConsultation(state,patientId); if(!chat)throw new Error('no-consultation'); const text=language==='zh'?'已收到这条演示消息。请继续观察症状；如明显加重，请立即联系急救服务。':'I received this demo message. Keep observing the symptom; if it becomes severe, contact emergency services immediately.'; chat.messages.push({id:Date.now()+1,sender:'doctor',name:bi('Dr. Sarah Chen','陈医生'),text:bi(text,text),time:clock(now)}); return chat.messages.at(-1); }
  function csv(state, language = 'en') { const zh = language === 'zh'; const head = zh ? '日期,时间,收缩压 (mmHg),舒张压 (mmHg),数据来源' : 'Date,Time,Systolic (mmHg),Diastolic (mmHg),Data source'; const source = zh ? '虚构演示数据' : 'Fictional demo data'; return '\uFEFF' + head + '\r\n' + current(state).records.map(r => `${r.date},${r.time},${r.systolic},${r.diastolic},${source}`).join('\r\n'); }
  // Synthetic collaboration only. Role switching illustrates permissions; it is not authentication.
  const careActor = state => ({ id: { doctor: 1, patient: 2, family: 3, nurse: 4, admin: 5 }[state.role], role: state.role,
    name: { doctor: bi('Dr. Sarah Chen','陈医生'), patient: current(state).name, family: bi('Wei Zhang','张伟'), nurse: bi('Nurse Lin','林护士'), admin: bi('Platform administrator','平台管理员') }[state.role] });
  const nextCareId = state => ++state.carePlanSequence;
  const textWithin = (value, max) => typeof value === 'string' && [...value.trim()].length > 0 && [...value.trim()].length <= max;
  function careInstant(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)) return null;
    const [year,month,date,hour,minute,second] = value.match(/\d+/g).slice(0,6).map(Number);
    const calendar = new Date(Date.UTC(year,month-1,date));
    if (calendar.getUTCFullYear() !== year || calendar.getUTCMonth() !== month-1 || calendar.getUTCDate() !== date || hour > 23 || minute > 59 || second > 59) return null;
    const parsed = new Date(value); return Number.isFinite(parsed.getTime()) && parsed.getUTCFullYear() >= 1000 && parsed.getUTCFullYear() <= 9999 ? parsed.toISOString() : null;
  }
  function careAccess(state, patientId, write = false, now = new Date()) {
    if (Number(patientId) !== state.patientId || !current(state) || !state.users.find(item => item.id === careActor(state).id)?.active) return false;
    if (state.role === 'doctor' || state.role === 'patient') return true;
    if (!['family','nurse'].includes(state.role)) return false;
    const valid = item => item.patientId === Number(patientId) && item.status === 'ACTIVE' && (!item.startsAt || new Date(item.startsAt) <= now) && (!item.expiresAt || new Date(item.expiresAt) > now);
    const grant = state.carePlanGrants.find(item => item.role === state.role && valid(item));
    if (!grant || (write && !['WRITE','PROXY'].includes(grant.permission))) return false;
    return state.role !== 'nurse' || state.nurseAssignments.some(item => item.nurseUserId === 4 && valid(item));
  }
  function requireCarePlan(state, planId, clinical = false, write = false, now = new Date()) {
    const plan = state.carePlans.find(item => item.id === Number(planId));
    if (!plan || !careAccess(state, plan.patientId, write, now) || (clinical && state.role !== 'doctor') || (plan.lifecycle === 'DRAFT' && state.role !== 'doctor')) throw new Error('care-plan-access');
    return plan;
  }
  function requireCareAction(state, actionId, clinical, write, now) {
    const action = state.carePlanActions.find(item => item.id === Number(actionId));
    if (!action) throw new Error('care-plan-access');
    const plan = requireCarePlan(state, action.planId, clinical, write, now);
    if (plan.lifecycle !== 'ACTIVE' || action.revisionId !== plan.currentRevisionId) throw new Error('care-plan-state');
    return { action, plan };
  }
  function careEvent(state, plan, eventType, action, payload = {}, now = new Date(), revisionId = plan.currentRevisionId || plan.draftRevisionId) {
    const actor = careActor(state);
    const event = { id: nextCareId(state), planId: plan.id, patientId: plan.patientId, revisionId, actionId: action?.id || null,
      actorId: actor.id, actorName: { ...actor.name }, actorRole: actor.role, eventType, recordedAt: now.toISOString(), entryMode: '', evidence: [], ...payload };
    state.carePlanEvents.push(event); plan.version++;
    recordAudit(state, bi(`Care plan demo · ${eventType}`, `照护计划演示 · ${eventType}`), 'care-plan', plan.id, plan.patientId, now);
    return event;
  }
  function validateCareDraft(state, input, now) {
    if (!input || !textWithin(input.title,160) || !textWithin(input.instructions,4000) || !['FOLLOW_UP','MEDICATION','DIALYSIS','NUTRITION'].includes(input.planType) || !Array.isArray(input.actions) || input.actions.length < 1 || input.actions.length > 50) throw new Error('invalid-care-plan');
    const actions = input.actions.map((item,index) => {
      const dueAt = careInstant(item.dueAt);
      if ((item.evidence && item.evidence.length) || item.ordinal !== index+1 || !textWithin(item.instruction,2000) || !dueAt || ![2,3,4].includes(item.assignedUserId)) throw new Error('invalid-care-plan');
      if (item.assignedUserId !== 2) {
        const role = item.assignedUserId === 3 ? 'family' : 'nurse';
        if (!careAccess({ ...state, role }, state.patientId, true, now)) throw new Error('invalid-care-plan');
      }
      return { ordinal: index+1, instruction: bi(item.instruction.trim(),item.instruction.trim()), dueAt, assignedUserId: item.assignedUserId, evidence: [] };
    });
    return { title: bi(input.title.trim(),input.title.trim()), instructions: bi(input.instructions.trim(),input.instructions.trim()), planType: input.planType, actions };
  }
  function createCarePlanDraft(state, input, now = new Date()) {
    if (state.role !== 'doctor' || !careAccess(state,state.patientId,false,now)) throw new Error('care-plan-access');
    const content = validateCareDraft(state,input,now), plan = { id: nextCareId(state), patientId: state.patientId, workflowVersion: 1, lifecycle: 'DRAFT', currentRevisionId: null, draftRevisionId: nextCareId(state), version: 0 };
    const revision = { id: plan.draftRevisionId, planId: plan.id, revisionNo: 1, status: 'DRAFT', ...content };
    state.carePlans.unshift(plan); state.carePlanRevisions.push(revision);
    careEvent(state,plan,'DRAFT_SAVED',null,{},now); return plan;
  }
  function saveCarePlanDraft(state, planId, input, now = new Date()) {
    const plan = requireCarePlan(state,planId,true,false,now);
    if (!plan.draftRevisionId || !['DRAFT','ACTIVE'].includes(plan.lifecycle)) throw new Error('care-plan-state');
    const content = validateCareDraft(state,input,now), revision = state.carePlanRevisions.find(item => item.id === plan.draftRevisionId);
    Object.assign(revision,content); careEvent(state,plan,'DRAFT_SAVED',null,{},now,revision.id); return plan;
  }
  function createCarePlanRevision(state, planId, now = new Date()) {
    const plan = requireCarePlan(state,planId,true,false,now);
    if (plan.lifecycle !== 'ACTIVE' || plan.draftRevisionId) throw new Error('care-plan-state');
    const previous = state.carePlanRevisions.find(item => item.id === plan.currentRevisionId);
    const revision = JSON.parse(JSON.stringify(previous)); revision.id = nextCareId(state); revision.revisionNo++; revision.status = 'DRAFT'; delete revision.publishedAt;
    plan.draftRevisionId = revision.id; state.carePlanRevisions.push(revision);
    careEvent(state,plan,'REVISION_DRAFTED',null,{},now,revision.id); return revision;
  }
  function publishCarePlan(state, planId, confirmation, now = new Date()) {
    const plan = requireCarePlan(state,planId,true,false,now);
    if (!plan.draftRevisionId || !['DRAFT','ACTIVE'].includes(plan.lifecycle)) throw new Error('care-plan-state');
    const revision = state.carePlanRevisions.find(item => item.id === plan.draftRevisionId);
    validateCareDraft(state, { ...revision, title: revision.title.en, instructions: revision.instructions.en, actions: revision.actions.map(item => ({ ...item, instruction: item.instruction.en })) }, now);
    const prior = state.carePlanActions.filter(item => item.planId === plan.id && item.revisionId === plan.currentRevisionId && ['OPEN','NEEDS_HELP','SUBMITTED'].includes(item.status));
    if (plan.currentRevisionId && (!confirmation || confirmation.currentRevisionId !== plan.currentRevisionId || JSON.stringify([...confirmation.supersededActionIds || []].sort()) !== JSON.stringify(prior.map(item => item.id).sort()))) throw new Error('revision-confirmation');
    prior.forEach(item => { item.status = 'SUPERSEDED'; item.version++; });
    revision.status = 'PUBLISHED'; revision.publishedAt = now.toISOString();
    const revising = Boolean(plan.currentRevisionId); plan.currentRevisionId = revision.id; plan.draftRevisionId = null; plan.lifecycle = 'ACTIVE';
    revision.actions.forEach(item => state.carePlanActions.push({ ...JSON.parse(JSON.stringify(item)), id: nextCareId(state), planId: plan.id, revisionId: revision.id, patientId: plan.patientId, version: 0, status: 'OPEN', firstSubmittedAt: null, latestSubmittedAt: null, reviewWaitingSince: null }));
    return careEvent(state,plan,revising ? 'REVISED' : 'PUBLISHED',null,{ notificationResult: 'DEMO_ONLY' },now);
  }
  function submitCarePlanAction(state, actionId, input, now = new Date()) {
    const { action,plan } = requireCareAction(state,actionId,false,true,now);
    if (!['patient','family','nurse'].includes(state.role)) throw new Error('care-plan-access');
    if (!['OPEN','NEEDS_HELP'].includes(action.status)) throw new Error('care-plan-state');
    const occurredAt = careInstant(input?.occurredAt);
    if (!textWithin(input?.note,2000) || !occurredAt || new Date(occurredAt) > now || (input.evidence && input.evidence.length) || !['SELF','ASSISTED'].includes(input.entryMode || 'ASSISTED')) throw new Error('invalid-receipt');
    action.status = 'SUBMITTED'; action.version++; action.firstSubmittedAt ||= now.toISOString(); action.latestSubmittedAt = now.toISOString(); action.reviewWaitingSince = now.toISOString();
    return careEvent(state,plan,'SUBMITTED',action,{ note: input.note.trim(), occurredAt, entryMode: state.role === 'patient' ? (input.entryMode || 'ASSISTED') : 'ASSISTED' },now);
  }
  function helpCarePlanAction(state, actionId, note, now = new Date()) {
    const { action,plan } = requireCareAction(state,actionId,false,true,now);
    if (!['patient','family','nurse'].includes(state.role)) throw new Error('care-plan-access');
    if (!['OPEN','NEEDS_HELP'].includes(action.status)) throw new Error('care-plan-state');
    if (!textWithin(note,1000)) throw new Error('invalid-care-note');
    action.status = 'NEEDS_HELP'; action.version++; return careEvent(state,plan,'NEEDS_HELP',action,{ note: note.trim() },now);
  }
  function followUpCarePlanAction(state, actionId, input, now = new Date()) {
    const { action,plan } = requireCareAction(state,actionId,false,true,now);
    if (!['doctor','nurse'].includes(state.role)) throw new Error('care-plan-access');
    if (!['OPEN','NEEDS_HELP','SUBMITTED'].includes(action.status)) throw new Error('care-plan-state');
    if (!['CONTACTED','AWAITING_INFORMATION','DOCTOR_NOTIFIED'].includes(input?.kind) || !textWithin(input.note,1000)) throw new Error('invalid-care-note');
    return careEvent(state,plan,'FOLLOW_UP',action,{ kind: input.kind, note: input.note.trim() },now);
  }
  function reviewCarePlanAction(state, actionId, input, now = new Date()) {
    const { action,plan } = requireCareAction(state,actionId,true,false,now);
    if (action.status !== 'SUBMITTED') throw new Error('care-plan-state');
    if (!['CONFIRM','RETURN'].includes(input?.decision) || (input.decision === 'RETURN' && !textWithin(input.note,1000)) || (input.note && !textWithin(input.note,1000))) throw new Error('invalid-care-note');
    action.status = input.decision === 'CONFIRM' ? 'CONFIRMED' : 'OPEN'; action.version++; action.reviewWaitingSince = null;
    return careEvent(state,plan,input.decision === 'CONFIRM' ? 'CONFIRMED' : 'RETURNED',action,{ note: input.note?.trim() || '' },now);
  }
  function closeCarePlan(state, planId, now = new Date()) {
    const plan = requireCarePlan(state,planId,true,false,now);
    if (plan.lifecycle !== 'ACTIVE') throw new Error('care-plan-state');
    const actions = state.carePlanActions.filter(item => item.revisionId === plan.currentRevisionId);
    if (!actions.length || actions.some(item => item.status !== 'CONFIRMED')) throw new Error('unconfirmed-care-plan');
    if (plan.draftRevisionId) throw new Error('care-plan-state');
    plan.lifecycle = 'COMPLETED'; return careEvent(state,plan,'CLOSED',null,{},now);
  }
  function cancelCarePlan(state, planId, note, now = new Date()) {
    const plan = requireCarePlan(state,planId,true,false,now);
    if (!['DRAFT','ACTIVE'].includes(plan.lifecycle) || !textWithin(note,1000)) throw new Error('invalid-care-note');
    plan.lifecycle = 'CANCELLED';
    state.carePlanActions.filter(item => item.planId === plan.id && ['OPEN','NEEDS_HELP','SUBMITTED'].includes(item.status)).forEach(item => { item.status = 'CANCELLED'; item.version++; });
    return careEvent(state,plan,'CANCELLED',null,{ note: note.trim() },now);
  }
  function visibleCarePlans(state, now = new Date()) {
    if (!careAccess(state,state.patientId,false,now)) return [];
    return state.carePlans.filter(plan => plan.patientId === state.patientId && (state.role === 'doctor' || plan.currentRevisionId)).map(plan => {
      const revisions = state.carePlanRevisions.filter(item => item.planId === plan.id && (state.role === 'doctor' || item.status === 'PUBLISHED'));
      const revision = revisions.find(item => item.id === plan.currentRevisionId) || revisions.find(item => item.id === plan.draftRevisionId);
      const events = state.carePlanEvents.filter(item => item.planId === plan.id && revisions.some(rev => rev.id === item.revisionId));
      const actions = state.carePlanActions.filter(item => item.planId === plan.id).map(item => ({ ...item, overdue: new Date(item.dueAt) < now && ['OPEN','NEEDS_HELP'].includes(item.status), events: events.filter(event => event.actionId === item.id) }));
      const view = { ...plan, ...revision, id: plan.id, currentRevisionId: plan.currentRevisionId, title: revision.title, revisions, actions, events, canRecord: careAccess(state,plan.patientId,true,now) };
      if (state.role !== 'doctor') delete view.draftRevisionId;
      return JSON.parse(JSON.stringify(view));
    });
  }

  globalThis.HealthDemo = { createState, current, complete, addVital, review, addPlan, addHandover, runFeature, toggleUser, updateBranding, currentConsultation, sendChatMessage, addDemoReply, csv, careAccess, createCarePlanDraft, saveCarePlanDraft, createCarePlanRevision, publishCarePlan, submitCarePlanAction, helpCarePlanAction, followUpCarePlanAction, reviewCarePlanAction, closeCarePlan, cancelCarePlan, visibleCarePlans };
})();
