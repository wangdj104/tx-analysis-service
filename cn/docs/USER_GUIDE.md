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

### 10.2 CSV export

Open **Data Export**, select one patient and a date range, then choose the data types. Inspect the generated CSV before sharing. Spreadsheet applications may infer dates or identifiers, so verify formatting after opening.

### 10.3 Download a backup

Open **Family Care → Backup & restore**, preview the export, review missing-attachment warnings, and download the archive. Store it in an encrypted location with appropriate access and retention.

The backup covers accessible family business data. It excludes account passwords, full notification secrets, and unrelated users.

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

The application does not create tables automatically. Run `src/main/resources/sql/init.sql` against a new MySQL database and verify the configured database URL and account permissions.

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
