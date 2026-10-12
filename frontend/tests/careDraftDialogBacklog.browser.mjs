/**
 * Scoped, real Vue/Element Plus DOM acceptance for UI items 1, 3, 6 and 7.
 * EN + CN × six shipped appearance themes × 320/375/390/768/1365px (legacy draft cases retain 375/1365). Synthetic data only.
 * Run: UI_SEVENTEEN_EVIDENCE_DIR=/path/to/evidence node frontend/tests/careDraftDialogBacklog.browser.mjs
 * Compile without launching a browser: append --compile-only
 * Optional CHROMIUM_PATH selects a browser; otherwise Playwright's installed Chromium is used.
 * Browser acceptance must run on an authorized runner. Compile-only never reports browser passes.
 */
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, readFileSync, readdirSync, rmSync, mkdirSync, renameSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join, relative } from 'node:path'
import { build, preview } from 'vite'
import vue from '@vitejs/plugin-vue'
import { chromium } from 'playwright'
import { contrastRatio, assertTaskHierarchy, assertTaskDensity, assertMedicationSemantics, measureTaskGroups, measureMedicationStatuses } from './careNineMeasurements.mjs'

const repo = fileURLToPath(new URL('../../', import.meta.url))
const evidence = process.env.UI_SEVENTEEN_EVIDENCE_DIR || process.env.CARE_DRAFT_EVIDENCE_DIR || join(repo, 'frontend/test-results/ui-seventeen')
const compileOnly = process.argv.includes('--compile-only')
const themes = ['platform', 'white', 'blue', 'mint', 'sand', 'dark']
const widths = [320, 375, 390, 768, 1365], legacyWidths = [375, 1365], editions = ['en', 'cn'], itemIds = [1, 3, 6, 7]
const commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim()
const sha256 = value => createHash('sha256').update(value).digest('hex')
const harnessSha256 = sha256(readFileSync(fileURLToPath(import.meta.url)))
const densityBaselineCommit = 'c8756525dd7f7892dc4a836ac22d677af764c132'
const densityBaselineDigests = { en: 'a9f32af9e90a781388d82e8db8891c2e6c015fa11f5e8c7164cb92db5edd74d1', cn: '76f7667b8b054ee1af9353566e50d1978e8c338f553dd962a4d77fab3ac08e4c' }
const baselineSources = Object.fromEntries(['en', 'cn'].map(edition => {
  const path = `frontend/tests/fixtures/care-density-baseline/${edition}.vue`
  const digest = sha256(readFileSync(join(repo, path)))
  assert.equal(digest, densityBaselineDigests[edition], 'Frozen baseline must remain byte-identical to source commit c8756525')
  return [edition, { path, sha256: digest }]
}))
const sourceFiles = []
function sourceTree(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) sourceTree(path)
    else if (entry.isFile()) sourceFiles.push({ path: relative(repo, path), sha256: sha256(readFileSync(path)) })
  }
}
sourceTree(join(repo, 'frontend/src')); sourceTree(join(repo, 'cn/frontend/src'))
sourceFiles.push(...Object.values(baselineSources), { path: 'frontend/tests/careNineMeasurements.mjs', sha256: sha256(readFileSync(join(repo, 'frontend/tests/careNineMeasurements.mjs'))) })
const sourceSha256 = sha256(JSON.stringify(sourceFiles))
const matrix = editions.flatMap(edition => widths.flatMap(viewport => themes.map(theme => ({ itemIds: legacyWidths.includes(viewport) ? itemIds : [1], nineItemIds: [5, 6], edition, viewport, theme }))))
mkdirSync(evidence, { recursive: true })
mkdirSync(join(evidence, 'care'), { recursive: true })
const report = {
  schemaVersion: 1, commit, mode: compileOnly ? 'compile-only' : 'browser', harnessSha256, sourceSha256,
  startedAt: new Date().toISOString(), results: [],
  planned: matrix.map(cell => ({ ...cell, status: 'not-run' })),
  sourceFiles, densityBaseline: { commit: densityBaselineCommit, sources: baselineSources },
  scope: 'Actual care, entry, consultation and CareJourney components with shipped CSS/appearance application; synthetic API responses and a test-only router toolbar. No production App/auth/server acceptance or assistive-technology claim.',
  fixture: 'Fictional adults, authorized records #11/#22, unchanged synthetic medication instructions. No real health data or external API calls.',
  limitations: compileOnly ? ['Browser launch is deliberately not attempted: the confirmed process_singleton socket permission denial remains. DOM assertions/screenshots are unexecuted.'] : [],
  compilation: [],
}
function saveReport() {
  report.finishedAt = new Date().toISOString()
  const temp = join(evidence, 'care-results.json.tmp')
  writeFileSync(temp, JSON.stringify(report, null, 2) + '\n')
  renameSync(temp, join(evidence, 'care-results.json'))
}
saveReport()

const labels = {
  en: { save: 'Save', cancel: 'Cancel', keep: 'Keep editing', discard: 'Discard changes', discardChat: 'Discard draft', family: 'Family care', recordDose: 'Record as taken', record: /Record health/, stock: 'Set or correct stock', chat: 'Open chat', refresh: 'Refresh', send: 'Send message', start: 'Start consultation', title: 'Title', medication: 'Medication', date: 'Effective date', time: 'Appointment time' },
  cn: { save: '保存', cancel: '取消', keep: '继续编辑', discard: '放弃修改', discardChat: '放弃草稿', family: '我是照护者', recordDose: '代记已服', record: /记录健康/, stock: '设置 / 修正库存', chat: '进入聊天', refresh: '刷新', send: '发送消息', start: '开始问诊', title: '标题', medication: '药品', date: '生效日期', time: '预约时间' },
}
// Independent six-preset oracle. Platform/white share a page/paper but differ
// in surface-subtle; relabeling a root dataset cannot satisfy this assertion.
const themeOracle = {
  platform: ['#ffffff', '#f4f7f6', '#f0f7f3', '#176b59'],
  white: ['#ffffff', '#f5f7fa', '#f0f7f3', '#176b59'],
  blue: ['#ffffff', '#f1f6fb', '#edf4fb', '#225b91'],
  mint: ['#ffffff', '#f0f7f3', '#edf7f1', '#176b59'],
  sand: ['#fffdf9', '#f8f2e9', '#f8f0e3', '#7a5429'],
  dark: ['#18262e', '#203139', '#203e37', '#7cd3b5'],
}
const expectedPageColors = { platform: 'rgb(245, 247, 251)', white: 'rgb(245, 247, 251)', blue: 'rgb(234, 241, 248)', mint: 'rgb(234, 244, 239)', sand: 'rgb(247, 240, 229)', dark: 'rgb(16, 26, 33)' }
const expectedPaperColors = { platform: 'rgb(255, 255, 255)', white: 'rgb(255, 255, 255)', blue: 'rgb(255, 255, 255)', mint: 'rgb(255, 255, 255)', sand: 'rgb(255, 253, 249)', dark: 'rgb(24, 38, 46)' }
function exportedNames(path) {
  return [...readFileSync(path, 'utf8').matchAll(/export\s+(?:async\s+)?(?:const|function)\s+(\w+)/g)].map(match => match[1])
}
function fixtureModule(names, call) { return names.map(name => `export const ${name}=(...args)=>${call}(${JSON.stringify(name)},args);`).join('\n') }

