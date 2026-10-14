# Complete Existing Health Workflows: Phase A Design and Finite Acceptance Roadmap

- Date: 2026-10-13
- Original drafting status: awaiting user review, design only. The written scope was approved 2026-10-13 16:17 UTC; this original contract remains the implementation authority
- Dated implementation checkpoint: the finite whole-branch findings have one consolidated fix at `922eaacc4d04b0dcc275e423cf9cfc1bee0e7cc1`; fresh backend389 per edition, Node106/98 and both builds pass on that exact source. Independent scoped re-review is pending. Native/runtime/browser/full-restore gates remain NOT_RUN; B/C/D remain later work. See the [release checkpoint](../../ALERT_WORKFLOW_RELEASE.md) for current restrictions and historical receipt provenance.
- Baseline: `6eb57cbf4f815370d16b6278b9500018324b12f8`, remote main rechecked at 15:54 UTC on 2026-10-13
- Goal: give existing patient, family, doctor and nursing scenarios an explicit next step, accountable person, feedback and traceable outcome
- The approved scope is implemented through the [Phase A plan](../plans/2026-10-13-health-closed-loop-phase-a.md); phases remain independently implementable and verifiable
- [Chinese edition](../../../cn/docs/superpowers/specs/2026-10-13-health-closed-loop-design.md)

## 1. Meaning of completion

A closed loop must explain its source, accountable person, acceptance, handling, required feedback, closure evidence and the next step when unfinished. Another generic Done button is insufficient.

The scope is the existing 16 scenario families. Splitting emergency assistance and exit/export/restore from family 16 creates 17 acceptance items, not an open-ended product expansion. Phase A delivers measurement → abnormal follow-up → explicit acceptance → retest/handling record → patient feedback → evidence-backed closure. Section 10 bounds the remaining work.

Closure means this follow-up record was processed under the rules. Delivery, reading, acceptance, execution, clinician review and health improvement are different facts in UI and reporting.

## 2. Selected architecture

Extend `alert_record` / `alert_event` for abnormal-reading follow-up, preserving original measurement links. When clinical instructions or multiple clinical actions are needed, link the existing collaborative care plan and retain its published versions, receipts, doctor review and reports.

Keep `health_measurement` and `bp_self_monitor_record` separate. Identify work by source type + source ID + source revision + metric; do not merge tables or migrate existing measurements.

Extract only the smallest reusable technical components from existing care-plan code for idempotency, UTC storage, transaction checks, durable notification claiming and uncertain-result handling. Existing plans keep dedicated adapters. Share technical mechanics, not clinical authority. Personal work must not masquerade as a doctor-published plan. Do not copy a second generic workflow engine. Preserve existing care-plan tables, states and API compatibility, with before/after behavior-preservation regression coverage.

Alternatives rejected: adding only a generic completion button cannot guarantee acceptance/evidence/authority; building a universal task platform duplicates medication, appointment and plan behavior and expands migration/security risk.

## 3. Phase A sources and creation

1. Reuse current rules, patient targets and unit conversions. Do not add diagnosis rules or treatment suggestions. Show the rule origin and assessment snapshot; crossing a configured range is not a diagnosis
2. Standardize the follow-up protocol, not clinical thresholds: journey readings retain CareJourneyService assessment; self-monitoring retains MonitoringServiceImpl BP/glucose severity, patient targets and measurement-period policy. Their existing glucose upper limits differ: without personal targets self-monitor fasting/post-meal references are 6.1/7.8 mmol/L, while the generic journey policy uses 11.1 mmol/L. Do not silently replace either policy. The adapter unifies outcome/provenance only; old trend lines remain labeled references, never frontend constants posing as authoritative follow-up state. Unrecognized units/required measurement periods display Unable to classify, confirm context, allow explicit manual follow-up, and never silently mean Normal. Combined BP/glucose records create separate metric follow-ups; one source revision/metric creates at most one. Normal metrics create none
3. Measurement save, source link, follow-up, creation event and outbox enqueue are one transaction. External delivery is outside it; delivery timeout must not cause another measurement save
4. New write commands carry a UUID commandKey. Same actor/key/content returns the original result; changed content with the same key conflicts. The server verifies actor and source ownership
5. Do not automatically convert old readings/alerts into accepted or completed work. Authorized explicit adoption remains deduplicated by source
6. New records use workflowVersion=1; legacy records retain their meaning. Legacy update/delete routes must not bypass new transitions, and legacy listings must not expose new records through old administrator shortcuts
7. Correcting a tracked self-monitor record requires a reason and preserves the prior snapshot. A referenced source cannot be hard-deleted: use reasoned invalidation. Source correction marks existing evidence as changed/pending review, without removing history or auto-closing
8. A corrected referenced source keeps the follow-up identity and adds a new source-revision link without overwriting evidence. A newly entered reading is an independent fact; identical values alone never deduplicate a legitimate retest

