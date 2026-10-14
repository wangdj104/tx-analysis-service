# Health Closed-Loop Phase A Implementation Plan

This plan separates staged Phase A implementation, independent requirements/quality verification, final source-publication review and later operator-controlled deployment. Checkboxes record the bounded implementation/evidence steps.

**Goal:** Deliver the approved measurement → abnormal follow-up → acceptance → handling → feedback → evidence-backed closure slice, including personal and clinical roles.

**Architecture:** Extend the existing alert aggregate and append-only events, link existing care plans, preserve the two measurement stores, and extract only narrow durable-command/notification mechanics. Keep authority and domain transitions specific to alerts. Feature defaults off, with bilingual existing-page entry points.

**Tech Stack:** Java 8-compatible source / JDK 17, Spring Boot 2.5, JdbcTemplate/MyBatis, MySQL 5.7.18, JUnit/Mockito/H2 synthetic tests, Vue 3/Vite and Node test runner.

**Spec:** `docs/superpowers/specs/2026-10-13-health-closed-loop-design.md`; equivalent Chinese text at `cn/docs/superpowers/specs/2026-10-13-health-closed-loop-design.md`.

**Implementation and release stages:** The written specification was approved on 2026-10-13 at 16:17 UTC. Implement and independently verify each bounded change, then review the complete branch before the source-publication checkpoint. Source publication is separate from deployment and activation. The first deliverable is the reviewed Phase A vertical slice; later B/C/D scenarios retain their own designs and acceptance gates.

## Dated implementation checkpoint

Approved design: 2026-10-13 16:17 UTC. The single consolidated fix after whole-branch review is sealed at `922eaacc4d04b0dcc275e423cf9cfc1bee0e7cc1`. Fresh complete root/CN backends pass389 each, Node106/98 and both builds pass on that same source; all1,011 runtime/test/pom files match before and after. Prior dde70db365 and ed956db99/91 receipts remain historical. Independent scoped fix re-review is pending; native MySQL5.7.18, actual Java8, browser/live HTTP, provider and full-restore acceptance remain NOT_RUN. B/C/D are subsequent work, not completed by this checkpoint. See [release evidence](../../ALERT_WORKFLOW_RELEASE.md) for exact identities and bounded editing/qualification limitations.

## Global Constraints

- MySQL 5.7.18 compatibility is mandatory. Do not depend on 8.0-only CHECK enforcement, SKIP LOCKED, window functions or collations.
- Independent ALERT_WORKFLOW_ENABLED=false gate; existing CARE_PLAN_ENABLED=false default stays unchanged.
- Keep health_measurement and bp_self_monitor_record separate. Source-specific current clinical thresholds remain unchanged; unknown required context is visibly unclassifiable, never silently Normal.
- Personal administrative closure is distinct from clinical review. Only a current assigned doctor closes clinical work; nurse assignment plus explicit MEASUREMENTS permission is required and never implies CARE_PLAN.
- Current enabled account, role, patient/module grant, expiry, assignment and source ownership are checked for every read, write, replay, evidence access and notification send. Administrator role alone grants no new clinical authority.
- Aggregate/event/command/outbox writes are one effective READ_COMMITTED transaction. commandKey is a UUID; expectedVersion is a nonnegative exact integer. Unknown keys and illegal transitions are rejected.
- No production database access/execution, MySQL upgrade, deployment, production feature enablement, real notifications/patient fixtures, credential/account-security changes or paid services.
- Root English and cn Chinese preserve protocol, permission keys and states. Preserve all six themes, 390px layouts, 200% text scaling and minimum 44px primary touch actions.
- Public commits contain product code, synthetic tests and product documentation only; exclude credentials, private health data and raw diagnostic outputs.
- PASS, FAIL, BLOCKED and NOT_RUN are distinct. H2/build success never substitutes for real MySQL/browser acceptance. Real-browser journeys and accessibility acceptance remain NOT_RUN until executed and verified.
- Source publication requires the separate reviewed checkpoint; it does not authorize production migration, deployment, feature activation or live delivery.

## Review Focus