function makeHarness(editionName) {
  const edition = join(repo, editionName === 'en' ? 'frontend' : 'cn/frontend')
  const root = mkdtempSync(join(repo, 'frontend/.care-dom-qa-'))
  const locale = editionName === 'en' ? 'en' : 'zh-cn'
  writeFileSync(join(root, 'index.html'), '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><div id="app"></div><script type="module" src="/main.js"></script></body></html>')
  const main = `
    import {createApp,h,ref,reactive} from 'vue';
    import {createRouter,createWebHistory,RouterView,useRoute} from 'vue-router';
    import ElementPlus from 'element-plus'; import locale from 'element-plus/es/locale/lang/${locale}';
    import 'element-plus/dist/index.css';
    import '/@fs/${edition}/src/styles/app-theme.css';
    import '/@fs/${edition}/src/styles/mobile-optimization.css';
    import '/@fs/${edition}/src/styles/health-redesign.css';
    import '/@fs/${edition}/src/styles/care-accessibility.css';
    import '/@fs/${edition}/src/styles/health-appearance.css';
    import {initializeAppearance} from '/@fs/${edition}/src/utils/healthAppearance.js';
    import {useCurrentPatient} from '/@fs/${edition}/src/composables/useCurrentPatient.js';
    import Care from '/@fs/${edition}/src/views/CareCenter.vue';
    import Entry from '/@fs/${edition}/src/components/CareEntryDialog.vue';
    import Journey from '/@fs/${edition}/src/views/CareJourneyManager.vue';
    import BaselinePlan from '/@fs/${join(repo, baselineSources[editionName].path)}';
    initializeAppearance();
    const patients=useCurrentPatient(); patients.setPatientList([{id:11,patientName:'Synthetic patient'},{id:22,patientName:'Synthetic second patient'}]);
    const state=reactive({writes:[],reads:[],failTarget:false,delayTarget:false,pending:0,revoked:false,deferRoomWrite:false,routePending:0});
    let pending=[];
    const room=id=>({id,patient_id:patients.currentPatientId.value,patient_name:patients.currentPatientName.value,doctor_name:'Synthetic doctor',symptom:'Synthetic room '+id,status:'OPEN',mode:'TEXT',can_message:true,can_close:true,messages:[],participants:[]});
    const patient=id=>({id,userId:id,name:id===11?'Synthetic patient':'Synthetic second patient'});
    const context=id=>({patient:patient(id),medications:[{id:17,drugName:'Synthetic medication'}],members:[],reports:[],actions:[],stocks:[{id:17,medicationId:17,drugName:'Synthetic medication',quantity:5,unit:'tablet',low:false}],intakes:['PENDING','MISSED','SNOOZED','TAKEN','SKIPPED','CANCELLED'].map((status,index)=>({id:101+index,status,drugName:'Synthetic '+status+' medication',scheduledAt:'2026-10-11 08:00:00',snoozeUntil:'2026-10-11 08:15:00',dosage:'Synthetic unchanged dose'})),items:[{id:31,kind:'HANDOVER',title:'Synthetic completed task',status:'DONE',details:{note:'Synthetic task context'}},{id:32,kind:'APPOINTMENT',title:'Synthetic cancelled appointment',status:'CANCELLED',details:{}},{id:33,kind:'APPOINTMENT',title:'Synthetic pending appointment',status:'OPEN',details:{}}]});
    const plan={id:71,patientId:11,title:'Synthetic doctor plan',revisionNo:2,instructions:'Synthetic unchanged instructions',version:2,actions:[{id:701,ordinal:1,instruction:'Synthetic reviewed history',status:'CONFIRMED',allowedActions:[]},{id:702,patientId:11,patientName:'Synthetic patient',ordinal:2,instruction:'Synthetic pending execution',status:'OPEN',assignedUserId:11,dueAt:'2026-10-11T08:00:00Z',events:[{id:1702,eventType:'FOLLOW_UP_RECORDED',actorName:'Synthetic care member',actorRole:'nurse',note:'Synthetic existing activity note',recordedAt:'2026-10-10T08:00:00Z'}],allowedActions:['SUBMIT_RECEIPT'],allowedEntryModes:['SELF']},{id:703,ordinal:3,instruction:'Synthetic waiting review',status:'SUBMITTED',allowedActions:[]}]};
    window.careQa={
      state,
      async careApi(name,args){state.reads.push({name,args});if(name==='getCareHome')return {data:[{patient:patient(11),intakes:[]},{patient:patient(22),intakes:[]}]};if(name==='getCareContext')return {data:context(args[0])};if(name==='getStockHistory')return {data:[]};state.writes.push({name,args});return {data:{}}},
      async planApi(name,args){if(name==='getCarePlanCapabilities')return {code:200,data:{enabled:true}};if(name==='listPlans')return {data:{items:[plan],nextCursor:null}};return {data:[]}},
      async journeyApi(name,args){
        state.reads.push({name,args});
        if(name==='getConsultation'){
          const id=Number(args[0]);
          if(state.revoked)throw Object.assign(Error('Synthetic revoked access'),{response:{status:403}});
          if(id===2&&state.delayTarget){state.pending++;await new Promise(resolve=>pending.push(resolve));state.pending--;}
          if(id===2&&state.failTarget)throw Error('Synthetic unavailable target');
          return {data:room(id)};
        }
        if(name==='listConsultations'||name==='listConsultationInbox')return {data:[room(1),room(2),room(3)]};
        if(name==='listClinicians')return {data:[{id:41,real_name:'Synthetic doctor',username:'synthetic-doctor'}]};
        if(name==='startConsultation'){state.writes.push({name,args});return {data:room(1)}};
        if(name==='sendConsultationMessage'){state.writes.push({name,args});return {data:{}}};
        return {data:[]};
      },
      resolve(){state.delayTarget=false;const old=pending;pending=[];old.forEach(resolve=>resolve());},
    };
    const EntryHost={setup(){const route=useRoute(),visible=ref(true);return()=>h(Entry,{modelValue:visible.value,'onUpdate:modelValue':value=>visible.value=value,patientId:patients.currentPatientId.value,patientName:patients.currentPatientName.value,kind:route.query.kind||'ORDER',medications:[{id:17,drugName:'Synthetic medication'}]})}};
    const BaselineHost={setup(){const route=useRoute();return()=>h('div',{'data-qa-baseline':'',style:{width:route.query.width+'px',fontSize:route.query.fontSize,fontFamily:route.query.fontFamily,lineHeight:route.query.lineHeight}},[h(BaselinePlan,{patientId:11,mode:'PATIENT'})])}};
    const Other={render:()=>h('main',{'data-qa-other':''},'Synthetic destination')};
    const router=createRouter({history:createWebHistory(),routes:[{path:'/',redirect:'/care'},{path:'/baseline',component:BaselineHost},{path:'/care',component:Care},{path:'/entry',component:EntryHost},{path:'/care-journey',component:Journey},{path:'/other',component:Other}]});
    let routeResolvers=[];
    router.beforeEach(async to=>{if(state.deferRoomWrite&&to.query.consultationId==='2'){state.routePending++;await new Promise(resolve=>routeResolvers.push(resolve));state.routePending--;}});
    const routes={care:'/care',entry:'/entry?kind=ORDER',appointment:'/entry?kind=APPOINTMENT',journey:'/care-journey?tab=consultation&consultationId=1',room2:'/care-journey?tab=consultation&consultationId=2',other:'/other'};
    function changePatient(){const event=new Event('care-plan-before-context-change',{cancelable:true});window.dispatchEvent(event);if(!event.defaultPrevented)patients.currentPatientId.value=patients.currentPatientId.value===11?22:11;}
    function storage(key){window.dispatchEvent(new StorageEvent('storage',{key,newValue:key===null?null:'synthetic-boundary'}));}
    const button=(id,label,action)=>h('button',{'data-qa':id,type:'button',onClick:action},label);
    const App={setup(){return()=>h('div',{class:'care-workspace'},[
      h('aside',{'data-qa-tools':'',hidden:true,inert:true,style:'position:fixed;z-index:9999;bottom:0;left:0;max-width:100vw;background:var(--paper);border:1px solid var(--line);font:12px sans-serif;'},[
        h('details',[h('summary','Synthetic QA controls'),h('div',{style:'display:flex;flex-wrap:wrap;max-height:140px;overflow:auto;gap:4px;'},[
          ...Object.entries(routes).map(([id,path])=>button('nav-'+id,id,()=>router.push(path))),
          button('patient','Change patient',changePatient),button('force-patient','External patient change',()=>patients.currentPatientId.value=22),
          button('logout','Synthetic logout',()=>window.dispatchEvent(new Event('auth-session-cleared'))),
          ...['token','userId',null,'ordinary-preference'].map(key=>button('storage-'+(key??'clear'),'Storage '+key,()=>storage(key))),
          button('fail-target','Fail target',()=>state.failTarget=true),button('healthy-target','Allow target',()=>state.failTarget=false),
          button('delay-target','Delay target',()=>state.delayTarget=true),button('resolve-target','Resolve target',window.careQa.resolve),
          button('revoke','Revoke consultation',()=>state.revoked=true),
          button('defer-route','Defer internal room route',()=>state.deferRoomWrite=true),button('resolve-route','Resolve room route',()=>{state.deferRoomWrite=false;const old=routeResolvers;routeResolvers=[];old.forEach(resolve=>resolve());}),
        ])]),
        h('output',{'data-qa-route':''},router.currentRoute.value.fullPath),h('output',{'data-qa-patient':''},String(patients.currentPatientId.value)),h('output',{'data-qa-writes':''},String(state.writes.length)),h('output',{'data-qa-pending':''},String(state.pending)),h('output',{'data-qa-route-pending':''},String(state.routePending)),
      ]),
      h(RouterView,null,{default:({Component,route})=>Component?h(Component,{key:route.path+(route.query.kind||'')}):null}),
    ])}};
    const app=createApp(App).use(router).use(ElementPlus,{locale}); router.isReady().then(()=>app.mount('#app'));
  `
  writeFileSync(join(root, 'main.js'), main)
  const virtual = {
    'care-dom-fixture': fixtureModule(exportedNames(join(edition, 'src/api/care.js')), 'window.careQa.careApi'),
    'journey-dom-fixture': fixtureModule(exportedNames(join(edition, 'src/api/careJourney.js')), 'window.careQa.journeyApi'),
    'plan-dom-fixture': fixtureModule(exportedNames(join(edition, 'src/api/carePlan.js')), 'window.careQa.planApi'),
  }
  const config = {
    configFile: false, root,
    plugins: [{ name: 'frozen-density-baseline', enforce: 'pre', transform(code, id) {
      if (id === join(repo, baselineSources[editionName].path)) return code.replace("from './ReceiptDialog.vue'", `from '/@fs/${edition}/src/components/care-plan/ReceiptDialog.vue'`)
    } }, { name: 'synthetic-care-dom-api', resolveId: id => id in virtual ? id : null, load: id => virtual[id] }, vue()],
    resolve: { alias: { '@/api/care': 'care-dom-fixture', '@/api/careJourney': 'journey-dom-fixture', '@/api/carePlan': 'plan-dom-fixture', '@': join(edition, 'src') }, dedupe: ['vue', 'vue-router', 'element-plus'] },
    server: { host: '127.0.0.1', port: 0, hmr: false, fs: { allow: [repo] } }, logLevel: 'error',
  }
  return { root, config, fixtureSha256: sha256(main + JSON.stringify(virtual) + JSON.stringify(baselineSources)) }
}

