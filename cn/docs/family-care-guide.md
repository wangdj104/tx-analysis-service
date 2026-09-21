# Family Care Features and Usage

The Family Care workspace is designed for patients and caregivers who need to coordinate everyday health work without losing the context behind each action.

## Core family workflows

1. Switch between patient and caregiver modes, and optionally enable large-text mode.
2. Record appointments, follow-up examinations, preparation notes, companions, and linked reports.
3. Preserve prescription versions, effective dates, dose schedules, stop orders, and attachments.
4. Confirm medication intake for yourself or a family member, with idempotent inventory deduction.
5. Record symptoms over time, including severity, duration, context, response, and progress.
6. Prepare questions before a visit and save the clinician's answer and follow-up action afterward.
7. Invite trusted relatives into shared care with a single-use, expiring invitation code.
8. Create handovers, assign caregivers, and optionally escalate overdue work through notification channels.

The **Backup & Restore** tab exports the selected account's accessible family health data. Restoring creates independent copies with remapped identifiers so existing records are not overwritten.

## Dialysis text import

The dialysis text importer accepts structured plain text for one or more sessions. Review every parsed field before saving, especially dates, weights, blood pressure, and fluid removal. Missing values should remain empty rather than being guessed.

Example:

```text
Date: 2026-09-17
Pre-dialysis weight: 63.4 kg
Post-dialysis weight: 60.8 kg
Blood pressure: 138/82 mmHg
Fluid removed: 2.6 kg
Notes: Stable session
```

Text import is an entry aid, not a clinical interpretation tool.

## Notification bots

企业微信和钉钉群机器人可在**通知设置**中配置。请在目标群创建机器人，将 Webhook 地址添加到澄心健康并发送测试消息；如平台支持，请启用关键词或签名等安全控制。

Webhook URLs and signing secrets are credentials. Store them only in the platform configuration, never in screenshots, issues, sample files, or source control.

Receiving inbound WeCom messages requires a separate custom-application or long-connection integration and is not provided by an outbound webhook.

## Deployment and verification

For the first deployment, run `src/main/resources/sql/init.sql` once. No upgrade scripts are required. The backend does not create or alter tables automatically. `demo-data.sql` is optional local demo data and must not be imported into production.

After changes, rebuild and restart the backend, rebuild and publish the frontend, then verify patient isolation, medication idempotency, invitations, notifications, exports, and backup restore.

Backups exclude system passwords, full bot secrets, and unrelated accounts. Historical attachments whose source files no longer exist are reported as warnings during preview. Large deployments should use dedicated protected object storage.