1. Owner changes a source after clinical closure: reopen a new review cycle, preserve historical conclusion, require new handling/feedback (Tasks 3–4 tests).
2. A plan dependency is replaced/cancelled or source access disappears while closing: replacement still needs doctor confirmation; closure cannot use a stale dependency snapshot (Tasks 3–4 tests).
3. Existing source classifiers disagree and units/measurement-period labels vary: preserve source policy and target precedence, label provenance, reject unknown context (Task 2 tests).
4. A nurse uses measurement-only authorization with CARE_PLAN off, then loses assignment during a command replay: no implicit grant and no cached-authority replay leak (Tasks 2–4 tests).
5. A timeout, navigation or recipient revocation races a write/send: one durable result, no stale-patient UI, no UNKNOWN auto-retry or private external content (Tasks 1, 5–6 tests).

## Structure and execution evidence

Production Java, SQL structures and workflow contracts are mirrored under `cn/`, with localized display copy. The [public verification record](../../verification/alert-workflow.json) maps the approved acceptance items to exact source identities, measured test/build receipts and explicit unrun gates. Verify current source identity before reusing evidence; documentation publication is separate from the runtime under test. Native/browser checks require an authorized supported environment and cannot be inferred from synthetic tests or builds.

### Task 1: Reuse durable command and notification safety primitives

**Files:**
- Create `src/main/java/org/familyhealthcare/service/workflow/WorkflowCommandExecutor.java`, `WorkflowData.java`, `NotificationClaim.java`, `NotificationAttemptPolicy.java` and their small typed exception if needed
- Modify only relevant mechanics in `service/careplan/CarePlanCommandStore.java`, `CarePlanData.java`, `CarePlanNotificationWorker.java`
- Test `src/test/java/org/familyhealthcare/service/workflow/WorkflowCommandExecutorTest.java`, `NotificationSafetyPrimitivesTest.java`, plus surviving `CarePlanNotificationLockTest`

**Interfaces:**
- `WorkflowCommandExecutor.execute(JdbcTemplate jdbc, Domain domain, long actorId, String key, String payload, Long aggregateId, long expectedVersion, Instant now, Supplier<Map<String,Object>> change)` returns a persisted safe result
- Fixed internal domains only: CARE_PLAN (`care_plan_command`, `plan_id`, result key `planId`/legacy `id`), ALERT (`alert_command`, `alert_id`, result key `alertId`), MEASUREMENT (`measurement_command`, `source_id`, result key `sourceId`). SQL identifiers never come from client input
- `WorkflowData` exports existing canonical JSON/hash, UTC bind/read, cached-result evidence stripping, and effective-transaction guard; care-plan package-local APIs remain as delegates
- `NotificationClaim.acquire(JdbcTemplate)` exposes the safe claim clause and an explicit `restore()`; preserve exact MySQL 5.7 bounded wait/pooled-session abort behavior
- `NotificationAttemptPolicy.firstAttempt(String)` and `nextAttempt(String requestId, String outcome, boolean retryable, int attempts)` implement current UUID:millisecond cycle, three attempts, +60/+300 seconds from first attempt, no UNKNOWN retry

- [ ] Add failing isolated tests: equal command replay returns original result; same key changed payload conflicts; incomplete reservation cannot commit; foreign result aggregate rejected; a failed callback leaves no command; cached results contain no source title/link
- [ ] Run new focused tests and record the actual pre-implementation failure
- [ ] Extract mechanisms without changing care-plan authority, tables, event shapes, feature gate, or transport. Existing constructor/callers remain compatible. Map shared error status/code back to the existing CarePlanException contract
- [ ] Test MySQL 5.7 claim timeout restore/abort, UNKNOWN recovery decision, retry budget and effect of a failed authorization outside the executor; rerun surviving care-plan lock regression and both-edition Maven suites
- [ ] Self-review and commit with report containing exact commands, results and code range

### Task 2: Add bounded storage, current authority and source assessment

