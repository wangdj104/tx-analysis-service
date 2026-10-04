import test from 'node:test'
import assert from 'node:assert/strict'
import { validateBrowserEnvironment, staticRequestPath, createCleanFrontendBuild, safeBootDiagnostic } from '../verify-care-plan-browser.mjs'
import * as browserRunner from '../verify-care-plan-browser.mjs'
import { appPath } from '../../frontend/e2e/paths.mjs'
import { mkdtemp, mkdir, writeFile, readFile, readdir, symlink, rm, chmod } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHash } from 'node:crypto'

for(const [edition, pomPath] of [['.', '../../pom.xml'], ['cn', '../../cn/pom.xml']]) {
  test(`${edition} production jar follows the actual Maven finalName and ignores unrelated jars`, async () => {
    assert.equal(typeof browserRunner.resolveProductionJar,'function','Exact edition jar resolver is required')
    const pom=await readFile(new URL(pomPath,import.meta.url),'utf8')
    const declared=pom.match(/<finalName>([^<]+)<\/finalName>/)?.[1]
    const finalName=declared==='${project.name}'?pom.match(/<name>([^<]+)<\/name>/)?.[1]:declared
    assert.ok(finalName,'Actual edition Maven finalName must resolve')
    const project=await mkdtemp(join(tmpdir(),'care-plan-jar-contract-'))
    try {
      await mkdir(join(project,'target'))
      const expected=join(project,'target',finalName+'.jar')
      await writeFile(expected,'synthetic path fixture; never executed as an application')
      await writeFile(join(project,'target','unrelated.jar'),'decoy')
      assert.equal(await browserRunner.resolveProductionJar(project,edition),expected)
      await rm(expected)
      await assert.rejects(browserRunner.resolveProductionJar(project,edition),{code:'ENOENT'})
      await symlink(join(project,'target','unrelated.jar'),expected)
      await assert.rejects(browserRunner.resolveProductionJar(project,edition),/regular.*jar/)
    } finally {await rm(project,{recursive:true,force:true})}
  })
}
test('production jar resolver rejects an unsupported edition before inspecting files', async () => {
  assert.equal(typeof browserRunner.resolveProductionJar,'function','Exact edition jar resolver is required')
  await assert.rejects(browserRunner.resolveProductionJar('/nonexistent-project','../other'),/edition/)
})

const valid = { CARE_PLAN_TEST_ONLY: 'true', CARE_PLAN_BROWSER_REQUIRED: 'true', CI: 'true', GITHUB_ACTIONS: 'true',
  CARE_PLAN_MYSQL_HOST: '127.0.0.1', CARE_PLAN_MYSQL_PORT: '13306', CARE_PLAN_MYSQL_DATABASE: 'care_plan_test_fresh',
  CARE_PLAN_MYSQL_UPGRADE_DATABASE: 'care_plan_test_upgrade', CARE_PLAN_MYSQL_RESTORE_DATABASE: 'care_plan_test_restore',
  CARE_PLAN_MYSQL_USER: 'care_plan_test_browser', CARE_PLAN_MYSQL_PASSWORD: 'SyntheticTestOnly0123456789',
  CARE_PLAN_MYSQL_CONTAINER_ID: '1234567890abcdef', CARE_PLAN_MYSQL_PROJECT: '.', CARE_PLAN_E2E_PASSWORD: 'SyntheticLoginOnly0123456789' }