async function qaClick(page, id) {
  const tools = page.locator('[data-qa-tools]')
  // Test-only controls may overlay a modal only for their own real DOM action.
  // They must never intercept the following production Save/Cancel/input click.
  await tools.evaluate(element => { element.hidden = false; element.inert = false })
  try {
    if (await tools.locator('details').getAttribute('open') === null) await tools.locator('summary').click()
    await page.locator(`[data-qa="${id}"]`).click()
  } finally {
    await tools.evaluate(element => { element.querySelector('details').open = false; element.hidden = true; element.inert = true })
  }
}
async function routeIs(page, path) { await page.waitForFunction(expected => document.querySelector('[data-qa-route]')?.textContent === expected, path) }
async function waitOutput(page, id, value) { await page.waitForFunction(({ id, value }) => document.querySelector(`[data-qa-${id}]`)?.textContent === value, { id, value: String(value) }) }
async function dialog(page) { const target = page.locator('.el-dialog:visible'); await target.waitFor(); return target }
async function button(scope, name) { await scope.getByRole('button', { name, exact: true }).click() }
async function requestTab(page, container, name) {
  // Element Plus implements Arrow navigation, not Enter activation. Use its real
  // DOM key handler to reach a tab even when the mobile nav strip clips it.
  const tabs = page.locator(`${container} > .el-tabs__header [role="tab"]:not(.is-disabled)`)
  const ids = await tabs.evaluateAll(elements => elements.map(element => element.id))
  const index = ids.indexOf(`tab-${name}`)
  assert.ok(index >= 0, `available tab ${name}`)
  await page.locator(`${container} #${ids[(index + ids.length - 1) % ids.length]}`).focus()
  await page.keyboard.press('ArrowRight')
}
async function tab(page, container, name) {
  await requestTab(page, container, name)
  await page.waitForFunction(selector => document.querySelector(selector)?.getAttribute('aria-selected') === 'true', `${container} #tab-${name}`)
}
async function checkTheme(page, theme) {
  // A visible component only proves Vue mounted. Wait for the shipped preset's
  // computed page and token values before taking the first style signature.
  // This remains a read-only, exact oracle; persistent CSS errors time out.
  await page.waitForFunction(({ theme, body, tokens }) => {
    const root = document.documentElement, css = getComputedStyle(root)
    return root.dataset.healthTheme === theme && getComputedStyle(document.body).backgroundColor === body &&
      ['--paper', '--surface-subtle', '--care-50', '--care-700'].every((token, index) => css.getPropertyValue(token).trim().toLowerCase() === tokens[index])
  }, { theme, body: expectedPageColors[theme], tokens: themeOracle[theme] }, { polling: 'raf', timeout: 10000 })
  const actual = await page.evaluate(() => ({ theme: document.documentElement.dataset.healthTheme, body: getComputedStyle(document.body).backgroundColor, tokens: ['--paper', '--surface-subtle', '--care-50', '--care-700'].map(token => getComputedStyle(document.documentElement).getPropertyValue(token).trim().toLowerCase()), ink: getComputedStyle(document.body).color, accent: getComputedStyle(document.documentElement).getPropertyValue('--care-700').trim(), innerWidth: window.innerWidth, innerHeight: window.innerHeight, devicePixelRatio: window.devicePixelRatio }))
  assert.equal(actual.theme, theme, 'real initializeAppearance applies saved preset')
  assert.equal(actual.body, expectedPageColors[theme], 'shipped CSS changes actual page background')
  assert.deepEqual(actual.tokens, themeOracle[theme], 'computed shipped theme tokens match independent six-preset oracle')
  return actual
}
async function checkPaper(target, theme) { assert.equal(await target.evaluate(element => getComputedStyle(element).backgroundColor), expectedPaperColors[theme], 'teleported surface uses shipped theme') }
async function screenshot(page, result, suffix) {
  const filename = `care/${result.edition}-${result.viewport}-${result.theme}-item${result.itemIds[0]}-${suffix}.png`
  const tools = page.locator('[data-qa-tools]')
  assert.equal(await tools.evaluate(element => element.hidden && element.inert), true, 'test controls remain hidden/inert during product capture')
  await page.screenshot({ path: join(evidence, filename), fullPage: true, animations: 'disabled' })
  result.screenshots.push(filename)
  result.screenshotSha256[filename] = sha256(readFileSync(join(evidence, filename)))
}
async function closeCleanDialog(page) { await page.locator('.el-dialog:visible .el-dialog__headerbtn').click(); await page.locator('.el-dialog:visible').waitFor({ state: 'hidden' }) }
async function associated(control, id, invalid = true) {
  await control.page().waitForFunction(({ id, invalid }) => {
    const error = document.getElementById(id)
    const input = [...document.querySelectorAll('input,textarea,[role="combobox"]')].find(element => element.getAttribute('aria-describedby')?.split(/\s+/).includes(id))
    return invalid ? Boolean(error?.textContent?.trim() && input?.getAttribute('aria-invalid') === 'true') : !input
  }, { id, invalid })
  if (invalid) assert.ok((await control.getAttribute('aria-describedby'))?.split(/\s+/).includes(id), 'error is on real inner native control')
}
async function focused(page, id) { await page.waitForFunction(id => document.activeElement?.getAttribute('aria-describedby')?.split(/\s+/).includes(id), id) }
const titleControl = page => page.locator('.care-entry .el-form-item').first().locator('input').first()
const requiredControl = (page, field) => page.locator(`[data-care-required-field="${field}"] input:not([type="hidden"])`).first()
async function selectMedication(page) {
  await requiredControl(page, 'medicationId').click()
  await page.getByRole('option', { name: 'Synthetic medication', exact: true }).click()
}
async function setDate(page, field, value) {
  const input = requiredControl(page, field)
  await input.fill(value); await input.press('Enter'); await input.press('Tab')
}
async function draftWarning(page, labels, keep = true) {
  await page.locator('.draft-warning').waitFor()
  await button(page.locator('.draft-warning'), keep ? labels.keep : labels.discard)
  await page.locator('.draft-warning').waitFor({ state: 'hidden' })
}
async function chatWarning(page, labels, keep = true) {
  await page.locator('.el-message-box:visible').waitFor()
  await button(page.locator('.el-message-box:visible'), keep ? labels.keep : labels.discardChat)
  await page.locator('.el-message-box:visible').waitFor({ state: 'hidden' })
}
function chatRow(page, id) { return page.locator('.consultation-history .el-table__body-wrapper tbody tr').filter({ hasText: `Synthetic room ${id}` }) }
async function openChat(page, labels, id) { await button(chatRow(page, id), labels.chat) }
async function roomIs(page, id) { await page.locator('.consultation-context').filter({ hasText: `Synthetic room ${id}` }).waitFor() }
const composer = page => page.locator('.consultation-editor textarea')

