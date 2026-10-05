import { expect } from '@playwright/test'
import { test, signOut } from './carePlanSessions.mjs'
import { ids, appPath, api, grant, login, assertNoOverflow } from './helpers.mjs'
import { panel, reportBody, selectPatient, setReportOptions, reportResponse, uiText, questionSentinel, screenshotReport, assertClearedReport, holdRealReportResponse } from './reportBrowser.mjs'

const oldHistory = 'Synthetic legacy history before refresh'
const newHistory = 'Synthetic fresh legacy history for this print'
const oldQuestion = 'Synthetic old care question for this print'
const newQuestion = 'Synthetic fresh care question for this print'
const refresh = page => page.getByRole('button',{name:uiText('Generate / refresh summary','生成 / 刷新摘要'),exact:true})
const printButton = page => page.getByRole('button',{name:uiText('Print / save PDF','打印 / 保存 PDF'),exact:true})
const legacyResponse = page => page.waitForResponse(response => response.request().method()==='GET' && new URL(response.url()).pathname==='/api/family-health/visit-summary')

async function openSummary(page, outputLanguage = 'en') {
  await page.goto(appPath('/monitoring'))
  await selectPatient(page,ids.reportPatient)
  await page.goto(appPath('/family-health?tab=summary'))
  await expect(refresh(page)).toBeVisible()
  await setReportOptions(page,outputLanguage)
}
async function generateSummary(page) {
  const legacy=legacyResponse(page), care=reportResponse(page)
  await refresh(page).click()
  expect((await legacy).status()).toBe(200)
  expect((await care).status()).toBe(200)
  await expect(page.locator('.visit-summary')).toBeVisible()
  await expect(reportBody(page)).toBeVisible()
  await expect(refresh(page)).not.toHaveClass(/is-loading/)
}
async function preparePrintData(owner, admin) {
  await api(admin,'/patient/update',{method:'PUT',body:{id:ids.reportPatient,medicalHistory:oldHistory}})
  const question=await api(owner,'/care/items',{method:'POST',body:{patientId:ids.reportPatient,kind:'QUESTION',title:oldQuestion,details:{description:'Synthetic print source before refresh'}}})
  return {
    async freshen() {
      await api(admin,'/patient/update',{method:'PUT',body:{id:ids.reportPatient,medicalHistory:newHistory}})
      await api(owner,'/care/items',{method:'POST',body:{id:question.id,patientId:ids.reportPatient,kind:'QUESTION',title:newQuestion,details:{description:'Synthetic print source after refresh'}}})
    },
    async dispose() {
      await api(owner,'/care/items/'+question.id,{method:'DELETE'})
      await api(admin,'/patient/update',{method:'PUT',body:{id:ids.reportPatient,medicalHistory:oldHistory}})
    },
  }
}
async function holdLegacy(page) {
  let release,reached,settled,used=false,error,disposed=false
  const gate=new Promise(resolve=>{release=resolve}),captured=new Promise(resolve=>{reached=resolve}),complete=new Promise(resolve=>{settled=resolve})
  const pattern='**/api/family-health/visit-summary?**'
  const handler=async route=>{
    if(used)return route.continue()
    used=true
    try{const response=await route.fetch();expect(response.status()).toBe(200);reached();await gate;await route.fulfill({response})}
    catch(failure){error=failure;reached()}finally{settled()}
  }
  await page.route(pattern,handler)
  return{reached:captured,release,async dispose(){if(disposed)return;disposed=true;release();if(used)await complete;await page.unroute(pattern,handler);if(error)throw error}}
}
async function observeRealPrint(page,{hold=false}={}) {
  await page.evaluate(({hold})=>{
    const native=window.open
    const observation={opened:0,focus:0,printed:0,ready:false,release:null}
    window.open=function(...args){
      const popup=native.apply(this,args)
      if(!popup)return popup
      observation.opened++
      const focus=popup.focus.bind(popup),print=popup.print.bind(popup),timeout=popup.setTimeout.bind(popup)
      popup.focus=()=>{observation.focus++;return focus()}
      popup.print=()=>{observation.printed++;return print()}
      if(hold)popup.setTimeout=(callback,delay,...args)=>timeout(()=>{observation.ready=true;observation.release=()=>callback(...args)},delay)
      return popup
    }
    window.__reportPrintObservation=observation
    window.__restoreReportPrint=()=>{window.open=native;delete window.__restoreReportPrint;delete window.__reportPrintObservation}
  },{hold})
  return()=>page.evaluate(()=>window.__restoreReportPrint?.())
}