## 4. Accountability and closure authority

### Explicit modes

- Personal follow-up is the default. The record owner confirms the outcome; an authorized family recorder can help. Closure says handling was recorded, without implying recovery or clinician review
- Clinical review begins when the owner requests it or a currently assigned doctor explicitly takes over and escalates. It cannot be downgraded to bypass clinician review. If no doctor is available, retain a visible awaiting-clinician routing state

### Roles

| Identity | Allowed | Excluded |
| --- | --- | --- |
| Record owner | Read, select an authorized assignee, accept own work, record handling/feedback, close eligible personal work, request clinical review | Closing clinical-review work on behalf of a doctor |
| Authorized family/proxy | Accept assigned work and assist with readings/handling/feedback under MEASUREMENTS WRITE/PROXY, with recorder identity | Gaining permission from assignment; impersonating patient feedback; final clinical closure |
| Currently assigned doctor | Accept clinical work, document advice, return for information, link/publish existing plans, perform final review/closure | Treating delivery as execution; silently supplying the patient's feedback |
| Nurse | Current nursing assignment plus explicit MEASUREMENTS grant; WRITE/PROXY to accept, follow up and assist | Clinical plan publication, changed instructions, final clinical review/closure |
| Administrator only | Assignment governance and nonclinical configuration | New-workflow clinical-content access or clinical actions solely by admin role |

Extract only module-independent active-account/assignment checks from existing nurse governance. Either CARE_PLAN or ALERT_WORKFLOW enablement permits its corresponding assignment-governance entry point; only an administrator assigns, and assignment never creates a grant. The new follow-up entry permits an owner to explicitly grant MEASUREMENTS-only access to an already assigned active nurse, without adding CARE_PLAN. Existing CARE_PLAN grants/content/clinical actions remain separately gated.

An owner declaring self-entry is self-report, not proof that the account and patient are the same natural person. Entries for another member and family/nurse entries display assisted/proxy identity and actual recorder.

Linked plans independently require current CARE_PLAN access; reports require MEDICAL access. A module grant never exposes another module's titles, values, prose, download URLs or history snapshots. Minors retain existing lawful owner/guardian relationships; no new family-wide visibility.

Every read, command, replay, source read and delivery checks current enabled account/role, patient, module grant, expiry and assignment. Authorship is not permanent access. A revoked assignee cannot act or receive notification; display reassignment required and leave a bounded path for an authorized owner/doctor, without silently granting someone else access.

## 5. States and actions

States: OPEN, ACCEPTED, IN_PROGRESS, WAITING_FEEDBACK, READY_FOR_REVIEW, CLOSED and CANCELLED. Unassigned, revoked-assignee, overdue and awaiting-clinician are explicit blockers, never substitutes for acceptance.

The deadline is a follow-up response target, set by the owner in personal mode or a current assigned doctor in clinical mode as an ISO 8601 offset timestamp. Changes require a reason/event; assignees confirm rather than gain deadline-edit authority. This cannot change a linked care-plan clinical deadline, which still requires plan revision. Missing deadlines display Not set rather than an invented clinical deadline. Acceptance requires confirming assignee and deadline.