async function todayAcceptance(page, labels, result) {
  await page.locator('[data-testid="plan-action-702"]').waitFor()
  const quick = page.locator('.quick-actions'), plans = page.locator('.plan-tasks'), history = page.locator('.care-history')
  assert.ok(await quick.evaluate(element => Boolean(element.compareDocumentPosition(document.querySelector('.plan-tasks')) & Node.DOCUMENT_POSITION_FOLLOWING)), 'quick recording precedes plan work in DOM')
  assert.ok((await quick.boundingBox()).y < (await plans.boundingBox()).y, 'quick recording is visually above plan work')
  const ordered = await plans.locator('.action-group').evaluateAll(elements => elements.map(element => element.dataset.testid))
  assert.deepEqual(ordered, ['plan-action-702', 'plan-action-703', 'plan-action-701'], 'pending and waiting work precede reviewed history')
  assert.notEqual(await plans.locator('[data-testid="plan-action-702"]').getAttribute('open'), null)
  assert.equal(await plans.locator('[data-testid="plan-action-701"]').getAttribute('open'), null)
  assert.equal(await history.getAttribute('open'), null, 'medication/care history starts closed')
  const pending = page.locator('.care-columns .care-task').filter({ has: page.locator('.medication-status') })
  assert.equal(await pending.count(), 3)
  for (const status of ['PENDING', 'MISSED', 'SNOOZED']) assert.equal(await pending.filter({ hasText: `Synthetic ${status} medication` }).count(), 1)
  assert.equal(await history.locator('.care-task').count(), 5, 'taken/skipped/cancelled doses and done/cancelled tasks retained')
  assert.equal(await history.locator('.care-task').first().isVisible(), false)
  const measurements = await careVisualMeasurements(page, result)
  await screenshot(page, result, 'today-collapsed')
  await history.locator('summary').focus(); await history.locator('summary').press('Enter')
  for (const status of ['TAKEN', 'SKIPPED', 'CANCELLED']) assert.equal(await history.locator('.care-task').filter({ hasText: `Synthetic ${status} medication` }).isVisible(), true)
  assert.equal(await history.getByRole('button').count(), 2, 'completed/cancelled task View actions remain')
  await plans.locator('[data-testid="plan-action-701"] > summary').click()
  assert.equal(await plans.locator('[data-testid="plan-action-701"] .action-card').isVisible(), true)
  await screenshot(page, result, 'history-expanded')
  await history.locator('summary').press('Enter'); assert.equal(await history.getAttribute('open'), null)
  await plans.locator('[data-testid="plan-action-702"] .action-event-history > summary').click()
  assert.equal(await plans.getByText('Synthetic existing activity note', { exact: true }).isVisible(), true, 'Existing activity history expands through its real disclosure')
  await plans.locator('[data-testid="plan-action-702"] .action-event-history > summary').click()
  await page.getByTestId('record-702').click()
  await page.locator('dialog.receipt-dialog[open]').waitFor()
  assert.match(await page.locator('dialog.receipt-dialog').innerText(), /Synthetic pending execution/)
  await page.getByTestId('close-receipt').click(); await page.locator('dialog.receipt-dialog').waitFor({ state: 'hidden' })
  await waitOutput(page, 'writes', 0)
  result.nineMeasurements = { 5: measurements.hierarchy, 6: measurements.medication }
  result.nineItemIds = [5, 6]
  result.checks.push('Quick recording first; pending/waiting plans first; all six intake statuses retained; closed history accessible by keyboard; original history actions retained')
}