for(const mobile of [false,true]) for(const outputLanguage of ['en','zh-CN']) test(`real print ${mobile?'390px':'desktop'} ${outputLanguage} freshly renders both legacy and care content, focuses one popup and cleans it on change`,async({sessions},testInfo)=>{
  test.setTimeout(120000)
  const page=mobile?await sessions.open('personal',{mobile:true}):sessions.owner
  const data=await preparePrintData(sessions.owner, sessions.admin)
  let restore
  try{
    await openSummary(page,outputLanguage);await generateSummary(page)
    await expect(page.locator('.visit-summary')).toContainText(oldHistory)
    await expect(reportBody(page)).toContainText(oldQuestion)
    await data.freshen()
    restore=await observeRealPrint(page,{hold:true})
    const legacy=legacyResponse(page),care=reportResponse(page,'preview',{language:outputLanguage}),opened=page.waitForEvent('popup')
    await printButton(page).focus();await expect(printButton(page)).toBeFocused();await printButton(page).press('Enter')
    const [legacyResult,careResult,popup]=await Promise.all([legacy,care,opened])
    expect((await legacyResult.json()).data.patient.medicalHistory).toBe(newHistory)
    expect(careResult.request().postDataJSON().language).toBe(outputLanguage)
    const careData=(await careResult.json()).data
    expect(careData.metadata.language).toBe(outputLanguage)
    expect(careData.questions.some(row=>row.title===newQuestion)).toBe(true)
    await expect(popup.locator('.visit-summary')).toContainText(newHistory)
    await expect(popup.locator('.visit-summary')).not.toContainText(oldHistory)
    await expect(popup.locator('.report-body')).toContainText(newQuestion)
    await expect(popup.locator('.report-body')).not.toContainText(oldQuestion)
    await expect(popup.locator('.report-body')).toContainText('Saved successfully')
    await expect(popup.locator('.report-body')).toContainText('Synthetic 护理原文 <script>synthetic-report</script>')
    await expect(popup.locator('.report-body')).not.toContainText('保存成功')
    for(const label of outputLanguage==='en'?['Current status','Period activity','Current visit questions','Submitted awaiting doctor review']:['当前状态','期间活动','当前就诊问题','已提交待医生复核'])await expect(popup.locator('.report-body')).toContainText(label)
    expect(await popup.locator('.report-controls').first().isVisible()).toBe(false)
    await expect.poll(()=>page.evaluate(()=>window.__reportPrintObservation?.ready)).toBe(true)
    expect(await page.evaluate(()=>({opened:window.__reportPrintObservation.opened,focus:window.__reportPrintObservation.focus,printed:window.__reportPrintObservation.printed}))).toEqual({opened:1,focus:1,printed:0})
    await expect.poll(()=>popup.evaluate(()=>document.hasFocus())).toBe(true)
    await assertNoOverflow(page)
    await popup.screenshot({path:testInfo.outputPath(`real-print-${mobile?'390':'desktop'}-${outputLanguage}-fresh-both-sources.png`),fullPage:true})
    await page.evaluate(()=>window.__reportPrintObservation.release())
    await expect.poll(()=>page.evaluate(()=>window.__reportPrintObservation.printed)).toBe(1)
    await panel(page).locator('select').selectOption(outputLanguage==='en'?'zh-CN':'en')
    await expect.poll(()=>popup.isClosed()).toBe(true)
    await expect(page.locator('.visit-summary')).toHaveCount(0)
    await assertClearedReport(page)
  }finally{if(restore)await restore();await data.dispose()}
})

