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
      featureRuns: {},
      audit: [
        { time: clock(now), actor: bi('Dr. Sarah Chen', '陈医生'), action: bi('Reviewed imported medical record', '复核导入的医疗记录'), result: 'success' },
        { time: '08:42', actor: bi('System automation', '系统自动化'), action: bi('Generated health alert candidates', '生成健康预警候选项'), result: 'success' }
      ],
      users: [
        { id: 1, name: bi('Dr. Sarah Chen', '陈医生'), role: bi('Doctor', '医生'), active: true },
        { id: 2, name: bi('Aihua Zhang', '张爱华'), role: bi('Patient', '患者'), active: true },
        { id: 3, name: bi('Wei Zhang', '张伟'), role: bi('Family caregiver', '家属照护者'), active: true }
      ]
    };
  }
  const current = state => state.patients.find(item => item.id === Number(state.patientId));
  function complete(state, id) { const task = current(state).tasks.find(item => item.id === Number(id)); if (!task || task.done) return false; task.done = true; if (task.type === 'medication') { const index = task.detail.en.includes(' B ') ? 1 : 0; current(state).stocks[index] = Math.max(0, current(state).stocks[index] - 1); } return true; }
  function addVital(state, systolic, diastolic, now = new Date()) { if (!Number.isInteger(systolic) || !Number.isInteger(diastolic) || systolic < 50 || systolic > 260 || diastolic < 30 || diastolic > 160 || systolic <= diastolic) throw new Error('invalid-vital'); const patient = current(state); patient.records.push({ id: Date.now(), date: day(now), time: clock(now), systolic, diastolic }); patient.tasks.filter(t => t.type === 'vital').forEach(t => { t.done = true; }); }
  function review(state, id, decision) { const item = state.reviews.find(row => row.id === Number(id)); if (!item || item.status !== 'pending' || !['approved', 'rejected'].includes(decision)) return false; item.status = decision; return true; }
  function addPlan(state, title, target) { const clean = String(title || '').trim(); if (!clean || !target) throw new Error('invalid-plan'); state.plans.unshift({ id: Date.now(), patientId: state.patientId, title: bi(clean, clean), owner: bi('Dr. Sarah Chen', '陈医生'), target, status: 'active' }); }
  function addHandover(state, text) { const clean = String(text || '').trim(); if (!clean) throw new Error('invalid-handover'); state.handovers.unshift({ id: Date.now(), patientId: state.patientId, author: bi('Demo family member', '演示家属'), text: bi(clean, clean), time: clock(new Date()) }); }
  function runFeature(state, key) { const clean=String(key||'').trim(); if(!clean) return false; state.featureRuns[clean]=(state.featureRuns[clean]||0)+1; state.audit.unshift({time:clock(new Date()),actor:bi('Demo operator','演示操作者'),action:bi(`Ran ${clean}`,`执行 ${clean}`),result:'success'}); return true; }
  function toggleUser(state, id) { const user=state.users.find(item=>item.id===Number(id)); if(!user)return false; user.active=!user.active; state.audit.unshift({time:clock(new Date()),actor:bi('Platform administrator','平台管理员'),action:bi(`${user.active?'Enabled':'Disabled'} ${user.name.en}`,`${user.active?'启用':'停用'} ${user.name.zh}`),result:'success'}); return true; }
  function updateBranding(state, input) { const color=String(input.pageBackground||'').trim(), name=String(input.platformName||'').trim(), org=String(input.organizationName||'').trim(), owner=String(input.ownershipText||'').trim(), logo=String(input.logo||'').trim(); if(!name||name.length>80||org.length>120||!/^#[0-9a-f]{6}$/i.test(color)||!owner||owner.length>240||!(/^(assets\/|data:image\/(png|jpeg|webp|svg\+xml);base64,)/i.test(logo))) throw new Error('invalid-branding'); state.branding={platformName:bi(name,name),organizationName:bi(org,org),logo,pageBackground:color.toLowerCase(),ownershipText:bi(owner,owner)}; return true; }
  function csv(state, language = 'en') { const zh = language === 'zh'; const head = zh ? '日期,时间,收缩压 (mmHg),舒张压 (mmHg),数据来源' : 'Date,Time,Systolic (mmHg),Diastolic (mmHg),Data source'; const source = zh ? '虚构演示数据' : 'Fictional demo data'; return '\uFEFF' + head + '\r\n' + current(state).records.map(r => `${r.date},${r.time},${r.systolic},${r.diastolic},${source}`).join('\r\n'); }
  globalThis.HealthDemo = { createState, current, complete, addVital, review, addPlan, addHandover, runFeature, toggleUser, updateBranding, csv };
})();