**Files:**
- Create `service/alertworkflow/AlertWorkflowProperties.java`, `AlertWorkflowAuthorizationService.java`, `AlertWorkflowContracts.java`, `AlertWorkflowRepository.java`, `MeasurementAssessmentService.java`, `AlertWorkflowException.java`
- Modify `application.yml`, both complete `sql/init.sql`; add `sql/alert_workflow_upgrade_20261013.sql`, `alert_workflow_preflight_20261013.sql`, `alert_workflow_postcheck_20261013.sql`
- Delegate existing assessment methods in `CareJourneyService.java` and `impl/MonitoringServiceImpl.java` to the extracted source-specific policy, without changing known-input results
- Test `service/alertworkflow/AlertWorkflowSchemaTest.java`, `AlertWorkflowAuthorizationTest.java`, `MeasurementAssessmentServiceTest.java`

**Interfaces:**
- Properties expose `isEnabled()`, `requireEnabled()`, `now()` and injected Clock for tests
- Authorization exposes `requireRead(actor,patient)`, `requireRecord(actor,patient)`, `requireOwner(actor,patient)`, `requireClinical(actor,patient)`, `requireNursing(actor,patient)`, `canReadEvidence(actor,patient,type,id)`, `eligibleAssignees(actor,patient,mode)` and server-derived `actorSnapshot(actor,patient)`
- Read/record use MEASUREMENTS. Current enabled assigned doctors have their existing doctor authority; family needs explicit active MEASUREMENTS grant; nurses additionally require active nursing assignment. No request-role/admin shortcuts. CARE_PLAN and MEDICAL references are independently checked
- `assessJourney(patientId,metric,primary,secondary,unit)` and `assessSelfMonitor(BpSelfMonitorRecord,PatientHealthTarget)` return metric assessments with policyKey, status NORMAL/ABNORMAL/UNCLASSIFIABLE, severity, normalized comparison value, source unit, period and applied thresholds. Source values remain unmodified
- Repository owns fixed SQL for aggregate/event/source/evidence/command tables and returns camelCase maps to the service. `lockAlert(id)` and `lockSource(type,id)` are lock-bearing lookups; entity existence is never returned to unauthorized clients

**Storage contract:**
- Add alert_record metadata: workflow_version default0, workflow_state, workflow_mode PERSONAL/CLINICAL, cycle_no, source_revision, responsible_user_id, due_at, accepted_by/at, lock_version, current_handling_event_id, current_feedback_event_id, source_review_required and current classification snapshot
- Extend alert_event with event_type, cycle_no, actor_role/relation, entry_mode, occurred_at, recorded_at and payload_json; expand note capacity to2000 without truncating existing rows. Legacy status columns remain a server-owned projection only
- Source identity/revision storage preserves the two stores and immutable snapshots; unique source-type/id/revision/metric link maps to one alert. A new same-valued reading is not the same identity
- `alert_evidence` stores event/source identity and revision/dependency state, not broad copied clinical content. `alert_command`/`measurement_command` follow Task1's fixed column contract. `alert_notification` mirrors durable delivery mechanics with its own alert_event FK and unique dispatch_key, never weakened care-plan FKs
- Validity checks cannot rely on MySQL 5.7 CHECK. New FK/index names are bounded, deterministic and collision-checked. Preflight must stop on conflicting existing objects/data; upgrade never replaces unknown triggers/routines or mutates current production

- [ ] Add failing tests for root/CN schema parity, unique source identity and command/event relationships; flag-off configuration creates no workflow side effect
- [ ] Add authority tests for owner/family READ versus WRITE/PROXY, nurse measurement-only, disabled/deleted roles/accounts, expired/revoked grant/assignment, doctor unassignment, cross-patient source and admin-only denial
- [ ] Add exact policy cases: generic glucose8.0 retains current generic result while fasting self-monitor8.0 is abnormal; targets override supported self-monitor thresholds; equivalent mg/dL/mmol/L; postmeal EN/CN mapping; missing/unknown required period or unit is unclassifiable
- [ ] Run red tests, implement schema/repository/auth/classifier contracts and verify known existing classifiers are unchanged for known inputs
- [ ] Run focused plus both-edition surviving suites; inspect fresh-init versus upgrade independently and mark native MySQL not-run if unavailable; commit/report

### Task 3: Implement authorized lifecycle, evidence and query API

