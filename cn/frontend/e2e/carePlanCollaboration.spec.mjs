import { test as base, expect } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { ids, language, appPath, login, api, assertAccessDenied, command, assignNurse, grant, draft, publish, detail, assertNoOverflow, pageErrorCounter } from './helpers.mjs'

// Each edition runs the same contract against its built Vue app and real Spring/MySQL.
// Only explicitly named resilience tests intercept transport, after real commit.
const cn = language === 'cn'
const text = (en, zh) => cn ? zh : en
const labels = {
  doctor: text('Doctor Workspace', '医生工作台'), plans: text('Care plans', '照护计划'),
  nurse: text('Nursing follow-up', '护理跟进'), care: text('Your care, today', '照顾好今天的自己'),
  assignEntry: text('Assign nursing team', '分配护理人员'), nurseId: text('Active nurse account ID', '有效护理账号编号'),
  role: text('Role', '角色'), family: text('Family', '家属'), nurseRole: text('Nurse', '护理人员'),
  recipient: text('Recipient user ID', '授权对象用户编号'), assignedNurse: text('Currently assigned nurse', '当前有效分配的护理人员'),
  level: text('Level', '权限级别'), write: text('Can record', '可录入'), modules: text('Visible modules', '可见模块'),
  module: text('Collaboration care plans', '协作照护计划'), authorize: text('Authorize', '授权'),
  title: text('Plan title', '计划标题'), instructions: text('Clinical instructions', '临床说明'),
  action: text('Action instructions', '行动说明'), assignee: text('Responsible person', '负责人'),
  deadline: text('Deadline, ISO date/time with explicit offset', '截止时间：包含明确偏移的 ISO 日期时间'),
  addAction: text('Add action', '添加行动'), history: text('Version history', '版本历史'),
  editDraft: text('Edit private draft', '编辑私有草稿'), keepPrivate: text('Keep private', '保持私有'),
  entry: text('Entry declaration', '录入声明'), occurred: text('Actual execution time, with explicit UTC offset', '实际执行时间，须含明确的 UTC 偏移'),
  followKind: text('Follow-up type', '跟进类型'), help: text('Ask for help', '请求协助'), followRoute: text('Record follow-up', '记录跟进'),
  returnNote: text('Required explanation (1–1000 characters)', '必填说明（1–1000 字）'),
  cancelNote: text('Required reason (1–1000 characters)', '必填原因（1–1000 字）'),
  selfDisclaimer: text('This is your declaration, not patient identity verification.', '这是您的录入声明，并非患者身份认证。'),
  assisted: text('Assisted entry', '协助录入'), self: text('Account-owner self-entry declaration', '账号所有者声明本人录入'),
  reviewedDisclaimer: text('It does not verify treatment effectiveness or disease improvement.', '不验证治疗有效性或疾病改善'),
  returnCare: text('Return to care', '返回照护'), signOut: text('Sign Out', '退出登录'),
  account: text('Current Account', '当前账号'), openMenu: text('Open navigation menu', '打开导航菜单'),
  revoke: text('Revoke', '撤销'), moreHistory: text('Use this existing plan', '使用此既有计划'),
}
const roleNames = { personal: /Patient|患者/, family: /Family Caregiver|家属照护者/, doctor: /Doctor|医生/, nurse: /Nurse|护理/, admin: /Administrator|系统管理员/, outsider: /Patient|患者/ }
const accountNames = { personal: 'Synthetic Personal', family: 'Synthetic Family', doctor: 'Synthetic Doctor', nurse: 'Synthetic Nurse', admin: 'Synthetic Administrator', outsider: 'Synthetic Outsider' }
const patientNames = { [ids.patientA]: 'Synthetic Patient A', [ids.patientB]: 'Synthetic Patient B', [ids.patientC]: 'Synthetic Patient C' }
const exact = value => new RegExp(`^${value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)
const planTitle = name => `Synthetic ${language} ${name}`
const planPath = id => `/care-plans/${id}`
const workspaceLink = (page, path) => page.locator(`.workspace-sidebar .workspace-nav a[href="${appPath(path)}"]`)
const expectUiRoute = (page, path) => expect(page).toHaveURL(`${process.env.CARE_PLAN_E2E_BASE_URL}${appPath(path)}`)
const actionCard = (page, instruction) => page.locator('.care-plan-detail > .action-card').filter({ has: page.getByRole('heading', { name: exact(instruction) }) })
const planDetail = page => page.locator('.care-plan-detail')

async function observeCommand(page, method, pathname, perform) {
  const response = page.waitForResponse(r => r.request().method() === method && new URL(r.url()).pathname === `/api${pathname}`)
  await perform()
  const result = await response
  expect(result.status(), `Actual ${method} ${pathname} HTTP status`).toBe(200)
  const json = await result.json()
  expect(json.code, `Actual ${method} ${pathname} business status`).toBe(200)
  return json.data
}

async function observeDeniedCommand(page, pathname, perform) {
  const response = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === `/api${pathname}`)
  await perform()
  const result = await response
  const json = await result.json()
  assertAccessDenied({ status: result.status(), code: json.code, data: json.data })
}

async function selectElementOption(page, control, name) {
  // Element Plus overlays readonly inputs with the selected-label placeholder.
  // Its combobox key handler supports ArrowDown for both plain/filterable selects.
  await expect(control).toBeVisible()
  await expect(control).toBeEnabled()
  await control.press('ArrowDown')
  await expect(control).toHaveAttribute('aria-expanded', 'true')
  await page.getByRole('option', { name, exact: typeof name === 'string' }).click()
  await page.keyboard.press('Escape')
}

async function selectPatient(page, patientId) {
  const switcher = page.locator('.patient-switcher')
  await expect(switcher.getByRole('combobox')).toBeEnabled()
  await selectElementOption(page, switcher.getByRole('combobox'), patientNames[patientId])
  await expect(switcher).toContainText(patientNames[patientId])
}

function formItem(page, form, label) {
  return form.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label').filter({ hasText: exact(label) }) })
}

async function showIdentity(page, role, mobile = false) {
  if (mobile) await page.getByRole('button', { name: labels.openMenu, exact: true }).click()
  const account = page.locator(mobile ? '.workspace-sidebar__footer--drawer .workspace-account' : '.workspace-sidebar > .workspace-sidebar__footer .workspace-account')
  await expect(account).toContainText(accountNames[role])
  await expect(account).toContainText(roleNames[role])
  if (mobile) await page.keyboard.press('Escape')
}

async function clearAccess(owner, admin) {
  // Cleanup never broadens permission: every test begins with family/nurse denied.
  for (const patientId of [ids.patientA, ids.patientB]) {
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

const test = base.extend({
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
      await use({ open, owner, admin, pages })
    } finally {
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
    }
  },
})

async function assignNurseThroughUi(admin, patientId) {
  await admin.goto(appPath('/system/patient'))
  const row = admin.locator('.el-table__body tr').filter({ hasText: patientNames[patientId] })
  await row.getByRole('button', { name: labels.assignEntry, exact: true }).click()
  const section = admin.locator('.nurse-assignments')
  await section.getByLabel(labels.nurseId, { exact: true }).fill(String(ids.nurse))
  const result = await observeCommand(admin, 'POST', '/care-nurse-assignments', () => section.getByTestId('assign-nurse').click())
  expect(result).toMatchObject({ patientId, nurseUserId: ids.nurse, assignedBy: ids.admin, status: 'ACTIVE' })
  await expect(section.getByTestId(`revoke-assignment-${result.id}`)).toBeVisible()
  return result
}

async function grantThroughUi(owner, role, patientId) {
  await owner.goto(appPath('/care-journey?tab=privacy'))
  await selectPatient(owner, patientId)
  const form = owner.locator('.care-plan-grant-form')
  await selectElementOption(owner, formItem(owner, form, labels.role).getByRole('combobox'), role === 'nurse' ? labels.nurseRole : labels.family)
  if (role === 'nurse') {
    await selectElementOption(owner, formItem(owner, form, labels.assignedNurse).getByRole('combobox'), 'Synthetic Nurse (#9004)')
  } else {
    await formItem(owner, form, labels.recipient).getByRole('spinbutton').fill(String(ids.family))
  }
  await selectElementOption(owner, formItem(owner, form, labels.level).getByRole('combobox'), labels.write)
  if (role !== 'nurse') await selectElementOption(owner, formItem(owner, form, labels.modules).getByRole('combobox'), labels.module)
  const result = await observeCommand(owner, 'POST', '/care-journey/access-grants', () => form.getByRole('button', { name: labels.authorize, exact: true }).click())
  expect(result).toMatchObject({ patient_id: patientId, grantee_user_id: ids[role], access_level: 'WRITE', visible_modules: 'CARE_PLAN', granted_by: ids.personal, status: 'ACTIVE' })
  return result
}

async function openDoctorPlans(doctor, patientId = ids.patientA) {
  await doctor.goto(appPath('/doctor-workspace'))
  await expect(doctor.getByRole('heading', { name: labels.doctor, exact: true })).toBeVisible()
  await doctor.getByRole('tab', { name: labels.plans, exact: true }).click()
  await selectElementOption(doctor, doctor.locator('.doctor-tabs .patient-filter:visible').getByRole('combobox'), patientNames[patientId])
  await expect(doctor.getByTestId('new-collaboration-draft')).toBeEnabled()
}

async function fillDraft(editor, title, actions) {
  await editor.getByLabel(labels.title, { exact: true }).fill(title)
  await editor.getByLabel(labels.instructions, { exact: true }).fill(`${title} instructions`)
  // End of the browser-local day stays inside TODAY without relying on CI wall-clock hour.
  const deadline = await editor.evaluate(() => { const time = new Date(); time.setHours(23, 59, 59, 999); return time.toISOString() })
  for (let i = 0; i < actions.length; i++) {
    if (i) await editor.getByRole('button', { name: new RegExp(`^${labels.addAction}`) }).click()
    const row = editor.locator('.action-editor').nth(i)
    await row.getByLabel(labels.action, { exact: true }).fill(actions[i].instruction)
    await row.getByLabel(labels.assignee, { exact: true }).selectOption(String(actions[i].assignee))
    await row.getByLabel(labels.deadline, { exact: true }).fill(deadline)
  }
}

async function acknowledgeExistingHistory(editor) {
  // A new editor reconciles real server history; no state injection or bypass.
  const notice = editor.getByTestId('acknowledge-create-history')
  await expect(notice).toBeEnabled()
  await notice.click()
}

async function createThroughUi(doctor, title, actions) {
  await openDoctorPlans(doctor)
  await doctor.getByTestId('new-collaboration-draft').click()
  const editor = doctor.locator('.care-plan-editor')
  await expect(editor.getByLabel(labels.title, { exact: true })).toBeEditable()
  await acknowledgeExistingHistory(editor)
  await fillDraft(editor, title, actions)
  const result = await observeCommand(doctor, 'POST', '/care-plans', () => editor.getByTestId('save-draft').click())
  await expect(editor.getByTestId('prepare-publish')).toBeEnabled()
  return { editor, view: await detail(doctor, result.id) }
}

async function publishThroughUi(doctor, editor, view) {
  await editor.getByTestId('prepare-publish').click()
  await expect(editor.getByTestId('confirm-publish')).toBeDisabled()
  await editor.getByRole('button', { name: labels.keepPrivate, exact: true }).click()
  expect((await detail(doctor, view.id)).lifecycle).toBe(view.lifecycle)
  await editor.getByTestId('prepare-publish').click()
  await editor.locator('.check input[type="checkbox"]').check()
  await observeCommand(doctor, 'POST', `/care-plans/${view.id}/revisions/${view.draftRevisionId}/publish`, () => editor.getByTestId('confirm-publish').click())
  return detail(doctor, view.id)
}

async function openPlan(page, view) {
  await page.goto(appPath(planPath(view.id)))
  await expect(planDetail(page).getByRole('heading', { name: view.title, exact: true })).toBeVisible()
}

function assertUtcInstant(value) {
  expect(typeof value).toBe('string')
  expect(value).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/)
  expect(Number.isFinite(Date.parse(value))).toBe(true)
}

function assertReceiptPersistence(before, after, result, actionId, note, entryMode, occurredAt, actorId) {
  expect(result).toMatchObject({ planId: before.id, actionId, actionStatus: 'SUBMITTED', lifecycle: 'ACTIVE', version: before.version + 1 })
  expect(after.version).toBe(before.version + 1)
  const previousAction = before.actions.find(a => a.id === actionId), action = after.actions.find(a => a.id === actionId)
  expect(action).toMatchObject({ status: 'SUBMITTED', version: previousAction.version + 1 })
  const events = action.events.filter(e => e.note === note)
  expect(events).toHaveLength(1)
  const role = Object.keys(accountNames).find(r => ids[r] === actorId)
  expect(events[0]).toMatchObject({ id: result.eventId, actorId, actorName: accountNames[role], actorRole: { personal: 'PATIENT', family: 'FAMILY', nurse: 'NURSE' }[role], entryMode, eventType: 'RECEIPT_SUBMITTED' })
  for (const time of [events[0].occurredAt, events[0].recordedAt, action.firstSubmittedAt, action.latestSubmittedAt, action.reviewWaitingSince]) assertUtcInstant(time)
  expect(Date.parse(events[0].occurredAt)).toBe(Date.parse(occurredAt))
  expect(Date.parse(events[0].recordedAt)).toBeGreaterThanOrEqual(Date.parse(events[0].occurredAt))
  expect(Date.parse(action.latestSubmittedAt)).toBeGreaterThanOrEqual(Date.parse(action.firstSubmittedAt))
}

async function assertVisibleReceipt(page, note, entryMode, actorId) {
  const card = planDetail(page).locator('.event-list > article').filter({ has: page.getByText(note, { exact: true }) })
  const role = Object.keys(accountNames).find(r => ids[r] === actorId)
  await expect(card).toContainText(accountNames[role])
  await expect(card).toContainText(entryMode === 'SELF' ? labels.self : labels.assisted)
  // Both occurred and recorded timestamps visibly name the browser zone and offset.
  await expect(card.locator('p').filter({ hasText: 'UTC+08:00, Asia/Shanghai' })).toHaveCount(2)
}

async function recordThroughUi(page, view, actionId, note, entryMode = 'ASSISTED', duplicate = false) {
  await openPlan(page, view)
  const before = await detail(page, view.id), actorId = await page.evaluate(() => Number(localStorage.getItem('userId')))
  await page.getByTestId(`route-record-${actionId}`).click()
  const dialog = page.locator('dialog.receipt-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByLabel(labels.entry, { exact: true })).toHaveValue('ASSISTED')
  if (entryMode === 'SELF') {
    await dialog.getByLabel(labels.entry, { exact: true }).selectOption('SELF')
    await expect(dialog).toContainText(labels.selfDisclaimer)
  } else {
    const modes = await dialog.getByLabel(labels.entry, { exact: true }).locator('option').evaluateAll(options => options.map(o => o.value))
    if (actorId !== ids.personal) expect(modes).toEqual(['ASSISTED'])
  }
  await dialog.getByTestId('receipt-note').fill(note)
  const occurredAt = new Date(Date.now() - 60000).toISOString()
  await dialog.getByLabel(labels.occurred, { exact: true }).fill(occurredAt)
  const result = await observeCommand(page, 'POST', `/care-plans/actions/${actionId}/receipts`, () => duplicate ? dialog.getByTestId('submit-receipt').dblclick() : dialog.getByTestId('submit-receipt').click())
  await expect(dialog).toBeHidden()
  const persisted = await detail(page, view.id)
  assertReceiptPersistence(before, persisted, result, actionId, note, entryMode, occurredAt, actorId)
  await assertVisibleReceipt(page, note, entryMode, actorId)
  return persisted
}

async function deny(page, path, options = {}) {
  const result = await api(page, path, { ...options, allowed: [403], allowedCodes: [403] })
  assertAccessDenied(result)
}

async function assertCleared(page, title) {
  await expect(planDetail(page)).toContainText(cn ? /此计划不可用|访问权限已不可用/ : /This plan is unavailable|Access is no longer available/)
  await expect(planDetail(page).getByRole('heading', { name: title, exact: true })).toHaveCount(0)
  await expect(page.locator('.care-plan-detail .action-card')).toHaveCount(0)
  await expect(page.locator('.care-plan-detail .event-list')).toHaveCount(0)
  await expect(page.locator('dialog.receipt-dialog [data-testid="receipt-note"]')).toHaveCount(0)
}

async function screenshot(page, testInfo, name) {
  await assertNoOverflow(page)
  await page.screenshot({ path: testInfo.outputPath(`${language}-${name}.png`), fullPage: true })
}

async function prepareTodayDraft(doctor, options) {
  // Secondary setup persists through the real service. The main loop creates in UI.
  const view = await draft(doctor, options)
  const dueAt = await doctor.evaluate(() => { const time = new Date(); time.setHours(23, 59, 59, 999); return time.toISOString() })
  return api(doctor, `${planPath(view.id)}/revisions/${view.draftRevisionId}/save`, { method: 'POST', body: command({ patientId: view.patientId, title: view.title, instructions: view.instructions, planType: view.planType, actions: view.actions.map((a, i) => ({ ordinal: i + 1, instruction: a.instruction, dueAt, assignedUserId: a.assignedUserId, evidence: [] })) }, view.version) })
}

test('real collaboration: separate assignment/grants, private draft, SELF/ASSISTED, help, return, review and closure', async ({ sessions }, testInfo) => {
  test.setTimeout(240000)
  const { owner, admin, open } = sessions
  const doctor = await open('doctor'), family = await open('family'), nurse = await open('nurse')
  await deny(family, `/care-plans?patientId=${ids.patientA}&queue=HISTORY`)
  await deny(nurse, `/care-plans?patientId=${ids.patientA}&queue=HISTORY`)
  await assignNurseThroughUi(admin, ids.patientA)
  await deny(nurse, `/care-plans?patientId=${ids.patientA}&queue=HISTORY`)
  await grantThroughUi(owner, 'family', ids.patientA)
  await grantThroughUi(owner, 'nurse', ids.patientA)
  // Reload the logged-in application to discover newly authorized names.
  await family.reload(); await showIdentity(family, 'family')
  await nurse.reload(); await showIdentity(nurse, 'nurse')
  const title = planTitle('complete UI loop')
  const { editor, view: privateView } = await createThroughUi(doctor, title, [
    { instruction: 'Synthetic personal execution', assignee: ids.personal },
    { instruction: 'Synthetic family execution', assignee: ids.family },
    { instruction: 'Synthetic nurse assisted execution', assignee: ids.nurse },
  ])
  for (const actor of [owner, family, nurse]) {
    // They already have module read: denial cannot be explained by missing grant.
    const list = await api(actor, `/care-plans?patientId=${ids.patientA}&queue=HISTORY`)
    expect(list.items.some(p => p.id === privateView.id)).toBe(false)
    await deny(actor, planPath(privateView.id))
    await actor.goto(appPath(planPath(privateView.id)))
    await assertCleared(actor, title)
  }
  const view = await publishThroughUi(doctor, editor, privateView)
  expect(view.lifecycle).toBe('ACTIVE')
  expect(view.actions).toHaveLength(3)
  await editor.getByTestId('close-editor').click()
  await owner.goto(appPath('/care')); await selectPatient(owner, ids.patientA)
  await expect(owner.getByTestId(`open-plan-${view.id}`)).toBeVisible()
  await family.goto(appPath('/care')); await selectPatient(family, ids.patientA)
  await expect(family.getByTestId(`open-plan-${view.id}`)).toBeVisible()
  await expect(family.getByTestId('legacy-care-restricted')).toBeVisible()
  const [personalAction, familyAction, nurseAction] = view.actions
  await recordThroughUi(owner, view, personalAction.id, 'Synthetic SELF original receipt', 'SELF', true)
  await recordThroughUi(family, view, familyAction.id, 'Synthetic ASSISTED family receipt')
  await openPlan(family, view)
  const nurseCard = actionCard(family, `${nurseAction.ordinal}. ${nurseAction.instruction}`)
  await nurseCard.getByRole('button', { name: labels.help, exact: true }).click()
  await family.getByTestId('receipt-note').fill('Synthetic difficulty needing nursing support')
  await observeCommand(family, 'POST', `/care-plans/actions/${nurseAction.id}/help`, () => family.getByTestId('submit-receipt').click())
  await expect(family.locator('dialog.receipt-dialog')).toBeHidden()
  await nurse.goto(appPath('/nurse-workspace'))
  await expect(nurse.getByRole('heading', { name: labels.nurse, exact: true })).toBeVisible()
  await expect(nurse.getByTestId(`followup-${nurseAction.id}`)).toBeVisible()
  await nurse.getByTestId(`followup-${nurseAction.id}`).click()
  await nurse.getByTestId('receipt-note').fill('Synthetic nursing contact and doctor notification')
  await nurse.getByLabel(labels.followKind, { exact: true }).selectOption('DOCTOR_NOTIFIED')
  await observeCommand(nurse, 'POST', `/care-plans/actions/${nurseAction.id}/follow-ups`, () => nurse.getByTestId('submit-receipt').click())
  await expect(nurse.locator('dialog.receipt-dialog')).toBeHidden()
  await recordThroughUi(nurse, view, nurseAction.id, 'Synthetic ASSISTED nurse receipt')
  await screenshot(nurse, testInfo, 'nursing-persisted')
  const beforeReturn = await detail(doctor, view.id)
  const originalAt = beforeReturn.actions[0].firstSubmittedAt
  assertUtcInstant(originalAt)
  await openPlan(doctor, view)
  await doctor.getByTestId(`review-return-${personalAction.id}`).click()
  await doctor.getByLabel(labels.returnNote, { exact: true }).fill('Synthetic doctor asks for execution detail')
  await observeCommand(doctor, 'POST', `/care-plans/actions/${personalAction.id}/reviews`, () => doctor.getByTestId('send-return').click())
  const returned = await detail(doctor, view.id)
  expect(returned.actions[0]).toMatchObject({ status: 'OPEN', firstSubmittedAt: originalAt })
  await recordThroughUi(owner, view, personalAction.id, 'Synthetic SELF supplemental receipt', 'SELF')
  for (const action of view.actions) {
    await openPlan(doctor, view)
    await observeCommand(doctor, 'POST', `/care-plans/actions/${action.id}/reviews`, () => doctor.getByTestId(`review-confirm-${action.id}`).click())
  }
  await openPlan(doctor, view)
  await expect(planDetail(doctor)).toContainText(labels.reviewedDisclaimer)
  await doctor.getByTestId('close-plan').click()
  await observeCommand(doctor, 'POST', `${planPath(view.id)}/close`, () => doctor.getByTestId('confirm-transition').click())
  for (const actor of [owner, family, nurse, doctor]) {
    await openPlan(actor, view); await actor.reload()
    const persisted = await detail(actor, view.id)
    expect(persisted.lifecycle).toBe('COMPLETED')
    expect(persisted.actions.every(a => a.status === 'CONFIRMED')).toBe(true)
    expect(persisted.actions[0].firstSubmittedAt).toBe(originalAt)
    expect(persisted.actions[0].events.filter(e => e.eventType === 'RECEIPT_SUBMITTED')).toHaveLength(2)
    expect(persisted.actions[0].events.some(e => e.eventType === 'RECEIPT_RETURNED')).toBe(true)
    await expect(planDetail(actor)).toContainText('Synthetic SELF original receipt')
    await expect(planDetail(actor)).toContainText('Synthetic SELF supplemental receipt')
    await expect(planDetail(actor)).toContainText('Synthetic nursing contact and doctor notification')
    await expect(actor.locator('[data-testid^="route-record-"]')).toHaveCount(0)
  }
  await screenshot(owner, testInfo, 'completed-history')
})

test('revision publication replaces actions; cancellation retains receipts and history', async ({ sessions }, testInfo) => {
  const doctor = await sessions.open('doctor'), owner = sessions.owner
  const original = await draft(doctor, { title: planTitle('revision and cancellation'), patientId: ids.patientA })
  await publish(doctor, original)
  const initial = await detail(doctor, original.id), oldAction = initial.actions[0]
  await recordThroughUi(owner, initial, oldAction.id, 'Synthetic pre-revision receipt', 'SELF')
  await openPlan(doctor, initial)
  await observeCommand(doctor, 'POST', `${planPath(initial.id)}/revisions`, () => doctor.getByTestId('create-revision').click())
  let current = await detail(doctor, initial.id)
  await expect(doctor.getByRole('button', { name: labels.editDraft, exact: true })).toBeVisible()
  await doctor.getByRole('button', { name: labels.editDraft, exact: true }).click()
  const editor = doctor.locator('.care-plan-editor')
  await editor.getByLabel(labels.action, { exact: true }).fill('Synthetic revised instruction')
  await observeCommand(doctor, 'POST', `${planPath(initial.id)}/revisions/${current.draftRevisionId}/save`, () => editor.getByTestId('save-draft').click())
  await deny(owner, `${planPath(initial.id)}/revisions/${current.draftRevisionId}`)
  const ownerRevisions = await api(owner, `${planPath(initial.id)}/revisions`)
  expect(ownerRevisions.items.every(r => r.status === 'PUBLISHED')).toBe(true)
  expect((await detail(owner, initial.id)).actions[0].instruction).toBe(oldAction.instruction)
  current = await api(doctor, `${planPath(initial.id)}/revisions/${current.draftRevisionId}`)
  await publishThroughUi(doctor, editor, current)
  await editor.getByTestId('close-editor').click()
  const revised = await detail(owner, initial.id)
  expect(revised.revisionNo).toBe(2)
  expect(revised.actions[0]).toMatchObject({ status: 'OPEN', instruction: 'Synthetic revised instruction' })
  expect(revised.actions[0].id).not.toBe(oldAction.id)
  await openPlan(owner, revised)
  await owner.getByLabel(labels.history, { exact: true }).selectOption(String(initial.currentRevisionId))
  await expect(planDetail(owner)).toContainText('Synthetic pre-revision receipt')
  await expect(owner.locator('[data-testid^="route-record-"]')).toHaveCount(0)
  const history = await api(owner, `${planPath(initial.id)}/revisions/${initial.currentRevisionId}`)
  expect(history.actions[0].status).toBe('SUPERSEDED')
  expect(history.actions[0].events.filter(e => e.note === 'Synthetic pre-revision receipt')).toHaveLength(1)
  await openPlan(doctor, revised)
  await doctor.getByTestId('cancel-plan').click()
  await doctor.getByLabel(labels.cancelNote, { exact: true }).fill('Synthetic plan replaced by a different care arrangement')
  await observeCommand(doctor, 'POST', `${planPath(initial.id)}/cancel`, () => doctor.getByTestId('confirm-transition').click())
  await openPlan(owner, revised); await owner.reload()
  expect((await detail(owner, initial.id)).lifecycle).toBe('CANCELLED')
  expect((await detail(owner, initial.id)).actions[0].status).toBe('CANCELLED')
  await owner.getByLabel(labels.history, { exact: true }).selectOption(String(initial.currentRevisionId))
  await expect(planDetail(owner)).toContainText('Synthetic pre-revision receipt')
  await screenshot(owner, testInfo, 'cancelled-retained-version')
})

test('module-only discoverability, cross-patient rejection, revoke clearing and authority-bound replay', async ({ sessions }, testInfo) => {
  const { owner, admin } = sessions, doctor = await sessions.open('doctor')
  const family = await sessions.open('family'), nurse = await sessions.open('nurse'), outsider = await sessions.open('outsider')
  await assignNurse(admin); const familyGrant = await grant(owner, 'family'); await grant(owner, 'nurse')
  const privatePlan = await prepareTodayDraft(doctor, { title: planTitle('minimal permission'), count: 3 }); await publish(doctor, privatePlan)
  const view = await detail(doctor, privatePlan.id), actionId = view.actions[0].id
  await family.reload(); await showIdentity(family, 'family')
  await family.goto(appPath('/care')); await selectPatient(family, ids.patientA)
  await expect(family.getByTestId(`open-plan-${view.id}`)).toBeVisible()
  await expect(family.getByTestId('legacy-care-restricted')).toBeVisible()
  // Legacy scope denial retains HTTP200/code400; new care-plan APIs use HTTP403.
  const fullContext = await api(family, `/care/context?patientId=${ids.patientA}`, { allowed: [200], allowedCodes: [400] })
  expect(fullContext.code).toBe(400)
  expect(fullContext.data).toBeNull()
  for (const actor of [family, nurse]) {
    await deny(actor, `/care-plans?patientId=${ids.patientB}&queue=HISTORY`)
    await deny(actor, `/care-plans?patientId=${ids.patientC}&queue=HISTORY`)
  }
  await deny(outsider, `/care-plans?patientId=${ids.patientA}&queue=HISTORY`)
  await deny(outsider, `/care-plans?patientId=${ids.patientB}&queue=HISTORY`)
  await deny(outsider, planPath(view.id))
  await deny(doctor, '/care-plans', { method: 'POST', body: command({ patientId: ids.patientC, title: 'Synthetic forbidden patient C', instructions: 'Synthetic forbidden cross-patient instructions', planType: 'FOLLOW_UP', actions: [{ ordinal: 1, instruction: 'Synthetic forbidden action', dueAt: new Date(Date.now() + 86400000).toISOString(), assignedUserId: ids.outsider, evidence: [] }] }) })
  // The owner genuinely has original-module read authority. A successful A reference
  // on an independent action is the positive control for the same actor's C rejection.
  await api(owner, `/care-plans/actions/${view.actions[2].id}/receipts`, { method: 'POST', body: command({ note: 'Synthetic same-patient evidence positive control', occurredAt: new Date(Date.now() - 60000).toISOString(), entryMode: 'SELF', evidence: [{ sourceType: 'MEASUREMENT', sourceId: 9001 }] }, view.version) })
  const withEvidence = await detail(owner, view.id)
  const positive = withEvidence.actions[2].events.find(e => e.note === 'Synthetic same-patient evidence positive control')
  expect(positive.actorId).toBe(ids.personal)
  expect(positive.evidence).toContainEqual(expect.objectContaining({ sourceType: 'MEASUREMENT', sourceId: 9001, restricted: false }))
  await deny(owner, `/care-plans/actions/${view.actions[1].id}/receipts`, { method: 'POST', body: command({ note: 'Synthetic wrong-patient evidence', occurredAt: new Date(Date.now() - 60000).toISOString(), entryMode: 'SELF', evidence: [{ sourceType: 'MEASUREMENT', sourceId: 9003 }] }, withEvidence.version) })
  expect((await detail(owner, view.id)).version).toBe(withEvidence.version)
  expect((await detail(owner, view.id)).actions[1].events).toHaveLength(0)
  expect((await detail(doctor, view.id)).actions[0].events).toHaveLength(0)
  const frozen = command({ note: 'Synthetic authorized receipt before revocation', occurredAt: new Date(Date.now() - 60000).toISOString(), entryMode: 'ASSISTED', evidence: [] }, withEvidence.version)
  await api(family, `/care-plans/actions/${actionId}/receipts`, { method: 'POST', body: frozen })
  await openPlan(family, view)
  await family.getByTestId(`route-record-${view.actions[1].id}`).click()
  await family.getByTestId('receipt-note').fill('Synthetic local receipt must clear after revoked authority')
  await owner.goto(appPath('/care-journey?tab=privacy')); await selectPatient(owner, ids.patientA)
  const row = owner.locator('.el-table__body tr').filter({ hasText: 'Synthetic Family' })
  await observeCommand(owner, 'DELETE', `/care-journey/access-grants/${familyGrant.id}`, () => row.getByRole('button', { name: labels.revoke, exact: true }).click())
  await observeDeniedCommand(family, `/care-plans/actions/${view.actions[1].id}/receipts`, () => family.getByTestId('submit-receipt').click())
  await expect(family.getByTestId('receipt-note')).toHaveCount(0)
  await family.getByTestId('close-receipt').click()
  await assertCleared(family, view.title)
  await deny(family, `${planPath(view.id)}/revisions/${view.currentRevisionId}`)
  await deny(family, `/care-plans/actions/${actionId}/receipts`, { method: 'POST', body: frozen })
  expect((await detail(doctor, view.id)).actions[0].events.filter(e => e.note === frozen.note)).toHaveLength(1)
  await openPlan(nurse, view)
  await actionCard(nurse, `${view.actions[0].ordinal}. ${view.actions[0].instruction}`).getByRole('button', { name: labels.followRoute, exact: true }).click()
  await nurse.getByTestId('receipt-note').fill('Synthetic local follow-up must clear after assignment revocation')
  const assignments = await api(admin, `/care-nurse-assignments?patientId=${ids.patientA}`)
  const assignment = assignments.find(a => a.nurseUserId === ids.nurse && a.status === 'ACTIVE')
  await admin.goto(appPath('/system/patient'))
  await admin.locator('.el-table__body tr').filter({ hasText: patientNames[ids.patientA] }).getByRole('button', { name: labels.assignEntry, exact: true }).click()
  await observeCommand(admin, 'POST', `/care-nurse-assignments/${assignment.id}/revoke`, () => admin.getByTestId(`revoke-assignment-${assignment.id}`).click())
  await observeDeniedCommand(nurse, `/care-plans/actions/${actionId}/follow-ups`, () => nurse.getByTestId('submit-receipt').click())
  await expect(nurse.getByTestId('receipt-note')).toHaveCount(0)
  await nurse.getByTestId('close-receipt').click()
  await assertCleared(nurse, view.title)
  await deny(nurse, planPath(view.id))
  await screenshot(family, testInfo, 'revoked-content-cleared')
})

test('real grant and assignment expiry deny persisted history and clear a previously opened plan', async ({ sessions }, testInfo) => {
  test.setTimeout(120000)
  const { owner, admin } = sessions, doctor = await sessions.open('doctor')
  const family = await sessions.open('family'), nurse = await sessions.open('nurse')
  const privatePlan = await draft(doctor, { title: planTitle('expiry') }); await publish(doctor, privatePlan)
  const view = await detail(doctor, privatePlan.id)
  const deadline = new Date(Date.now() + 30000)
  // Legacy grants use server-local LocalDateTime. CI app and MySQL are UTC;
  // nurse assignments use explicit-offset instants. These are separate contracts.
  await assignNurse(admin, ids.patientA, { expiresAt: deadline.toISOString() })
  await grant(owner, 'nurse')
  await grant(owner, 'family', ids.patientA, { expiresAt: deadline.toISOString().slice(0, 19) })
  await openPlan(family, view); await openPlan(nurse, view)
  for (const actor of [family, nurse]) {
    await expect.poll(async () => {
      const result = await api(actor, planPath(view.id), { allowed: [200, 403], allowedCodes: [200, 403] })
      return result.code === 403
    }, { timeout: 45000, intervals: [1000] }).toBe(true)
    await actor.getByTestId('reload-detail').click()
    await assertCleared(actor, view.title)
    await deny(actor, `${planPath(view.id)}/revisions/${view.currentRevisionId}`)
  }
  expect((await api(owner, `/care-journey/access-grants?patientId=${ids.patientA}`)).find(g => Number(g.grantee_user_id) === ids.nurse).status).toBe('ACTIVE')
  await screenshot(nurse, testInfo, 'expired-assignment-cleared')
})

test('390px real Vue: keyboard receipt, focus, patient A→B→A, Back/Forward and logout', async ({ sessions }, testInfo) => {
  test.setTimeout(120000)
  const doctor = await sessions.open('doctor'), mobile = await sessions.open('personal', { mobile: true })
  const source = await prepareTodayDraft(doctor, { title: planTitle('mobile patient A') }); await publish(doctor, source)
  const sourceB = await prepareTodayDraft(doctor, { patientId: ids.patientB, title: planTitle('mobile patient B') }); await publish(doctor, sourceB)
  const view = await detail(doctor, source.id), actionId = view.actions[0].id
  await mobile.goto(appPath('/care')); await selectPatient(mobile, ids.patientA)
  await expect(mobile.getByTestId(`open-plan-${view.id}`)).toBeVisible()
  await expect(mobile.getByTestId(`open-plan-${sourceB.id}`)).toHaveCount(0)
  await selectPatient(mobile, ids.patientB)
  await expect(mobile.getByTestId(`open-plan-${view.id}`)).toHaveCount(0)
  await expect(mobile.getByTestId(`open-plan-${sourceB.id}`)).toBeVisible()
  await selectPatient(mobile, ids.patientA)
  await expect(mobile.getByTestId(`open-plan-${view.id}`)).toBeVisible()
  await expect(mobile.getByTestId(`open-plan-${sourceB.id}`)).toHaveCount(0)
  await mobile.getByTestId(`open-plan-${view.id}`).click()
  await expectUiRoute(mobile, planPath(view.id))
  const opener = mobile.getByTestId(`route-record-${actionId}`)
  await opener.focus(); await expect(opener).toBeFocused(); await opener.press('Enter')
  const dialog = mobile.locator('dialog.receipt-dialog')
  await expect(dialog).toBeVisible()
  await expect.poll(() => dialog.evaluate(d => d.contains(document.activeElement))).toBe(true)
  await dialog.getByTestId('close-receipt').focus()
  await mobile.keyboard.press('Tab'); await expect(dialog.getByTestId('receipt-note')).toBeFocused()
  await mobile.keyboard.press('Shift+Tab'); await expect(dialog.getByTestId('close-receipt')).toBeFocused()
  await dialog.getByTestId('receipt-note').fill('Synthetic mobile keyboard input that will be discarded')
  await mobile.keyboard.press('Escape')
  await expect(dialog).toBeHidden(); await expect(opener).toBeFocused()
  expect((await detail(mobile, view.id)).actions[0].events).toHaveLength(0)
  await opener.press('Enter')
  await dialog.getByTestId('receipt-note').fill('Synthetic mobile keyboard-assisted execution')
  await expect(dialog.getByLabel(labels.entry, { exact: true })).toHaveValue('ASSISTED')
  const occurredAt = new Date(Date.now() - 60000).toISOString()
  await dialog.getByLabel(labels.occurred, { exact: true }).fill(occurredAt)
  await assertNoOverflow(mobile)
  await dialog.getByTestId('submit-receipt').focus()
  const result = await observeCommand(mobile, 'POST', `/care-plans/actions/${actionId}/receipts`, () => dialog.getByTestId('submit-receipt').press('Enter'))
  await expect(dialog).toBeHidden()
  assertReceiptPersistence(view, await detail(mobile, view.id), result, actionId, 'Synthetic mobile keyboard-assisted execution', 'ASSISTED', occurredAt, ids.personal)
  await assertVisibleReceipt(mobile, 'Synthetic mobile keyboard-assisted execution', 'ASSISTED', ids.personal)
  await screenshot(mobile, testInfo, '390-real-receipt')
  await mobile.getByRole('link', { name: labels.returnCare, exact: true }).click()
  await expectUiRoute(mobile, '/care')
  await mobile.goBack(); await expectUiRoute(mobile, planPath(view.id))
  await expect(planDetail(mobile)).toContainText('Synthetic mobile keyboard-assisted execution')
  await mobile.goForward(); await expectUiRoute(mobile, '/care')
  await selectPatient(mobile, ids.patientB)
  await expect(mobile.getByTestId(`open-plan-${view.id}`)).toHaveCount(0)
  await expect(mobile.getByTestId(`open-plan-${sourceB.id}`)).toBeVisible()
  await selectPatient(mobile, ids.patientA)
  await expect(mobile.getByTestId(`open-plan-${view.id}`)).toBeVisible()
  await expect(mobile.getByTestId(`open-plan-${sourceB.id}`)).toHaveCount(0)
  await mobile.getByRole('button', { name: labels.openMenu, exact: true }).click()
  await mobile.locator('.workspace-sidebar__footer--drawer .workspace-account').click()
  await mobile.getByRole('dialog', { name: labels.account, exact: true }).getByRole('button', { name: labels.signOut, exact: true }).click()
  await expectUiRoute(mobile, '/login')
  await mobile.goBack(); await expectUiRoute(mobile, '/login')
  await expect(mobile.getByText('Synthetic mobile keyboard-assisted execution', { exact: true })).toHaveCount(0)
  await login(mobile, 'outsider'); await showIdentity(mobile, 'outsider', true)
  await mobile.goto(appPath(planPath(view.id))); await assertCleared(mobile, view.title)
  await deny(mobile, planPath(view.id))
  await screenshot(mobile, testInfo, '390-logout-new-account-denied')
})

test('dirty doctor editor guards visible SPA leave, Back/Forward and patient changes until acknowledged close', async ({ sessions }, testInfo) => {
  const doctor = await sessions.open('doctor'), title = planTitle('unsaved guarded input')
  // Build real SPA history through visible router links, without router injection.
  await doctor.goto(appPath('/care')); await selectPatient(doctor, ids.patientA)
  const doctorLink = workspaceLink(doctor, '/doctor-workspace')
  const careLink = workspaceLink(doctor, '/care')
  await doctorLink.click(); await expectUiRoute(doctor, '/doctor-workspace')
  await careLink.click(); await expectUiRoute(doctor, '/care')
  await doctor.goBack(); await expectUiRoute(doctor, '/doctor-workspace')
  await doctor.getByRole('tab', { name: labels.plans, exact: true }).click()
  await selectElementOption(doctor, doctor.locator('.doctor-tabs .patient-filter:visible').getByRole('combobox'), patientNames[ids.patientA])
  await doctor.getByTestId('new-collaboration-draft').click()
  const editor = doctor.locator('.care-plan-editor')
  await acknowledgeExistingHistory(editor)
  await fillDraft(editor, title, [{ instruction: 'Synthetic unsaved action preserved until explicit discard', assignee: ids.personal }])
  const assertRetained = async () => {
    await expectUiRoute(doctor, '/doctor-workspace')
    await expect(editor.getByTestId('confirm-abandon')).toBeVisible()
    await expect(editor.getByLabel(labels.title, { exact: true })).toHaveValue(title)
    await expect(editor.getByLabel(labels.action, { exact: true })).toHaveValue('Synthetic unsaved action preserved until explicit discard')
    await editor.getByTestId('keep-editing').click()
    await expect(editor.getByTestId('confirm-abandon')).toHaveCount(0)
  }
  await careLink.click(); await assertRetained()
  await doctor.goBack(); await assertRetained()
  await doctor.goForward(); await assertRetained()
  await selectElementOption(doctor, doctor.locator('.patient-switcher').getByRole('combobox'), patientNames[ids.patientB])
  await expect(doctor.locator('.patient-switcher')).toContainText(patientNames[ids.patientA])
  await assertRetained()
  await screenshot(doctor, testInfo, 'dirty-editor-navigation-input-retained')
  await editor.getByTestId('close-editor').click()
  await expect(editor.getByTestId('confirm-abandon')).toBeVisible()
  await editor.getByTestId('confirm-abandon').click()
  await expect(editor).toHaveCount(0)
  expect((await api(doctor, `/care-plans?patientId=${ids.patientA}&queue=HISTORY`)).items.some(p => p.title === title)).toBe(false)
  await careLink.click(); await expectUiRoute(doctor, '/care')
})

test('resilience: lost create response after actual commit recovers from server history without another draft', async ({ sessions }, testInfo) => {
  const doctor = await sessions.open('doctor'), title = planTitle('UNKNOWN draft recovery')
  await openDoctorPlans(doctor)
  await doctor.getByTestId('new-collaboration-draft').click()
  let editor = doctor.locator('.care-plan-editor')
  await expect(editor.getByLabel(labels.title, { exact: true })).toBeEditable()
  await acknowledgeExistingHistory(editor)
  await fillDraft(editor, title, [{ instruction: 'Synthetic uncertain committed instruction', assignee: ids.personal }])
  let lost = false
  await doctor.route('**/api/care-plans', async route => {
    if (route.request().method() !== 'POST' || lost) return route.continue()
    lost = true
    const response = await route.fetch() // Real Spring action completes its transaction first.
    expect(response.status()).toBe(200)
    expect((await response.json()).code).toBe(200)
    await route.abort('failed') // Drop only its delivery; never invent a success.
  })
  await editor.getByTestId('save-draft').click()
  await expect(editor.getByTestId('retry-original')).toBeVisible()
  const list = await api(doctor, `/care-plans?patientId=${ids.patientA}&queue=HISTORY`)
  const committed = list.items.filter(p => p.title === title)
  expect(committed).toHaveLength(1)
  expect((await api(sessions.owner, `/care-plans?patientId=${ids.patientA}&queue=HISTORY`)).items.some(p => p.id === committed[0].id)).toBe(false)
  await deny(sessions.owner, planPath(committed[0].id))
  await workspaceLink(doctor, '/care').click()
  await expectUiRoute(doctor, '/doctor-workspace')
  await expect(editor.getByTestId('retry-original')).toBeVisible()
  await editor.getByTestId('close-editor').click()
  await expect(editor.getByTestId('confirm-abandon')).toBeVisible()
  await editor.getByTestId('confirm-abandon').click()
  await expect(editor).toHaveCount(0)
  await doctor.unroute('**/api/care-plans')
  await doctor.getByTestId('new-collaboration-draft').click()
  editor = doctor.locator('.care-plan-editor')
  const existing = editor.locator('.notice li').filter({ hasText: title })
  await expect(existing).toBeVisible()
  await existing.getByRole('button', { name: labels.moreHistory, exact: true }).click()
  await expect(editor.getByLabel(labels.title, { exact: true })).toHaveValue(title)
  await expect(editor.getByTestId('prepare-publish')).toBeEnabled()
  expect((await api(doctor, `/care-plans?patientId=${ids.patientA}&queue=HISTORY`)).items.filter(p => p.title === title)).toHaveLength(1)
  await screenshot(doctor, testInfo, 'unknown-create-history-recovered')
})

test('resilience: lost receipt response after actual commit replays original input once', async ({ sessions }, testInfo) => {
  const doctor = await sessions.open('doctor'), owner = sessions.owner
  const source = await draft(doctor, { title: planTitle('UNKNOWN receipt replay') }); await publish(doctor, source)
  const view = await detail(owner, source.id), actionId = view.actions[0].id
  await openPlan(owner, view)
  await owner.getByTestId(`route-record-${actionId}`).click()
  const dialog = owner.locator('dialog.receipt-dialog')
  await dialog.getByTestId('receipt-note').fill('Synthetic frozen receipt that committed')
  let intercepted = false, replayKey = null, sends = 0
  await owner.route(`**/api/care-plans/actions/${actionId}/receipts`, async route => {
    const payload = route.request().postDataJSON()
    sends++
    if (replayKey === null) replayKey = payload.commandKey
    else expect(payload.commandKey === replayKey, 'Retry keeps original command key without exporting it').toBe(true)
    expect(payload.note).toBe('Synthetic frozen receipt that committed')
    if (intercepted) return route.continue()
    intercepted = true
    const response = await route.fetch()
    expect(response.status()).toBe(200); expect((await response.json()).code).toBe(200)
    await route.abort('failed')
  })
  await dialog.getByTestId('submit-receipt').click()
  await expect(dialog.getByTestId('retry-original')).toBeVisible()
  expect((await detail(doctor, view.id)).actions[0].events.filter(e => e.eventType === 'RECEIPT_SUBMITTED')).toHaveLength(1)
  await dialog.getByTestId('receipt-note').fill('Synthetic newer local input must never replace frozen receipt')
  await observeCommand(owner, 'POST', `/care-plans/actions/${actionId}/receipts`, () => dialog.getByTestId('retry-original').click())
  await expect(dialog.getByTestId('receipt-note')).toHaveValue('Synthetic newer local input must never replace frozen receipt')
  expect(sends).toBe(2)
  const persisted = await detail(doctor, view.id)
  expect(persisted.actions[0].events.filter(e => e.eventType === 'RECEIPT_SUBMITTED')).toHaveLength(1)
  expect(persisted.actions[0].events[0].note).toBe('Synthetic frozen receipt that committed')
  await dialog.getByTestId('close-receipt').click()
  await expect(dialog).toBeHidden()
  await owner.unroute(`**/api/care-plans/actions/${actionId}/receipts`)
  await owner.reload(); await expect(planDetail(owner)).toContainText('Synthetic frozen receipt that committed')
  await screenshot(owner, testInfo, 'unknown-receipt-replayed-once')
})

test('resilience: UNKNOWN doctor review guards SPA navigation, browser Forward and patient context', async ({ sessions }, testInfo) => {
  test.setTimeout(120000)
  const doctor = await sessions.open('doctor'), owner = sessions.owner
  const source = await draft(doctor, { title: planTitle('UNKNOWN review navigation') }); await publish(doctor, source)
  const view = await detail(owner, source.id), actionId = view.actions[0].id
  await api(owner, `/care-plans/actions/${actionId}/receipts`, { method: 'POST', body: command({ note: 'Synthetic execution awaiting guarded doctor review', occurredAt: new Date(Date.now() - 60000).toISOString(), entryMode: 'SELF', evidence: [] }, view.version) })
  await openPlan(doctor, view); await selectPatient(doctor, ids.patientA)
  const careLink = workspaceLink(doctor, '/care')
  await careLink.click(); await expectUiRoute(doctor, '/care')
  await doctor.goBack(); await expectUiRoute(doctor, planPath(view.id))
  await expect(doctor.getByTestId(`review-confirm-${actionId}`)).toBeEnabled()
  let lost = false, key = null
  await doctor.route(`**/api/care-plans/actions/${actionId}/reviews`, async route => {
    const body = route.request().postDataJSON()
    if (key === null) key = body.commandKey
    else expect(body.commandKey === key, 'Doctor retry retains the command key without exporting it').toBe(true)
    if (lost) return route.continue()
    lost = true
    const result = await route.fetch()
    expect(result.status()).toBe(200); expect((await result.json()).code).toBe(200)
    await route.abort('failed')
  })
  await doctor.getByTestId(`review-confirm-${actionId}`).click()
  await expect(doctor.getByTestId('retry-detail-original')).toBeVisible()
  await careLink.click()
  await expectUiRoute(doctor, planPath(view.id))
  await expect(doctor.getByTestId('retry-detail-original')).toBeVisible()
  await doctor.goForward()
  await expectUiRoute(doctor, planPath(view.id))
  await expect(doctor.getByTestId('retry-detail-original')).toBeVisible()
  await selectElementOption(doctor, doctor.locator('.patient-switcher').getByRole('combobox'), patientNames[ids.patientB])
  await expect(doctor.locator('.patient-switcher')).toContainText(patientNames[ids.patientA])
  await expect(doctor.getByTestId('retry-detail-original')).toBeVisible()
  const committed = await detail(owner, view.id)
  expect(committed.actions[0].events.filter(e => e.eventType === 'RECEIPT_CONFIRMED')).toHaveLength(1)
  await observeCommand(doctor, 'POST', `/care-plans/actions/${actionId}/reviews`, () => doctor.getByTestId('retry-detail-original').click())
  await expect(doctor.getByTestId('retry-detail-original')).toHaveCount(0)
  expect((await detail(owner, view.id)).actions[0].events.filter(e => e.eventType === 'RECEIPT_CONFIRMED')).toHaveLength(1)
  await doctor.unroute(`**/api/care-plans/actions/${actionId}/reviews`)
  await screenshot(doctor, testInfo, 'unknown-doctor-review-navigation-retained')
  await careLink.click(); await expectUiRoute(doctor, '/care')
})

base('390px static-demo smoke: distinct local model, dialog keyboard/focus and retained history', async ({ browser }, testInfo) => {
  const width = 390, videoDir = testInfo.outputPath('static-demo-390-video')
  await mkdir(videoDir, { recursive: true })
  const context = await browser.newContext({ baseURL: process.env.CARE_PLAN_E2E_BASE_URL, viewport: { width, height: 900 }, recordVideo: { dir: videoDir, size: { width, height: 900 } } })
  const page = await context.newPage(), checkErrors = pageErrorCounter(page), businessRequests = []
  page.on('request', request => { if (['fetch', 'xhr'].includes(request.resourceType())) businessRequests.push(request.resourceType()) })
  page.on('websocket', () => businessRequests.push('websocket'))
  try {
    await page.goto('/__demo/#plans')
    await expect(page.locator('[data-role="doctor"]')).toHaveAttribute('aria-pressed', 'true')
    const create = page.locator('[data-action="cp-create"]')
    await create.focus(); await create.press('Enter')
    const dialog = page.locator('#action-dialog')
    await expect(dialog).toBeVisible()
    await assertNoOverflow(page)
    await page.keyboard.press('Escape'); await expect(dialog).toBeHidden(); await expect(create).toBeFocused()
    await create.press('Enter')
    await dialog.locator('[name="title"]').fill(text('Synthetic static mobile smoke', '合成静态移动端冒烟计划'))
    await dialog.locator('#action-submit').focus(); await dialog.locator('#action-submit').press('Enter')
    await expect(dialog).toBeHidden()
    const planId = await page.locator('[data-plan-id]').first().getAttribute('data-plan-id')
    await page.locator('[data-role="family"]').click(); await page.locator('[data-page="careplan"]').click()
    await expect(page.locator('[data-plan-id]')).toHaveCount(0)
    await page.locator('[data-role="doctor"]').click(); await page.locator('[data-page="plans"]').click()
    await page.locator(`[data-action="cp-publish"][data-id="${planId}"]`).click()
    await dialog.locator('[name="confirm"]').check(); await dialog.locator('#action-submit').click(); await expect(dialog).toBeHidden()
    await page.locator('[data-role="family"]').click(); await page.locator('[data-page="careplan"]').click()
    await expect(page.locator('[data-plan-id]')).toHaveCount(1)
    await page.locator('[data-action="cp-submit"]').first().click()
    await expect(dialog).toContainText('ASSISTED')
    await dialog.locator('#action-submit').click(); await expect(dialog).toBeHidden()
    await expect(page.locator('.cp-plan > .cp-action[data-care-status="SUBMITTED"]')).toHaveCount(1)
    await page.locator('.cp-history > summary').click()
    await screenshot(page, testInfo, '390-static-demo-history')
    await page.locator('#reset').click()
    await expect(page.locator('[data-plan-id]')).toHaveCount(0)
    expect(businessRequests).toEqual([])
  } finally { await context.close(); checkErrors() }
})
