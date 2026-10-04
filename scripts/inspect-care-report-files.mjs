// Test-only real-byte inspection. No network, auth, or report payload logging.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { lstat, readFile, readdir, mkdir, mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, parse, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { inflateSync } from 'node:zlib'
import { REPORT_CSV_SCHEMA } from './care-report-csv-schema.mjs'

export const REPORT_MAX_BYTES=32*1024*1024
const MAX_PAGES=256,MAX_PNG_BYTES=30*1024*1024,MAX_TEXT_OUTPUT=24*1024*1024
const formats=Object.freeze({html:['text/html','html','html'],pdf:['application/pdf','pdf','pdf'],actions_csv:['text/csv','actions','csv'],events_csv:['text/csv','events','csv']})
const hash=bytes=>createHash('sha256').update(bytes).digest('hex')
export function reportCsvSchema(format,language) {
  assert.ok(Object.hasOwn(REPORT_CSV_SCHEMA,format) && ['en','zh-CN'].includes(language),'Invalid report CSV schema request')
  return REPORT_CSV_SCHEMA[format].map(c=>({key:c.key,header:language==='en'?c.en:c.zh}))
}
export async function assertSafeReportPath(path,{directory=false,allowMissing=false}={}) {
  const absolute=resolve(path),root=parse(absolute).root,parts=absolute.slice(root.length).split('/').filter(Boolean)
  for(let i=0;i<parts.length;i++) {
    let metadata
    try {metadata=await lstat(join(root,...parts.slice(0,i+1)))}catch(error){if(allowMissing && error.code==='ENOENT')return absolute;throw error}
    assert.ok(!metadata.isSymbolicLink(),'Report path or ancestor must never be a symlink')
    assert.ok(i===parts.length-1&&!directory?metadata.isFile():metadata.isDirectory(),'Report path has an invalid file type')
  }
  return absolute
}
export function assertReportResponseHeaders(status,input,{format,language,filename}={}) {
  const headers=Object.fromEntries(Object.entries(input).map(([key,value])=>[key.toLowerCase(),value]))
  assert.equal(status,200,'Actual report export failed')
  assert.ok(Object.hasOwn(formats,format) && ['en','zh-CN'].includes(language),'Invalid report format or language')
  const [mime,kind,extension]=formats[format]
  assert.match(headers['content-type']||'',new RegExp('^'+mime.replace('/','\\/')+'(?:\\s*;|$)','i'),'Actual report MIME differs')
  if(format!=='pdf')assert.match(headers['content-type'],/charset\s*=\s*UTF-8/i,'UTF-8 report charset required')
  assert.ok(/^\d{1,9}$/.test(headers['content-length']||''),'Bounded report Content-Length required')
  const bytes=Number(headers['content-length']);assert.ok(bytes>0 && bytes<=REPORT_MAX_BYTES,'Report response size exceeds bound')
  assert.match(headers['cache-control']||'',/(?:^|,\s*)no-store(?:,|$)/,'Report must not be cached')
  assert.match(headers['cache-control']||'',/(?:^|,\s*)private(?:,|$)/,'Report cache must be private')
  assert.equal(headers['x-content-type-options'],'nosniff','Report sniffing must be disabled')
  assert.match(filename||'',new RegExp('^care-execution-report-'+language+'-[0-9]{8}T[0-9]{6}Z-'+kind+'\\.'+extension+'$'),'Unsafe report download filename')
  const disposition=headers['content-disposition']||''
  assert.match(disposition,/^attachment(?:;|$)/i,'Report response must be an attachment')
  // Safe filenames are ASCII; accept Spring's ordinary quoted/unquoted parameter.
  const match=disposition.match(/(?:^|;)\s*filename=(?:"([^"]+)"|([^;\s]+))(?:;|$)/i)
  assert.equal(match?.[1]??match?.[2],filename,'Delivered filename differs from header')
  return {bytes}
}
export function inspectReportDenial(status,input,bytes,{status:expectedStatus=403,errorCode='ACCESS_DENIED',limitKind,limit}={}) {
  const headers=Object.fromEntries(Object.entries(input).map(([key,value])=>[key.toLowerCase(),value]))
  assert.equal(status,expectedStatus,'Expected report denial status')
  assert.ok(Buffer.isBuffer(bytes)&&bytes.length>0&&bytes.length<=8192,'Report denial exceeds bounded JSON size')
  assert.match(headers['content-type']||'',/^application\/json(?:\s*;|$)/i,'Report denial must be JSON')
  assert.ok(!headers['content-disposition'],'Denied report must not be downloadable')
  assert.match(headers['cache-control']||'',/(?:^|,\s*)no-store(?:,|$)/,'Denied report must not be cached')
  assert.match(headers['cache-control']||'',/(?:^|,\s*)private(?:,|$)/,'Denied report must remain private')
  const parsed=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes))
  assert.ok(parsed && typeof parsed==='object' && !Array.isArray(parsed),'Invalid denial wrapper')
  assert.deepEqual(Object.keys(parsed).sort(),['code','data','msg','timestamp'],'Denied report wrapper has unexpected fields')
  assert.equal(parsed.code,expectedStatus,'Expected report denial code')
  assert.ok(typeof parsed.msg==='string' && parsed.msg.length<=256 && Number.isSafeInteger(parsed.timestamp) && parsed.timestamp>0,'Bounded public denial message required')
  const expectedData={errorCode}
  if(errorCode==='REPORT_LIMIT_EXCEEDED') {
    const limits={CURRENT_ACTIONS:1000,PERIOD_EVENTS:5000,QUESTIONS:200,SOURCE_TEXT_BYTES:8388608,OUTPUT_BYTES:33554432}
    assert.ok(expectedStatus===422 && Object.hasOwn(limits,limitKind) && limit===limits[limitKind],'Invalid report limit assertion contract')
    Object.assign(expectedData,{limitKind,limit})
  } else assert.ok(limitKind==null && limit==null,'Unexpected report limit metadata')
  assert.deepEqual(parsed.data,expectedData,'Denied report contains nonpublic metadata')
  return {status,errorCode}
}
async function execute(command,args,{input,limit=65536,timeout=60000}={}) {
  const env=Object.fromEntries(['PATH','LANG','LC_ALL'].filter(k=>process.env[k]!=null).map(k=>[k,process.env[k]]))
  return new Promise((yes,no)=>{
    const child=spawn(command,args,{env,stdio:['pipe','pipe','pipe']});let bytes=0,output=[],settled=false
    const finish=(error,value)=>{if(settled)return;settled=true;clearTimeout(timer);error?no(error):yes(value)}
    const timer=setTimeout(()=>{child.kill('SIGKILL');finish(new Error('Report inspection subprocess exceeded time budget'))},timeout)
    child.stdout.on('data',chunk=>{bytes+=chunk.length;if(bytes>limit){child.kill('SIGKILL');finish(new Error('Report inspection subprocess exceeded output budget'))}else output.push(chunk)})
    child.stderr.resume() // Tool messages can include paths/content. Never log them.
    child.once('error',()=>finish(new Error('Required report inspection utility unavailable')))
    child.once('close',code=>finish(code===0?null:new Error('Report content inspection failed'),Buffer.concat(output)))
    child.stdin.on('error',()=>{});child.stdin.end(input??'')
  })
}
async function logical(file,options) {
  const inspector=fileURLToPath(new URL('./inspect-care-report-content.py',import.meta.url))
  const encoded=JSON.stringify(options);assert.ok(Buffer.byteLength(encoded)<=65536,'Report inspection expectation limit exceeded')
  return JSON.parse((await execute('python3',[inspector,file],{input:encoded,limit:MAX_TEXT_OUTPUT})).toString('utf8'))
}
const paeth=(a,b,c)=>{const p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);return pa<=pb&&pa<=pc?a:pb<=pc?b:c}
export function inspectPngPixels(bytes) {
  assert.ok(bytes.length>32&&bytes.length<=MAX_PNG_BYTES&&bytes.subarray(0,8).equals(Buffer.from('89504e470d0a1a0a','hex')),'Report page is not a bounded PNG')
  let offset=8,width,height,color,channels,idat=[],ended=false
  while(offset<bytes.length) {
    assert.ok(offset+12<=bytes.length,'Invalid report PNG chunk');const length=bytes.readUInt32BE(offset),kind=bytes.toString('ascii',offset+4,offset+8)
    assert.ok(offset+12+length<=bytes.length,'Invalid report PNG chunk length');const data=bytes.subarray(offset+8,offset+8+length)
    if(kind==='IHDR'){assert.equal(length,13);width=data.readUInt32BE(0);height=data.readUInt32BE(4);color=data[9];assert.equal(data[8],8);assert.equal(data[10],0);assert.equal(data[11],0);assert.equal(data[12],0);channels=({0:1,2:3,6:4})[color]}
    else if(kind==='IDAT')idat.push(data)
    else if(kind==='IEND'){ended=true;break}
    offset+=12+length
  }
  assert.ok(ended&&width>0&&height>0&&width*height<=20_000_000&&channels,'Invalid report PNG geometry')
  const stride=width*channels,raw=inflateSync(Buffer.concat(idat),{maxOutputLength:(stride+1)*height})
  assert.equal(raw.length,(stride+1)*height,'Invalid report PNG decoded size')
  let previous=Buffer.alloc(stride),nonwhitePixels=0
  for(let y=0;y<height;y++) {
    const type=raw[y*(stride+1)],row=Buffer.from(raw.subarray(y*(stride+1)+1,(y+1)*(stride+1)))
    assert.ok(type<=4,'Unknown report PNG filter')
    for(let x=0;x<stride;x++){const a=x>=channels?row[x-channels]:0,b=previous[x],c=x>=channels?previous[x-channels]:0;row[x]=(row[x]+(type===1?a:type===2?b:type===3?Math.floor((a+b)/2):type===4?paeth(a,b,c):0))&255}
    for(let x=0;x<stride;x+=channels)if((channels!==4||row[x+3]>0)&&(channels===1?row[x]<245:Math.min(row[x],row[x+1],row[x+2])<245))nonwhitePixels++
    previous=row
  }
  assert.ok(nonwhitePixels>20,'Report PDF page is blank')
  return {width,height,nonwhitePixels}
}
export async function inspectReportFile(file,{format,language,expected={},pngDirectory,maxBytes=REPORT_MAX_BYTES}={}) {
  assert.ok(Object.hasOwn(formats,format)&&['en','zh-CN'].includes(language),'Invalid report inspection format/language')
  assert.ok(Number.isSafeInteger(maxBytes)&&maxBytes>0&&maxBytes<=REPORT_MAX_BYTES,'Invalid report size limit')
  const path=await assertSafeReportPath(file),metadata=await lstat(path)
  assert.ok(metadata.size>0&&metadata.size<=maxBytes,'Actual downloaded report size is outside bounds')
  const bytes=await readFile(path);assert.equal(bytes.length,metadata.size,'Downloaded report changed during inspection')
  const common={bytes:bytes.length,sha256:hash(bytes)}
  if(format!=='pdf')return {...common,...await logical(path,{format,language,expected,...(format.endsWith('_csv')?{schema:reportCsvSchema(format,language)}:{})})}
  assert.ok(bytes.subarray(0,8).toString('ascii').startsWith('%PDF-'),'Actual report bytes are not PDF')
  const temp=await mkdtemp(join(tmpdir(),'care-report-pdf-inspection-'))
  let output,createdOutput=false
  try {
    const info=(await execute('pdfinfo',['-isodates',path])).toString('utf8'),match=info.match(/^Pages:\s+(\d+)\s*$/m)
    const pageCount=Number(match?.[1]);assert.ok(pageCount>0&&pageCount<=MAX_PAGES,'Actual report PDF page count exceeds inspection bound')
    if(expected.minPages!=null)assert.ok(pageCount>=expected.minPages,'Report PDF page count differs')
    const boxes=(await execute('pdfinfo',['-f','1','-l',String(pageCount),'-box',path],{limit:256*1024})).toString('utf8')
    const sizes=[...boxes.matchAll(/^Page\s+\d+ size:\s+([0-9.]+) x ([0-9.]+) pts/mg)]
    assert.equal(sizes.length,pageCount,'Every report PDF page geometry must be known before rendering')
    assert.ok(sizes.every(size=>Number(size[1])>=100&&Number(size[1])<=1000&&Number(size[2])>=100&&Number(size[2])<=1600),'Report PDF page geometry exceeds bounded inspection')
    // Care PDF deliberately has no actionable links, even for authorized evidence.
    const annotations=(await execute('pdfinfo',['-url',path],{limit:256*1024})).toString('utf8').trim().split(/\r?\n/)
    assert.ok(annotations.length===1&&/^Page\s+Type\s+URL$/.test(annotations[0]),'Report PDF contains an actionable reference annotation')
    const textFile=join(temp,'text.txt');await execute('pdftotext',['-enc','UTF-8','-layout',path,textFile])
    const textMetadata=await lstat(textFile);assert.ok(textMetadata.size>0&&textMetadata.size<=16*1024*1024,'Actual report PDF extracted text exceeds bound')
    const logicalResult=await logical(textFile,{format:'pdf_text',language,expected})
    output=pngDirectory?await assertSafeReportPath(pngDirectory,{directory:true,allowMissing:true}):join(temp,'pages')
    try {await lstat(output);throw new Error('Report PDF page output must be a new owned directory')}catch(error){if(error.code!=='ENOENT')throw error}
    await mkdir(dirname(output),{recursive:true});await mkdir(output);createdOutput=true;await execute('pdftoppm',['-r','120','-png',path,join(output,'page')],{timeout:120000})
    const names=(await readdir(output)).sort();assert.equal(names.length,pageCount,'Every actual PDF page must be rendered exactly once')
    let totalPngBytes=0;const pages=[]
    for(const name of names){assert.match(name,/^page-\d+\.png$/);const page=join(output,name);await assertSafeReportPath(page);const data=await readFile(page);totalPngBytes+=data.length;assert.ok(totalPngBytes<=256*1024*1024,'Report rendered-page payload exceeds bound');pages.push({path:page,bytes:data.length,sha256:hash(data),...inspectPngPixels(data)})}
    return {...common,...logicalResult,pageCount,pages}
  } catch(error){if(createdOutput&&pngDirectory)await rm(output,{recursive:true,force:true});throw error}
  finally {await rm(temp,{recursive:true,force:true})}
}