test('browser execution requires explicit disposable configuration', () => {
  assert.deepEqual(validateBrowserEnvironment(valid), { project: '.', backendPort: 18081, browserPort: 14173 })
  for (const key of Object.keys(valid)) assert.throws(() => validateBrowserEnvironment({ ...valid, [key]: undefined }), /guard/)
})
for (const [key, value] of Object.entries({ CARE_PLAN_TEST_ONLY:'false', CARE_PLAN_BROWSER_REQUIRED:'false', CI:'false',
  GITHUB_ACTIONS:'false', CARE_PLAN_MYSQL_HOST:'db.example.org', CARE_PLAN_MYSQL_PORT:'3306?production',
  CARE_PLAN_MYSQL_DATABASE:'family_health', CARE_PLAN_MYSQL_USER:'root', CARE_PLAN_MYSQL_CONTAINER_ID:'production',
  CARE_PLAN_MYSQL_PROJECT:'../production', CARE_PLAN_E2E_PASSWORD:'short' })) {
  test(`browser rejects unsafe ${key}`, () => assert.throws(() => validateBrowserEnvironment({ ...valid, [key]:value }), /guard/))
}
test('browser rejects colliding databases and arbitrary service ports', () => {
  assert.throws(() => validateBrowserEnvironment({ ...valid, CARE_PLAN_MYSQL_RESTORE_DATABASE:valid.CARE_PLAN_MYSQL_UPGRADE_DATABASE }), /guard/)
  assert.throws(() => validateBrowserEnvironment({ ...valid, CARE_PLAN_E2E_API_PORT:'80' }), /guard/)
  assert.throws(() => validateBrowserEnvironment({ ...valid, CARE_PLAN_E2E_WEB_PORT:'8080' }), /guard/)
})
test('CN production mount preserves /cn/ routes and serves actual assets beneath dist', () => {
  assert.deepEqual(staticRequestPath('/cn/assets/app.js','/tmp/dist','/tmp/demo','cn'),{base:'/tmp/dist',file:'/tmp/dist/assets/app.js',spaFallback:false})
  assert.deepEqual(staticRequestPath('/cn/care-plans/123','/tmp/dist','/tmp/demo','cn'),{base:'/tmp/dist',file:'/tmp/dist/care-plans/123',spaFallback:true})
  assert.deepEqual(staticRequestPath('/login','/tmp/dist','/tmp/demo','cn'),{status:404})
  assert.deepEqual(staticRequestPath('/cn','/tmp/dist','/tmp/demo','cn'),{redirect:'/cn/'})
  assert.deepEqual(staticRequestPath('/__demo/app.js','/tmp/dist','/tmp/demo','cn'),{base:'/tmp/demo',file:'/tmp/demo/app.js',spaFallback:false})
  assert.deepEqual(staticRequestPath('/__demo/','/tmp/dist','/tmp/demo','cn'),{base:'/tmp/demo',file:'/tmp/demo/index.html',spaFallback:false})
  assert.deepEqual(staticRequestPath('/assets/app.js','/tmp/dist','/tmp/demo','.'),{base:'/tmp/dist',file:'/tmp/dist/assets/app.js',spaFallback:false})
  assert.deepEqual(staticRequestPath('/care-plans/123','/tmp/dist','/tmp/demo','.'),{base:'/tmp/dist',file:'/tmp/dist/care-plans/123',spaFallback:true})
  assert.deepEqual(staticRequestPath('/cn/../../outside','/tmp/dist','/tmp/demo','cn'),{status:403})
})
test('edition-aware navigation leaves API/demo origin and query/hash intact', () => {
  assert.equal(appPath('/login','en'),'/login')
  assert.equal(appPath('/login','cn'),'/cn/login')
  assert.equal(appPath('/care?patientId=9001#plans','cn'),'/cn/care?patientId=9001#plans')
  assert.equal(appPath('/cn/care','cn'),'/cn/care')
  assert.equal(appPath('/__demo/#plans','cn'),'/__demo/#plans')
  assert.equal(appPath('/api/care-plans','cn'),'/api/care-plans')
  assert.throws(()=>appPath('//external.invalid','cn'),/path/)
})
test('clean build copies only declared source, never opens .env variants or follows source symlinks', async () => {
  const source=await mkdtemp(join(tmpdir(),'care-plan-build-source-'))
  let stage
  try {
    await mkdir(join(source,'node_modules'));await mkdir(join(source,'src'))
    await writeFile(join(source,'package.json'),'{"scripts":{"build":"vite build"}}')
    await writeFile(join(source,'vite.config.js'),"export default {base:'/cn/'}")
    await writeFile(join(source,'src/main.js'),'synthetic approved source')
    await writeFile(join(source,'untracked-private.txt'),'must not enter the declared source build')
    for(const name of ['.env','.env.local','.env.production','.env.production.local','.env.example'])await symlink('/nonexistent-sensitive-source',join(source,name))
    stage=await createCleanFrontendBuild(source,['package.json','vite.config.js','src/main.js','.env','.env.local','.env.production','.env.production.local','.env.example'])
    assert.equal(await readFile(join(stage,'vite.config.js'),'utf8'),"export default {base:'/cn/'}")
    assert.equal(await readFile(join(stage,'src/main.js'),'utf8'),'synthetic approved source')
    assert.equal((await readdir(stage)).some(name=>name.startsWith('.env')),false)
    assert.equal((await readdir(stage)).includes('untracked-private.txt'),false)
    await symlink('/nonexistent-sensitive-source',join(source,'src/unsafe.js'))
    await assert.rejects(createCleanFrontendBuild(source,['src/unsafe.js']),/symlink/)
  } finally {if(stage)await rm(stage,{recursive:true,force:true});await rm(source,{recursive:true,force:true})}
})
test('clean build refuses symlink ancestors before copying a declared file', async () => {
  const source=await mkdtemp(join(tmpdir(),'care-plan-build-ancestor-')),outside=await mkdtemp(join(tmpdir(),'care-plan-outside-source-'))
  try {
    await mkdir(join(source,'node_modules'));await writeFile(join(outside,'main.js'),'outside declared source')
    await symlink(outside,join(source,'src'),'dir')
    await assert.rejects(createCleanFrontendBuild(source,['src/main.js']),/symlink/)
  } finally {await rm(source,{recursive:true,force:true});await rm(outside,{recursive:true,force:true})}
})
test('startup diagnostics admit bounded class names only, never raw error/credential messages', () => {
  const safe='CARE_PLAN_BROWSER_BOOT_FAILURE org.springframework.beans.factory.BeanCreationException > java.lang.IllegalArgumentException'
  assert.equal(safeBootDiagnostic(safe),safe)
  for(const value of ['raw error password=SyntheticSecret',safe+' password=SyntheticSecret','CARE_PLAN_BROWSER_BOOT_FAILURE SyntheticSecret',safe+'\nAuthorization: Bearer SyntheticToken','CARE_PLAN_BROWSER_BOOT_FAILURE '+'org.'+'x'.repeat(3000)])assert.equal(safeBootDiagnostic(value),null)
})

