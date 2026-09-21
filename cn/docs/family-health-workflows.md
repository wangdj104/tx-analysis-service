# Everyday Family Health Workflows

This guide describes the shortest reliable path through common daily tasks. For detailed family collaboration, see the [Family Care Guide](family-care-guide.md). A first deployment only needs `src/main/resources/sql/init.sql`.

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

## Regression checks

- Switching patients clears stale page data and never displays another patient's records.
- A repeated medication confirmation does not deduct stock twice.
- Cancelled schedules and stopped prescriptions remain visible in history.
- CSV exports contain only the selected scope.
- Backup restore creates a separate patient copy and preserves attachment relationships.
- Failed restores roll back all inserted data.
