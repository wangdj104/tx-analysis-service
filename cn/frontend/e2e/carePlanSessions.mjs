import { reportPhase } from './reportOutcomePhase.mjs'
import { test as base, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { ids, language, login, api, pageErrorCounter, appPath } from './helpers.mjs'

// One six-role fixture for the existing collaboration and new report workflows.
// Preserve the original primary role/video directory contract and real UI login.
const text = (en, zh) => language === 'cn' ? zh : en
const labels = { openMenu: text('Open navigation menu','打开导航菜单'), account: text('Current Account','当前账号'), signOut: text('Sign Out','退出登录') }
const roleNames = { personal: /Patient|患者/, family: /Family Caregiver|家属照护者/, doctor: /Doctor|医生/, nurse: /Nurse|护理/, admin: /Administrator|系统管理员/, outsider: /Patient|患者/ }
export const accountNames = { personal: 'Synthetic Personal', family: 'Synthetic Family', doctor: 'Synthetic Doctor', nurse: 'Synthetic Nurse', admin: 'Synthetic Administrator', outsider: 'Synthetic Outsider' }

export async function showIdentity(page, role, mobile = false) {
  if (mobile) await page.getByRole('button', { name: labels.openMenu, exact: true }).click()
  const account = page.locator(mobile ? '.workspace-sidebar__footer--drawer .workspace-account' : '.workspace-sidebar > .workspace-sidebar__footer .workspace-account')
  await expect(account).toContainText(accountNames[role])
  await expect(account).toContainText(roleNames[role])
  if (mobile) await page.keyboard.press('Escape')
}

async function clearAccess(owner, admin) {
  // Cleanup never broadens permission: every test begins with family/nurse denied.
  for (const patientId of [ids.patientA, ids.patientB, ids.reportPatient, ids.emptyReportPatient, ids.limitedReportPatient]) {
    for (const row of await api(owner, `/care-journey/access-grants?patientId=${patientId}`)) {
      if ([ids.family, ids.nurse].includes(Number(row.grantee_user_id)) && row.status === 'ACTIVE')
        await api(owner, `/care-journey/access-grants/${row.id}`, { method: 'DELETE' })
    }
    for (const row of await api(admin, `/care-nurse-assignments?patientId=${patientId}`)) {
      if (row.nurseUserId === ids.nurse && row.status === 'ACTIVE')
        await api(admin, `/care-nurse-assignments/${row.id}/revoke`, { method: 'POST' })
    }
  }
}

export const test = base.extend({
  sessions: async ({ browser }, use, testInfo) => {
    const contexts = [], checks = [], pages = {}
    const open = async (role, { mobile = false } = {}) => {
      const width = mobile ? 390 : 1365
      const videoDir = testInfo.outputPath(`${role}-${mobile ? '390' : 'desktop'}-video`)
      await mkdir(videoDir, { recursive: true })
      const context = await browser.newContext({ baseURL: process.env.CARE_PLAN_E2E_BASE_URL, viewport: { width, height: 900 }, timezoneId: 'Asia/Shanghai', recordVideo: { dir: videoDir, size: { width, height: 900 } } })
      contexts.push(context)
      const page = await context.newPage()
      checks.push(pageErrorCounter(page))
      await login(page, role)
      await showIdentity(page, role, mobile)
      pages[role] = page
      return page
    }
    try {
      const owner = await open('personal'), admin = await open('admin')
      await clearAccess(owner, admin)
      await reportPhase('test-body')
      await use({ open, owner, admin, pages, sibling: async (page, label) => {
        const sibling = await page.context().newPage(); checks.push(pageErrorCounter(sibling)); pages[label] = sibling; return sibling
      } })
    } finally {
      await reportPhase('teardown',async()=>{
      if (testInfo.status !== testInfo.expectedStatus) {
        for (const [role, page] of Object.entries(pages)) {
          if (!page.isClosed()) {
            try { await page.screenshot({ path: testInfo.outputPath(`${language}-${role}-failure.png`), fullPage: true, timeout: 5000 }) }
            catch { /* A crashed page must not replace the original failure. */ }
          }
        }
      }
      for (const context of contexts) await context.close()
      for (const check of checks) check()
      })
    }
  },
})


export async function signOut(page, mobile = false) {
  if (mobile) await page.getByRole('button', { name: labels.openMenu, exact: true }).click()
  await page.locator(mobile ? '.workspace-sidebar__footer--drawer .workspace-account' : '.workspace-sidebar > .workspace-sidebar__footer .workspace-account').click()
  await page.getByRole('dialog', { name: labels.account, exact: true }).getByRole('button', { name: labels.signOut, exact: true }).click()
  await expect(page).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath('/login')}`)
}