**Files:**
- Create `service/alertworkflow/AlertWorkflowService.java`, `AlertWorkflowQueryService.java`, `AlertWorkflowEventStore.java`, `AlertWorkflowNotificationQueue.java`, `AlertWorkflowEvidenceService.java`
- Create `controller/AlertWorkflowController.java`, `AlertWorkflowExceptionAdvice.java`
- Test `service/alertworkflow/AlertWorkflowLifecycleTest.java`, `AlertWorkflowConcurrencyTest.java`, `controller/AlertWorkflowControllerTest.java`

**Interfaces:**
- `AlertWorkflowService.command(actorId,alertId,operation,body,commandKey,expectedVersion)` returns `{alertId,version,state,mode,cycleNo,eventId}`
- Operations: ASSIGN, ACCEPT, REJECT_ASSIGNMENT, ESCALATE, FOLLOW_UP, SUBMIT, FEEDBACK, RETURN, REVIEW_SOURCE_CHANGE, RESOLVE_REFERENCE_CHANGE, CLOSE, CANCEL, REOPEN; shared strict envelope `{commandKey,expectedVersion,body}`
- `AlertWorkflowService.adopt(actorId,sourceType,sourceId,metric,body,commandKey)` explicitly adopts a permitted legacy source and returns the same result; normal/unclassifiable sources can only create an explicitly labeled manual follow-up
- Query `detail(actor,alertId)`, `list(actor,patientId,queue,cursor,limit)` and `events(actor,alertId,cursor,limit)` return current permissions, minimal patient identity and sanitized evidence. Limit1–100; cursor bound to actor/patient/queue
- API root `/alert-workflows`: GET capabilities, list, `/{id}`, `/{id}/events`, `/assignees`; POST `/adopt` and `/{id}/{action}`. Fixed action names use kebab-case URLs, mapped to the above operations; reject arbitrary status patches and unknown body/query keys
- Durable enqueue boundary `AlertWorkflowNotificationQueue.enqueue(eventId)` persists jobs in the same transaction, using only current authorized owner/assignee/relevant clinician recipients. Worker delivery belongs to Task5

- [ ] Add failing complete personal/clinical state-chain tests, including all table transitions in spec§5, reason/text/evidence bounds, acceptance prerequisite/deadline ownership and forbidden clinician/nurse/assisted-identity claims
- [ ] Add mode-escalation test: owner request invalidates personal acceptance/closure, no doctor keeps OPEN routing blocker, clinical mode cannot downgrade
- [ ] Implement commands under fixed lock order and effective READ_COMMITTED, Task1 executor, versioned aggregate/event/outbox atomicity and post-wait/replay authorization recheck
- [ ] Implement source/evidence capabilities: PERSONAL nonclinical references owner-resolved; clinical/plan dependencies doctor-only; replacement remains blocking until confirmed; current-cycle evidence/feedback required and legacy notes do not satisfy it
- [ ] Test two competing accepts/closes, rollback orphan prevention, revoked replay, rejected source visibility and no-store/nonexistence-safe API contracts. No outbound transport in tests
- [ ] Run focused and full both-edition tests, self-review, commit/report

### Task 4: Connect measurement writes, correction review and nurse governance

**Files:**
- Create `service/alertworkflow/MeasurementWorkflowBridge.java`, `AlertWorkflowNurseGovernance.java`
- Modify `CareJourneyService.java`, `impl/BpSelfMonitorRecordServiceImpl.java`, their controllers, `AlertServiceImpl.java`, `AlertController.java`, and current grant/candidate/nurse-governance code only at named boundaries
- Modify `service/careplan/CarePlanService.java` and `CarePlanActionService.java` only to integrate dependency locking/change notification while preserving original gates
- Test `MeasurementWorkflowBridgeTest.java`, `AlertWorkflowSourceCorrectionTest.java`, `AlertWorkflowNurseGovernanceTest.java`, `AlertWorkflowPlanDependencyTest.java`

