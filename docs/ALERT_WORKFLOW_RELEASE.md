# Measurement follow-up: Phase A implementation checkpoint

Status at 2026-10-14 UTC: **independent scoped source review complete: SPEC PASS, QUALITY PASS with one nonblocking documentation finding; bounded source-publication readiness READY**. External acceptance remains pending. This is not production rollout approval. No push, deployment, production migration, feature activation or real notification was performed. `ALERT_WORKFLOW_ENABLED=false` and `CARE_PLAN_ENABLED=false` remain independent defaults.

The written design was approved on 2026-10-13 at 16:17 UTC. This checkpoint covers Phase A only. The medication, stock, appointment, consultation, specialty and comprehensive restoration work in [the remaining roadmap](superpowers/specs/2026-10-13-health-closed-loop-design.md#10-finite-remaining-roadmap) is not complete.

- [Chinese release note](../cn/docs/ALERT_WORKFLOW_RELEASE.md)
- [Small Chinese Phase A handoff](ALERT_WORKFLOW_MORNING_20261014.zh-CN.md)
- [Machine-readable verification record](verification/alert-workflow.json)
- [User journey and limitations](alert-workflow-ui.md)
- [Operator-only database and deployment instructions](OFFLINE_DEPLOYMENT.md)

## What is implemented

Both existing measurement stores remain separate. A workflow-enabled save returns a durable identity receipt; a separately authorized read supplies current classification, source revision and follow-up links. Personal follow-up records acceptance, handling, self/assisted feedback and reasoned owner closure. Clinical escalation starts a new cycle and requires a current assigned doctor for final closure. A nurse needs both a current nursing assignment and explicit `MEASUREMENTS` access; neither assignment nor administrator status supplies clinical authority or `CARE_PLAN` access.

Correction/invalidation retains the old source snapshot and reason, opens a new review cycle, and removes old completion qualifications. Source/reference review, return for information, cancellation and reopening remain explicit actions. Normal retesting does not automatically close work. Current authorization is checked again for commands, replay, evidence and notification dispatch. Timeout recovery reuses the original request rather than creating another measurement.

The existing Care Center, Care Journey, BP/glucose, Alerts, Doctor Workspace and Nurse Workspace contain permission-aware entries. Compiled-component tests use the real Vue state and handlers with synthetic APIs; they do not connect a real browser to a real API.

The assessment API currently lacks an authoritative comparison-unit field. The UI therefore withholds normalized comparison and threshold numbers. Original readings/units, classification, source-specific policy, context and distinct threshold names remain visible. No clinical threshold or conversion policy was changed.

Notifications distinguish queued, no channel, explicit failure, unknown, suppressed and channel acceptance. Unknown delivery never retries automatically; permitted manual retry acknowledges duplicate risk. Channel acceptance does not prove reading, accepted responsibility, handling, clinical review or health improvement. See the [notification contract](alert-workflow-notifications.md).

## Exact evidence identity

All six fresh gates ran on sealed source/test commit **`922eaacc4d04b0dcc275e423cf9cfc1bee0e7cc1`** (tree `0db246a6480c8cff1429fdbb7883d6018a6a40eb`), finishing 2026-10-14 01:07:44 UTC. No production or test source was edited after that seal. The separate later documentation-only receipt commit is identified by Git history; it does not assert its own future SHA.

| Measured gate | Result | Source |
| --- | --- | --- |
| Root backend full suite | 389 passed; 0 failures/errors/skips | `922eaac` |
| CN backend full suite | 389 passed; 0 failures/errors/skips | `922eaac` |
| Root Node full suite | 106 passed; 0 failures/skips | `922eaac` |
| CN Node full suite | 98 passed; 0 failures/skips | `922eaac` |
| Root/CN production builds | Both exit 0 | `922eaac` |

All **1,011** tracked runtime/test/pom files matched before and after the runs: 646 backend/SQL/pom and 365 frontend files. Surefire XML independently contains exactly 389 cases per edition with zero failed/error/skipped cases. Backend runs used the existing JDK17.0.20.1/Maven3.9.9 tooling and dependencies in offline mode; Java8-compatible source/target is not an actual Java8 runtime. Node24.19.0 and npm11.9.0 executed the frontend checks/builds without installation.

The prior backend365 receipts at `dde70db1455134e4d026fc014ddaa68ecbd5b0ab`, frontend99/91/builds at `ed956db69d511a32ce66fe67bc08c7368bcd5593`, and older backend364 receipts remain historical with their original counts, output sizes/digests and source identities. They are not relabeled. Production Java, executable SQL and frontend source **do change** in this fix; the historical unchanged-production comparison no longer describes current source.

The [verification record](verification/alert-workflow.json) records each actual gate, source identity, output digest/size and original historical comparison. Raw diagnostic logs are not distributed. Existing Vite large-chunk and npm environment warnings remain; both builds succeeded. Test totals are separate suite executions, not distinct clinical scenarios.

## Finite correction and integration fixes

The previous whole-branch review found correction-context, clinical-evidence freshness and notification-navigation defects. The independent scoped review of **`55620399282eea69d259ab04ee097c126c2a27bb`**, using tested source **`922eaacc4d04b0dcc275e423cf9cfc1bee0e7cc1`**, found those findings and four bounded quality items resolved, with no residual product blocker or fix-induced regression in scope. This is not a repeated original-base audit.

The earlier whole-branch FAIL and the review-pending status recorded at receipt seal `5562039` remain accurate history. The completed review issued SPEC PASS, QUALITY PASS with one nonblocking Minor/P3 item D1, and bounded source-publication readiness READY. D1 omitted the existing frontend receipt links from acceptance item 6; this later docs-only disposition adds `frontend-en` and `frontend-cn`. The original reviewer checks only that documentation change; no product edits or test/build reruns are needed. This closing commit is located through Git history, without a circular self-SHA.

- Journey omissions preserve native labels/notes. Journey metricName/remark and self-monitor sourceType/sourceExternalId/remark are deliberately read-only in versioned corrections: identical values/omissions are allowed, real changes/clearing rejected. This is a current editing limitation, made explicit in the form and [guide](alert-workflow-ui.md); no broad prose snapshot or raw-read expansion was added
- Corrected receipt sources now carry immutable, server-only action-version qualification independent of alert identity. First/later/new-alert attachment cannot reuse an earlier confirmation. Unknown metadata and invalidated sources remain blocked. Genuine later doctor confirmation, current authority and old plan state rules are preserved
- Actual notification locations include patient context. The authorized detail uses route-local context, preserves global selection, rejects mismatches/late responses, and offers retesting only when global and detail patients match
- Filtered lists project at most 100 candidates per request with fair continuation, including empty pages. Native occurrence timestamps are normalized once. Workflow times display the browser-local zone with an explicit offset; original naive measurement times are unchanged

## Approved-design §9 evidence map

1. **Sources/policy/deduplication: synthetic PASS.** `MeasurementWorkflowBridgeTest.bothNativeSourcesCompleteDistinctPersonalAndClinicalJourneys` executes four real-service/H2 cases: two source paths × personal/clinical mode. Native saves, actual commands and event-history assertions are connected within each case. `MeasurementAssessmentServiceTest` covers policy disagreement, patient targets, periods, equivalent units and unknown context. Separate same-valued readings survive; same-key retries do not duplicate sources
2. **Accountability and feedback: synthetic PASS.** `AlertWorkflowLifecycleTest` checks named acceptance, actual assisted identity, required handling/feedback, doctor return and closure authority. The two clinical walkthroughs use nurse handling/assisted feedback, reject owner/nurse closure and accept current-doctor closure. `alertWorkflowReviewRegression.test.mjs` executes the real compiled Vue detail/action seams for owner, family, nurse and doctor permissions
3. **Current authority: synthetic PASS.** `AlertWorkflowAuthorizationTest`, `AlertWorkflowNurseGovernanceTest` and `MeasurementTrackingGuardTest` cover foreign patients/modules, read-only writes, account/role/assignment/grant expiry and revocation, and measurement-only nursing with care plans off
4. **Replay/concurrency/rollback: synthetic PASS.** `WorkflowCommandExecutorTest`, `AlertWorkflowConcurrencyTest` and native bridge tests cover key/content conflict, competing commands, post-wait authorization and all-or-nothing writes. The new `reassignmentHoldingSourceLockWinsAgainstAlreadyAuthorizedStaleClosure` case observes an already-authorized CLOSE unable to return through the held source-lock boundary, then commits actual ASSIGN first: stale CLOSE gets 409, version advances once, nurse 4 is current, acceptance clears while handling/feedback history remains, and only one command/event plus the two correct recipient jobs commit; no orphan or stale closure survives. Real Vue state/handler fixtures cover duplicate clicks, uncertain results, context changes and late responses. They are not native-driver or live-HTTP timing tests
5. **Correction/dependencies: synthetic PASS.** `AlertWorkflowSourceCorrectionTest`, `AlertWorkflowPlanDependencyTest` and lifecycle/concurrency tests cover post-closure correction, lost/replaced/cancelled dependencies, actual receipt-versus-correction races, fresh doctor confirmation, difficulty, cancellation/reopening and escalation without invented assignees
6. **Notification outcomes: synthetic PASS.** Worker, lock, transport and safety-primitive tests cover fake-provider outcomes, restart recovery, revocation, token-bound retry, lease/claim races, due deduplication and no channel. No live provider or enabled scheduler was exercised
7. **Bilingual/browser/accessibility: NOT_RUN overall.** Root/CN Node checks, compiled SFC/SSR and static theme/mobile/focus contracts pass; both builds pass. Actual six-theme contrast, 390px/200% geometry, measured 44px controls, keyboard traversal/focus trap/return, sticky controls and navigation/unload behavior remain unaccepted
8. **Existing care-plan preservation: bounded checks PASS; full reacceptance NOT_RUN.** Retained command/notification regressions and real receipt/review/cancellation integration test the modified technical seams. They do not constitute a new complete publication/version/report/browser acceptance. The historical [collaboration](CARE_PLAN_COLLABORATION_RELEASE.md) and [report](CARE_EXECUTION_REPORT_RELEASE.md) releases retain separate evidence
9. **Database/restore: source/synthetic checks PASS; native acceptance NOT_RUN.** Schema, extracted guard/predicate, upgrade-source and edition-parity tests distinguish empty initialization from existing preflight/upgrade/postcheck. They do not execute those paths on MySQL 5.7.18 or establish actual inventory, trigger privileges, query plans or restoration

All named backend files are in [the current test tree](../src/test/java/org/familyhealthcare/service/alertworkflow/MeasurementWorkflowBridgeTest.java) and mirrored under `cn/src/test`; the manifest lists exact paths. Fresh full execution includes all retained checks and 24 new backend cases per edition for the finite findings; the prior competing-reassignment case remains part of the suite. Combining service walkthroughs and Vue/fake-API fixtures does not create a connected browser end-to-end result.

## Before any rollout

Use the [operator handoff](OFFLINE_DEPLOYMENT.md#1-back-up-and-prepare-mysql). Each edition's initialization source contains **86 CREATE TABLE statements and 22 owned `aw57_` guards**. These are source declarations, not a count of the user's database. Empty init and existing upgrade are separate, single-purpose paths. Stop on any partial/prior/unknown object, preflight error or unexpected result; never use `mysql --force`, overwrite unknown triggers, or weaken server security to pass a check.

The current production Compose file does not forward `ALERT_WORKFLOW_ENABLED`; adding it to `.env.production` alone cannot activate this feature. The handoff supplies a minimal user-applied, disabled-by-default environment override for a future authorized acceptance. No override was created or executed by this checkpoint.

**NOT_RUN:** native MySQL 5.7.18 parser/DDL/guards/locking/query plans, actual Java 8, real pooled-driver restoration, live HTTP role/authorization journeys, actual browser/accessibility checks, full database/attachment backup-restore, provider delivery, production migration/deployment/enablement, push and new remote CI.

Family archive format 1 excludes workflow history, feedback, evidence/snapshots, source tracking, commands, outbox, assignments and grants; it cannot restore collaboration history. Full recovery requires a separately authorized complete database and attachment backup/restore drill. Legacy raw-measurement aggregate/AI/timeline/export permission semantics were preserved, not comprehensively hardened or newly accepted by Phase A.