for(const delayed of ['legacy','care']) for(const change of ['patient','account','unmount']) test(`real print race: delayed ${delayed} response cannot mount stale DOM after ${change}`,async({sessions},testInfo)=>{
  test.setTimeout(120000)
  const page=sessions.owner
  await openSummary(page);await generateSummary(page)
  const sibling=change==='account'?await sessions.sibling(page,`print-auth-${delayed}`):null
  if(sibling)await sibling.goto(appPath('/monitoring'))
  const hold=delayed==='legacy'?await holdLegacy(page):await holdRealReportResponse(page)
  let popups=0,reads=0
  const popup=()=>popups++,read=request=>{if(new URL(request.url()).pathname==='/api/family-health/visit-summary')reads++}
  page.on('popup',popup);page.on('request',read)
  try{
    await printButton(page).click();await hold.reached
    // Native disabled click attempts cannot start a second paired refresh.
    await printButton(page).evaluate(button=>{button.click();button.click()})
    expect(reads).toBe(1)
    if(change==='patient')await selectPatient(page,ids.emptyReportPatient)
    else if(change==='account'){await signOut(sibling);await login(sibling,'outsider')}
    else await page.goto(appPath('/care'))
    hold.release();await hold.dispose()
    await expect(page.locator('.visit-summary')).toHaveCount(0)
    await assertClearedReport(page)
    expect(popups).toBe(0)
    await screenshotReport(page,testInfo,`print-${delayed}-${change}-cleared`)
  }finally{await hold.dispose();page.off('popup',popup);page.off('request',read)}
})

test('real print authorization: revoked full-record grant rejects legacy refresh and never opens stale summary',async({sessions},testInfo)=>{
  const owner=sessions.owner
  await grant(owner,'family',ids.reportPatient,{accessLevel:'READ'})
  await grant(owner,'family',ids.reportPatient,{granteeRole:'GUARDIAN',accessLevel:'READ',visibleModules:''})
  const family=await sessions.open('family')
  await openSummary(family);await generateSummary(family)
  const grants=await api(owner,`/care-journey/access-grants?patientId=${ids.reportPatient}`)
  const full=grants.find(row=>row.grantee_role==='GUARDIAN'&&Number(row.grantee_user_id)===ids.family)
  await api(owner,`/care-journey/access-grants/${full.id}`,{method:'DELETE'})
  // A denied legacy read legitimately aborts an outstanding care preview. Hold
  // only its real delivery so this case can also prove the separate CARE_PLAN
  // grant still authorizes the paired request; no response or authority changes.
  const hold=await holdLegacy(family)
  let popups=0;const opened=()=>popups++;family.on('popup',opened)
  try{
    const legacy=legacyResponse(family),care=reportResponse(family)
    await printButton(family).click();await hold.reached
    expect((await care).status()).toBe(200)
    hold.release()
    const denial = await (await legacy).json()
    // Existing legacy Result semantics use code400 here, unlike report HTTP403.
    expect(denial.code).toBe(400); expect(denial.data).toBeNull()
    await expect(refresh(family)).not.toHaveClass(/is-loading/)
    await expect(family.locator('.visit-summary')).toHaveCount(0)
    await assertClearedReport(family)
    expect(popups).toBe(0)
    await screenshotReport(family,testInfo,'print-full-record-revoked')
  }finally{family.off('popup',opened);await hold.dispose()}
})

test('real print callback: a prepared popup closes on logout before its delayed native print callback',async({sessions},testInfo)=>{
  const page=sessions.owner
  await openSummary(page);await generateSummary(page)
  const sibling=await sessions.sibling(page,'print-callback-account');await sibling.goto(appPath('/monitoring'))
  const restore=await observeRealPrint(page,{hold:true})
  try{
    const opened=page.waitForEvent('popup');await printButton(page).click();const popup=await opened
    await expect(popup.locator('.visit-summary')).toContainText('Synthetic Report Patient')
    await expect(popup.locator('.report-body')).toContainText(questionSentinel)
    await expect.poll(()=>page.evaluate(()=>window.__reportPrintObservation.ready)).toBe(true)
    await signOut(sibling);await login(sibling,'outsider')
    await expect.poll(()=>popup.isClosed()).toBe(true)
    await page.evaluate(()=>window.__reportPrintObservation.release())
    expect(await page.evaluate(()=>window.__reportPrintObservation.printed)).toBe(0)
    await assertClearedReport(page)
    await screenshotReport(page,testInfo,'print-callback-account-cleared')
  }finally{await restore()}
})
