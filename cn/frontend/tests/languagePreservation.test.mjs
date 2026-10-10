import test from 'node:test'
import assert from 'node:assert/strict'
import {localizePayload,localizeServerText} from '../src/utils/serverText.js'
import {localizeHealthTimelineEntry} from '../src/utils/timelineText.js'

test('Chinese transport preserves all authored data and never mutates the server payload',()=>{
 const data={patient:{name:'Patient',medicalHistory:'Normal'},drugName:'No data',actorName:'Doctor',title:'Care Plan',summary:'Blood Pressure is my note',remark:'Saved successfully',status:'NORMAL',messages:[{content:'Failed to save'}]}
 const original={code:200,msg:'Saved successfully',data},before=structuredClone(original)
 const result=localizePayload(original,'/care-journey/consultations/1')
 assert.equal(result.msg,'保存成功');assert.deepEqual(result.data,before.data);assert.deepEqual(original,before)
 assert.equal(localizePayload('Normal','/unknown'),'Normal')
})
test('Chinese transport localizes authentication presentation without changing identities or custom labels',()=>{
 const original={code:200,data:{user:{username:'Doctor',realName:'Patient'},roles:[{roleCode:'doctor',roleName:'Doctor'},{roleCode:'custom',roleName:'Doctor'}],menus:[{menuCode:'care-journey',menuName:'Care Journey'},{menuCode:'custom',menuName:'Care Journey'}]}}
 const result=localizePayload(original,'/auth/info')
 assert.deepEqual(result.data.user,original.data.user)
 assert.equal(result.data.roles[0].roleName,'医生');assert.equal(result.data.roles[1].roleName,'Doctor')
 assert.equal(result.data.menus[0].menuName,'健康照护全流程');assert.equal(result.data.menus[1].menuName,'Care Journey')
})
test('Chinese clinical presentation preserves care titles, drug evidence and custom interaction advice',()=>{
 const original={code:200,data:{attention:{items:[{type:'CARE',title:'Normal',evidence:'Normal',recommendedAction:'Complete, reassign, or document why it is deferred'},{type:'MEDICATION',title:'Patient · PENDING',evidence:'Normal',recommendedAction:'Confirm taken, snooze, or record the reason'}]},medicationSafety:{allergies:'Normal',findings:[{type:'ALLERGY',title:'Medication matches the recorded allergy list',evidence:'Patient',action:'Do not change treatment in the app; contact the prescribing clinician or pharmacist promptly.'},{type:'INTERACTION',title:'Potential medication interaction',evidence:'Doctor',action:'Normal'}]}}}
 const result=localizePayload(original,'/clinical-workbench/overview').data
 assert.equal(result.attention.items[0].title,'Normal');assert.equal(result.attention.items[0].evidence,'Normal')
 assert.equal(result.attention.items[0].recommendedAction,'完成任务、重新指派或记录延期原因')
 assert.equal(result.attention.items[1].title,'Patient · 待处理');assert.equal(result.attention.items[1].evidence,'Normal')
 assert.equal(result.medicationSafety.allergies,'Normal');assert.equal(result.medicationSafety.findings[0].evidence,'Patient')
 assert.equal(result.medicationSafety.findings[0].title,'药品与已记录的过敏清单匹配')
 assert.equal(result.medicationSafety.findings[1].action,'Normal')
})
for(const sourceType of ['CONSULTATION','VISIT','INTAKE','MEDICATION_LOG','MANUAL'])test(`${sourceType} timeline leaves clinical prose and remarks exact`,()=>{
 const entry={sourceType,title:sourceType==='CONSULTATION'?'Remote consultation':'Normal',summary:'Blood Pressure is the text the patient wrote',remark:'Normal'}
 const result=localizeHealthTimelineEntry(entry)
 assert.equal(result.summary,entry.summary);assert.equal(result.remark,entry.remark)
 if(sourceType==='CONSULTATION')assert.equal(result.title,'远程问诊')
 else assert.equal(result.title,'Normal')
})
test('generated timeline summary localization does not replace arbitrary clinical phrases',()=>{
 const entry={sourceType:'MEASUREMENT',title:'Blood Pressure & Glucoserecord',summary:'Blood Pressure is my note',remark:'Normal'}
 assert.equal(localizeHealthTimelineEntry(entry).summary,entry.summary)
 assert.equal(localizeHealthTimelineEntry(entry).remark,entry.remark)
})

test('custom names on built-in menu and role codes remain exact',()=>{
 const original={code:200,data:{menus:[{menuCode:'workspace',menuName:'Normal'}],roles:[{roleCode:'doctor',roleName:'Patient'}]}}
 const result=localizePayload(original,'/auth/info')
 assert.deepEqual(result.data,original.data)
})

test('unrecognized error text cannot resolve Object prototype members',()=>{
 for(const text of ['constructor','toString','__proto__'])assert.equal(localizeServerText(text),text)
})

test('timeline fixed-title lookup preserves names matching Object prototype keys',()=>{
 for(const title of ['constructor','toString','__proto__'])assert.equal(localizeHealthTimelineEntry({sourceType:'VISIT',title,summary:'Normal'}).title,title)
});

test('AI draft period labels only translate recognizable generated calendar labels',()=>{
 const original={code:200,data:{issues:[{type:'AI_DRAFT',title:'AI analysis draft needs clinical review',detail:'Patient'},{type:'AI_DRAFT',title:'AI analysis draft needs clinical review',detail:'2026-09 Monthly overview'}]}}
 const result=localizePayload(original,'/clinical-workbench/data-quality')
 assert.equal(result.data.issues[0].detail,'Patient')
 assert.equal(result.data.issues[1].detail,'2026-09 月度概览')
})
