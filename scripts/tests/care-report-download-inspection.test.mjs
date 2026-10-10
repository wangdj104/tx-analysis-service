import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, writeFile, readFile, mkdir, rm, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { deflateSync } from 'node:zlib'
let inspector={}
try { inspector=await import('../inspect-care-report-files.mjs') } catch(error) { if(error.code!=='ERR_MODULE_NOT_FOUND')throw error }
const inspect=(...args)=>{assert.equal(typeof inspector.inspectReportFile,'function','Bounded real-format inspection is required');return inspector.inspectReportFile(...args)}
const privateTemp=async(fn)=>{const root=await mkdtemp(join(tmpdir(),'care-report-inspector-test-'));try{return await fn(root)}finally{await rm(root,{recursive:true,force:true})}}
function plainPdf(objects) {
  let pdf='%PDF-1.4\n',offsets=[0];for(let i=0;i<objects.length;i++){offsets.push(Buffer.byteLength(pdf));pdf+=`${i+1} 0 obj\n${objects[i]}\nendobj\n`}
  const xref=Buffer.byteLength(pdf);return pdf+`xref\n0 ${objects.length+1}\n0000000000 65535 f \n`+offsets.slice(1).map(n=>`${String(n).padStart(10,'0')} 00000 n \n`).join('')+`trailer\n<< /Size ${objects.length+1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
}
const html='<!DOCTYPE html><html lang="en"><head><style>td{white-space:pre-wrap}</style></head><body><h1>Care execution report</h1><p>Saved successfully</p><p>Synthetic 护理原文</p><p>&lt;script&gt;synthetic-report&lt;/script&gt;</p></body></html>'
test('logical HTML inspection preserves escaped clinical text and rejects active resources',()=>privateTemp(async root=>{
  const file=join(root,'report.html');await writeFile(file,html)
  await writeFile(file,html.replace('</body>','<a href="/care-journey?tab=measurements&amp;patientId=9101&amp;measurementId=19101">Protected measurement</a><a href="/medical-record?tab=list&amp;patientId=9101&amp;recordId=19102">Protected medical record</a></body>'))
  const result=await inspect(file,{format:'html',language:'en',expected:{contains:['Saved successfully','Synthetic 护理原文','<script>synthetic-report</script>'],excludes:['Unrelated synthetic patient']}})
  assert.ok(result.text.includes('<script>synthetic-report</script>'));assert.equal(result.bytes,(await readFile(file)).length);assert.match(result.sha256,/^[a-f0-9]{64}$/)
  for(const active of ['<script>run()</script>','<img src="https://invalid.example/x">','<a href="file:///etc/passwd">x</a>','<style>@import "https://invalid.example";</style>']){
    await writeFile(file,html.replace('</body>',active+'</body>'));await assert.rejects(inspect(file,{format:'html',language:'en'}),/inspection/)
  }
}))
test('CSV uses the fixed bilingual schema, standard multiline parser and formula-safe logical cells',()=>privateTemp(async root=>{
  assert.equal(typeof inspector.reportCsvSchema,'function','Frozen CSV assertion schema is required')
  for(const format of ['actions_csv','events_csv'])for(const language of ['en','zh-CN']) {
    const schema=inspector.reportCsvSchema(format,language),file=join(root,'report.csv')
    const values=Object.fromEntries(schema.map(({key})=>[key,'']))
    Object.assign(values,{schema:'1',generated:'2026-10-04T21:00:00Z',asOf:'2026-10-04T20:59:59.123456Z',patient:'9101',scopeCode:'ALL_PLANS',from:'2026-09-05',to:'2026-10-04',zone:'UTC',start:'2026-09-05T00:00:00Z',end:'2026-10-05T00:00:00Z',language,source:'CARE_PLAN',completeCode:'COMPLETE',plan:'19101',revision:'19101',revisionNo:'1',action:'19101',statusCode:'OPEN','event.id':'19104','event.typeCode':'RECEIPT_SUBMITTED',instruction:'\'\t=SUM(1,2)\r\nSynthetic "quoted", text'})
    const encode=row=>row.map(v=>'"'+v.replaceAll('"','""')+'"').join(',')+'\r\n'
    await writeFile(file,'\ufeff'+encode(schema.map(s=>s.header))+encode(schema.map(s=>values[s.key])))
    const result=await inspect(file,{format,language,expected:{patientId:9101,rows:1,cells:[values.instruction],contains:['Synthetic "quoted", text']}})
    assert.equal(result.csvRowCount,1);assert.equal(result.rows[0].length,schema.length)
    await assert.rejects(inspect(file,{format,language,expected:{timeZone:'Asia/Shanghai'}}),/inspection/)
    values.instruction='\t=SUM(1,2)';await writeFile(file,'\ufeff'+encode(schema.map(s=>s.header))+encode(schema.map(s=>values[s.key])))
    await assert.rejects(inspect(file,{format,language}),/inspection/)
    await writeFile(file,'\ufeff'+encode(schema.map(s=>s.header)))
    assert.equal((await inspect(file,{format,language,expected:{rows:0}})).csvRowCount,0)
    await writeFile(file,'\ufeff'+encode(schema.map(s=>s.header).reverse()))
    await assert.rejects(inspect(file,{format,language}),/inspection/)
  }
}))
test('bounded file inspection rejects empty, oversized and symlink input and output',()=>privateTemp(async root=>{
  const file=join(root,'report.html');await writeFile(file,'')
  await assert.rejects(inspect(file,{format:'html',language:'en'}),/size/)
  await writeFile(file,html);await assert.rejects(inspect(file,{format:'html',language:'en',maxBytes:10}),/size/)
  await symlink(file,join(root,'alias.html'));await assert.rejects(inspect(join(root,'alias.html'),{format:'html',language:'en'}),/symlink/)
  await mkdir(join(root,'actual'));await symlink(join(root,'actual'),join(root,'aliased'),'dir')
  await assert.rejects(inspect(join(root,'aliased','none.html'),{format:'html',language:'en'}),/symlink/)
}))
test('HTTP export headers are bounded, private, exact-format and match the actual safe filename',()=>{
  assert.equal(typeof inspector.assertReportResponseHeaders,'function')
  const filename='care-execution-report-zh-CN-20261004T210000Z-actions.csv'
  const headers={'content-type':'text/csv;charset=UTF-8','content-length':'12','content-disposition':`attachment; filename="${filename}"`,'cache-control':'no-store, private','x-content-type-options':'nosniff'}
  assert.deepEqual(inspector.assertReportResponseHeaders(200,headers,{format:'actions_csv',language:'zh-CN',filename}),{bytes:12})
  for(const patch of [{'content-type':'application/json'},{'content-length':'33554433'},{'cache-control':'public'},{'content-disposition':'inline'},{'x-content-type-options':''}])assert.throws(()=>inspector.assertReportResponseHeaders(200,{...headers,...patch},{format:'actions_csv',language:'zh-CN',filename}))
  assert.throws(()=>inspector.assertReportResponseHeaders(200,headers,{format:'actions_csv',language:'en',filename}))
})
test('error inspector accepts only a small JSON denial with exact public metadata and no file',async()=>{
  assert.equal(typeof inspector.inspectReportDenial,'function')
  const headers={'content-type':'application/json','cache-control':'no-store, private','x-content-type-options':'nosniff'}
  const denial=Buffer.from(JSON.stringify({code:403,msg:'Access denied',timestamp:1791147600000,data:{errorCode:'ACCESS_DENIED'}}))
  assert.deepEqual(inspector.inspectReportDenial(403,headers,denial),{status:403,errorCode:'ACCESS_DENIED'})
  const limited=Buffer.from(JSON.stringify({code:422,msg:'Report limit exceeded',timestamp:1791147600000,data:{errorCode:'REPORT_LIMIT_EXCEEDED',limitKind:'QUESTIONS',limit:200}}))
  assert.deepEqual(inspector.inspectReportDenial(422,headers,limited,{status:422,errorCode:'REPORT_LIMIT_EXCEEDED',limitKind:'QUESTIONS',limit:200}),{status:422,errorCode:'REPORT_LIMIT_EXCEEDED'})
  for(const bad of [Buffer.alloc(8193),Buffer.from(JSON.stringify({code:403,data:{errorCode:'ACCESS_DENIED',patient:'Synthetic hidden'}})),Buffer.from('%PDF-1.4')])assert.throws(()=>inspector.inspectReportDenial(403,headers,bad))
  assert.throws(()=>inspector.inspectReportDenial(403,{...headers,'content-disposition':'attachment'},denial))
})
test('actual Poppler inspection extracts text and renders every page while rejecting blank pixels',()=>privateTemp(async root=>{
  assert.equal(typeof inspector.inspectPngPixels,'function')
  // A two-page ordinary PDF fixture created here exercises the binary inspector, not HTTP acceptance.
  const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
  for(let page=1;page<=2;page++){const stream=`BT /F1 14 Tf 30 250 Td (Synthetic report page ${page}) Tj ET`;objects.push(`<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`)}
  const pdf=plainPdf(objects)
  const file=join(root,'report.pdf');await writeFile(file,pdf)
  const result=await inspect(file,{format:'pdf',language:'en',pngDirectory:join(root,'pages'),expected:{contains:['Synthetic report page 1','Synthetic report page 2'],minPages:2}})
  assert.equal(result.pageCount,2);assert.equal(result.pages.length,2)
  for(const page of result.pages){assert.ok(page.nonwhitePixels>0);assert.equal((await readFile(page.path)).subarray(0,8).toString('hex'),'89504e470d0a1a0a')}
  const sentinel=join(root,'pages','sentinel.txt');await writeFile(sentinel,'Preserve an existing owned directory')
  await assert.rejects(inspect(file,{format:'pdf',language:'en',pngDirectory:join(root,'pages')}),/new owned/)
  assert.equal(await readFile(sentinel,'utf8'),'Preserve an existing owned directory')
  const giant=objects.map(value=>value.replace('/MediaBox [0 0 300 300]','/MediaBox [0 0 1100 300]'));await writeFile(file,plainPdf(giant))
  await assert.rejects(inspect(file,{format:'pdf',language:'en',pngDirectory:join(root,'oversized-pages')}),/geometry exceeds/)
}))
test('renderer evidence selection includes exactly fresh coverage and long PDFs in both output languages',async()=>{
  let collector={};try{collector=await import('../collect-care-report-renderer-evidence.mjs')}catch(error){if(error.code!=='ERR_MODULE_NOT_FOUND')throw error}
  assert.equal(typeof collector.rendererEvidenceSelection,'function','Exact renderer specimen selection is required')
  for(const project of ['.','cn']) {
    const selected=collector.rendererEvidenceSelection(project)
    assert.equal(selected.length,4)
    assert.deepEqual(selected.map(item=>[item.language,item.scenario]),[['en','task5-coverage'],['en','task5-long'],['zh-CN','task5-coverage'],['zh-CN','task5-long']])
    assert.ok(selected.every(item=>item.edition===(project==='cn'?'cn':'en')&&/\/(coverage|long)-report\.pdf$/.test(item.path)))
    assert.ok(selected.every(item=>!item.path.includes('probe')&&!item.path.includes('red')))
  }
  for(const project of ['../cn','/tmp','frontend',''])assert.throws(()=>collector.rendererEvidenceSelection(project))
})

test('decoded PNG checks reject fully white actual pixel buffers and malformed dimensions',()=>{
  const crc32=bytes=>{let crc=0xffffffff;for(const byte of bytes){crc^=byte;for(let bit=0;bit<8;bit++)crc=(crc>>>1)^((crc&1)?0xedb88320:0)}return (crc^0xffffffff)>>>0}
  const chunk=(kind,data)=>{const name=Buffer.from(kind),size=Buffer.alloc(4),crc=Buffer.alloc(4);size.writeUInt32BE(data.length);crc.writeUInt32BE(crc32(Buffer.concat([name,data])));return Buffer.concat([size,name,data,crc])}
  const header=Buffer.alloc(13);header.writeUInt32BE(10,0);header.writeUInt32BE(10,4);header[8]=8;header[9]=2
  const raw=Buffer.alloc(310,255);for(let y=0;y<10;y++)raw[y*31]=0
  const png=()=>Buffer.concat([Buffer.from('89504e470d0a1a0a','hex'),chunk('IHDR',header),chunk('IDAT',deflateSync(raw)),chunk('IEND',Buffer.alloc(0))])
  assert.throws(()=>inspector.inspectPngPixels(png()),/blank/)
  for(let y=0;y<10;y++)for(let x=1;x<=15;x++)raw[y*31+x]=0
  assert.equal(inspector.inspectPngPixels(png()).nonwhitePixels,50)
  header.writeUInt32BE(100000,0);assert.throws(()=>inspector.inspectPngPixels(png()),/geometry|size/)
})

const requestFacts={patientId:9101,fromDate:'2026-09-05',toDate:'2026-10-04',timeZone:'UTC',planId:null}
const encodeCsv=values=>values.map(value=>'"'+String(value).replaceAll('"','""')+'"').join(',')+'\r\n'
const baselineCsv=(format,language)=>Object.fromEntries(inspector.reportCsvSchema(format,language).map(({key})=>[key,({schema:'1',generated:'2026-10-04T21:00:00Z',asOf:'2026-10-04T20:59:59Z',patient:'9101',scopeCode:'ALL_PLANS',scopeLabel:language==='en'?'All plans':'全部计划',from:'2026-09-05',to:'2026-10-04',zone:'UTC',start:'2026-09-05T00:00:00Z',end:'2026-10-05T00:00:00Z',language,source:'CARE_PLAN',completeCode:'COMPLETE',plan:'19101',revision:'19101',revisionNo:'1',action:'19101',statusCode:'OPEN','event.id':'19104','event.typeCode':'RECEIPT_SUBMITTED','event.actor':'9001','event.roleCode':'OWNER','event.relationCode':'SELF','event.modeCode':'SELF'})[key]??'']))

test('review invalid HTML lacks requested metadata and labels even with all original clinical phrases',()=>privateTemp(async root=>{
  const file=join(root,'review.html')
  await writeFile(file,'<!doctype html><html lang="zh-CN"><body><p>Saved successfully</p><p>Synthetic doctor reviewed action</p><p>Synthetic 护理原文 &lt;script&gt;synthetic-report&lt;/script&gt;</p></body></html>')
  await assert.rejects(inspect(file,{format:'html',language:'zh-CN',expected:{...requestFacts,contains:['Saved successfully','Synthetic doctor reviewed action','Synthetic 护理原文 <script>synthetic-report</script>']}}),/inspection/)
}))

test('restricted evidence inspection checks hidden href attributes and source fields, not only visible URL text',()=>privateTemp(async root=>{
  const file=join(root,'review.html'),expected={evidenceRestricted:true,allowedReferences:[],contains:['Saved successfully']}
  await writeFile(file,html)
  await inspect(file,{format:'html',language:'en',expected})
  for(const leaked of ['<a href="/care-journey?tab=measurements&amp;patientId=9101&amp;measurementId=19101">Source</a>','<table><tr><th>Evidence source ID</th><td>19101</td></tr></table>']) {
    await writeFile(file,html.replace('</body>',leaked+'</body>'))
    await assert.rejects(inspect(file,{format:'html',language:'en',expected}),/inspection/)
  }
}))

for(const format of ['actions_csv','events_csv']) test(`review ${format} semantic oracle rejects blank/duplicate identity, invalid state/actor and evidence-only disclosures`,()=>privateTemp(async root=>{
  const file=join(root,'review.csv'),schema=inspector.reportCsvSchema(format,'en'),key=format==='actions_csv'?'action':'event.id'
  const row=baselineCsv(format,'en'),second={...row,[key]:format==='actions_csv'?'19102':'19105'}
  const records=[row,second].map(record=>Object.fromEntries([key,'statusCode',...(format==='events_csv'?['action','event.typeCode','event.actor','event.roleCode']:[])].map(column=>[column,record[column]])))
  const expected={...requestFacts,rows:2,recordKey:key,records,evidenceRestricted:true}
  const save=async rows=>writeFile(file,'\ufeff'+encodeCsv(schema.map(column=>column.header))+rows.map(record=>encodeCsv(schema.map(column=>record[column.key]))).join(''))
  await save([row,second]);await inspect(file,{format,language:'en',expected})
  const mutations=[values=>{values[0][key]=''},values=>{values[1]={...values[0]}},values=>{values[0].statusCode='INVALID_STATUS'},values=>{values[0][format==='actions_csv'?'evidence':'event.evidence']='MEASUREMENT #19101'}]
  if(format==='events_csv')mutations.push(values=>{values[0]['event.typeCode']='INVALID_EVENT'},values=>{values[0]['event.actor']='9003'},values=>{values[0]['event.roleCode']='DOCTOR'})
  for(const mutate of mutations){const values=[{...row},{...second}];mutate(values);await save(values);await assert.rejects(inspect(file,{format,language:'en',expected}),/inspection/)}
}))

test('actual PDF invisible URI annotations are checked against the allowed protected references',()=>privateTemp(async root=>{
  const file=join(root,'annotated.pdf'),stream='BT /F1 14 Tf 30 250 Td (Saved successfully) Tj ET'
  const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R /Annots [6 0 R] >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',`<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`,'<< /Type /Annot /Subtype /Link /Rect [30 200 150 220] /A << /S /URI /URI (/care-journey?tab=measurements&patientId=9101&measurementId=19101) >> >>']
  await writeFile(file,plainPdf(objects))
  await assert.rejects(inspect(file,{format:'pdf',language:'en',expected:{contains:['Saved successfully'],allowedReferences:[],evidenceRestricted:true}}),/reference|inspection/)
}))

test('independent fixture CSV oracles preserve action/event identities, historical actors and exact restricted evidence cells in both languages',()=>privateTemp(async root=>{
  const {fixtureReportExpectation}=await import('../care-report-fixture-oracle.mjs')
  for(const format of ['actions_csv','events_csv'])for(const language of ['en','zh-CN'])for(const narrow of [false,true]) {
    const expected={...fixtureReportExpectation({format,language,narrow}),...requestFacts},schema=inspector.reportCsvSchema(format,language),file=join(root,'fixture.csv')
    const values=expected.records.map(record=>({...baselineCsv(format,language),...record}))
    // Use a standard joined CSV document, never Array.toString separators.
    const write=async records=>writeFile(file,'\ufeff'+encodeCsv(schema.map(column=>column.header))+records.map(row=>encodeCsv(schema.map(column=>row[column.key]))).join(''))
    await write(values);const result=await inspect(file,{format,language,expected});assert.equal(result.csvRowCount,format==='actions_csv'?4:7)
    const invalid=values.map(row=>({...row}));invalid[0][format==='actions_csv'?'receipt.actor':'event.actor']='9006';await write(invalid)
    await assert.rejects(inspect(file,{format,language,expected}),/inspection/)
    if(narrow){const leak=values.map(row=>({...row}));leak[0][format==='actions_csv'?'receipt.evidence':'event.evidence']='MEASUREMENT #19101';await write(leak);await assert.rejects(inspect(file,{format,language,expected}),/inspection/)}
  }
}))

test('pre-click header-only metadata requires exact range, scope and valid generation time from the accepted filename',async()=>{
  const {assertDownloadClickMetadata,filenameGenerationSecond}=await import('../care-report-fixture-oracle.mjs')
  for(const language of ['en','zh-CN'])for(const planId of [null,19101]) {
    const request={...requestFacts,patientId:9102,language,planId},format='actions_csv',filename=`care-execution-report-${language}-20261004T210000Z-actions.csv`,zh=language==='zh-CN'
    const generated=new Intl.DateTimeFormat(language,{timeZone:'UTC',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',timeZoneName:'longOffset'}).format(new Date('2026-10-04T21:00:00Z'))
    const paragraphs=[zh?'0条 · 仅表头。此空CSV不包含离线范围元数据。':'0 rows · header only. This empty CSV does not contain offline scope metadata.',`${planId==null?(zh?'全部计划':'All plans'):(zh?'仅此计划':'Only this plan')} · ${zh?'患者':'Patient'} #9102${planId==null?'':` · ${zh?'计划':'Plan'} #${planId}`}`,`2026-09-05 — 2026-10-04 · UTC · ${language} · ${zh?'报告格式版本':'Report schema'} 1`,`${zh?'生成时间':'Generated'}: ${generated} (UTC+00:00, UTC)`]
    const observation={text:paragraphs.join(' '),paragraphs,connected:true,fileName:filename},options={request,format,filename,rowCount:0}
    assert.deepEqual(assertDownloadClickMetadata(observation,options),{generatedSecond:'2026-10-04T21:00:00Z'})
    const invalid=[{...observation,text:'#9102 en UTC schema 1 0 rows',paragraphs:['#9102 en UTC schema 1 0 rows']},...paragraphs.map((_,index)=>({...observation,paragraphs:paragraphs.filter((_,i)=>i!==index)})),{...observation,paragraphs:paragraphs.map(value=>value.replace('2026-09-05','2026-09-04'))},{...observation,fileName:filename.replace('210000','210001')}]
    for(const value of invalid)assert.throws(()=>assertDownloadClickMetadata(value,options),/metadata|scope|dates|time|count|file/i)
    for(const stamp of ['20260230T210000Z','20261004T250000Z'])assert.throws(()=>filenameGenerationSecond(filename.replace('20261004T210000Z',stamp),{format,language}))
  }
})