**Interfaces:**
- Bridge creates stable source revisions and metric links, invokes Task3 creation/event logic, and wraps save with Task1 MEASUREMENT command identity. Persisted result `{sourceType,sourceId,sourceRevision,followUps:[{alertId,state,version}]}` supports normal readings with no alert
- When feature enabled, existing measurement save/update UI calls send `commandKey` (UUID) and corrections also `expectedVersion`/`reason`; controllers delegate the enabled branch, and the disabled branch preserves existing behavior. Missing required workflow command returns a refresh-client validation error, never an untracked enabled write
- Corrections/invalidation require valid source write authority, preserve prior snapshot, and open a new cycle for every linked follow-up including previously terminal cases. `SOURCE_CHANGED` clears only through Task3 explicit review; new handling/feedback required
- `AlertWorkflowNurseGovernance` reuses the existing care_nurse_assignment table/account/assignment checks. New `/alert-workflows/nurse-assignments` governance is feature-gated, admin-write/owner-or-assigned-doctor-read; patient owner grants MEASUREMENTS-only through the existing grant service without implicitly adding CARE_PLAN
- Linked-plan lock order is plan aggregates sorted by ID → source identities sorted by type/id → alert aggregates sorted by ID → command. Detect changed link sets and conflict rather than locking an unplanned parent later

- [ ] Add failing end-to-end service tests for both saves producing one source+alert per retry, combined BP/BG per-metric links, no false deduplication of separate readings and no alert for ordinary normal readings
- [ ] Add source-correction tests including a post-CLOSED correction, source invalidation, unauthorized author replay, terminal history retained, and unchanged handling/feedback never sufficient after reopen
- [ ] Wire enabled save paths without external network in the transaction, source creation and outbox atomic; protect new records against legacy arbitrary update/delete/admin-list bypass
- [ ] Implement measurement-only nurse candidate/grant path with careplans off; existing CARE_PLAN grant/publication gates unchanged. Assignment does not itself grant access
- [ ] Integrate dependency changes/reads with version/locking so concurrent plan supersession/cancellation/return or source correction cannot race alert close. Replacement waits for confirmation; explicit reasoned termination is audited
- [ ] Run focused/full both editions; report a concrete synthetic backend vertical walkthrough with IDs/steps and no real patient data; commit/report

### Task 5: Deliver truthful durable notification outcomes

**Files:**
- Create `service/alertworkflow/AlertWorkflowNotificationWorker.java`, `task/AlertWorkflowNotificationTask.java`
- Modify narrow existing NotificationDeliveryService transport adapter exposure if required; do not configure channels or send test notifications
- Extend workflow API with GET `/{id}/delivery-status`, POST `/notifications/{id}/retry` requiring current admin governance and explicit duplicate-risk flag for UNKNOWN
- Test `AlertWorkflowNotificationWorkerTest.java` and existing shared/care-plan notification tests

**Interfaces:**
- Worker `tick(Instant now)` runs outside business transactions, using Task1 claim/attempt policies and Task3 durable queue
- Domain-specific current recipient validation runs at enqueue, claim/preparation, immediately before transport and manual retry. Transport reuses existing provider mechanism with generic title/path and result enums
- Fixed300-second claim lease; unattempted expired claims requeue; attempted uncertain claims become UNKNOWN; three total attempts, first-cycle +60/+300 seconds. Due key includes alert/cycle/deadline identity, so one due event per current target

- [ ] Add failing synthetic/fake-transport tests for no channel, explicit retryable/nonretryable failure, UNKNOWN, crash before/after attempt-start, expired lease and exact retry budget
- [ ] Verify multi-instance claiming/pooled-session restore and permission revocation before transport suppresses send; no private source values/names/notes in outbound text
- [ ] Implement worker, due enqueue and current-authority status projection/manual retry; delivery never changes workflow acceptance/closure
- [ ] Run focused/shared/old-plan tests and full both editions, self-review, commit/report

### Task 6: Complete bilingual role-aware patient-facing workflow

**Files:**
- Create `frontend/src/api/alertWorkflow.js`, `composables/useAlertWorkflow.js`, `components/alert-workflow/FollowUpList.vue`, `FollowUpDetail.vue`, `FollowUpActionDialog.vue`, `views/AlertWorkflowDetailRoute.vue`
- Modify existing `CareJourneyManager.vue`, `BpSelfMonitorManager.vue`, `AlertManager.vue`, `CareCenter.vue`, `DoctorWorkspace.vue`, `NurseWorkspace.vue`, route/menu wiring and grant controls at narrow integration points
- Test `frontend/tests/alertWorkflowState.test.mjs`, `alertWorkflowIntegration.test.mjs`, `alertWorkflowPresentation.test.mjs`; add browser acceptance fixture/harness only if supported

