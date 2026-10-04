// Independent acceptance facts from the synthetic SQL fixture, never a preview/renderer output.
import assert from 'node:assert/strict'
const refs=Object.freeze(['/care-journey?tab=measurements&patientId=9101&measurementId=19101','/medical-record?tab=list&patientId=9101&recordId=19102'])
const instructions=Object.freeze(['Synthetic 护理原文 <script>synthetic-report</script>','Synthetic difficulty outside selected activity dates','Synthetic submitted before deadline, waiting for review','Synthetic doctor reviewed action'])
const states=Object.freeze(['OPEN','NEEDS_HELP','SUBMITTED','CONFIRMED'])
const notes=Object.freeze({19102:'Synthetic old difficulty stays visible',19103:'Synthetic old administrative contact',19104:'Synthetic initial receipt',19105:'Synthetic supplement required',19106:'Synthetic first submitted before deadline',19107:'Synthetic clarify then resubmit',19108:"'\t=SUM(1,2)\r\nSynthetic \"quoted\", text",19109:'Synthetic nurse assisted receipt',19110:'Synthetic doctor review is not efficacy proof'})
const events=Object.freeze([
  [19102,19102,'HELP_REQUESTED',9002,'FAMILY','AUTHORIZED_FAMILY','ASSISTED','NEEDS_HELP'],
  [19103,19102,'FOLLOW_UP_RECORDED',9004,'NURSE','ASSIGNED_NURSE','',''],
  [19104,19101,'RECEIPT_SUBMITTED',9001,'OWNER','SELF','SELF','SUBMITTED'],
  [19105,19101,'RECEIPT_RETURNED',9003,'DOCTOR','ASSIGNED_DOCTOR','','OPEN'],
  [19106,19103,'RECEIPT_SUBMITTED',9001,'OWNER','SELF','SELF','SUBMITTED'],
  [19107,19103,'RECEIPT_RETURNED',9003,'DOCTOR','ASSIGNED_DOCTOR','','OPEN'],
  [19108,19103,'RECEIPT_SUBMITTED',9002,'FAMILY','AUTHORIZED_FAMILY','ASSISTED','SUBMITTED'],
  [19109,19104,'RECEIPT_SUBMITTED',9004,'NURSE','ASSIGNED_NURSE','ASSISTED','SUBMITTED'],
  [19110,19104,'RECEIPT_CONFIRMED',9003,'DOCTOR','ASSIGNED_DOCTOR','','CONFIRMED'],
])
const names=Object.freeze({9001:'Synthetic Personal',9002:'Synthetic historical Family',9003:'Synthetic Doctor',9004:'Synthetic historical Nurse'})
export function fixtureReportExpectation({format,language,narrow=false,patientId=9101}) {
  assert.ok(['html','pdf','actions_csv','events_csv'].includes(format)&&['en','zh-CN'].includes(language)&&[9101,9102,9103].includes(patientId),'Invalid bounded fixture expectation')
  const zh=language==='zh-CN',t=(en,cn)=>zh?cn:en
  const stateLabel=code=>({OPEN:t('To do or supplement','待执行或补充'),NEEDS_HELP:t('Needs help','遇到困难'),SUBMITTED:t('Submitted awaiting doctor review','已提交待医生复核'),CONFIRMED:t('Reviewed by doctor','医生已复核')})[code]
  const csvExcludes=['Synthetic question outside activity period','Synthetic answered question','Synthetic cancelled question','Synthetic unknown question status',...(narrow?['/care-journey?tab=measurements','/medical-record?tab=list']:[])]
  const common={patientId,planId:null,timeZone:'UTC',evidenceRestricted:narrow}
  if(patientId!==9101){assert.ok(format.endsWith('_csv'),'Empty fixture file oracle is CSV-only');return {...common,rows:0,recordKey:format==='actions_csv'?'action':'event.id',records:[]}}
  const evidence=narrow?`${t('Restricted evidence exists','存在受限证据')}\n${t('Restricted evidence exists','存在受限证据')}`:`MEASUREMENT | ${t('Measurement','测量')} | 19101 | WEIGHT | ${refs[0]}\nMEDICAL_RECORD | ${t('Medical record','病历')} | 19102 | OTHER | ${refs[1]}`
  const eventRecord=(id,prefix)=>{
    if(id==null)return Object.fromEntries(['id','typeCode','actor','name','roleCode','relationCode','modeCode','kindCode','note','evidence'].map(key=>[prefix+key,'']))
    const [eventId,,type,actor,role,relation,mode]=events.find(row=>row[0]===id)
    return {[prefix+'id']:String(eventId),[prefix+'typeCode']:type,[prefix+'actor']:String(actor),[prefix+'name']:names[actor],[prefix+'roleCode']:role,[prefix+'relationCode']:relation,[prefix+'modeCode']:mode,[prefix+'kindCode']:id===19103?'CONTACTED':'',[prefix+'note']:notes[id],[prefix+'evidence']:id===19104?evidence:''}
  }
  const source=action=>({scopeLabel:t('All plans','全部计划'),completeLabel:t('Complete for selected scope and authorized sections','所选范围及已授权区域完整'),plan:'19101',revision:'19101',revisionNo:'1',action:String(action),title:'Synthetic report published plan',instructions:'Saved successfully',instruction:instructions[action-19101]})
  if(format==='actions_csv')return {...common,excludes:csvExcludes,rows:4,recordKey:'action',records:states.map((statusCode,index)=>({
    ...source(19101+index),statusCode,statusLabel:stateLabel(statusCode),assignee:'9001',availableCode:'true',overdueCode:index<2?'true':'false',supplementCode:index===0?'true':'false',evidence:index===0?evidence:'',
    ...eventRecord([19104,null,19108,19109][index],'receipt.'),...eventRecord([19105,null,19107,null][index],'return.'),...eventRecord(index===3?19110:null,'review.'),...eventRecord(index===1?19102:null,'help.'),...eventRecord(index===1?19103:null,'follow.'),
  }))}
  if(format==='events_csv')return {...common,excludes:csvExcludes,rows:7,recordKey:'event.id',records:events.filter(row=>row[0]>=19104).map(row=>({...source(row[1]),statusCode:row[7],statusLabel:stateLabel(row[7]),lifecycleCode:'ACTIVE',currentCode:'true',...eventRecord(row[0],'event.')}))}
  return {...common,allowedReferences:narrow?[]:[...refs],evidenceEntries:narrow?[]:[{type:`${t('Measurement','测量')} (MEASUREMENT)`,id:'19101',title:'WEIGHT',count:3},{type:`${t('Medical record','病历')} (MEDICAL_RECORD)`,id:'19102',title:'OTHER',count:3}],contains:[
    'Saved successfully',...instructions,'Synthetic old difficulty stays visible','Synthetic old administrative contact',
    t('Current attention','当前待处理'),t('Current actions and sources','当前事项及来源'),t('Visit questions','就诊问题'),
    t('Submitted actions await doctor review and are not patient-overdue.','已提交事项等待医生复核，不计为患者逾期。'),
    narrow?t('Questions are not authorized; their content and count were not read.','无就诊问题读取权限；未读取问题正文或数量。'):'Synthetic question outside activity period',
  ],excludes:narrow?['Synthetic question outside activity period']:[],fields:[
    [t('Patient','患者'),'Synthetic Report Patient'],[t('Current actions (N)','当前事项（N）'),'4'],
    [t('To do or supplement','待执行或补充'),'1 / 4'],[t('Needs help','遇到困难'),'1 / 4'],
    [t('Submitted awaiting doctor review','已提交待医生复核'),'1 / 4'],[t('Reviewed by doctor','医生已复核'),'1 / 4'],
    [t('Overdue (subset)','逾期（子集）'),'2'],[t('Needs supplement (subset)','待补充（子集）'),'1'],
    [t('Public event count','公开事件数'),'7'],[t('Distinct actions involved','涉及去重事项数'),'3'],
    ...states.map(code=>[t('Current status','当前状态'),`${({OPEN:t('To do or supplement','待执行或补充'),NEEDS_HELP:t('Needs help','遇到困难'),SUBMITTED:t('Submitted awaiting doctor review','已提交待医生复核'),CONFIRMED:t('Reviewed by doctor','医生已复核')})[code]} (${code})`]),
    ...[19101,19102,19103,19104].map(id=>[t('Action ID','事项ID'),String(id)]),
    ...[19104,19105,19106,19107,19108,19109,19110].map(id=>[t('Event ID','事件ID'),String(id)]),
    ...(!narrow?[[t('Evidence type','证据类型'),`${t('Measurement','测量')} (MEASUREMENT)`],[t('Evidence source ID','证据源ID'),'19101'],[t('Evidence title','证据标题'),'WEIGHT'],[t('Evidence type','证据类型'),`${t('Medical record','病历')} (MEDICAL_RECORD)`],[t('Evidence source ID','证据源ID'),'19102'],[t('Evidence title','证据标题'),'OTHER']]:[]),
  ]}
}