| Action | Prior state | Required result |
| --- | --- | --- |
| Request/escalate clinical review | Any nonterminal state | Explicit owner/current-assigned-doctor action with reason; fix clinical mode, return OPEN, clear current acceptance and personal closure eligibility; retain old feedback as history; select only an explicitly chosen currently authorized doctor/nurse, otherwise show awaiting-clinician |
| Assign/reassign | Any nonterminal state | Verify new assignee, require reason, clear prior acceptance, return to OPEN; retain evidence/feedback pending new assignee review |
| Accept | OPEN | Only the named assignee; confirm deadline, record actor/time, enter ACCEPTED |
| Reject assignment | OPEN | Named assignee supplies reason; remain OPEN with reassignment required; owner queue remains visible |
| Follow up | ACCEPTED/IN_PROGRESS | Responsible person supplies note and actual time; enter/remain IN_PROGRESS |
| Submit handling | ACCEPTED/IN_PROGRESS | Handling note plus at least one checkable outcome; enter WAITING_FEEDBACK |
| Patient/proxy feedback | WAITING_FEEDBACK | Feedback/time and explicit self/assisted identity; enter READY_FOR_REVIEW, or return to IN_PROGRESS with reason if difficulty remains |
| Request information | READY_FOR_REVIEW | Owner for personal work, doctor for clinical work; reason required; return to IN_PROGRESS or WAITING_FEEDBACK according to what is missing |
| Close | READY_FOR_REVIEW | Owner for personal, current assigned doctor for clinical; reasoned closure after checking evidence/feedback; enter CLOSED |
| Cancel/invalidate mistaken entry | Any nonterminal state | Owner for personal, current assigned doctor for clinical; reason required, retained history; never count as completion |
| Reopen | CLOSED/CANCELLED | Current owner for personal or current assigned doctor for clinical, not necessarily the original closer; new reason; enter OPEN and clear current-cycle acceptance/review qualifications, retaining history and clinical mode |

### Resolving source/dependency changes

- A correction/invalidation of a referenced source in any state opens a new review cycle: current state becomes OPEN with SOURCE_CHANGED, clearing current-cycle acceptance/handling/feedback/review qualification. Preserve prior closed/cancelled cycles and conclusions as history, never as completion evidence for the new cycle. Normal corrected values do not auto-clear this review
- Explicit reviewSourceChange is performed by the current owner for personal or current assigned doctor for clinical work, with source-read authority. Record the review reason, select current source revision and replace/withdraw affected evidence before clearing SOURCE_CHANGED. This does not substitute for fresh acceptance, handling and feedback
- New-cycle handling and feedback must be submitted. Old material may be explicitly referenced, but old events alone cannot satisfy new-cycle completion. Revoked assignees retain reassignment-required blocking state
- SUPERSEDED/CANCELLED linked plan actions, invalidated evidence or lost source access create REFERENCE_CHANGED and block closure. For personal nonclinical evidence, the current owner with source-read authority may use resolveReferenceChange to explicitly replace or reasonedly withdraw the reference. Clinical-mode evidence and any clinical-plan dependency require a current assigned doctor; personal mode cannot clear a clinical requirement. A replacement plan action remains a mandatory dependency until that plan's doctor confirms it; only explicit reasoned termination can remove the dependency. Insufficient access requires legitimate restored access or another currently authorized reviewer with the appropriate authority, never silent removal
- These commands also require commandKey/expectedVersion, event history, current authority and fixed lock order. Alert closure must check current dependency versions when racing source correction or plan revision/cancellation; stale evidence cannot win the race

Evidence may be a linked retest, authorized record/plan receipt, or a checkable contact/handling entry with time, relationship and outcome. Do not require retesting every abnormality or force fabricated clinical evidence. A reported phone call is labeled self-reported, not certified institutional evidence.

Notes/feedback: 1–2000 characters; reasons: 1–1000; at most five existing-record references. Reject future occurrence times, cross-patient sources and revoked evidence access. Retain occurrence and server-recorded times separately. No new upload entry point.

Closure requires current-cycle acceptance, valid current handling evidence, patient or explicitly assisted feedback, valid closure authority and no unhandled correction/information request. Linked care-plan actions must be doctor-confirmed; replacement actions also require doctor confirmation. Only an explicit reasoned termination through the dependency-change command removes a dependency; invalid actions are never silently ignored. Normal retest values never auto-close. Missing feedback, no response and lost access never become completion.

## 6. Persistence, concurrency and notifications