async function careVisualMeasurements(page, result) {
  const plans = page.locator('.plan-tasks')
  const actions = await plans.locator('.action-group').evaluateAll(measureTaskGroups)
  result.nineMeasurements[5] = { actions }
  assertTaskHierarchy(actions, result.viewport)
  const statuses = (await page.locator('.care-columns .medication-status').evaluateAll(measureMedicationStatuses)).map(row => ({ ...row, contrast: contrastRatio(row.color, row.background) }))
  result.nineMeasurements[6] = { theme: result.theme, statuses }
  assertMedicationSemantics(statuses)
  const expectedLabels = result.edition === 'en' ? { PENDING: 'Due', MISSED: 'Missed', SNOOZED: 'Snoozed' } : { PENDING: '待服用', MISSED: '已漏服', SNOOZED: '已稍后提醒' }
  for (const status of statuses) assert.equal(status.label, expectedLabels[status.status], 'Medication status retains its actual localized meaning')
  const measureDensity = node => {
    const css = getComputedStyle(node), box = node.getBoundingClientRect()
    return { width: box.width, fontSize: css.fontSize, fontFamily: css.fontFamily, lineHeight: css.lineHeight,
      regionHeight: box.height, planCardHeight: node.querySelector('.plan-card').getBoundingClientRect().height,
      openActionHeights: [...node.querySelectorAll('.action-group[open]')].map(action => action.getBoundingClientRect().height),
      actionIds: [...node.querySelectorAll('.action-group')].map(action => action.dataset.testid) }
  }
  const current = await plans.evaluate(measureDensity)
  const baselinePage = await page.context().newPage()
  let baseline
  try {
    const url = new URL('/baseline', page.url())
    for (const key of ['width', 'fontSize', 'fontFamily', 'lineHeight']) url.searchParams.set(key, String(current[key]))
    await baselinePage.goto(url.href)
    await baselinePage.getByTestId('plan-action-702').waitFor()
    baseline = await baselinePage.locator('.plan-tasks').evaluate(measureDensity)
    assert.deepEqual(baseline.actionIds, current.actionIds, 'Density compares the same real API fixture in the same order')
    for (const property of ['fontSize', 'fontFamily', 'lineHeight']) assert.equal(baseline[property], current[property], `Baseline inherits identical ${property}`)
    // Actual baseline pixels accompany numeric evidence; no historical heights are invented.
    await screenshot(baselinePage, result, 'density-frozen-baseline')
  } finally { await baselinePage.close() }
  const density = { measuredSelector: '.plan-card', baselineCommit: densityBaselineCommit, baselineSourceSha256: baselineSources[result.edition].sha256,
    baselineSourcePath: baselineSources[result.edition].path, baselineHeights: [baseline.planCardHeight], currentHeights: [current.planCardHeight],
    baselineContainerWidth: baseline.width, currentContainerWidth: current.width,
    baselineRegionHeight: baseline.regionHeight, currentRegionHeight: current.regionHeight,
    baselineOpenActionHeights: baseline.openActionHeights, currentOpenActionHeights: current.openActionHeights,
    actionIds: current.actionIds, fontSize: current.fontSize, fontFamily: current.fontFamily, lineHeight: current.lineHeight }
  result.nineMeasurements[5].density = density
  density.regionReductionRatio = 1 - current.regionHeight / baseline.regionHeight
  density.reductionRatio = 1 - current.planCardHeight / baseline.planCardHeight
  assertTaskDensity(density)
  return { hierarchy: { actions, density }, medication: { theme: result.theme, statuses } }
}

async function identityAcceptance(page, labels, result) {
  await page.locator('.quick-actions').waitFor()
  await page.locator('.care-controls .el-radio-button').filter({ hasText: labels.family }).click()
  const cards = page.locator('.family-cards button')
  assert.equal(await cards.count(), 2)
  for (const [name, id] of [['Synthetic patient', 11], ['Synthetic second patient', 22]]) {
    const card = cards.filter({ hasText: name }).filter({ hasText: String(id) })
    assert.equal(await card.count(), 1, 'authorized name and record ID share one patient card')
  }
  assert.equal(await cards.first().getAttribute('aria-pressed'), 'true')
  await screenshot(page, result, 'family-choice')
  await button(page.locator('.quick-actions'), labels.record)
  const quick = await dialog(page); await checkPaper(quick, result.theme)
  assert.match(await quick.locator('.care-context').innerText(), /Synthetic patient[\s\S]*11/)
  await screenshot(page, result, 'quick-record-identity'); await closeCleanDialog(page)
  await button(page.locator('.care-columns .care-task').filter({ hasText: 'Synthetic PENDING medication' }), labels.recordDose)
  const dose = await dialog(page); assert.match(await dose.locator('.care-context').innerText(), /Synthetic patient[\s\S]*11/)
  await screenshot(page, result, 'dose-identity'); await closeCleanDialog(page)
  await tab(page, '.care-tabs', 'stock')
  // Use the production primary action, independent of small copy differences between editions.
  await page.locator('#pane-stock .section-head .el-button--primary').click()
  const stock = await dialog(page); assert.match(await stock.locator('.care-context').innerText(), /Synthetic patient[\s\S]*11/)
  await screenshot(page, result, 'stock-identity'); await closeCleanDialog(page)
  await tab(page, '.care-tabs', 'orders'); await page.locator('#pane-orders .section-head .el-button--primary').click()
  assert.match(await page.locator('.entry-patient').innerText(), /Synthetic patient[\s\S]*11/)
  await screenshot(page, result, 'entry-identity'); await closeCleanDialog(page)
  await tab(page, '.care-tabs', 'today'); await cards.filter({ hasText: 'Synthetic second patient' }).click()
  await waitOutput(page, 'patient', 22); await page.locator('.quick-actions').waitFor()
  await button(page.locator('.quick-actions'), labels.record)
  assert.match(await (await dialog(page)).locator('.care-context').innerText(), /Synthetic second patient[\s\S]*22/)
  await screenshot(page, result, 'changed-authorized-record')
  await closeCleanDialog(page); await waitOutput(page, 'writes', 0)
  result.checks.push('Both authorized patient cards identify name/ID; quick reading, dose, stock and care-entry contexts identify the active authorized record; switching to #22 updates context without a save')
}