export function filenameGenerationSecond(filename,{format,language}) {
  const kinds={html:'html.html',pdf:'pdf.pdf',actions_csv:'actions.csv',events_csv:'events.csv'}
  assert.ok(Object.hasOwn(kinds,format)&&['en','zh-CN'].includes(language),'Invalid report filename contract')
  assert.ok(typeof filename==='string'&&filename.length<=100,'Invalid report filename')
  const match=filename.match(/^care-execution-report-(en|zh-CN)-(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z-(html\.html|pdf\.pdf|actions\.csv|events\.csv)$/)
  assert.ok(match&&match[1]===language&&match[8]===kinds[format],'Invalid report filename')
  const stamp=`${match[2]}-${match[3]}-${match[4]}T${match[5]}:${match[6]}:${match[7]}Z`
  assert.equal(new Date(stamp).toISOString(),stamp.replace('Z','.000Z'),'Invalid report filename calendar time')
  return stamp
}
export function assertDownloadClickMetadata(observation,{request,format,filename,rowCount}) {
  assert.ok(observation&&observation.connected===true&&observation.fileName===filename,'Metadata must belong to the actual pre-click file')
  assert.ok(Array.isArray(observation.paragraphs)&&observation.paragraphs.length<=8&&observation.paragraphs.every(value=>typeof value==='string'&&value.length<=1024),'Bounded pre-click metadata required')
  assert.ok(typeof observation.text==='string'&&observation.text.length<=8192,'Bounded pre-click text required')
  assert.ok(Number.isSafeInteger(request.patientId)&&request.patientId>0&&request.timeZone==='UTC','Known fixture scope/timezone required')
  const generatedSecond=filenameGenerationSecond(filename,{format,language:request.language}),zh=request.language==='zh-CN'
  const flat=value=>value.replace(/\s+/g,' ').trim(),paragraphs=observation.paragraphs.map(flat)
  for(const key of ['fromDate','toDate'])assert.ok(/^\d{4}-\d\d-\d\d$/.test(request[key])&&new Date(request[key]+'T00:00:00Z').toISOString().startsWith(request[key]),'Exact calendar request dates required')
  const scope=request.planId==null?(zh?'全部计划':'All plans'):(zh?'仅此计划':'Only this plan')
  const patient=`${scope} · ${zh?'患者':'Patient'} #${request.patientId}${request.planId==null?'':` · ${zh?'计划':'Plan'} #${request.planId}`}`
  assert.ok(paragraphs.includes(patient),'Pre-click scope does not match the exact request')
  assert.ok(paragraphs.includes(`${request.fromDate} — ${request.toDate} · UTC · ${request.language} · ${zh?'报告schema':'Report schema'} 1`),'Pre-click dates/language/schema do not match the exact request')
  const date=new Intl.DateTimeFormat(request.language,{timeZone:'UTC',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',timeZoneName:'longOffset'}).format(new Date(generatedSecond))
  assert.ok(paragraphs.includes(flat(`${zh?'生成时间':'Generated'}: ${date} (UTC+00:00, UTC)`)),'Pre-click generation time does not match the actual server filename')
  if(rowCount!=null){assert.ok(Number.isSafeInteger(rowCount)&&rowCount>=0&&rowCount<=5000,'Bounded row count required');assert.ok(paragraphs.some(value=>rowCount===0?value.startsWith(zh?'0条 · 仅表头。':'0 rows · header only.'):value===`${rowCount} ${zh?'条':'rows'}`),'Pre-click row count differs')}
  return {generatedSecond}
}
