// Actual UI download inspection. Successful payloads are never mocked or logged.
import assert from 'node:assert/strict'
import { mkdtemp, open, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { assertReportResponseHeaders, assertInspectedResponseBytes, inspectReportDenial, inspectReportFile, REPORT_MAX_BYTES } from '../../scripts/inspect-care-report-files.mjs'
import { filenameGenerationSecond } from '../../scripts/care-report-fixture-oracle.mjs'
let sequence=0
export async function inspectReportDownload({download,response,format,language,expected={},testInfo,scenario}) {
  const phase=async(name,operation)=>testInfo?(await import('./reportOutcomePhase.mjs')).reportPhase(name,operation):operation()
  const filename=download.suggestedFilename()
  const declaredBytes=await phase('download-headers',async()=>assertReportResponseHeaders(response.status(),await response.allHeaders(),{format,language,filename}).bytes)
  const body=await phase('download-request',async()=>{
    const request=response.request(),body=request.postDataJSON()
    assert.equal(request.method(),'POST','Download must originate from the actual report export')
    assert.equal(new URL(response.url()).pathname,'/api/care-plans/reports/export','Unexpected report export endpoint')
    assert.equal(body.format,format,'UI requested a different report format')
    assert.equal(body.language,language,'UI requested a different report language')
    if(expected.patientId!=null)assert.equal(body.patientId,expected.patientId,'UI requested a different report patient')
    for(const key of ['fromDate','toDate','timeZone','planId'])if(Object.hasOwn(expected,key))assert.equal(body[key]??null,expected[key],'UI request differs from selected metadata')
    return body
  })
  const directory=await mkdtemp(join(tmpdir(),'care-report-download-')),file=join(directory,filename)
  try {
    await phase('download-stream',async()=>{
      assert.equal(await download.failure(),null,'Actual report download failed')
      const input=await download.createReadStream();assert.ok(input,'Actual downloaded report stream is required')
      const output=await open(file,'wx',0o600);let bytes=0
      try {for await(const chunk of input){bytes+=chunk.length;assert.ok(bytes<=REPORT_MAX_BYTES&&bytes<=declaredBytes,'Actual download exceeds declared bounded size');let offset=0;while(offset<chunk.length){const written=await output.write(chunk,offset,chunk.length-offset);assert.ok(written.bytesWritten>0,'Downloaded report file write stalled');offset+=written.bytesWritten}}}finally{await output.close();input.destroy()}
      assert.equal(bytes,declaredBytes,'Actual download differs from response Content-Length')
    })
    const result=await phase('download-parse',async()=>{
      const pngDirectory=format==='pdf'&&testInfo?testInfo.outputPath(`report-pages-${language}-${scenario||'inspection'}-${++sequence}`):undefined
      const requestMetadata={fromDate:body.fromDate,toDate:body.toDate,timeZone:body.timeZone,planId:body.planId??null}
      const inspectionExpected={...expected,...Object.fromEntries(Object.entries(requestMetadata).filter(([,value])=>value!==undefined)),generatedSecond:filenameGenerationSecond(filename,{format,language})}
      return inspectReportFile(file,{format,language,expected:inspectionExpected,pngDirectory})
    })
    await phase('download-hash',async()=>assertInspectedResponseBytes(await response.body(),{format,bytes:declaredBytes,sha256:result.sha256}))
    if(scenario)await phase('download-stage',async()=>{
      const {stageReportEvidence}=await import('../../scripts/package-care-report-artifacts.mjs')
      const edition=process.env.CARE_PLAN_E2E_LANGUAGE==='cn'?'cn':'en',kind=format.replace('_csv','')
      await stageReportEvidence(file,{edition,language,scenario,format:kind,bytes:result.bytes,sha256:result.sha256,result:'passed',...(format==='pdf'?{pageCount:result.pageCount}:format.endsWith('_csv')?{csvRowCount:result.csvRowCount}:{})})
    })
    // Parsed text/rows stay in test memory only, never an artifact or log.
    return {...result,filename}
  } finally {await rm(directory,{recursive:true,force:true})}
}
export async function assertReportDeniedResponse(response,expected={}) {
  const headers=typeof response.allHeaders==='function'?await response.allHeaders():response.headers()
  if(headers['content-length']!=null)assert.ok(/^\d{1,4}$/.test(headers['content-length'])&&Number(headers['content-length'])<=8192,'Denied report must stay small')
  return inspectReportDenial(response.status(),headers,await response.body(),expected)
}