async function requiredAcceptance(page, labels, result) {
  await qaClick(page, 'nav-entry'); await page.locator('.care-entry').waitFor()
  const d = await dialog(page); await checkPaper(d, result.theme)
  await setDate(page, 'startDate', '')
  await button(d, labels.save)
  for (const field of ['title', 'medicationId', 'startDate', 'confirmed']) {
    const control = field === 'title' ? titleControl(page) : field === 'confirmed' ? page.locator('.clinical-confirmation input') : requiredControl(page, field)
    await associated(control, `care-entry-error-${field}`)
  }
  await focused(page, 'care-entry-error-title'); await waitOutput(page, 'writes', 0)
  await screenshot(page, result, 'persistent-native-errors')
  // Persistent errors remain after ordinary focus movement, then disappear only when corrected.
  await page.locator('.clinical-confirmation input').focus()
  assert.equal(await page.locator('#care-entry-error-medicationId').isVisible(), true)
  await titleControl(page).fill('Synthetic prescription')
  await associated(titleControl(page), 'care-entry-error-title', false)
  await button(d, labels.save); await focused(page, 'care-entry-error-medicationId')
  await selectMedication(page); await associated(requiredControl(page, 'medicationId'), 'care-entry-error-medicationId', false)
  await button(d, labels.save); await focused(page, 'care-entry-error-startDate')
  await setDate(page, 'startDate', '2026-10-11'); await associated(requiredControl(page, 'startDate'), 'care-entry-error-startDate', false)
  await button(d, labels.save); await focused(page, 'care-entry-error-confirmed'); await waitOutput(page, 'writes', 0)
  await page.locator('.clinical-confirmation input').check(); await associated(page.locator('.clinical-confirmation input'), 'care-entry-error-confirmed', false)
  assert.equal(await page.locator('.entry-errors').count(), 0)
  await screenshot(page, result, 'required-corrected')
  await button(d, labels.save); await page.locator('.care-entry').waitFor({ state: 'hidden' }); await waitOutput(page, 'writes', 1)
  await qaClick(page, 'nav-appointment'); await page.locator('.care-entry').waitFor()
  await button(await dialog(page), labels.save); await associated(requiredControl(page, 'eventAt'), 'care-entry-error-eventAt')
  await focused(page, 'care-entry-error-title'); await waitOutput(page, 'writes', 1)
  await titleControl(page).fill('Synthetic appointment'); await button(await dialog(page), labels.save)
  await focused(page, 'care-entry-error-eventAt'); await screenshot(page, result, 'required-time-focus')
  await setDate(page, 'eventAt', '2026-10-11 08:00:00'); await associated(requiredControl(page, 'eventAt'), 'care-entry-error-eventAt', false)
  await button(await dialog(page), labels.save); await page.locator('.care-entry').waitFor({ state: 'hidden' }); await waitOutput(page, 'writes', 2)
  result.checks.push('Real title/time/medication/effective-date/checkbox inputs carry persistent error association; correction removes associations; first-invalid focus advances through all required fields; no invalid write; corrected records each save once')
}

async function entryDraftAcceptance(page, labels, result) {
  await qaClick(page, 'nav-entry'); await page.locator('.care-entry').waitFor()
  await titleControl(page).fill('Synthetic unsaved prescription')
  for (const dismiss of ['escape', 'header', 'outside', 'cancel']) {
    if (dismiss === 'escape') await page.keyboard.press('Escape')
    if (dismiss === 'header') await page.locator('.el-dialog:visible .el-dialog__headerbtn').click()
    if (dismiss === 'outside') await page.locator('.el-overlay-dialog:visible').click({ position: { x: 3, y: 3 } })
    if (dismiss === 'cancel') await button(await dialog(page), labels.cancel)
    await page.locator('.draft-warning').waitFor()
    assert.equal(await titleControl(page).inputValue(), 'Synthetic unsaved prescription')
    if (dismiss === 'escape') await screenshot(page, result, 'entry-keep-discard')
    await draftWarning(page, labels)
    assert.equal(await titleControl(page).inputValue(), 'Synthetic unsaved prescription', `${dismiss} Keep editing retains source`)
  }
  await qaClick(page, 'storage-ordinary-preference'); assert.equal(await titleControl(page).inputValue(), 'Synthetic unsaved prescription')
  await qaClick(page, 'nav-other'); await draftWarning(page, labels); await routeIs(page, '/entry?kind=ORDER')
  await qaClick(page, 'patient'); await draftWarning(page, labels); await waitOutput(page, 'patient', 11)
  assert.equal(await titleControl(page).inputValue(), 'Synthetic unsaved prescription')
  await button(await dialog(page), labels.cancel); await draftWarning(page, labels, false)
  await page.locator('.care-entry').waitFor({ state: 'hidden' })
  await qaClick(page, 'nav-other'); await routeIs(page, '/other')
  await qaClick(page, 'nav-entry'); await page.locator('.care-entry').waitFor(); assert.equal(await titleControl(page).inputValue(), '')
  // Unexpected patient change is a security boundary, not a resumable draft for the next record.
  await titleControl(page).fill('Synthetic confidential entry'); await qaClick(page, 'force-patient')
  await page.locator('.care-entry').waitFor({ state: 'hidden' }); await waitOutput(page, 'patient', 22)
  await waitOutput(page, 'writes', 0)
  result.checks.push('Entry Escape/header/outside/Cancel/route/patient-change Keep editing retains exact input; explicit discard clears; unexpected record boundary clears without writing')
}

