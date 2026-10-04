import { expect } from '@playwright/test'
import { appPath } from './paths.mjs'
export { appPath } from './paths.mjs'
export const ids = Object.freeze({ personal:9001, family:9002, doctor:9003, nurse:9004, admin:9005, outsider:9006, patientA:9001, patientB:9002, patientC:9003, reportPatient:9101, emptyReportPatient:9102, limitedReportPatient:9103, outsideReportPatient:9104, reportPlan:19101, reportMeasurement:19101, reportMedical:19102, outsideReportSource:19104 })
export const language = process.env.CARE_PLAN_E2E_LANGUAGE || 'en'
export async function login(page, role) {
  if(!Object.hasOwn(ids,role) || !process.env.CARE_PLAN_E2E_PASSWORD)throw new Error('Synthetic login configuration absent')
  await page.goto(appPath('/login'))
  await page.locator('input[name="username"]').fill(`care-plan-e2e-${role}`)
  await page.locator('input[name="password"]').fill(process.env.CARE_PLAN_E2E_PASSWORD)
  const accountResponse = page.waitForResponse(response => response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/auth/info')
  await page.locator('.login-button').click()
  await expect(page).toHaveURL(new RegExp(`${appPath('/monitoring')}(?:[?#]|$)`))
  // This asserts persisted session identity; the workflow must also assert visible role/workspace.
  await expect.poll(()=>page.evaluate(()=>Number(localStorage.getItem('userId')))).toBe(ids[role])
  const response = await accountResponse
  expect(response.status()).toBe(200)
  const account = await response.json()
  expect(account.code).toBe(200)
  expect(account.data.user.id).toBe(ids[role])
  // Match OnboardingGuide's actual per-account role key. Reading an existing
  // dismissal distinguishes repeat login; only the visible button writes it.
  const roleCode = ['admin', 'doctor', 'patient', 'family'].find(code => account.data.roles.some(item => item.roleCode === code)) || 'patient'
  const alreadyDismissed = await page.evaluate(({ accountId, roleCode }) => localStorage.getItem(`care-onboarding-prompt:${accountId}:${roleCode}`) === 'seen' || localStorage.getItem(`care-onboarding:${accountId}:${roleCode}`) === 'complete', { accountId: ids[role], roleCode })
  const welcome = page.getByRole('dialog', { name: language === 'cn' ? '欢迎使用健康工作台' : 'Welcome to your health workspace', exact: true })
  if (!alreadyDismissed) {
    await expect(welcome).toBeVisible()
    await welcome.getByRole('button', { name: language === 'cn' ? '以后再说' : 'Maybe Later', exact: true }).click()
  }
  await expect(welcome).toBeHidden()
  // Element Plus can hide dialog content before its leaving overlay disappears.
  await expect(page.locator('.el-overlay:visible')).toHaveCount(0)
  await expect(page.locator('.el-message').filter({ hasText: language === 'cn' ? '登录成功' : 'Signed in successfully' })).toBeHidden()
}
export function assertAccessDenied(result) {
  expect(result.status).toBe(403)
  expect(result.code).toBe(403)
  // The real advice returns this exact nonclinical error object. Extra patient,
  // plan, action, receipt or cached replay fields must fail this assertion.
  expect(result.data, 'Denied response contains only the public error code').toEqual({ errorCode: 'ACCESS_DENIED' })
}
export async function api(page, path, {method='GET',body,allowed=[200],allowedCodes=[200]}={}) {
  if(!path.startsWith('/') || path.startsWith('//'))throw new Error('Only same-origin API paths are allowed')
  const result=await page.evaluate(async({path,method,body})=>{
    const response=await fetch('/api'+path,{method,headers:{'Content-Type':'application/json',Authorization:'Bearer '+localStorage.getItem('token')},...(body===undefined?{}:{body:JSON.stringify(body)})})
    const json=await response.json();return {status:response.status,code:json.code,data:json.data}
  },{path,method,body})
  if(!allowed.includes(result.status) || (result.status===200 && !allowedCodes.includes(result.code)))throw new Error(`Actual API ${method} ${path.split('?')[0]} rejected: HTTP ${result.status}, code ${result.code}`)
  return result.status===200 && result.code===200?result.data:result
}
export function command(body={},version=0){return {...body,commandKey:crypto.randomUUID(),expectedVersion:version}}
export async function assignDoctor(admin,patientId=ids.patientA){return api(admin,'/doctor-workspace/assignments',{method:'POST',body:{doctorUserId:ids.doctor,patientId}})}
export async function assignNurse(admin,patientId=ids.patientA,extra={}){return api(admin,'/care-nurse-assignments',{method:'POST',body:{patientId,nurseUserId:ids.nurse,...extra}})}
export async function grant(owner,role,patientId=ids.patientA,extra={}){return api(owner,'/care-journey/access-grants',{method:'POST',body:{patientId,granteeUserId:ids[role],granteeRole:role==='nurse'?'NURSE':'FAMILY',accessLevel:'WRITE',visibleModules:'CARE_PLAN',...extra}})}
export async function draft(doctor,{patientId=ids.patientA,title='Synthetic browser collaboration',count=1,assignee=ids.personal}={}){
  const dueAt=new Date(Date.now()+86400000).toISOString()
  return api(doctor,'/care-plans',{method:'POST',body:command({patientId,title,instructions:'Synthetic browser clinical instructions',planType:'FOLLOW_UP',actions:Array.from({length:count},(_,i)=>({ordinal:i+1,instruction:`Synthetic browser action ${i+1}`,dueAt,assignedUserId:assignee,evidence:[]}))})})
}
export async function publish(doctor,view){return api(doctor,`/care-plans/${view.id}/revisions/${view.draftRevisionId}/publish`,{method:'POST',body:command({currentRevisionId:view.currentRevisionId||null,supersededActionDigest:view.revisionImpact?.digest||null},view.version)})}
export async function detail(page,id){return api(page,`/care-plans/${id}`)}
export async function assertNoOverflow(page){expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBe(true)}
export function pageErrorCounter(page){const errors=[];page.on('pageerror',error=>errors.push(error.name));return ()=>expect(errors).toEqual([])}