test('HTML metadata has exact requested identity/range/scope/generation and localized meaning, with protected href checks independent of text',()=>privateTemp(async root=>{
  const file=join(root,'metadata.html')
  for(const language of ['en','zh-CN']) {
    const zh=language==='zh-CN',t=(en,cn)=>zh?cn:en
    const fields=[[t('Patient ID','患者ID'),'9101'],[t('Scope','范围'),t('All plans','全部计划')],[t('Scope plan ID','范围计划ID'),t('Not applicable','不适用')],[t('Report schema version','报告结构版本'),'1'],[t('From date','开始日期'),'2026-09-05'],[t('To date (inclusive)','结束日期（含当日）'),'2026-10-04'],[t('Time zone','时区'),'UTC'],[t('Language','界面语言'),language],[t('Completeness','完整性'),t('Complete for selected scope and authorized sections','所选范围及已授权区域完整')],[t('Generated at','生成时间'),'2026-10-04T21:00:00.123456Z'],[t('Current state as of','当前状态读取时间'),'2026-10-04T20:59:59Z'],[t('Range start','期间开始'),'2026-09-05T00:00:00Z'],[t('Range end (exclusive)','期间结束（不含）'),'2026-10-05T00:00:00Z'],[t('Current actions (N)','当前事项（N）'),'4']]
    const headings=[t('Care execution report','照护执行报告'),t('Current state and counting basis','当前状态与统计口径'),t('Period activity','期间活动')]
    const reference='/medical-record?tab=list&patientId=9101&recordId=19102'
    const markup=`<html lang="${language}"><body>${headings.map(value=>`<h2>${value}</h2>`).join('')}<table>${fields.map(([key,value])=>`<tr><th>${key}</th><td>${value}</td></tr>`).join('')}</table><a href="${reference.replaceAll('&','&amp;')}">${reference.replaceAll('&','&amp;')}</a></body></html>`
    const expected={...requestFacts,generatedSecond:'2026-10-04T21:00:00Z',fields:[[t('Current actions (N)','当前事项（N）'),'4']],allowedReferences:[reference]}
    await writeFile(file,markup);await inspect(file,{format:'html',language,expected})
    for(const [old,replacement] of [['>9101<','>9102<'],['2026-09-05','2026-09-04'],['21:00:00.123456','21:00:01.123456'],['>4<','>5<'],[headings[0],zh?'Care execution report':'照护执行报告'],['href="'+reference.replaceAll('&','&amp;'),'href="'+reference.replace('19102','19104').replaceAll('&','&amp;')]]) {
      await writeFile(file,markup.replace(old,replacement));await assert.rejects(inspect(file,{format:'html',language,expected}),/inspection/)
    }
  }
}))