async function consultationDraftAcceptance(page, labels, result) {
  await qaClick(page, 'nav-journey'); await roomIs(page, 1)
  await composer(page).fill('Synthetic unsent room-one message')
  await openChat(page, labels, 2); await chatWarning(page, labels); await roomIs(page, 1)
  assert.equal(await composer(page).inputValue(), 'Synthetic unsent room-one message')
  await qaClick(page, 'fail-target'); await openChat(page, labels, 2); await chatWarning(page, labels, false)
  await page.locator('.el-message').filter({ hasText: labels.keep === 'Keep editing' ? /could not be opened/ : /未能打开/ }).waitFor()
  await roomIs(page, 1); assert.equal(await composer(page).inputValue(), 'Synthetic unsent room-one message')
  // The route-linked target uses the real router guard, not direct setupState invocation.
  await qaClick(page, 'nav-room2'); await chatWarning(page, labels, false)
  await routeIs(page, '/care-journey?tab=consultation&consultationId=1'); await roomIs(page, 1)
  assert.equal(await composer(page).inputValue(), 'Synthetic unsent room-one message')
  await screenshot(page, result, 'failed-target-retains-source')
  await qaClick(page, 'healthy-target')
  await qaClick(page, 'nav-other'); await chatWarning(page, labels); await roomIs(page, 1)
  // Actual parent tab must remain selected while the prompt is open/cancelled.
  await requestTab(page, '.journey-tabs', 'measurements'); await page.locator('.el-message-box:visible').waitFor()
  assert.equal(await page.locator('.journey-tabs #tab-consultation').getAttribute('aria-selected'), 'true')
  await screenshot(page, result, 'parent-tab-guard'); await chatWarning(page, labels)
  await roomIs(page, 1); assert.equal(await composer(page).inputValue(), 'Synthetic unsent room-one message')
  await qaClick(page, 'patient'); await chatWarning(page, labels); await waitOutput(page, 'patient', 11)
  // Back/Forward navigation is also genuine browser history, protected by the router.
  await page.goBack(); await chatWarning(page, labels); await roomIs(page, 1)
  assert.equal(await composer(page).inputValue(), 'Synthetic unsent room-one message')
  await openChat(page, labels, 2); await chatWarning(page, labels, false); await roomIs(page, 2)
  assert.equal(await composer(page).inputValue(), '', 'authorized successful target activation clears approved source draft')
  await composer(page).fill('Synthetic room-two message'); await qaClick(page, 'nav-other'); await chatWarning(page, labels, false)
  await routeIs(page, '/other'); await page.goBack(); await page.locator('.consultation-workspace').waitFor()
  await roomIs(page, 2); assert.equal(await composer(page).inputValue(), '')
  // A clean source stays editable during an in-flight target read; newly typed text is not approved for discard.
  await qaClick(page, 'delay-target'); await openChat(page, labels, 1); await roomIs(page, 1)
  await openChat(page, labels, 2); await waitOutput(page, 'pending', 1)
  await composer(page).fill('Synthetic newly typed while loading'); await qaClick(page, 'resolve-target')
  await waitOutput(page, 'pending', 0); await roomIs(page, 1)
  assert.equal(await composer(page).inputValue(), 'Synthetic newly typed while loading')
  await page.waitForFunction(text => [...document.querySelectorAll('.consultation-compose-footer button')].some(element => element.textContent.includes(text) && !element.disabled), labels.send)
  // A canceled newer target must release loading ownership instead of stranding Send.
  await composer(page).fill(''); await qaClick(page, 'delay-target'); await openChat(page, labels, 2); await waitOutput(page, 'pending', 1)
  await composer(page).fill('Synthetic protected pending draft'); await openChat(page, labels, 3); await chatWarning(page, labels)
  await qaClick(page, 'resolve-target'); await waitOutput(page, 'pending', 0); await roomIs(page, 1)
  assert.equal(await composer(page).inputValue(), 'Synthetic protected pending draft')
  await page.waitForFunction(text => [...document.querySelectorAll('.consultation-compose-footer button')].some(element => element.textContent.includes(text) && !element.disabled), labels.send)
  await screenshot(page, result, 'pending-cancel-recovered')
  await qaClick(page, 'storage-ordinary-preference'); assert.equal(await composer(page).inputValue(), 'Synthetic protected pending draft')
  await qaClick(page, 'revoke'); await button(page.locator('.consultation-history'), labels.refresh)
  await page.locator('.consultation-editor').waitFor({ state: 'hidden' }); await waitOutput(page, 'writes', 0)
  result.checks.push('Real chat buttons and linked-room router guard preserve failed-target draft; route, browser Back and actual parent-tab Keep editing retain input; approved target/navigation discard clears; typing during delayed load retained; canceled newer switch restores Send; unrelated storage retains; permission loss clears')
}

async function startDraftAcceptance(browser, base, cell, labels, result) {
  const { page, context, errors } = await newPage(browser, base, cell)
  try {
    await qaClick(page, 'nav-journey'); await roomIs(page, 1)
    const start = page.locator('.consultation-start'), textareas = start.locator('textarea')
    await start.locator('.el-select').nth(1).click()
    await page.getByRole('option', { name: 'Synthetic doctor', exact: true }).click()
    await textareas.nth(0).fill('Synthetic new consultation question')
    await textareas.nth(1).fill('Synthetic confidential history')
    await qaClick(page, 'nav-other'); await chatWarning(page, labels)
    assert.equal(await textareas.nth(0).inputValue(), 'Synthetic new consultation question')
    assert.equal(await textareas.nth(1).inputValue(), 'Synthetic confidential history')
    await qaClick(page, 'patient'); await chatWarning(page, labels); await waitOutput(page, 'patient', 11)
    const stored = await page.evaluate(() => Object.values(localStorage).join(' '))
    assert.equal(stored.includes('Synthetic confidential history'), false, 'clinical draft is not persisted')
    await screenshot(page, result, 'start-draft-retained')
    await button(start, labels.start); await waitOutput(page, 'writes', 1)
    await page.waitForFunction(() => [...document.querySelectorAll('.consultation-start textarea')].every(element => element.value === ''))
    await qaClick(page, 'nav-other'); await routeIs(page, '/other')
    assert.equal(await page.locator('.el-message-box:visible').count(), 0, 'successful create establishes clean baseline despite retained doctor choice')
    await page.goBack(); await roomIs(page, 1); assert.equal(await composer(page).inputValue(), '')
    assert.deepEqual(errors, [])
  } finally { await context.close() }
  result.checks.push('Actual new-consultation symptom/history/doctor form protects navigation and patient changes without persistent clinical storage; successful creation resets submitted text and permits clean navigation without a false-dirty warning')
}

async function concurrentRouteAcceptance(browser, base, cell, labels, result) {
  const { page, context, errors } = await newPage(browser, base, cell)
  try {
    await qaClick(page, 'nav-journey'); await roomIs(page, 1)
    await qaClick(page, 'defer-route'); await openChat(page, labels, 2)
    await roomIs(page, 2); await waitOutput(page, 'route-pending', 1)
    await composer(page).fill('Synthetic concurrent room-two draft')
    await qaClick(page, 'nav-other'); await chatWarning(page, labels)
    await roomIs(page, 2); assert.equal(await composer(page).inputValue(), 'Synthetic concurrent room-two draft')
    await screenshot(page, result, 'concurrent-route-retained')
    await qaClick(page, 'resolve-route'); await waitOutput(page, 'route-pending', 0)
    assert.equal(await composer(page).inputValue(), 'Synthetic concurrent room-two draft')
    await qaClick(page, 'nav-other'); await chatWarning(page, labels, false); await routeIs(page, '/other')
    await waitOutput(page, 'writes', 0); assert.deepEqual(errors, [])
  } finally { await context.close() }
  result.checks.push('An unrelated real navigation remains draft-protected while an internal room router.replace awaits an asynchronous guard')
}

async function boundaryAcceptance(browser, base, cell, labels, result) {
  // Separate lifetimes prevent an earlier session clear from making later boundary probes vacuous.
  for (const surface of ['entry', 'journey']) for (const boundary of ['logout', 'storage-token', 'storage-userId', 'storage-clear']) {
    const { page, errors, context } = await newPage(browser, base, cell)
    try {
      await qaClick(page, `nav-${surface}`)
      if (surface === 'entry') { await page.locator('.care-entry').waitFor(); await titleControl(page).fill('Synthetic confidential boundary draft') }
      else { await roomIs(page, 1); await composer(page).fill('Synthetic confidential boundary message'); await qaClick(page, 'delay-target'); await openChat(page, labels, 2); await chatWarning(page, labels, false); await waitOutput(page, 'pending', 1) }
      await qaClick(page, boundary)
      await page.locator(surface === 'entry' ? '.care-entry' : '.consultation-editor').waitFor({ state: 'hidden' })
      if (surface === 'journey') { await qaClick(page, 'resolve-target'); await waitOutput(page, 'pending', 0); assert.equal(await page.locator('.consultation-room').count(), 0, 'obsolete response cannot restore source after session boundary') }
      assert.equal(await page.getByText(/Synthetic confidential boundary (draft|message)/).count(), 0)
      await waitOutput(page, 'writes', 0); assert.deepEqual(errors, [])
    } finally { await context.close() }
  }
  result.checks.push('Independent entry/consultation lifetimes clear on logout and token/userId/null storage boundaries; pending target responses cannot restore cleared confidential content')
}

