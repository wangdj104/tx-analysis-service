import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
test('fresh print fixture uses existing admin only for bounded profile writes and owner for questions',async()=>{
 const source=fs.readFileSync(new URL('../e2e/careExecutionReportPrint.spec.mjs',import.meta.url),'utf8')
 const code=source.slice(source.indexOf('async function preparePrintData('),source.indexOf('async function holdLegacy('))
 const calls=[],owner={},admin={},ids={reportPatient:9101},api=async(actor,path,options)=>{calls.push({actor,path,options});return {id:71}}
 const prepare=new Function('api','ids','oldHistory','newHistory','oldQuestion','newQuestion',code+';return preparePrintData')(api,ids,'old','new','old question','new question')
 const data=await prepare(owner,admin);await data.freshen();await data.dispose()
 assert.equal(calls.length,6)
 for(const call of calls){const profile=call.path==='/patient/update';assert.equal(call.actor,profile?admin:owner);if(profile)assert.deepEqual(Object.keys(call.options.body).sort(),['id','medicalHistory']);if(call.options.body)assert.equal(call.options.body.patientId??call.options.body.id,9101)}
 assert.match(source,/preparePrintData\(sessions\.owner,\s*sessions\.admin\)/)
})