test('actual PDF text must carry requested-language labels and exact metadata, not only unchanged clinical phrases',()=>privateTemp(async root=>{
  const file=join(root,'metadata.pdf')
  const lines=['Care execution report','Current state and counting basis','Period activity','Patient ID 9101','Scope All plans','Scope plan ID Not applicable','Report schema version 1','From date 2026-09-05','To date (inclusive) 2026-10-04','Time zone UTC','Language en','Completeness Complete for selected scope and authorized sections','Generated at 2026-10-04T21:00:00Z','Current state as of 2026-10-04T20:59:59Z','Range start 2026-09-05T00:00:00Z','Range end (exclusive) 2026-10-05T00:00:00Z','Current actions (N) 4','Saved successfully']
  const escape=value=>value.replaceAll('\\','\\\\').replaceAll('(','\\(').replaceAll(')','\\)')
  const stream='BT /F1 10 Tf 14 TL 25 650 Td '+lines.map((line,index)=>`${index?'T* ':''}(${escape(line)}) Tj`).join('\n')+' ET'
  const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 500 700] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',`<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`]
  await writeFile(file,plainPdf(objects))
  const expected={...requestFacts,contains:['Saved successfully'],generatedSecond:'2026-10-04T21:00:00Z',fields:[['Current actions (N)','4']]}
  const result=await inspect(file,{format:'pdf',language:'en',expected});assert.equal(result.pageCount,1)
  for(const invalid of [{language:'zh-CN',expected},{language:'en',expected:{...expected,patientId:9102}},{language:'en',expected:{...expected,fromDate:'2026-09-04'}},{language:'en',expected:{...expected,fields:[['Current actions (N)','5']]}},{language:'en',expected:{...expected,generatedSecond:'2026-10-04T21:00:01Z'}}])await assert.rejects(inspect(file,{format:'pdf',...invalid}),/inspection/)
}))