async function newPage(browser, base, cell) {
  const context = await browser.newContext({ viewport: { width: cell.viewport, height: 900 }, reducedMotion: 'reduce', timezoneId: 'UTC', serviceWorkers: 'block' })
  const page = await context.newPage(), errors = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(({ theme }) => {
    localStorage.setItem('token', 'synthetic-only'); localStorage.setItem('userId', '11'); localStorage.setItem('currentPatientId', '11')
    localStorage.setItem('userRoleCodes', '["patient"]'); localStorage.setItem('userMenus', '["/care","/care-journey"]')
    localStorage.setItem('chengxin-health-appearance-v1', JSON.stringify({ version: 1, theme }))
  }, { theme: cell.theme })
  // Do not transmit any fixture data or load remote fonts/images/scripts. Only
  // this harness's loopback origin is allowed; accidental real APIs fail the case.
  await context.route('**/*', route => {
    const url = new URL(route.request().url())
    if (url.origin !== new URL(base).origin) {
      errors.push(`Blocked external request: ${url.origin}${url.pathname}`)
      return route.abort('blockedbyclient')
    }
    if (url.pathname === '/api/patient/names' && route.request().method() === 'GET' && route.request().headers().authorization === 'Bearer synthetic-only') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: [{ id: 11, patientName: 'Synthetic patient' }, { id: 22, patientName: 'Synthetic second patient' }] }) })
    }
    if (/^\/api(?:\/|$)/.test(url.pathname)) {
      errors.push(`Unexpected real API request: ${url.pathname}`)
      return route.fulfill({ status: 500, contentType: 'application/json', body: '{"code":500}' })
    }
    return route.continue()
  })
  await context.routeWebSocket('**/*', socket => {
    const url = new URL(socket.url())
    if (url.host !== new URL(base).host) errors.push(`Blocked external WebSocket: ${url.origin}${url.pathname}`)
    // Preview serves no dev client. Close every socket without an upstream connection;
    // unexpected foreign endpoints remain a failed acceptance boundary.
    socket.close()
  })
  try {
    await page.goto(`${base}/care`); await page.locator('.quick-actions').waitFor()
    const styleSignature = await checkTheme(page, cell.theme)
    assert.equal(styleSignature.innerWidth, cell.viewport, 'actual viewport width matches matrix')
    assert.equal(styleSignature.innerHeight, 900, 'actual viewport height matches evidence')
    return { page, errors, context, styleSignature }
  } catch (error) { await context.close(); throw error }

}

function assertCompiledNoDevClient(compiled) {
  const output = (Array.isArray(compiled) ? compiled : [compiled]).flatMap(bundle => bundle.output || [])
  assert.ok(output.some(file => file.type === 'chunk'), 'real compiled JavaScript must exist')
  for (const file of output) {
    if (file.type === 'chunk') assert.deepEqual(Object.keys(file.modules).filter(id => /\/vite\/dist\/client\//.test(id)), [], 'compiled artifact contains no injected Vite dev client')
    if (file.fileName.endsWith('.html')) assert.equal(String(file.source).includes('/@vite/client'), false, 'compiled HTML must not inject HMR')
  }
}

let browser, failed = false
try {
  if (!compileOnly) browser = await chromium.launch({ ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}), headless: true })
  for (const edition of editions) {
    const harness = makeHarness(edition)
    let server
    try {
      // A dev server injects @vite/client even with hmr:false and can create a
      // port-zero handshake. Serve the actual compiled artifact with preview;
      // do not relax the external HTTP/WebSocket boundary to hide that symptom.
      const config = { ...harness.config, build: { outDir: join(harness.root, 'compiled'), emptyOutDir: true, minify: false } }
      const compiled = await build(config)
      assertCompiledNoDevClient(compiled)
      report.compilation.push({ edition, status: 'compiled', fixtureSha256: harness.fixtureSha256, devClientAbsent: true, browserServing: 'vite-preview' })
      saveReport()
      if (compileOnly) continue
      server = await preview({ ...config, preview: { host: '127.0.0.1', port: 0 } })
      const base = `http://127.0.0.1:${server.httpServer.address().port}`
      for (const viewport of widths) for (const theme of themes) {
        const cell = { edition, viewport, theme }, copy = labels[edition]
        for (const item of legacyWidths.includes(viewport) ? itemIds : [1]) {
          const result = { ...cell, itemIds: [item], nineMeasurements: {}, status: 'failed', commit, sourceSha256, harnessSha256, fixtureSha256: harness.fixtureSha256, screenshots: [], screenshotSha256: {}, checks: [] }
          let session
          try {
            session = await newPage(browser, base, cell)
            const { page, errors, styleSignature } = session
            result.themeStyleSignature = styleSignature
            result.actualViewport = { width: styleSignature.innerWidth, height: styleSignature.innerHeight, devicePixelRatio: styleSignature.devicePixelRatio }
            if (item === 1) await todayAcceptance(page, copy, result)
            if (item === 3) await identityAcceptance(page, copy, result)
            if (item === 7) await requiredAcceptance(page, copy, result)
            if (item === 6) { await entryDraftAcceptance(page, copy, result); await qaClick(page, 'nav-care'); await qaClick(page, 'patient'); await waitOutput(page, 'patient', 11); await consultationDraftAcceptance(page, copy, result); await boundaryAcceptance(browser, base, cell, copy, result); await startDraftAcceptance(browser, base, cell, copy, result); await concurrentRouteAcceptance(browser, base, cell, copy, result) }
            assert.deepEqual(errors, [], 'no uncaught page errors or unexpected real API calls')
            assert.ok(result.screenshots.length > 0, 'each passed item has screenshot evidence')
            result.status = 'passed'
          } catch (error) {
            failed = true; result.error = error.stack || String(error)
            try { if (session) await screenshot(session.page, result, 'failure') } catch (captureError) { result.screenshotError = String(captureError) }
          } finally { await session?.context.close(); report.results.push(result); saveReport() }
        }
      }
    } finally { await server?.close(); rmSync(harness.root, { recursive: true, force: true }) }
  }
  assert.equal(execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim(), commit, 'HEAD unchanged during evidence run')
} catch (error) { failed = true; report.infrastructureError = error.stack || String(error) }
finally { await browser?.close(); report.browserExecuted = !compileOnly && report.results.length > 0; saveReport() }
if (failed) process.exitCode = 1
console.log(`Care QA ${report.mode}: ${report.compilation.length} editions compiled, ${report.results.filter(result => result.status === 'passed').length} passed browser item-cells, ${report.results.filter(result => result.status === 'failed').length} failed; evidence ${join(evidence, 'care-results.json')}`)
