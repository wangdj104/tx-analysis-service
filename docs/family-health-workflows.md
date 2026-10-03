# Everyday Family Health Workflows

This guide describes the shortest reliable path through common daily tasks. For detailed family collaboration, see the [Family Care Guide](family-care-guide.md). New databases require `init.sql` → `doctor_workspace_20260921.sql` → `care_platform_upgrade_20260921.sql` → `care_plan_collaboration_20261003.sql`; see the [installation steps](../README.md#local-development).

## Daily routine

1. Sign in and select the correct family member in the top bar.
2. Open **Family Care** and review medication, appointment, handover, and low-stock tasks.
3. Confirm doses only after they are taken. If a dose is skipped, record the reason.
4. Record blood pressure or glucose with the correct measurement period.
5. Add symptoms, questions, or family handovers while the context is fresh.
6. Review the health timeline for unexpected gaps or duplicate entries.

## Medication changes

1. Add the medication to the catalog.
2. Create a prescription version with effective dates, units, dose times, and repeat days.
3. Configure reminders if proactive notifications are needed.
4. Set actual stock and warning thresholds.
5. Create a new prescription version for changes; do not overwrite historical instructions.

## Before and after a visit

Before the visit, create an appointment, assign a companion, add preparation notes, and collect questions. Generate a visit summary and review recent measurements, medication, unresolved alerts, symptoms, and records.

After the visit, save answers and follow-up actions, link relevant reports, update prescriptions through a new version, and create the next appointment if needed.

## Follow a published doctor plan

1. The currently assigned doctor saves a private draft with explicit action deadlines and responsible accounts, then reviews and publishes its scope.
2. The record owner opens **Family Care → Doctor plan tasks**. Family caregivers need explicit `CARE_PLAN` read access; recording additionally requires `WRITE` or `PROXY`. Plans load independently of access to the older full-record care context.
3. Record execution with the actual past/current time and a note, or request help with a reason. A family or nurse entry is marked assisted; an account-owner declaration does not verify the patient's identity. Optional references use existing authorized measurements or medical records, with at most five per record.
4. Nursing staff use **Nursing follow-up** after both administrative assignment and owner authorization. They can record follow-up and assisted execution; doctors review receipts.
5. If the doctor requests more information, add a new receipt. Confirmation preserves the evidence and means the record was reviewed. The doctor can close only after all current actions are confirmed.
6. Clinical instruction/deadline changes require a newly published version. Unfinished older actions become superseded; previous receipts stay in history. Cancellation requires a reason and preserves earlier records.

A submitted receipt waiting for doctor review is distinct from an open overdue patient action. A timeout may mean a command already committed: keep the original input, use the original retry or reload server history before starting another command. Closing a dialog does not cancel an in-flight server write. Access revocation removes current plan access, including historical versions.

This workflow defaults off (`CARE_PLAN_ENABLED=false`). The final runtime passed software workflow validation in [CI 37137746134 and its release evidence](CARE_PLAN_COLLABORATION_RELEASE.md), and the backend is not deployed or activated in production. The [static demo](https://wangdj104.github.io/tx-analysis-service/) uses fictional tab-local data.

## Preserve records

Family archive format 1 and its declared table list are unchanged. It excludes collaborative plans, revisions, actions, receipts, events/evidence, nurse assignments, notification outbox and commands. Restore creates independent copies of included records; complete database and attachment-storage backup/restore is required to preserve the collaborative workflow.

## Regression checks

- Switching patients clears stale page data and never displays another patient's records.
- A repeated medication confirmation does not deduct stock twice.
- Cancelled schedules and stopped prescriptions remain visible in history.
- CSV exports contain only the selected scope.
- Backup restore creates a separate patient copy and preserves attachment relationships.
- Failed restores roll back all inserted data.