- Extend the alert aggregate with source revision, mode, state, assignee, acceptance, deadline, version and closure metadata; extend append-only events for actions/feedback. Evidence is linked rather than broadly copied
- Supporting storage is limited to alert-source revisions/deduplication, evidence, command results and outbox. Do not weaken care-plan-specific foreign keys into arbitrary IDs or insert personal work into doctor_care_plan
- Commands require expectedVersion, effective same-datasource READ_COMMITTED transactions and a fixed lock order. Aggregate/event/command/outbox commit together; recheck authority after lock waits and before replay returns
- Competing acceptance/reassignment/closure has one winner; the loser gets 409 and retains UI input. Correction competing with closure must not yield a closure based on invalid evidence
- New times are UTC DATETIME(6), offset-aware input and explicit local display. Preserve legacy timestamp interpretation
- Durable in-app work is primary. Reuse existing transport and conservative outcomes: QUEUED, DELIVERED, NO_CHANNEL, FAILED, UNKNOWN, SUPPRESSED. DELIVERED means channel acceptance only
- Clearly retryable failures get at most three total attempts, approximately one and five minutes after the first. UNKNOWN never retries automatically; manual retry acknowledges duplicate risk and rechecks authorization
- Creation/assignment, rejection, handling awaiting feedback, feedback awaiting review, information request, closure/cancellation and one due event may notify. Unique event/recipient/channel identity prevents scheduler duplicates
- External messages contain generic copy and login-protected internal location only, without patient name, value, diagnosis, feedback prose or prescription; do not broadcast to every family member. Missing channels and no acceptance remain visible
- Independent ALERT_WORKFLOW_ENABLED=false gate; existing CARE_PLAN_ENABLED=false default stays unchanged. Disabling the new gate preserves history and measurement functionality without disabling other modules

## 7. UI and API boundaries

Add the same permission-aware follow-up entry/detail to existing measurements, self-monitoring, alerts, family care, doctor workspace and nursing queue; no second workbench. A saved reading can link directly to its follow-up. Today views distinguish waiting acceptance, assigned to me, waiting feedback/review and blocked work.

Detail shows source/revision, mode, assignee, deadline, next step, evidence, feedback, delivery outcomes and event history. Backend capabilities guide buttons; every action still receives independent server authorization.

A narrow follow-up API uses assign/accept/escalate/submit/feedback/return/reviewSourceChange/resolveReferenceChange/close/cancel/reopen commands, never arbitrary status PATCH. Reject unknown fields, forged actors, foreign sources and illegal transitions. Use indistinguishable absent/forbidden results, version/allowed-actions responses and no-store caching.

Root English and cn Chinese preserve protocol, permission keys and states. All six themes, 390px layouts, long EN/CN and 200% text scaling must work without whole-page horizontal overflow. Primary touch actions are at least 44px; dialog bottom actions and keyboard focus remain usable.

Patient switching, sign-out, A→B→A, dialog reopening, browser history and late responses must not reintroduce previous-patient data. Double clicks send once. A timeout retains command/input for lookup or identical retry; dismissing a dialog never claims to cancel a server write.

## 8. Database and delivery boundaries

MySQL 5.7.18 compatibility is mandatory. Enforce new invariants in services and necessary 5.7-compatible database mechanisms; do not depend on 8.0-only CHECK enforcement, SKIP LOCKED, window functions or collations.

Update root/CN complete init.sql for empty databases. Existing databases use a separate versioned additive upgrade with preflight/postcheck. Inspect schema/conflicts/duplicates first, preserve unknown native objects, and never use fresh initialization as an upgrade. DDL is non-atomic: document completed steps and recovery after failure.

No production database access/execution, MySQL upgrade, deployment, production feature enablement, real notifications/patient fixtures, credential/account-security changes or paid services. Public commits contain product code, synthetic tests and product documentation only.

## 9. Phase A acceptance and evidence

1. Both measurement entry paths complete personal and clinical synthetic journeys, including existing-policy disagreements, patient-target precedence, period mapping and unit-equivalent readings; identical retries create one record/follow-up, legitimate retests survive deduplication
2. Family assistance, nurse acceptance/follow-up and doctor return/review display actual identity; missing doctor/feedback/acceptance never falsely completes
3. Deny foreign patient/module, READ-only writes, revoked/expired grants, ended doctor assignment, disabled nurse role/assignment and admin-only clinical actions
4. Cover duplicate clicks, timeout replay, key/content conflict, racing accept/close/correct/reassign and transaction rollback without orphan events/outbox
5. Corrections including post-closure edits, superseded/cancelled plan actions, lost reference access, continuing difficulty, cancellation/mistake and reopening have explicit history-preserving paths; dependency-change/review versus closure races cannot skip evidence. Clinical escalation immediately removes personal closure eligibility without inventing an assignee
6. Distinguish no channel, explicit failure, UNKNOWN, restart recovery, revocation and concurrent worker claiming without blind duplicate delivery
7. Execute root/CN, six-theme, mobile, 200% text, action/focus and sensitive-context interaction acceptance
8. Preserve existing plan publishing/version/receipt/review/report/notification behavior through technical extraction; do not change old clinical authority/closure
9. Verify empty init and upgrade separately; mark real MySQL 5.7.18 requirements separately from simulated database tests. Full backup/restore remains an operator-authorized environment activity