test('selected report font survives the narrow environment while unrelated secrets never do', () => {
  assert.equal(typeof browserRunner.environment,'function')
  const input={...valid,REPORT_PDF_FONT_PATH:'/tmp/owned/care-report-font/wqy-microhei.ttf',RUNNER_TEMP:'/tmp/owned',DB_PASSWORD:'forbidden',OPENAI_API_KEY:'forbidden',JWT_SECRET:'forbidden',UNRELATED:'forbidden'}
  const output=browserRunner.environment(input)
  assert.equal(output.REPORT_PDF_FONT_PATH,input.REPORT_PDF_FONT_PATH)
  assert.equal(output.RUNNER_TEMP,input.RUNNER_TEMP)
  for(const key of ['DB_PASSWORD','OPENAI_API_KEY','JWT_SECRET','UNRELATED'])assert.equal(Object.hasOwn(output,key),false)
})
test('report font requires exact owned regular readable CI path and rejects missing or symlink paths', async () => {
  assert.equal(typeof browserRunner.validateReportFontEnvironment,'function')
  const base=await mkdtemp(join(tmpdir(),'care-report-font-guard-')),directory=join(base,'care-report-font'),font=join(directory,'wqy-microhei.ttf')
  try {
    const bytes=Buffer.from([0,1,0,0,...Buffer.alloc(12)])
    await mkdir(directory);await writeFile(font,bytes)
    const summary={sourceSha256:'a'.repeat(64),ttfSha256:createHash('sha256').update(bytes).digest('hex'),glyphCount:3,unicodeCmapCount:2,unicodeCodepointCount:2,tableCount:10,nameSha256:'b'.repeat(64),fontPackageVersion:'0.2.0',fonttoolsPackageVersion:'4.57.0',fonttoolsVersion:'4.57.0',faceIndex:0,copyrightSha256:'c'.repeat(64)}
    await writeFile(join(directory,'font-preparation.json'),JSON.stringify(summary))
    const input={CI:'true',GITHUB_ACTIONS:'true',RUNNER_TEMP:base,REPORT_PDF_FONT_PATH:font}
    assert.equal((await browserRunner.validateReportFontEnvironment(input)).path,font)
    for(const key of Object.keys(input))await assert.rejects(browserRunner.validateReportFontEnvironment({...input,[key]:undefined}),/font/)
    await assert.rejects(browserRunner.validateReportFontEnvironment({...input,REPORT_PDF_FONT_PATH:join(base,'other.ttf')}),/font/)
    await writeFile(font,Buffer.from([0,1,0,0,...Buffer.alloc(12,9)]))
    await assert.rejects(browserRunner.validateReportFontEnvironment(input),/hash/)
    await writeFile(font,bytes);await chmod(font,0)
    await assert.rejects(browserRunner.validateReportFontEnvironment(input),/readable/)
    await chmod(font,0o600)
    await writeFile(join(directory,'font-preparation.json'),JSON.stringify({...summary,token:'forbidden'}))
    await assert.rejects(browserRunner.validateReportFontEnvironment(input),/metadata/)
    await writeFile(join(directory,'font-preparation.json'),JSON.stringify(summary))
    await rm(font);await symlink('/nonexistent-source',font)
    await assert.rejects(browserRunner.validateReportFontEnvironment(input),/font|symlink/)
    await rm(font);await rm(directory,{recursive:true});await symlink('/nonexistent-directory',directory)
    await assert.rejects(browserRunner.validateReportFontEnvironment(input),/font|symlink/)
  } finally {await rm(base,{recursive:true,force:true})}
})