test('authorized evidence fields must retain the allowed type/id/title association and exact occurrence count',()=>privateTemp(async root=>{
  const file=join(root,'evidence.html'),path='/medical-record?tab=list&patientId=9101&recordId=19102'
  const block='<table><tr><th>Evidence type</th><td>Medical record (MEDICAL_RECORD)</td></tr><tr><th>Evidence source ID</th><td>19102</td></tr><tr><th>Evidence title</th><td>OTHER</td></tr><tr><th>Protected in-app detail</th><td><a href="'+path.replaceAll('&','&amp;')+'">'+path.replaceAll('&','&amp;')+'</a></td></tr></table>'
  const markup='<html lang="en"><body>'+block+'</body></html>',expected={allowedReferences:[path],evidenceEntries:[{type:'Medical record (MEDICAL_RECORD)',id:'19102',title:'OTHER',count:1}]}
  await writeFile(file,markup);await inspect(file,{format:'html',language:'en',expected})
  for(const invalid of [markup.replace('>19102<','>19104<'),markup.replace('>OTHER<','>Synthetic private source title<'),markup.replace('Medical record (MEDICAL_RECORD)','Measurement (MEASUREMENT)'),markup.replace('</body>',block+'</body>')]) {
    await writeFile(file,invalid);await assert.rejects(inspect(file,{format:'html',language:'en',expected}),/inspection/)
  }
}))