Recent main removed most older suites/runners; some focused regressions remain. Historical test totals are not current evidence. The plan will identify minimal new/retained synthetic checks. Every result is PASS, FAIL, BLOCKED or NOT_RUN. Build success is not browser, real-database or clinical-effectiveness acceptance. Real-browser acceptance remains NOT_RUN until the required journeys and accessibility checks are completed.

Phase A publication checkpoint: after local implementation and independent spec/quality review, report publication readiness and unrun boundaries before coordinated shared-main push. No deployment.

## 10. Finite remaining roadmap

| Item | Scenario | Phase and observable ending |
| --- | --- | --- |
| 1 | Registration, invitation, authorization | A/D: accepted invitation or reasoned termination; immediate revocation/expiry; reassignment of unfinished work |
| 2 | Measurements/corrections | A: source/revision/assistance traceable; evidence retained; legitimate retests preserved |
| 3 | Abnormal readings/retests | A: this design's acceptance, handling, feedback and legal closure |
| 4 | Prescription/medication | B: human reconciles drug, dose, unit, time and effective boundary before explicitly replacing reminders; taken/skipped/deferred receipts and omission handling; no automatic dose interpretation |
| 5 | Stock/replenishment | B: actual replenishment and consumption reconciliation close low-stock work; no double deduction or automatic purchasing |
| 6 | Family symptoms, questions, accompaniment, handovers | B: type-specific acceptance, response/handling and required originator confirmation; rejection/handoff/no-response exits |
| 7 | Appointments/rescheduling/no-show/follow-up | C: attendance/no-show recorded; old reminders cancelled; next appointment neither duplicated nor silently lost |
| 8 | Reports/OCR/review | C: original-source review, reasoned returns, invalidated old review after content change, resubmission; OCR is not confirmed data |
| 9 | Post-visit summary/tests/referral | C: explicit no-follow-up or human-confirmed next action with owner/deadline/submitted result/professional feedback |
| 10 | Remote consultation | C: discussion ending/archive distinct from resolution; no response/disconnection outcomes and separately owned next actions |
| 11 | Care plans/nursing/reports | A/C/D: reuse/regress existing version/receipt/return/review/closure/report consistency |
| 12 | Rehabilitation/nutrition/lifestyle | C: existing plan linked to logs, missed/abnormal feedback, manual adjustment and phase review; no new treatment advice |
| 13 | Dialysis/specialty follow-up | C: schedule/completion/complication links; delay/cancel reasons, specialty authorization and next owner |
| 14 | Growth/vaccination/maternity | C: complete/defer/cancel original plan and trace next stage; appropriate guardian and sharing authority |
| 15 | Mental-health/private feedback | C: authorized professional reading/follow-up, private feedback and reassessment/closure; explicit no-clinician state; no broader family visibility |
| 16A | Emergency assistance | B: request, accepted/unanswered, human progress and resolution/mistake cancellation; explicit limitation that it cannot replace local emergency services or guarantee response |
| 16B | Exit/export/restore | D: accurate coverage manifest; independent restored copy without automatically restarting old grants/notifications; separate full database/attachment acceptance for complete collaboration history |

B/C use each existing domain's rules, not the abnormal-reading clinical closure condition. Each phase gets its concrete design and implementation plan before entry. D combines role/failure/timeout/revocation, bilingual/themes/mobile and restoration evidence; fixing acceptance defects does not add unbounded subproducts.

## 11. Current source anchors

Paths refer to the pinned baseline:

- `src/main/java/org/familyhealthcare/service/CareJourneyService.java`: measurement, prescription, visit, emergency and specialty writes
- `src/main/java/org/familyhealthcare/service/impl/BpSelfMonitorRecordServiceImpl.java`: separate self-monitor save/correction
- `src/main/java/org/familyhealthcare/service/impl/AlertServiceImpl.java`: legacy alert states/events/source/deduplication fields
- `src/main/java/org/familyhealthcare/service/careplan/`: explicit authority, receipt/review, commands, events, outbox and reports
- `src/main/java/org/familyhealthcare/service/FamilyCareService.java`, `ConsultationService.java`, `FamilyBackupService.java`: existing family, consultation and archive boundaries
- `src/main/resources/sql/init.sql` and `mysql57_*_20261012.sql`: existing fresh-init and 5.7-upgrade foundation
