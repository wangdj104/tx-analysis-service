# Care-plan Collaboration Release Evidence

**Status: final runtime software acceptance and the listed recording-deliverable scope passed.** Updated 2026-10-03 UTC. The backend is **not deployed or activated in production**. `CARE_PLAN_ENABLED` defaults to `false`.

The [machine-readable evidence](verification/care-plan-collaboration.json) records exact acceptance identities. Tested final runtime commit: `af06584b655b875ebfd16d0482a9712a99dc6cec`, complete tree `f98e3ed9fe29800fabc13cae0ced4ac983761c92`. Reconciled local commit `54848b6ac7cf67387900b51688afc15d1ecf478d` has the same tree. This runtime includes the mirrored backup warnings/contracts and reviewed logo/default-save corrections. [Final CI 37137746134](https://github.com/wangdj104/tx-analysis-service/actions/runs/37137746134) passed all seven jobs. The later evidence-only documentation commit remains distinct from the tested runtime SHA.

## What is implemented

- Assigned doctors save private drafts, publish explicit scope and immutable versions, review execution records, publish revisions, cancel with a reason and close after all current actions are reviewed.
- Record owners and authorized family caregivers see published one-time actions, record execution or ask for help. Assisted entries retain their actor/role; an owner self-entry declaration is not patient identity verification.
- Nursing has a separate restricted queue for help/follow-up and waiting-for-doctor-review work. Nurses can record assisted execution and administrative follow-up without making clinical publication/review decisions.
- Version history, receipts and append-only events are retained. Revised publication creates new actions and supersedes unfinished previous actions. Closed or cancelled plans cannot be reopened in this milestone.
- Current authority is checked on reads, writes, evidence, history and command replay. Command keys and expected versions protect retries and concurrent changes. The persistent notification outbox distinguishes delivery outcomes and suppresses unauthorized recipients.

These behaviors passed synthetic software workflow validation on the exact final runtime; no clinical-effectiveness validation is implied. The milestone covers one-time actions; recurring clinical schedules, prescription changes and new receipt-file uploads are outside its scope.

## Roles, access and states

| Role | Required authority and boundary |
| --- | --- |
| Record owner | Active account/role and owned active patient record; can read and record published tasks |
| Assigned doctor | Current active doctor role plus active patient assignment; clinical authority comes from that assignment |
| Family caregiver | Current family role plus explicit active `CARE_PLAN` grant; `READ` reads, `WRITE`/`PROXY` also records |
| Nurse | Current active nurse role plus active unexpired assignment and explicit `CARE_PLAN` grant; writes require `WRITE`/`PROXY` |
| Administrator | Can govern nurse assignments; the role provides no clinical read/publish/review shortcut and cannot replace owner consent |

Family membership and old blank/all-module grants do not implicitly add `CARE_PLAN`. An action assignment is not an access grant. Evidence additionally requires current access to its originating `MEASUREMENTS` or `MEDICAL` module; restricted evidence yields only a generic existence placeholder. Losing authority also removes access to old versions and successful command replays.

Plan lifecycle: `DRAFT`, `ACTIVE`, `COMPLETED`, `CANCELLED`. Action states: `OPEN`, `NEEDS_HELP`, `SUBMITTED`, `CONFIRMED`, `SUPERSEDED`, `CANCELLED`. Open/help-needed overdue work is separate from submitted work awaiting review. `CONFIRMED` means a doctor reviewed the record; it does not establish clinical improvement. Historical first/latest submission times remain separate from current review waiting time.

## Actual API boundary

Controller paths below are relative to the configured API base. Each clinical action is server-authorized; client `allowedActions` is only a rendering hint. Mutation commands use `commandKey` and `expectedVersion`; the notification manual-retry endpoint instead accepts `duplicateRiskAcknowledged`.

| Method | Controller route | Purpose |
| --- | --- | --- |
| GET | `/care-plans/capabilities` | Authenticated feature availability |
| GET | `/care-plans` | Authorized patient/queue list |
| GET | `/care-plans/assignees` | Currently authorized recording accounts |
| GET | `/care-plans/{id}` | Authorized plan detail |
| GET | `/care-plans/{id}/revisions` | Revision history |
| GET | `/care-plans/{id}/revisions/{revisionId}` | Current-authority revision snapshot |
| GET | `/care-plans/{id}/events` | Authorized append-only history |
| POST | `/care-plans` | New private draft |
| POST | `/care-plans/{id}/revisions/{revisionId}/save` | Save private draft |
| POST | `/care-plans/{id}/revisions` | Create next revision draft |
| POST | `/care-plans/{id}/revisions/{revisionId}/publish` | Publish after scope/revision-impact confirmation |
| POST | `/care-plans/{id}/cancel` | Cancel with reason |
| POST | `/care-plans/{id}/close` | Close after all current actions are confirmed |
| POST | `/care-plans/actions/{id}/receipts` | Execution record |
| POST | `/care-plans/actions/{id}/help` | Request help with reason |
| POST | `/care-plans/actions/{id}/follow-ups` | Nursing administrative follow-up |
| POST | `/care-plans/actions/{id}/reviews` | Doctor confirms or returns a receipt |
| GET | `/care-plans/notifications` | Scoped delivery statuses |
| POST | `/care-plans/notifications/{id}/retry` | Explicit authorized manual retry |
| GET / POST | `/care-nurse-assignments` | Assignment listing / administrative assignment |
| POST | `/care-nurse-assignments/{id}/revoke` | Administrative assignment revocation |

No arbitrary status-update endpoint is introduced. Receipt notes contain 1–2000 Unicode characters; help/return explanations contain 1–1000. At most five existing authorized references are accepted. Occurred time cannot be in the future. Deadlines use explicit ISO 8601 offsets, persist as UTC and display the browser timezone.

## Installation, old records and recovery

New databases run the raw SQL scripts in this order:

1. `src/main/resources/sql/init.sql`
2. `src/main/resources/sql/doctor_workspace_20260921.sql`
3. `src/main/resources/sql/care_platform_upgrade_20260921.sql`
4. `src/main/resources/sql/care_plan_collaboration_20261003.sql`

Use the corresponding `cn/` paths for the independent Chinese edition. `demo-data.sql` is optional fictional local data and must not enter production. Existing databases require complete database/attachment backups and applicable idempotent upgrades, without rerunning the baseline. Apply only the collaboration increment when its prerequisites are already installed.

Legacy `workflow_version=0` internal plans keep their original API behavior and visibility, with no automatic publication/actions/notifications. Copying one creates a separate draft and still requires explicit publication. Installing schema does not assign nurse accounts, grant patient access or enable the feature. Disable the feature while retaining published tables/history if rollback is needed.

**Family archive version 1 stays unchanged.** Its existing table list remains intact. It excludes collaborative plans, revisions, actions, receipts, events/evidence, nurse assignments, notification outbox and commands, alongside the other pre-existing exclusions. Only database-embedded attachments are included. It cannot recover this workflow: preserve the complete database and attachment storage separately. Real MySQL full synthetic restore is separate acceptance evidence, not a property of the limited family archive.

## Final software acceptance and evidence

| Check | Final result | Scope and limits |
| --- | --- | --- |
| Final CI | `37137746134` on `af06584b655b875ebfd16d0482a9712a99dc6cec`, 7/7 jobs succeeded | Includes Task 13 warning/tests and logo/default-save corrections |
| Real MySQL | EN/CN each two 9-method passes, 0 failures/errors/skips; 56 actual UTC combinations and full synthetic restore per pass | Tables/rows/columns/keys/relationships match; no production data |
| Real browser | EN/CN each 10/10, 0 failures/skips/not-run cases; no fail-fast/retries; real CN logo decode passed | Nine real Spring/MySQL/Vue workflows plus one static-demo scenario |
| Both backends | Complete test and package steps succeeded in both final CI jobs | Aggregate test counts not guessed; exact independent native counts are above |
| Both frontends | Verified full suites EN 1143/1143 and CN 1150/1150, no failures/skips and both builds; corresponding final CI jobs succeeded | Final runtime product source |
| Static demo | Final CI job succeeded; 22 captured public files had HTTP 200/exact bytes and their hashes match final source | Static demo does not deploy the backend |
| Archive contract | Red: 3 tests with 2 expected warning failures per locale; green: 3/3, 0 skips; final backend CI included/passed those tests | Version 1 and its existing declared table list preserved |
| Listed final media | Four real CN main-role MP4s and four original CN/EN screenshots verified for digest/manifest/frame/pixel integrity | Selected contact sheets/frames inspected; EN MP4s were not separately downloaded/inspected and all suite videos were not watched end to end |

The [EN real-acceptance job](https://github.com/wangdj104/tx-analysis-service/actions/runs/37137746134/job/111245622313) passed native MySQL at 16:42:42 and 16:42:54 UTC, then all ten browser cases at 16:47:53 UTC (4.6 minutes). The [CN job](https://github.com/wangdj104/tx-analysis-service/actions/runs/37137746134/job/111245622164) passed at 16:42:59 and 16:43:13 UTC, then all ten browser cases at 16:49:57 UTC (6.2 minutes). Each restore checked all tables/rows and column/key/relationship definitions.

The matrix covers authority/draft privacy, owner/assisted execution, help/return/review/closure, revision/cancellation, module-only access, cross-patient rejection/revocation/replay, expiry, 390px keyboard/focus/context/navigation/logout, dirty-editor guards and recovery after uncertain committed responses. The logo correction covers `/cn/` default display and saving the default value without expanding patient permissions.

Final MP4s were produced by the acceptance runner and copied without re-encoding/editing. All are H.264, 1364×900, 25fps: personal 63.72s/1593 frames; family 59.36s/1484; doctor 62.32s/1558; nurse 56.68s/1417. The corrected CN logo renders in selected role frames and completed-history screenshots; CN/EN history structures match, and real 390px receipt/new-account denial screens are visible. The machine record includes six GitHub artifact IDs/names, ZIP digests, runner manifests and per-file hashes. Public-safe provenance SHA256: `dac870bd1bec67c74880bb0a50b9c16297a2ac9b45622fb98432e07fe6a876e9`. Private user file delivery is separate from public repository evidence.

The public [English demo](https://wangdj104.github.io/tx-analysis-service/) and [Chinese demo](https://wangdj104.github.io/tx-analysis-service/cn/) use fictional tab-local data. The captured 2026-10-03 14:52 UTC check covered both HTML roots plus 20 script/style/image assets: HTTP 200 and exact immutable-source/reported-Pages-deployment bytes. Those hashes also match published `75eea7cfadafd6366c0e2190c9df13b30a744d6d` and final runtime source; demo source did not change. This refresh reused captured network bytes and compared final source, with no fresh network fetch or claim of freshly reverified Pages deployment metadata. Public browser spot checks covered drafts, synthetic publication, receipt, nursing scope and review controls; the separate real CI establishes complete backend workflow acceptance.

Earlier `/care`, mobile focus and synthetic-fixture failures remain historical and were followed by corrections and a full new acceptance run. Earlier green `37135152973` preceded backup-contract/logo integration; final `37137746134` is this report's complete tested-runtime identity. Failed attempts and old clips do not substitute for final proof.

## Evidence-only documentation identity

The evidence-only commit containing these eleven documents is identified by this file's Git history and may use the approved `[skip ci]` marker. Test claims remain bound to runtime `af06584b655b875ebfd16d0482a9712a99dc6cec`; they must not be reassigned to the documentation commit SHA.

Excluding the eleven exact documentation paths, the 1,127 tracked mode/type/blob-ID/path entries have SHA256 `f42036bca283a71faafe1397d562ba505b4f452d35feabf204b4511f047ed02c`. The algorithm retains `git ls-tree -r` order, joins entries with LF and includes the final LF. The accepted reconciled source matches that manifest. For documentation publication, verify the entries and hash remain identical and resolve the documentation commit on remote main to prove runtime, tests, CI and deployment source retain the accepted identity. This is the verification method, not a claim that a future publication check has already run.

## Deployment and verification boundary

Final runtime and the listed media scope passed. Evidence-only publication requires independent review and verification of all 1,127 source entries, remote main and unchanged public-demo content. Local packaging status is recorded in the evidence JSON. Production migration, backend rollout and enabling `CARE_PLAN_ENABLED` require a separately authorized target; none has been executed here.

External channels use generic notices and authenticated page locations. `DELIVERED` means transport delivery, not reading; `UNKNOWN` is not automatically resent, and manual retry must acknowledge possible duplication. Real provider delivery has not been validated. No clinical-effectiveness claim is supported.