test('literal Chromium pre-click UTC timestamps accept only the zero-offset GMT alias',async()=>{
 const {assertDownloadClickMetadata}=await import('../care-report-fixture-oracle.mjs')
 for(const [language,generated] of [['en','Generated: 10/04/2026, 11:09:44 PM GMT (UTC+00:00, UTC)'],['zh-CN','生成时间: 2026/10/04 GMT 23:09:44 (UTC+00:00, UTC)']]){
  const zh=language==='zh-CN',filename=`care-execution-report-${language}-20261004T230944Z-actions.csv`,request={patientId:9102,planId:null,fromDate:'2026-09-05',toDate:'2026-10-04',language,timeZone:'UTC'}
  const paragraphs=[zh?'0条 · 仅表头。':'0 rows · header only.',`${zh?'全部计划 · 患者':'All plans · Patient'} #9102`,`2026-09-05 — 2026-10-04 · UTC · ${language} · ${zh?'报告格式版本':'Report schema'} 1`,generated]
  const observation={paragraphs,text:paragraphs.join(' '),connected:true,fileName:filename},options={request,filename,format:'actions_csv',rowCount:0}
  for(const value of [generated,generated.replace('GMT ','GMT+00:00 ')])assert.doesNotThrow(()=>assertDownloadClickMetadata({...observation,paragraphs:[...paragraphs.slice(0,3),value]},options))
  for(const [from,to] of [['2026','2025'],['10/04','10/03'],['09:44','09:45'],['GMT','GMT-00:00'],['GMT','GMT+00:01']])assert.throws(()=>assertDownloadClickMetadata({...observation,paragraphs:[...paragraphs.slice(0,3),generated.replace(from,to)]},options))
  for(const [from,to] of [['09:44','09:45'],['2026','2025'],['GMT','GMT+08:00'],['UTC+00:00','UTC+08:00'],['UTC)','Asia/Shanghai)'],['9102','9101'],['2026-09-05','2026-09-04'],[zh?'全部计划':'All plans',zh?'仅此计划':'Only this plan']])assert.throws(()=>assertDownloadClickMetadata({...observation,paragraphs:paragraphs.map(p=>p.replace(from,to))},options))
 }
})

