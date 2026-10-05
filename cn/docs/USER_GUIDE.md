# 澄心健康平台用户指南

本指南说明澄心健康完整应用的日常使用方式。实际页面与功能取决于账号权限和当前选择的家庭成员。

## Contents

1. [First sign-in](#1-first-sign-in)
2. [Navigation and patient context](#2-navigation-and-patient-context)
3. [Patient profiles](#3-patient-profiles)
4. [Daily care and vital signs](#4-daily-care-and-vital-signs)
5. [Medications, prescriptions, reminders, and stock](#5-medications-prescriptions-reminders-and-stock)
6. [Appointments, symptoms, questions, and family collaboration](#6-appointments-symptoms-questions-and-family-collaboration)
7. [Medical records and analytics](#7-medical-records-and-analytics)
8. [Dialysis and dry weight](#8-dialysis-and-dry-weight)
9. [Notifications and automated analysis](#9-notifications-and-automated-analysis)
10. [Export, backup, and restore](#10-export-backup-and-restore)
11. [Administration](#11-administration)
12. [Troubleshooting](#12-troubleshooting)
13. [Suggested product tour](#13-suggested-product-tour)

## 1. First sign-in

### 1.1 Obtain an account

The installation does not include a production default account. An administrator creates accounts, or the operator explicitly enables the one-time bootstrap administrator before first startup.

Enter your username and password on the sign-in page. If sign-in fails, verify the server address, network connection, account status, and credentials. Repeated failures may be rate-limited.

### 1.2 Change the password and sign out

Select the account control at the bottom of the desktop sidebar or in the mobile navigation drawer, then choose **Change Password**. The new password must contain at least 10 characters, including letters, numbers, and special characters.

Changing the password signs the account out. Sign in again with the new password. Always sign out on a shared device.

## 2. Navigation and patient context

The desktop interface uses a left sidebar, top patient switcher, contextual feature tabs, and `Ctrl/Cmd + K` feature search. Mobile uses a top bar, bottom shortcuts, and a full navigation drawer.

Select a family member before entering patient-specific data. The active name in the top bar defines the scope for family care, medication, records, dialysis, analytics, and exports. Selecting **All patients** is intended for supported overview screens; editing screens may still require one patient.

If the feature menu cannot refresh, the platform may temporarily show permissions already verified for the current session. Retry when connectivity returns. Permission checks still run on the server.

## 3. Patient profiles

Open **Patient Management** to add or edit a person. Complete the name and only the personal information necessary for care. Avoid collecting optional identifiers without a clear purpose.

Clinical details may include dialysis type, vascular access, primary diagnosis, allergy history, fluid limit, emergency contact, usual hospital, and clinician. These details guide forms and summaries but do not generate diagnoses.

Sensitive values are masked by default where supported. Revealing them on screen does not change access control; use that option only in a private environment.

## 4. Daily care and vital signs

### 4.1 Family Care home

Open **Family Care** to see medication tasks, upcoming appointments, handovers, and low-stock warnings. Patients can record their own actions; caregivers can record on behalf of a family member. The activity history identifies who performed each action.

Large-text mode increases readability for older users. It is a local interface preference and does not alter stored health data.

### 4.2 Blood pressure and glucose

Use **Record blood pressure / glucose** for a quick entry, or open **Blood Pressure & Glucose** for full history.

- Enter systolic and diastolic pressure in mmHg.
- Enter blood glucose in mmol/L when available.
- Select the correct period: fasting, before meal, after meal, bedtime, or random.
- Add context such as activity, symptoms, or an unusual measurement condition.

Review the value before saving. If a measurement appears dangerous or is accompanied by severe symptoms, follow the clinician's emergency plan or seek urgent care; do not rely on the platform alone.

### 4.3 Timeline, schedule, and goals

The **Health Timeline** combines measurements, dialysis, medication activity, and manual events. Edit source records in their original module.

Use **Care Plan** for today's tasks, dialysis schedules, personal blood-pressure and weight targets, and a visit summary. Targets should come from an appropriate clinician.

### 4.4 协作医生计划事项

该可选流程位于“家庭照护 → 医生计划事项”、医生工作台及独立护理跟进页，默认关闭（`CARE_PLAN_ENABLED=false`）。[已验收运行时证据](CARE_PLAN_COLLABORATION_RELEASE.md)记录双语软件流程验证与限制；后端尚未生产部署或启用。

有效分配医生建立私密草稿，添加 1–50 个一次性行动项、当前有效负责人及明确 UTC 偏移的期限。“保存私密草稿”不共享医嘱，“审阅并发布”展示患者及获授权协作者范围；修订还展示说明变化及旧版被替代事项。已发布正文只能通过新版本更改。历史内部计划可复制为新草稿，原记录保留。

记录所有者可查看已发布事项。家属必须获明确 `CARE_PLAN` 授权；`READ` 可查看，`WRITE` 或 `PROXY` 另可记录及求助。家庭成员身份不足以授权。计划事项使用独立接口，其他照护模块受限时仍能按权限访问。

执行记录要求 1–2000 字说明及实际时间，不允许未来时间；求助原因 1–1000 字。每条最多引用 5 条已有测量／病历，不支持新上传。读取引用仍需原模块权限，受限时仅说明存在关联记录。家属与护理固定标记代录；账户所有者的本人记录声明不是患者身份验证。

管理员先分配护理，记录所有者再单独授予 `CARE_PLAN`。护理需要有效角色、未过期分配及授权，护理跟进包含困难／跟进和待医生复核队列，可代录及记录管理跟进，不能发布、修订、确认或关闭计划；管理员角色也不提供临床权限。

医生确认回执或填写原因退回补充，后续回执追加而不删除旧证据。“记录已复核”不表示治疗成功。未完成／求助事项的逾期与提交后待复核时长分开显示。修订生成新的待执行事项并保留旧回执；取消需原因，关闭需当前全部事项已确认。本阶段关闭／取消后不能重开。

冲突后使用“重新加载服务器状态，保留我的输入”。结果不明时重试同一原命令或先核对服务器历史。关闭对话框不会取消已发出的写请求。切换患者、退出或撤权会清除上下文，历史也需当前权限。时间以 UTC 保存并注明浏览器本地时区。

### 4.5 照护执行与就诊准备

本工具只读，沿用协作照护前置条件，仍默认关闭。[发布证据](CARE_EXECUTION_REPORT_RELEASE.md)中的最终软件／有限报告验收已通过，前期协作里程碑验收单独保留。生产启用、真实供应商投递及临床效果未经验证。

**打开报告。** 可从家庭照护的医生计划事项、有效分配医生工作台、护理跟进或计划详情进入；计划详情预设单计划范围。专用路由为 `/care-plans/reports`，使用 `patientId` 与可选 `planId`，不要求开放旧综合报告菜单。旧就诊摘要／报告／导出页面仅在原页面权限下嵌入照护区域。

**确认范围。** 选择患者、全部已发布计划或单计划、起止日期、命名 IANA 时区及输出语言（`en` 或 `zh-CN`）。默认包含所选时区今天及此前 29 个日历日期；日期须成对填写，范围 1–366 天（含两端），结束日期不能在未来。浏览器与后端支持时可用 Japan、GMT 等无歧义别名，拒绝歧义缩写及裸偏移。日期只按记录时间筛选期间活动，不筛选当前状态，也不按实际执行时间筛选。

**分开理解两部分。** 当前 N 为 ACTIVE 计划当前已发布版本中的唯一行动项数量；OPEN、NEEDS_HELP、SUBMITTED、CONFIRMED 四类之和等于 N。零事项表示空集合，不显示完成率。当前困难、逾期／待补充、待复核及可读 OPEN 问题即使早于期间仍展示。期限前提交后等待医生复核，不因后来跨过期限就算患者逾期；退回／再次提交使用相应的新等待起点。`currentAsOf` 是数据库快照时间，`generatedAt` 是生成时间；待复核时长依据快照，不随浏览器时钟增长。

期间活动分别统计公开记录事件及不同事项 ID；重复提交是不同事件，计划级事件可无事项 ID。不能将各类不同事项数相加，也不能以当前 N 为期间事件分母。修订保留旧版说明及回执；取消／关闭将事项移出当前 N，历史仍保留。持久化 COMPLETED 计划代码显示为“已关闭”，不代表治疗成功。

**理解身份与原文。** SELF／ASSISTED 声明、操作者 ID 与历史角色、实际时间和记录时间分别保留。记录所有者的本人记录声明不是独立患者身份验证。护理管理跟进不同于执行回执及医生复核。问题的已记录答复是可编辑旧数据，不表示独立医生确认或完整答复历史；旧问题时间明确无时区，不伪造 UTC 瞬时。医嘱、备注、困难、答复及复核意见保留原语言。

**核对权限与未纳入部分。** READ 可准备／下载报告，不提供计划写权限。护理仍须当前护理角色、有效且未过期分配和明确 CARE_PLAN 授权。家庭成员身份及管理员角色都不提供捷径。CARE_PLAN-only 不扩大用药、病史、问题或旧页面／菜单权限。问题区分有权有数据、有权零数据、无权及单计划未纳入，且不进入两类照护 CSV。证据另需当前 MEASUREMENTS 或 MEDICAL 权限和患者／来源匹配；受限引用仅显示存在记录，有权引用提供需认证的原模块位置，不带原数值或附件。打开来源时再次授权。

**共享前重新生成。** 每次预览、导出和包含照护的打印均重新检查权限并读取数据，生成时间可不同。旧摘要与照护组合打印重新读取两者，不宣称跨模块原子临床快照。患者、计划、日期、时区、语言或账号改变使旧输出失效。关闭／取消仅保证客户端不接受晚到响应，不保证服务器立即停止。最终交付前发现撤权会丢弃准备内容；最终检查之后或文件交付后撤权不能收回网络或已保存副本。报告不更改照护状态、不发送照护通知；下载成功仅指浏览器获得文件，不表示医生已读或已确认。

**选择格式。** HTML／PDF 单份选一种界面语言，自由原文不翻译。PDF 需要经验证的配置字体覆盖全部实际可见字符。已有拉丁／中日韩文字测试；未支持的复杂塑形、从右到左、组合或补充字符及缺字明确失败，可改用 HTML，不承诺通用 Unicode 支持。打印／PDF 长文换行并重复表头，不静默截断备注。

当前事项 CSV 每行一个当前事项，期间事件 CSV 每行一个公开记录事件；字段名与展示标签本地化，稳定代码保留，非空行重复范围／时区／语言／版本／生成信息。UTF-8 BOM、引号及多行单元格是有意格式。公式／控制前缀文本安全转义不改源记录；CSV 不是原始字节归档或匿名化。空 CSV 只有 BOM 与表头，无虚构元数据行；实际点击下载前页面显示 0 行、范围、时区、语言、版本及生成时间，安全文件名含语言和生成时间。空文件本身不承诺离线自含元数据。

**限制与错误。** 上限为当前事项 1,000、期间事件 5,000、纳入问题 200、UTF-8 原文 8 MiB、输出 32 MiB；服务端总预算 30 秒，新报告客户端超时 45 秒，不静默截断。保守的 8 MiB 原始存储单元预检也可能拒绝仅部分内容会被展示的旧单元。可缩短期间、选择单计划或独立较小 CSV；完整预览失败不禁用其自身集合未超限的 CSV。

无权、FEATURE_DISABLED、REPORT_ACCESS_CHANGED（重新生成）、数据不一致、带明确类别／数值的超限、渲染不可用及超时／网络失败分别处理，普通失败不能当作零结果成功。失败生成不应出现部分成功文件。报告仅是有限复核辅助，不是处方、医学证明、可恢复档案或已验证临床结果；家庭备份格式及既有排除项不变。

## 5. Medications, prescriptions, reminders, and stock

### 5.1 Create the medication catalog

Open **Medication Management → Medication List** and add the medication name, strength, unit, dosage form, manufacturer, and notes as available. Catalog details describe the product; they do not replace prescription instructions.

### 5.2 Create prescription versions

In **Family Care → Prescriptions**, choose the medication and enter the clinician, effective date, unit, dose times, quantities, repeat days, and instructions. Attach a supporting prescription image when appropriate.

When directions change, create a revised version. To stop a medication, create a stop version. Preserving versions makes the history auditable and prevents old reminders from being mistaken for current directions.

### 5.3 Configure reminders

Open **Medication Reminders** to define reminder times, repeat days, start and end dates, and notification channels. A reminder requires an active medication and patient.

Mark a dose **Taken**, **Snoozed**, or **Skipped**. Supply a reason when skipping. A confirmed dose updates medication stock once; retrying the same action does not deduct stock again.

### 5.4 Manage inventory

Open **Family Care → Medication stock** to set the actual remaining quantity, unit, warning days, and warning quantity. The unit must match the prescription. Use **Restock** for purchases and **Correct** for a verified count adjustment. The history preserves each movement and actor.

## 6. Appointments, symptoms, questions, and family collaboration

### 6.1 Appointments

Create an appointment with date, time, hospital, department, clinician, preparation notes, companion, and an optional linked report. Complete or cancel it after the event. Add the next appointment when instructed.

### 6.2 Symptoms and questions

Record symptom severity, duration, context, response, and whether it is ongoing, improved, or resolved. Repeated entries show the change from the previous score.

Add questions whenever they come to mind. During or after the visit, record the clinician's answer and follow-up action. A follow-up can become a family care task.

### 6.3 Invite caregivers

The patient record owner can generate a single-use invitation code under **Family collaboration**. Send it through a trusted channel. The recipient chooses **Join shared care**, enters the code and relationship, and then receives access to that family member.

Invitation codes expire after seven days and after first use. The owner or an administrator can remove a caregiver. Removing access does not delete the historical activity that identifies previous actions.

### 6.4 Handovers and escalation

Create a handover with a clear task, assigned caregiver, due time, and notes. In **Care settings**, choose whether dialysis features are visible and whether overdue tasks should notify a caregiver after a configured delay.

## 7. Medical records and analytics

### 7.1 Upload and verify reports

Open **Medical Records → Upload Report** and choose an image or PDF. Optional OCR can propose record metadata and structured test items. Always compare extracted values with the original report before saving.

OCR confidence is not clinical certainty. Correct names, dates, units, reference ranges, and abnormal flags manually. Keep the original attachment when policy allows.

The record list supports viewing, editing, deletion, and attachment download. **Abnormal Results** groups flagged items; **Result Trends** charts compatible results over time.

### 7.2 Nutrition, alerts, and reports

- **Nutrition Diary** records meals, protein, calories, fluid, appetite, symptoms, and notes.
- **Nutrition Assessment** summarizes weight, BMI, intake, laboratory context, and SGA fields.
- **Complication Tracking** records events, severity, treatment, outcome, and dialysis context.
- **Health Alerts** evaluates configured rules against supported health indicators.
- **Blood Pressure Pattern Analysis** summarizes variation, high/low counts, and weight relationships.
- **Health Report** combines selected periods and charts into a reviewable report.

Analytics highlight patterns for review. They do not establish a diagnosis or treatment plan.

## 8. Dialysis and dry weight

Open **Dialysis Management** to enter session date, pre- and post-dialysis weight, previous post-dialysis weight, interval, fluid removed, blood pressure, and notes. The platform derives weight gain and comparison ranges when enough data is present.

Use text import for structured notes, then verify every parsed value. The statistics and trend pages compare sessions over a selected period. AI analysis is optional and should be reviewed against source data.

Use **Dry Weight** to maintain monthly targets and adjustment rationale. Changes should reflect professional guidance. Preserve previous values rather than rewriting history.

## 9. Notifications and automated analysis

### 9.1 Add a channel

Open **Notification Settings**, select a supported channel, enter a descriptive name and webhook configuration, then send a test message. WeCom and DingTalk bot webhooks support outbound notifications. Browser notifications require user permission and the page's secure-origin requirements.

Never paste webhook URLs or signing secrets into issues, screenshots, or public documentation. API responses mask stored secrets.

### 9.2 Recipients and delivery

Medication reminders use their selected channels. Care escalation uses the caregiver configured in **Care settings** and that account's channels. Automated analysis sends a draft-ready notice to the selected channels; clinical text is sent only after a user approves the draft and chooses **Approve & notify**.

A successful test confirms delivery configuration, not that every future message is clinically appropriate. Keep message content minimal and avoid unnecessary sensitive details.

### 9.3 Automated health analysis

Open **Health Analytics → Automated Analysis** to create a task. Choose the patient, frequency, run time, analysis range, analysis items, and notification channels. Each run creates a **review-required draft**. Open **Clinical Workbench → Data Quality** to approve, approve and notify, or reject it.

### 9.4 Clinical Workbench

Open **Clinical Workbench** for six coordinated workflows:

- **Attention Center** combines unresolved alerts, due medication tasks, low stock, and overdue care items with severity, evidence, service level, and next action.
- **Data Quality** lists unverified imports, low-confidence OCR, incomplete context, possible duplicates, incomplete dialysis quality fields, and AI drafts.
- **Medication Safety** screens recorded allergies, possible duplicate therapy, curated interaction rules, renal cautions, and recent adherence. It never changes a prescription automatically.
- **Dialysis Quality** calculates IDWG percentage, UFR, Kt/V, URR, and vascular-access flags from recorded sessions. Recurring schedules must be previewed and explicitly confirmed.
- **Emergency Card** produces a printable, minimum-necessary handoff summary. Verify it before sharing.
- **FHIR / Device Import** parses supported observations into a preview. Confirmed imports remain marked **review required** until a person verifies them.

Automation requires the backend scheduler and any configured AI provider. It should assist review, not make unattended clinical decisions.

## 10. Export, backup, and restore

### 10.1 Visit summaries and reports

Generate a **Visit Summary** before an appointment. It includes the selected patient's recent measurements, current medication, unresolved alerts, symptoms, questions, and key context. Review it before printing or saving as PDF.


照护区域及“照护执行报告”使用[第 4.5 节](#45-照护执行与就诊准备)的独立权限、当前／期间口径和重新生成规则；原综合摘要访问条件不扩大。组合打印重新读取旧摘要和照护，不宣称跨模块一致快照。

### 10.2 CSV export

Open **Data Export**, select one patient and a date range, then choose the data types. Inspect the generated CSV before sharing. Spreadsheet applications may infer dates or identifiers, so verify formatting after opening.


照护的当前事项与期间事件 CSV 使用[第 4.5 节](#45-照护执行与就诊准备)的独立行模型、稳定代码和公式防护。空文件仅 BOM＋表头，0 行及完整范围元数据在下载前页面展示；这些有限报告不能恢复协作历史。

### 10.3 Download a backup

Open **Family Care → Backup & restore**, preview the export, review missing-attachment warnings, and download the archive. Store it in an encrypted location with appropriate access and retention.

家庭归档格式 1 仅包含预览列出的记录类型，表清单保持不变；不包含问诊／聊天、照护全流程、医生工作台、协作计划、版本、行动项、回执、事件／证据、护理分配、通知发件箱、命令、授权或系统账号。附件仅包含数据库内嵌内容。恢复协作历史需要另行备份完整数据库及附件存储。

### 10.4 Restore as an independent copy

Preview the archive before restoring. Restore creates new patient and record identifiers and remaps relationships, leaving existing records unchanged. If any insert fails, the restore transaction rolls back.

## 11. Administration

Administrators can manage users, roles, menus, and audit logs.

- **User Management:** create or disable accounts, assign roles, and reset passwords securely.
- **Role Management:** assign menu permissions according to least privilege. The built-in administrator role is protected.
- **Menu Management:** maintain navigation names, routes, icons, permissions, order, and status.
- **Audit Log:** review request actor, method, path, status, duration, client address, and time.

Administrative UI permissions do not replace backend authorization. Test new roles with a non-administrator account before production use.

## 12. Troubleshooting

### The page signs out or reports unauthorized access

The token may have expired, the password may have changed, or permissions may have been revoked. Sign in again. If the issue continues, ask an administrator to verify account status and roles.

### A feature is missing

The feature may be hidden by role permissions or the selected patient's care settings. Refresh the feature menu, verify the role, and check whether dialysis features are disabled.

### Data belongs to the wrong patient

Stop editing immediately. Confirm the active family member in the top bar. Report any confirmed cross-patient access as a security issue.

### OCR returned incorrect values

Use a clear, upright image with the full report visible, retry with the correct report type, and manually correct the proposal. Never save OCR output without source comparison.

### Notifications do not arrive

Send a channel test, verify outbound HTTPS access, webhook security settings, channel status, and the relevant reminder or automation selection. A server does not need a public IP for outbound webhook messages.

### The database is empty after startup

应用不会自动创建表。新 MySQL 库依次运行 `init.sql`、`doctor_workspace_20260921.sql`、`care_platform_upgrade_20260921.sql`、`care_plan_collaboration_20261003.sql`；核对数据库连接与账号权限。已有库先完整备份，再应用适用增量脚本，不重跑基础脚本。

## 13. Suggested product tour

1. Sign in and create a fictional family member.
2. Add a medication and create a prescription version.
3. Set stock and complete a medication check-in.
4. Record blood pressure and review its trend.
5. Create an appointment, symptom, question, and handover.
6. Upload a fictional report and verify structured results.
7. Configure a test notification channel using a non-production group.
8. Generate a visit summary and CSV export.
9. Download a backup, preview it, and restore an independent copy in a test environment.
10. Review the audit log and sign out.

Use fictional data for demonstrations. Do not use the public static demo or screenshots for real health information.
