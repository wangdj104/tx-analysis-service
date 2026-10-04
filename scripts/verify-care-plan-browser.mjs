#!/usr/bin/env node
// CI-only actual Spring/MySQL + built Vue acceptance. No application .env is read.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer, request as httpRequest } from 'node:http'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { resolve, join, extname, relative } from 'node:path'
import { readFile, mkdir, readdir, lstat, writeFile, mkdtemp, copyFile, symlink, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { constants } from 'node:fs'
import { access, open } from 'node:fs/promises'
import { assertDirectoryAncestors, digest } from './package-care-plan-browser-artifacts.mjs'
const root = resolve(fileURLToPath(new URL('..', import.meta.url)))
export function validateBrowserEnvironment(env) {
  const guard = (ok, label) => assert.ok(ok, `care-plan browser guard: ${label}`)
  for (const key of ['CARE_PLAN_TEST_ONLY','CARE_PLAN_BROWSER_REQUIRED','CI','GITHUB_ACTIONS']) guard(env[key]==='true',key)
  guard(['127.0.0.1','localhost','::1','mysql'].includes(env.CARE_PLAN_MYSQL_HOST),'host')
  guard(/^[1-9][0-9]{0,4}$/.test(env.CARE_PLAN_MYSQL_PORT||'') && +env.CARE_PLAN_MYSQL_PORT<=65535,'port')
  const db = ['CARE_PLAN_MYSQL_DATABASE','CARE_PLAN_MYSQL_UPGRADE_DATABASE','CARE_PLAN_MYSQL_RESTORE_DATABASE'].map(k=>env[k])
  for (const name of db) guard(/^care_plan_test_[a-z0-9_]+$/.test(name||'') && name.length<=64,'database')
  guard(new Set(db).size===3,'distinct databases')
  guard(/^care_plan_test_[a-z0-9_]+$/.test(env.CARE_PLAN_MYSQL_USER||'') && env.CARE_PLAN_MYSQL_USER.length<=32,'user')
  guard(/^[a-zA-Z0-9]{24,128}$/.test(env.CARE_PLAN_MYSQL_PASSWORD||''),'generated database password')
  guard(/^[a-f0-9]{12,64}$/.test(env.CARE_PLAN_MYSQL_CONTAINER_ID||''),'container ID')
  guard(['.','cn'].includes(env.CARE_PLAN_MYSQL_PROJECT),'project')
  guard(/^[a-zA-Z0-9]{24,128}$/.test(env.CARE_PLAN_E2E_PASSWORD||''),'generated login password')
  guard(env.CARE_PLAN_E2E_API_PORT==null || env.CARE_PLAN_E2E_API_PORT==='18081','isolated backend port')
  guard(env.CARE_PLAN_E2E_WEB_PORT==null || env.CARE_PLAN_E2E_WEB_PORT==='14173','isolated web port')
  return { project:env.CARE_PLAN_MYSQL_PROJECT, backendPort:18081, browserPort:14173 }
}
export function environment(input) {
  // Do not inherit real DB/AI/provider/JWT/bootstrap settings or unrelated credentials.
  const allowed = ['PATH','JAVA_HOME','HOME','TMPDIR','CI','GITHUB_ACTIONS','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','PLAYWRIGHT_BROWSERS_PATH','RUNNER_TEMP','REPORT_PDF_FONT_PATH',
    'CARE_PLAN_TEST_ONLY','CARE_PLAN_BROWSER_REQUIRED','CARE_PLAN_MYSQL_HOST','CARE_PLAN_MYSQL_PORT','CARE_PLAN_MYSQL_DATABASE',
    'CARE_PLAN_MYSQL_UPGRADE_DATABASE','CARE_PLAN_MYSQL_RESTORE_DATABASE','CARE_PLAN_MYSQL_USER','CARE_PLAN_MYSQL_PASSWORD',
    'CARE_PLAN_MYSQL_CONTAINER_ID','CARE_PLAN_MYSQL_PROJECT','CARE_PLAN_E2E_PASSWORD']
  return Object.fromEntries(allowed.filter(k=>input[k]!=null).map(k=>[k,input[k]]))
}

export async function validateReportFontEnvironment(input) {
  assert.ok(input.CI==='true' && input.GITHUB_ACTIONS==='true','Report font requires actual CI contract')
  const temporary=input.RUNNER_TEMP,path=input.REPORT_PDF_FONT_PATH
  assert.ok(typeof temporary==='string' && temporary===resolve(temporary) && !temporary.split('/').includes('..'),'Report font requires absolute runner temp')
  assert.ok(typeof path==='string' && path===join(temporary,'care-report-font','wqy-microhei.ttf'),'Report font path must match the owned CI face')
  await assertDirectoryAncestors(path)
  for(const directory of [temporary,join(temporary,'care-report-font')]) {
    const metadata=await lstat(directory)
    assert.ok(metadata.isDirectory() && !metadata.isSymbolicLink() && metadata.uid===process.getuid(),'Report font directory must be owned and non-symlink')
  }
  const metadata=await lstat(path)
  assert.ok(metadata.isFile() && !metadata.isSymbolicLink() && metadata.uid===process.getuid() && (metadata.mode&0o444)!==0 && metadata.size>=12 && metadata.size<=30*1024*1024,'Report font must be an owned readable regular TTF')
  await access(path,constants.R_OK)
  const file=await open(path,'r')
  try {const signature=Buffer.alloc(4);await file.read(signature,0,4,0);assert.ok(signature.equals(Buffer.from([0,1,0,0])),'Report font must be TrueType face, not a collection')}finally{await file.close()}
  const summaryPath=join(temporary,'care-report-font','font-preparation.json'),summaryMetadata=await lstat(summaryPath)
  assert.ok(summaryMetadata.isFile() && !summaryMetadata.isSymbolicLink() && summaryMetadata.uid===process.getuid() && summaryMetadata.size<=4096,'Report font preparation metadata must be owned and bounded')
  const summary=JSON.parse(await readFile(summaryPath,'utf8'))
  const keys=['sourceSha256','ttfSha256','glyphCount','unicodeCmapCount','unicodeCodepointCount','tableCount','nameSha256','fontPackageVersion','fonttoolsPackageVersion','fonttoolsVersion','faceIndex','copyrightSha256']
  assert.ok(summary && Object.keys(summary).length===keys.length && keys.every(key=>Object.hasOwn(summary,key)) && summary.faceIndex===0,'Report font preparation metadata keys invalid')
  for(const key of ['sourceSha256','ttfSha256','nameSha256','copyrightSha256'])assert.ok(/^[a-f0-9]{64}$/.test(summary[key]),'Report font preparation metadata hash invalid')
  for(const key of ['glyphCount','unicodeCmapCount','unicodeCodepointCount','tableCount'])assert.ok(Number.isSafeInteger(summary[key]) && summary[key]>0 && summary[key]<=1114112,'Report font preparation metadata counts invalid')
  for(const key of ['fontPackageVersion','fonttoolsPackageVersion','fonttoolsVersion'])assert.ok(typeof summary[key]==='string' && /^[A-Za-z0-9.+:~_-]{1,100}$/.test(summary[key]),'Report font preparation metadata version invalid')
  const sha256=await digest(path)
  assert.equal(sha256,summary.ttfSha256,'Report font hash differs from complete-face preparation')
  return {path,sha256}
}

async function run(command, args, cwd, env, {background=false}={}) {
  const child = spawn(command,args,{cwd,env,stdio:background?['ignore','ignore','pipe']:'inherit'})
  if(background) {
    let buffer=''
    child.stderr.on('data',data=>{
      buffer+=data.toString();if(buffer.length>8192){buffer='';return}
      let newline
      while((newline=buffer.indexOf('\n'))>=0){const safe=safeBootDiagnostic(buffer.slice(0,newline).trim());if(safe)console.error(safe);buffer=buffer.slice(newline+1)}
    })
  }
  const done = new Promise((yes,no)=>{ child.once('error',no); child.once('exit',(code,signal)=>code===0?yes():no(new Error(`${command} failed (${code??signal})`))) })
  if (background) { done.catch(()=>{}); return {child,done} }
  await done
}
export function safeBootDiagnostic(value) {
  if(typeof value!=='string' || value.length>2048 || !/^CARE_PLAN_BROWSER_BOOT_FAILURE (?:java|javax|org|com)\.[A-Za-z0-9_.$]+(?: > (?:java|javax|org|com)\.[A-Za-z0-9_.$]+){0,7}$/.test(value))return null
  return value
}
export async function resolveProductionJar(project, edition) {
  // Exact production POM contracts: EN literal finalName; CN ${project.name}.
  const names={'.':'tx-analysis-service',cn:'family-health-care'}
  assert.ok(Object.hasOwn(names,edition),'Invalid production jar edition')
  const jar=join(project,'target',names[edition]+'.jar'),metadata=await lstat(jar)
  assert.ok(metadata.isFile() && !metadata.isSymbolicLink(),'Expected regular production jar is required')
  return jar
}
async function probeVideo(video, env) {
  const child=spawn('ffprobe',['-v','error','-count_frames','-select_streams','v:0','-show_entries','stream=codec_name,width,height,nb_read_frames','-show_entries','format=duration','-of','json',video],{cwd:root,env,stdio:['ignore','pipe','inherit']})
  let output='';child.stdout.on('data',data=>{output+=data;if(output.length>16384)child.kill('SIGTERM')})
  await new Promise((yes,no)=>{child.once('error',no);child.once('exit',code=>code===0?yes():no(new Error('Genuine recording probe failed')))})
  const data=JSON.parse(output),stream=data.streams?.[0]
  assert.ok(stream?.width>0 && stream?.height>0 && Number(stream.nb_read_frames)>0 && Number(data.format?.duration)>0,'Recording must contain actual decoded frames and positive duration')
  return {width:stream.width,height:stream.height,decodedFrames:Number(stream.nb_read_frames),durationSeconds:Number(data.format.duration)}
}
async function capture(command,args,env) {
  const child=spawn(command,args,{cwd:root,env,stdio:['ignore','pipe','pipe']});let stdout=''
  child.stdout.on('data',data=>{stdout+=data;if(stdout.length>65536)child.kill('SIGTERM')})
  // Refusal details can contain credentials/container configuration; never print raw stderr.
  child.stderr.resume()
  await new Promise((yes,no)=>{child.once('error',no);child.once('exit',code=>code===0?yes():no(new Error('Disposable service identity verification failed')))})
  return stdout.trim()
}
async function verifyService(env) {
  const id=env.CARE_PLAN_MYSQL_CONTAINER_ID
  const image=await capture('docker',['inspect','--format','{{.Config.Image}}',id],env)
  assert.ok(image==='mysql:8.0' || image.startsWith('mysql:8.0@sha256:'),'Official disposable MySQL8 service required')
  const lines=(await capture('docker',['inspect','--format','{{range .Config.Env}}{{println .}}{{end}}',id],env)).split(/\r?\n/)
  assert.ok(lines.includes('MYSQL_ALLOW_EMPTY_PASSWORD=yes'),'Declared disposable empty-password service required')
  assert.deepEqual(lines.filter(line=>line.startsWith('MYSQL_ROOT_HOST=')),['MYSQL_ROOT_HOST=localhost'],'Local-only service root required')
  const identity=(await capture('docker',['exec',id,'mysql','--user=root','--protocol=socket','--batch','--skip-column-names','--execute',"SELECT CURRENT_USER(), VERSION(), @@server_uuid, (SELECT COUNT(*) FROM mysql.user WHERE User='root' AND Host<>'localhost')"],env)).split('\t')
  assert.ok(identity[0]==='root@localhost' && identity[1]?.startsWith('8.0.') && identity[3]==='0','Actual socket-local MySQL8 service identity required')
  return identity[2]
}
async function waitReady(url, process) {
  const deadline=Date.now()+90000
  while(Date.now()<deadline) {
    if(process.child.exitCode!=null) throw new Error('Actual Spring application stopped before readiness')
    try { const response=await fetch(url,{signal:AbortSignal.timeout(1500)}); if(response.ok && (await response.json()).status==='UP') return }
    catch {}
    await new Promise(yes=>setTimeout(yes,300))
  }
  throw new Error('Actual Spring health readiness failed; no browser acceptance was executed')
}
export function staticRequestPath(pathname, dist, demo, project) {
  assert.ok(project==='.' || project==='cn','Invalid static edition')
  if(pathname==='/__demo')return {redirect:'/__demo/'}
  const demoPage=pathname.startsWith('/__demo/')
  if(!demoPage && project==='cn') {
    if(pathname==='/' || pathname==='/cn')return {redirect:'/cn/'}
    if(!pathname.startsWith('/cn/'))return {status:404}
  }
  const base=demoPage?demo:dist,local=demoPage?pathname.slice('/__demo'.length):project==='cn'?pathname.slice('/cn'.length):pathname
  const file=local==='/'?join(base,'index.html'):resolve(base,'.'+local)
  if(!file.startsWith(base+'/') && file!==base)return {status:403}
  // A missing built asset must not silently become HTML. Only application routes get SPA fallback.
  return {base,file,spaFallback:!demoPage && !extname(local)}
}
export async function createCleanFrontendBuild(frontend, trackedFiles) {
  const stage=await mkdtemp(join(tmpdir(),'care-plan-frontend-build-'))
  try {
    for(const name of trackedFiles) {
      assert.ok(typeof name==='string' && name!=='' && !name.startsWith('/') && !name.split('/').includes('..'),'Invalid declared frontend source path')
      // Filter names before any lstat/read/copy. Never follow or open an environment-file entry.
      if(name.split('/').some(part=>/^\.env(?:\.|$)/.test(part)) || /^(node_modules|dist|test-results|\.npm-cache)(\/|$)/.test(name))continue
      const segments=name.split('/')
      for(let i=0;i<segments.length;i++)assert.ok(!(await lstat(join(frontend,...segments.slice(0,i)))).isSymbolicLink(),'Declared frontend source ancestor must not be a symlink')
      const source=join(frontend,name),metadata=await lstat(source)
      assert.ok(!metadata.isSymbolicLink(),'Declared frontend source must not be a symlink')
      assert.ok(metadata.isFile(),'Declared frontend build input must be a file')
      const destination=join(stage,name);await mkdir(resolve(destination,'..'),{recursive:true});await copyFile(source,destination)
    }
    // Only installed, pinned dependencies are shared; production config/source is copied unchanged.
    await symlink(join(frontend,'node_modules'),join(stage,'node_modules'),'dir')
    assert.ok(!(await readdir(stage)).some(name=>/^\.env(?:\.|$)/.test(name)),'Clean build stage contains an environment file')
    return stage
  } catch(error) {await rm(stage,{recursive:true,force:true});throw error}
}
async function staticServer(dist, demo, project, port, apiPort) {
  const types={'.html':'text/html; charset=utf-8','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.webp':'image/webp','.ico':'image/x-icon','.woff2':'font/woff2'}
  const server=createServer(async(req,res)=>{
    if(req.url.startsWith('/api/')) {
      const proxy=httpRequest({host:'127.0.0.1',port:apiPort,path:req.url,method:req.method,headers:req.headers},upstream=>{res.writeHead(upstream.statusCode,upstream.headers);upstream.pipe(res)})
      proxy.on('error',()=>{res.writeHead(502);res.end('Actual API unavailable')});req.pipe(proxy);return
    }
    try {
      const pathname=decodeURIComponent(new URL(req.url,'http://127.0.0.1').pathname)
      const route=staticRequestPath(pathname,dist,demo,project)
      if(route.redirect){res.writeHead(308,{Location:route.redirect});res.end();return}
      if(route.status){res.writeHead(route.status);res.end();return}
      let file=route.file
      try {const metadata=await lstat(file);if(metadata.isSymbolicLink()){res.writeHead(403);res.end();return}if(!metadata.isFile())throw new Error('Not a file')}
      catch {if(!route.spaFallback){res.writeHead(404);res.end();return}file=join(route.base,'index.html')}
      const data=await readFile(file);res.writeHead(200,{'Content-Type':types[extname(file)]||'application/octet-stream'});res.end(data)
    } catch {res.writeHead(500);res.end('Static acceptance server failed')}
  })
  await new Promise((yes,no)=>{server.once('error',no);server.listen(port,'127.0.0.1',yes)})
  return server
}
async function artifactFiles(directory) {
  const files=[]
  for(const entry of await readdir(directory,{withFileTypes:true})) {
    const path=join(directory,entry.name)
    if(entry.isSymbolicLink())throw new Error('Acceptance artifacts cannot contain symlinks')
    if(entry.isDirectory())files.push(...await artifactFiles(path));else files.push(path)
  }
  return files
}
export async function main(input=process.env) {
  const contract=validateBrowserEnvironment(input)
  const font=await validateReportFontEnvironment(input)
  const env=environment(input),project=resolve(root,contract.project),frontend=join(project,'frontend')
  console.log(`Verified complete report font SHA-256: ${font.sha256}`)
  await run('bash',[join(root,'scripts/verify-care-plan-mysql.sh'),'--check-guards'],root,env)
  env.CARE_PLAN_BROWSER_SERVER_UUID=await verifyService(env)
  const outputs=join(frontend,'test-results')
  try {assert.ok(!(await lstat(outputs)).isSymbolicLink(),'Artifact directory must not be a symlink')}catch(error){if(error.code!=='ENOENT')throw error}
  await mkdir(outputs,{recursive:true})
  // Build the real application and the real edition. Never use a dev/mock frontend.
  await run('mvn',['-B','-DskipTests','package','org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath','-Dmdep.includeScope=test','-Dmdep.outputFile=target/care-plan-browser-classpath.txt'],project,env)
  const productionJar=await resolveProductionJar(project,contract.project)
  const jarEntries=await capture('jar',['tf',productionJar],env)
  assert.ok(!/CarePlanBrowserApplication|SyntheticExternalServices|care-plan-e2e-fixture\.sql/.test(jarEntries),'Test launcher/external overrides/fixture must never enter the production artifact')
  const entries=new Set(jarEntries.split(/\r?\n/)),testClasses=join(project,'target/test-classes')
  for(const file of (await artifactFiles(testClasses)).filter(file=>file.endsWith('.class'))) {
    const name=relative(testClasses,file)
    assert.ok(!entries.has(name) && !entries.has('BOOT-INF/classes/'+name),'A test-only class entered the production artifact')
  }
  const tracked=(await capture('git',['-C',root,'ls-files','-z','--',relative(root,frontend)+'/'],env)).split('\0').filter(Boolean).map(name=>relative(frontend,join(root,name)))
  const cleanFrontend=await createCleanFrontendBuild(frontend,tracked)
  try {
  await run('npm',['run','build'],cleanFrontend,env)
  const dependencies=(await readFile(join(project,'target/care-plan-browser-classpath.txt'),'utf8')).trim()
  const app=await run('java',['-Duser.timezone=UTC','-cp',[join(project,'target/test-classes'),join(project,'target/classes'),dependencies].join(':'),'org.familyhealthcare.service.CarePlanBrowserApplication'],project,env,{background:true})
  let server
  try {
    await waitReady(`http://127.0.0.1:${contract.backendPort}/api/health`,app)
    server=await staticServer(join(cleanFrontend,'dist'),join(project,'demo'),contract.project,contract.browserPort,contract.backendPort)
    await run('npm',['run','test:e2e'],frontend,{...env,CARE_PLAN_E2E_BASE_URL:`http://127.0.0.1:${contract.browserPort}`,CARE_PLAN_E2E_LANGUAGE:contract.project==='cn'?'cn':'en'})
    const files=await artifactFiles(outputs),videos=files.filter(f=>f.endsWith('.webm')),screenshots=files.filter(f=>f.endsWith('.png'))
    assert.ok(videos.length>0,'Genuine Playwright videos are required');assert.ok(screenshots.length>0,'Successful workflow screenshots are required')
    const recordingEvidence=[]
    for(const video of videos) {
      recordingEvidence.push(await probeVideo(video,env))
      await run('ffmpeg',['-v','error','-y','-i',video,'-frames:v','1',video.replace(/\.webm$/,'.first-frame.png')],root,env)
      await run('ffmpeg',['-v','error','-y','-i',video,'-c:v','libx264','-preset','fast','-pix_fmt','yuv420p','-movflags','+faststart',video.replace(/\.webm$/,'.mp4')],root,env)
    }
    // No traces, HAR, storageState, dumps, request headers or login responses are uploaded.
    assert.ok(files.every(f=>/\.(webm|png|mp4)$/.test(f) || ['.last-run.json','acceptance-evidence.json'].includes(relative(outputs,f))),'Unexpected artifact type')
    await writeFile(join(outputs,'acceptance-evidence.json'),JSON.stringify({language:contract.project==='cn'?'cn':'en',actualSpring:true,actualMySQL:true,builtVue:true,videoCount:videos.length,screenshotCount:screenshots.length,recordings:recordingEvidence,trace:false},null,2))
    console.log('Actual Spring / native MySQL / built Vue browser assertions and genuine recording probes passed')
  } finally {
    if(server)await new Promise(yes=>server.close(yes))
    app.child.kill('SIGTERM'); await Promise.race([app.done.catch(()=>{}),new Promise(yes=>setTimeout(yes,5000))]);if(app.child.exitCode==null)app.child.kill('SIGKILL')
  }
  } finally {await rm(cleanFrontend,{recursive:true,force:true})}
}
if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href) main().catch(()=>{console.error('Care-plan browser acceptance failed; inspect sanitized command/test output');process.exitCode=1})