test('installed Playwright CDP callback preserves inspected CSV bytes with raw or single-BOM-omitted response representations',async()=>{
 const {inspectReportDownload}=await import('../../frontend/e2e/reportDownloads.mjs'),{Readable}=await import('node:stream'),{runInNewContext}=await import('node:vm')
 let source;for(const edition of ['frontend','cn/frontend']){try{source=await readFile(new URL(`../../${edition}/node_modules/playwright-core/lib/server/chromium/crNetworkManager.js`,import.meta.url),'utf8');break}catch(error){if(error.code!=='ENOENT')throw error}};assert.ok(source,'Pinned installed browser source required')
 const start=source.indexOf('const getResponseBody = async () => {'),end=source.indexOf('\n    };',start)+7
 assert.ok(start>0&&end>start,'Installed pinned callback must be found')
 const callback=source.slice(start,end)+';getResponseBody'
 for(const format of ['actions_csv','events_csv'])for(const language of ['en','zh-CN']){
  const raw=Buffer.from('\ufeff'+inspector.reportCsvSchema(format,language).map(s=>'"'+s.header.replaceAll('"','""')+'"').join(',')+'\r\n'),kind=format.replace('_csv',''),filename=`care-execution-report-${language}-20261004T230944Z-${kind}.csv`
  const inspectTransport=async(downloaded,represented,declared=downloaded.length)=>{
   const body={patientId:9102,planId:null,fromDate:'2026-09-05',toDate:'2026-10-04',timeZone:'UTC',language,format}
   const download={suggestedFilename:()=>filename,failure:async()=>null,createReadStream:async()=>Readable.from([downloaded])}
   const response={allHeaders:async()=>({'content-type':'text/csv;charset=UTF-8','content-length':String(declared),'content-disposition':`attachment; filename="${filename}"`,'cache-control':'no-store, private','x-content-type-options':'nosniff'}),status:()=>200,request:()=>({method:()=> 'POST',postDataJSON:()=>body}),url:()=> 'http://127.0.0.1:18081/api/care-plans/reports/export',body:async()=>represented}
   return inspectReportDownload({download,response,format,language,expected:{patientId:9102,rows:0}})
  }
  for(const base64Encoded of [true,false]){
   const body=base64Encoded?raw.toString('base64'):new TextDecoder('utf-8',{fatal:true}).decode(raw)
   const actual=await runInNewContext(callback,{Buffer,responsePayload:{headers:{'content-length':String(raw.length)}},request:{_requestId:'synthetic-model',session:{send:async method=>{assert.equal(method,'Network.getResponseBody');return{body,base64Encoded}}}}})()
   assert.equal(actual.length,raw.length-(base64Encoded?0:3))
   const result=await inspectTransport(raw,actual);assert.equal(result.bytes,raw.length);assert.equal(result.csvRowCount,0)
  }
  for(const bad of [raw.subarray(4),Buffer.concat([raw,Buffer.from('x')]),Buffer.concat([Buffer.from('\ufeff'),raw]),Buffer.from(raw.subarray(3).toString().replace('\r\n','\n')),Buffer.concat([raw.subarray(3,-1),Buffer.from('x')])])await assert.rejects(inspectTransport(raw,bad))
  await assert.rejects(inspectTransport(raw,raw,raw.length+1))
  for(const bad of [raw.subarray(3),Buffer.concat([Buffer.from('\ufeff'),raw]),Buffer.concat([raw.subarray(0,-2),Buffer.from([0xff,0xfe])])])await assert.rejects(inspectTransport(bad,bad))
 }
})

test('only CSV permits one omitted BOM; HTML/PDF and all other byte changes retain exact length and hash',async()=>{
 const {createHash}=await import('node:crypto'),bom=Buffer.from([0xef,0xbb,0xbf]),raw=Buffer.from('\ufeffSynthetic,UTF8,护理\r\n'),sha256=createHash('sha256').update(raw).digest('hex')
 for(const format of ['html','pdf','actions_csv','events_csv']){
  const expected={format,bytes:raw.length,sha256};assert.doesNotThrow(()=>inspector.assertInspectedResponseBytes(raw,expected))
  if(format.endsWith('_csv'))assert.doesNotThrow(()=>inspector.assertInspectedResponseBytes(raw.subarray(3),expected));else assert.throws(()=>inspector.assertInspectedResponseBytes(raw.subarray(3),expected))
  for(const value of [raw.subarray(1),raw.subarray(4),Buffer.concat([bom,raw]),Buffer.concat([bom,raw.subarray(6)]),Buffer.from(raw.toString().replace('Synthetic','Synthetid')),Buffer.from(raw.toString().replace('\r\n','\n'))])assert.throws(()=>inspector.assertInspectedResponseBytes(value,expected))
 }
})