**Interfaces:**
- API exports capabilities/list/detail/events/assignees/adopt/command/source-save/source-correct and deliveryStatus/retry with captured auth session and AbortSignal, using Tasks3–5 paths
- `useAlertWorkflow(patientRef)` owns list/detail/dialog epochs, current auth/patient, retained original command/input on timeout and explicit command phases idle/submitting/uncertain/succeeded/conflict/denied
- Detail renders server capabilities, distinct mode/state/blockers/nextStep/source policy+revision/evidence/feedback/delivery/history; it never derives final permissions from role text
- Existing save forms generate commandKey once per attempt and preserve it until known result, include source version/reason for correction, and deep-link to returned follow-ups. Feature-off UI falls back to current source workflow

- [ ] Add failing Node tests for double-click, uncertain replay, changed-input/new-key, 409 input retention, patient/session changes and old-response suppression including A→B→A/dialog-reopen/history
- [ ] Implement same components integrated in current role workspaces, with owner personal closure, doctor clinical closure, nurse follow-up-only controls and explicit missing-assignee/permission states
- [ ] Add localized EN/CN copy for every system state/action/error; do not translate clinical prose/names or duplicate hidden stale patient data. Privacy/provenance and assisted identity are visible
- [ ] Verify six theme tokens,390px/200% text,44px primary actions, sticky dialog bottom actions, keyboard/focus and long-content layout through supported checks; preserve previous mobile-control fixes
- [ ] Run both frontend Node suites and production builds plus applicable backend suite; execute browser flows only if a supported route is actually available, otherwise mark not-run with exact blocker; self-review/commit/report

### Task 7: Validate cross-role contracts and prepare publication checkpoint

**Files:**
- Add focused cross-role regression fixtures/tests under existing test trees, no resurrection of removed broad historical suites
- Update `docs/USER_GUIDE.md`, `docs/OFFLINE_DEPLOYMENT.md`, `README.md` and mirrored docs where relevant
- Add `docs/ALERT_WORKFLOW_RELEASE.md`, `docs/verification/alert-workflow.json`; only measured evidence, explicit runtime commit identity

**Interfaces:**
- Release matrix maps spec§9 to actual check command/log/result and responsible unrun gate
- Existing DB upgrade and empty initialization remain separate operator instructions; no actual production execution or deployment

- [x] Bind complete backend/frontend receipts to their actual tested commits; recheck current source identity and map existing synthetic lifecycle/gate/authority/correction/race/notification checks without duplicating tests
- [x] Inspect root/CN source parity, SQL5.7 guard declarations and archive coverage; distinguish source/synthetic checks from real MySQL/browser/full-restore acceptance, which remains NOT_RUN
- [x] Prepare the small Chinese dated handoff summary showing implemented paths, measured evidence and remaining gates; no fixture dump or fabricated screenshots
- [ ] Obtain one whole-branch independent review of base6eb57cb..candidate, including documented deferred findings and design decisions; use one bounded final fix wave and scoped re-review
- [x] Verify documentation and source identity, preserve executable SQL while correcting two table-count comments, add the missing reassignment race and freshly verify both backend suites, record exact tested runtime commits and prepare the local review checkpoint without pushing; final rollout approval remains pending

## Self-review and scope closure

Spec§1–2 map to Tasks1–4; sources/classifiers§3 to Tasks2/4; authority§4 to Tasks2/4/6; state/evidence§5 to Tasks3/4; persistence/outbox§6 to Tasks1–5; UI/API§7 to Tasks3/6; DB/delivery§8 to Tasks2/7; acceptance§9 to all tasks and Task 7. §10's B/C/D domain expansions remain finite subsequent phase work, not silently represented as implemented by Phase A. No named Phase A requirement is intentionally omitted.

Release review must check cross-task authority, source/evidence, command and notification contracts against the approved design. The release matrix preserves measured evidence, unresolved acceptance gates and the distinction between Phase A implementation and later roadmap work.
