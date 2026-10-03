# Product Review: Care-plan Collaboration

## Implemented capability

The existing family-care workspace, clinical workbench and specialist modules remain the baseline. Collaborative care plans add a bounded workflow: assigned doctors save private drafts and publish versioned instructions with 1–50 one-time actions; record owners and explicitly authorized family caregivers submit execution records or request help; assigned and separately authorized nurses record assisted execution and administrative follow-up; doctors return or confirm receipts and explicitly close or cancel plans.

Published instructions are immutable. A revised publication creates new actions and supersedes unfinished earlier actions; historical receipts stay with their original version. Doctor confirmation means the record was reviewed and does not establish treatment success. Old internal `workflow_version=0` plans keep their old behavior and are never automatically published.

## Permissions and clinical limits

- A current active doctor role and patient assignment are required for clinical decisions. An administrator role is not a clinical bypass.
- Family readers need an explicit `CARE_PLAN` grant; recording requires `WRITE` or `PROXY`. A general family invitation or old blank/all-module grant does not add this module.
- Nurses need an active nurse role, active unexpired patient assignment and explicit module grant. Assignment administration does not itself share clinical content; owners grant access separately.
- Evidence references require access to the original measurement or medical-record module. Restricted references do not expose values, titles or attachment links. History and command replay recheck current authority.
- This milestone does not change prescriptions, implement recurring clinical actions, upload new receipt files or prove clinical effectiveness. Real provider delivery has not been validated.

## Installation, recovery and activation

New databases run `init.sql`, `doctor_workspace_20260921.sql`, `care_platform_upgrade_20260921.sql`, then `care_plan_collaboration_20261003.sql`; `init.sql` alone is insufficient. Existing installations back up the complete database and attachment storage and apply applicable additive upgrades without rerunning the baseline. Optional fictional demo data must not enter production.

`CARE_PLAN_ENABLED` defaults to `false`. Schema installation alone neither activates the workflow nor assigns nurse accounts or grants access. Disabling the feature retains published history; table deletion is not a rollback strategy. Family archive version 1 remains a limited export and excludes collaborative plans, revisions, actions, receipts, events/evidence, nursing assignments, notification outbox and commands. Full database and attachment backups are needed to recover the workflow.

## Verified scope and open acceptance

Final runtime `af06584b655b875ebfd16d0482a9712a99dc6cec` passed all seven jobs in [CI 37137746134](https://github.com/wangdj104/tx-analysis-service/actions/runs/37137746134): both complete backend test/package jobs, both frontend test/build jobs, static-demo checks and both real MySQL/browser jobs. EN/CN each passed two nine-method native runs with zero failures/errors/skips, 56 actual UTC combinations and complete synthetic restore per pass; all ten browser scenarios per locale passed, including corrected CN logo decode. Verified full frontend suites are EN 1143/1143 and CN 1150/1150, with both builds. Final backend/static job results are stated without guessing unavailable aggregate counts; the earlier isolated backend result was 456 discovered / 455 executed with one optional native skip per locale.

The public demo check covered 22 exact deployed files, whose captured hashes match final source. Four final CN role MP4s and four selected CN/EN screenshots were verified for integrity and inspected frames, with explicit scope in the [release evidence](CARE_PLAN_COLLABORATION_RELEASE.md). EN MP4s were not separately inspected. The final runtime includes the archive warning/contracts and reviewed logo/default-save corrections. The evidence-only documentation commit is identified by Git history. Its `[skip ci]` marker requires independent review and unchanged-source/remote verification, preserving the 1,127 runtime/test/CI/deployment entries and cannot replace the tested runtime SHA. The backend has not been deployed or activated in production; real provider delivery and clinical effectiveness remain unvalidated. Exact records are in the [machine-readable evidence](verification/care-plan-collaboration.json).

## Further work

- Preserve the accepted runtime identity and verify the source manifest and remote main whenever evidence-only documentation is published.
- Validate external provider delivery using an authorized non-production destination, and develop operational service monitoring.
- Extend accessibility and real-device coverage beyond the synthetic browser scenarios.
- Upgrade the Java / Spring baseline and maintain dependency vulnerability scanning.
- Add complete family-portable collaborative records as a separate versioned export milestone; preserve format 1 compatibility.
- Strengthen encrypted attachment storage, malware scanning and retention controls.
- Evaluate clinical outcomes only through separately designed and approved clinical validation.
