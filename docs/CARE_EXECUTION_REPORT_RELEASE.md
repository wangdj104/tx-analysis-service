# Care execution report release evidence

**Status: final software and the explicitly bounded export/visual/media acceptance PASS.** Frozen 2026-10-05 UTC. Acceptance uses the exact 5b18 runtime, EN execution1 and CN execution2 after one disclosed same-source CN job rerun. All 22 criteria pass within the layered scope and limitations below. The later evidence-only documentation publication remains separate. The backend is **not deployed or activated in production**; `CARE_PLAN_ENABLED` remains default `false`.

Accepted tested runtime: `5b18a6d9043cf65a34b71eb7644ee5a852b407b2`, tree `86405f52c3902691ab25b31fbe73a5114e30bb66`; reviewed test-only implementation `59417c4e33a251c45e0752c0aae93f5784a174e4` and reconciled local `7571bf9c6138a8ea0cc3bba1c4a074c41293e464` have the same complete tree. [Exact source CI 37262008418](https://github.com/wangdj104/tx-analysis-service/actions/runs/37262008418) now has all seven selected required results successful: six carried-forward attempt-1 successes plus the one same-source CN attempt-2 rerun. The [machine-readable record](verification/care-execution-report.json) freezes the accepted runtime and scoped evidence. Historical green candidate `e670a7c9fd27dbd4c54956c821f22afde06d5895` / tree `c685141eb99ec57468b59158fe90088427b1d58e` / run 37257169838 remains verified supporting evidence. A later documentation-only commit is distinct from the tested runtime. The [previous collaboration release](CARE_PLAN_COLLABORATION_RELEASE.md) retains its separate historical acceptance.

Approved requirement baseline: [care execution report design](https://github.com/wangdj104/tx-analysis-service/blob/e670a7c9fd27dbd4c54956c821f22afde06d5895/docs/superpowers/specs/2026-10-04-care-execution-report-design.md), SHA256 `8f4df780222b7e47ceb58f0b002500bfde58e3b18392ef9762c09424fbbc868f`; the spec's historical pre-implementation wording does not replace this release ledger.

## Implemented scope

This report prepares a minimum-necessary, read-only review aid from the authenticated patient's published collaborative plans. It adds current-state preview, period activity and EN/zh-CN HTML, PDF, current-actions CSV and period-events CSV. No new business table, persisted report, background report queue, public file URL, long-lived download token or new clinical write operation is introduced. Generating it sends no care notifications and modifies no receipts, review decisions, evidence, grants or source records.

Entry is available through authorized Family Care doctor-plan tasks, assigned-doctor workspace, Nursing follow-up and plan detail. The dedicated route is `/care-plans/reports?patientId=<id>` with optional `planId`; plan detail presets a single plan. Existing visit summary, health report and data export embed the panel only within their existing permissions. CARE_PLAN-only users are not granted the old comprehensive menus/endpoints. Care-inclusive printing rereads the old summary and the care projection and validates their context; it does not make the legacy modules a cross-module atomic clinical snapshot.

The existing public EN/CN static demo remains the earlier fictional showcase. This milestone adds no new execution-report simulator or backend deployment. Its original source is included in the runtime-identity comparison. Report downloads are not prescriptions, medical proof, anonymization, a restorable archive, doctor-read confirmation or verified clinical outcomes. Recurring clinical schedules, new evidence uploads, clinical effectiveness, provider delivery and whole-platform completion are outside this acceptance.

## Current versus period semantics

- Current N is a unique stable-action-ID set from ACTIVE plans' current published revision. OPEN, NEEDS_HELP, SUBMITTED and CONFIRMED sum to N; zero never becomes a completion rate. Current attention, difficulty, supplement, review wait and readable OPEN questions do not disappear when outside the chosen activity period.
- A submission before its deadline remains waiting for doctor review after the deadline. Return and resubmission preserve append history and establish the correct latest waiting origin. Waiting duration is computed against database `currentAsOf`, not the current browser clock. `generatedAt` separately names generation.
- Period events use recorded_at in a selected IANA-day half-open interval. Actual execution time is separate; backfilled activity follows recording time. Default is 30 local calendar dates; explicit paired dates allow 1–366 inclusive dates, no future end. UTC/Asia/DST/leap/boundary behavior has fast-test layers; browser role fixtures use UTC. Supported unambiguous aliases are allowed, while ambiguous abbreviations/plain offsets are rejected.
- Event count and distinct action count are different. Repeated submissions and plan-level events remain separate; per-category distinct counts cannot be added to a total or divided by current N. A single ended plan can have N=0 and retained history.
- Revision does not copy old receipts/confirmation to new actions. Original instructions/version, actor ID, historical role, SELF/ASSISTED, occurred and recorded times, difficulties, follow-up and review remain distinct. Persisted COMPLETED displays as Closed while keeping its code. Neither SELF nor CONFIRMED establishes identity or clinical improvement.
- Current editable questions distinguish authorization, zero, single-plan exclusion and known/unknown statuses. Recorded answers are not independently doctor-confirmed or complete answer history. Legacy times stay LEGACY_UNZONED. All free-text instructions, notes, questions, answers and opinions retain their original language.

## Roles source scopes and revocation

| Actor | Required current authority |
| --- | --- |
| Record owner | Active account/role and owned active patient; no self-entry identity-verification inference |
| Assigned doctor | Current active doctor role plus active patient assignment |
| Family caregiver | Current family role and explicit valid CARE_PLAN grant; READ is sufficient to download |
| Nurse | Current active nurse role, active unexpired assignment and explicit valid CARE_PLAN grant |
| Administrator/outsider | No clinical shortcut from role, menu visibility or knowledge of patient/plan ID |

Family membership alone and old blank/all-module grants do not implicitly supply CARE_PLAN. Report READ does not grant write or review powers. Optional question authority follows existing full-record/assigned-doctor rules; nursing provenance cannot become a legacy full-record shortcut. CARE_PLAN-only does not load unrelated medication/history/question bodies or question counts.

Every evidence reference separately needs its original MEASUREMENTS or MEDICAL scope and matching patient/source. A restricted reference reveals only existence; a readable one contains permitted type/identifier/title and a fixed authenticated source location, without values/body/attachments or external download links. Opening that focused source checks current ownership/scope again, including cold-route/session changes.

Body data uses an independent effective read-only REPEATABLE_READ snapshot with database UTC time at the first patient-table read. Current authority is checked separately in fresh READ_COMMITTED reads before projection/render and after the complete response is buffered. Base loss rejects the whole report; included optional-scope loss gives REPORT_ACCESS_CHANGED and discards prepared bytes. Newly granted authority does not mutate an already projected report. Authorized zero-question sections still participate in final checks. Revocation after the final check or delivery cannot recall in-flight or saved copies; no stronger assurance is claimed.

## Actual API and failure contract

Routes below are relative to the configured `/api` base. Both POSTs are read-only and do not use commandKey/expectedVersion.

| Method and route | Result |
| --- | --- |
| POST `/care-plans/reports/preview` | HTTP 200 Result of typed report DTO and completeness metadata |
| POST `/care-plans/reports/export` | HTTP 200 fully buffered HTML/PDF/actions_csv/events_csv attachment |

Only one `application/json` object is accepted, with `patientId`, optional `planId`, paired optional `fromDate`/`toDate`, required `timeZone`/`language`; export additionally requires `format=html|pdf|actions_csv|events_csv`. Preview forbids format. Duplicate/unknown keys, trailing JSON, malformed UTF-8, query parameters, invalid enums/IDs/dates/zones and future dates are rejected. Missing, draft, unreadable or cross-patient plan IDs all use ACCESS_DENIED without object-existence detail. Report schema 1 is distinct from workflow and family-backup schema versions; the DTO is minimum fields, not a full Patient/PatientClinical object.

| HTTP | Error | User-visible handling |
| --- | --- | --- |
| 400 | INVALID_REQUEST | Correct input; ranges beyond 366 dates are invalid input |
| 403 | ACCESS_DENIED | Do not display a successful empty report |
| 404 | FEATURE_DISABLED | Feature remains off |
| 409 | REPORT_ACCESS_CHANGED | Regenerate with fresh optional authority |
| 422 | REPORT_LIMIT_EXCEEDED | Show errorCode, numeric limit and CURRENT_ACTIONS / PERIOD_EVENTS / QUESTIONS / SOURCE_TEXT_BYTES / OUTPUT_BYTES |
| 500 | REPORT_DATA_INCONSISTENT | Fail closed on corrupt relations/state |
| 503 | REPORT_RENDER_UNAVAILABLE | Choose HTML when PDF resources/character support fail |
| 503 | REPORT_TIMEOUT | Retry/shrink scope; no partial successful file |

Other errors expose only errorCode, not input, clinical payload or raw exceptions. Unauthenticated requests use the existing authentication layer. Responses use `Cache-Control: no-store, private`; attachments use exact Content-Type, safe Content-Disposition and `X-Content-Type-Options: nosniff`. Filenames use fixed prefixes, language and generation time, with no patient names/diagnoses/free text. Success is fully generated before headers/body are committed; no early partial streaming or persistent cache. Network failure remains a separate failure.

## Export fonts privacy and resource limits

HTML/PDF uses one selected interface language with original free text unchanged. Dynamic markup is escaped; PDF accepts only fixed local templates and verified configured fonts, rejecting external entities/network/arbitrary local resources. The official CI font is the complete face 0 of WenQuanYi Micro Hei, prepared without subsetting/remapping; the expected prepared TTF SHA256 is `1c6503e656e7bda78d5958185a49fa9c7692c0446b7a3031a14655095acc8b61`. Candidate preparation/license artifacts are verified in both editions: source TTC SHA256 `2420e8078af796b19a3f6ef13de527a1a91c1e7171eea115926c614ced1009b3`, complete face with 49,531 glyphs/34,600 mapped codepoints; guarded native/application/export jobs received the prepared font. Raw JVM font-use logs were not separately retrieved. Font configuration and availability are installation-specific. Supported Latin/CJK examples, including Chinese in English output, have test coverage; every actual visible character is checked. Unsupported complex/RTL/combining/supplementary text or unavailable glyphs fail with REPORT_RENDER_UNAVAILABLE and an HTML fallback. Universal Unicode/font/OS support is unverified.

PDF/print wraps long originals and repeats table headers. A hash, nonblank raster, extracted text or media decode is an integrity check, not human visual inspection. Final actual-page review must explicitly identify every covered occurrence and any exact RGB+dimension reuse; there is no fuzzy/timestamp masking reuse.

CSV action/event row models remain separate, with fixed localized headers and stable codes, BOM, strict UTF-8, proper quotes and multiline cells. Nonempty rows repeat scope/time-zone/language/schema/generated-time metadata. Formula/control-leading text receives literalizing protection without changing source records; CSV is not raw-byte archival. Empty CSV is exactly BOM plus headers, not a fake metadata row. Before the real download click, the generation page shows zero rows and actual scope/zone/language/schema/time; filename preserves language/time. Empty files alone lack offline self-contained metadata.

| Production report bound | Value |
| --- | ---: |
| Current actions | 1,000 |
| Period public events | 5,000 |
| Included current questions | 200 |
| Included UTF-8 original text | 8 MiB |
| Output file/response | 32 MiB |
| Total server query/render/final-authorization budget | 30 seconds |
| New report client timeout | 45 seconds |

One-extra-row probes fail rather than silently truncate. Conservative 8 MiB raw storage-cell checks precede allocation and can reject a legacy cell even if only part would be rendered; cumulative included text remains bounded separately. Independent actions_csv does not load period/questions, and events_csv does not load current detail/questions. A full preview overflow does not forbid a smaller unaffected CSV. Shorten the activity range, use one plan or another independently smaller export. Server cancellation is cooperative, not a promise an uninterruptible library stops immediately.

Patient/plan/options/auth changes invalidate old DTOs, errors, export metadata and Blob URLs; logout, revocation and unmount clear them. Ownership is checked at dispatch, transformations/scans, metadata publication and before click. Repeated generation controls are disabled while active. Closing rejects late client responses without guaranteeing server cancellation. Body text is not put in URLs, browser storage, telemetry or logs. Download receipt does not show a doctor has read, reviewed or received it.

## Installation and recovery

No report-specific business migration or permission widening is introduced. Use the already reviewed collaboration prerequisites and applicable idempotent upgrades from the [collaboration release](CARE_PLAN_COLLABORATION_RELEASE.md#installation-old-records-and-recovery), separately for root and CN editions. Installing those prerequisites does not activate CARE_PLAN_ENABLED, assign a nurse or grant patient access. Production migration/deployment/activation has not occurred for this milestone.

Family archive version 1 and its declared table list remain unchanged. Collaborative plans/revisions/actions/receipts/events/evidence, nurse assignments, grants/commands/outbox and their history remain outside that limited archive. A report cannot restore them. Complete database and attachment-storage backup/restore is separate; CI's full synthetic MySQL restore is not a new property of family archive exports.

## Verification ledger

The following complete local gates are bound to reviewed `6ab6db8eca1fd0fb00c02b14ddded818e9f2f129` / tree `c685141eb99ec57468b59158fe90088427b1d58e`, byte-equivalent to the published candidate. Their retained digests were independently checked while preparing this draft. They are root make test/build equivalents using an installed JDK17/Maven3.9.11 wrapper, not a claim that a literal make command or local native/browser gate ran.

| Local gate | Observed result |
| --- | --- |
| EN and CN full backend tests | Each 595 total: 594 pass, 1 explicitly unconfigured CarePlanMysqlIntegrationTest SKIPPED; 0 failures/errors |
| EN and CN backend package after tests | BUILD SUCCESS, package step deliberately skips rerunning tests |
| EN clean-install frontend tests/build | Unchanged-lock npm ci; 1,379 pass, no failures/cancellations/skips; production build pass |
| CN clean-install frontend tests/build | Unchanged-lock npm ci; 1,386 pass, no failures/cancellations/skips; production build pass |
| Both demo syntax and combined demo/CN-demo/scripts Node suites | 376 pass, no failures/cancellations/skips |
| Font preparation tests | 3 pass |
| Grouped semantic focused RED→GREEN | Final frontend tests: 46 controls pass/34 expected semantic failures → 80/80 pass; new backend lifecycle tests: 2 expected assertion failures per edition → affected suites 53/53 each |

Initial npm-ci environment/cache failures and the concurrent missing-installed-Playwright-source script failure remain historical failures; complete unchanged-lock retries passed. The frontend harness diagnosis and intermediate overly broad text assertion are not the final product RED evidence. No dependency/lockfile adjustment or weakened assertion hid these attempts.

Independent whole-branch review found no Critical/Important issue; scoped fix3 and M10–M13 reviews each approve specification and code quality. M10 OWNER, M11 optional Today completeness, M12 supported aliases and M13 persisted lifecycle labels were addressed. Those review approvals do not establish hosted native/browser acceptance.

**Confirmed historical green-candidate gates, not final acceptance:** CI 37257169838 on `e670a7c9fd27dbd4c54956c821f22afde06d5895` has all seven terminal jobs successful. The [EN native/browser job](https://github.com/wangdj104/tx-analysis-service/actions/runs/37257169838/job/111596643137) and [CN job](https://github.com/wangdj104/tx-analysis-service/actions/runs/37257169838/job/111596643144) both passed the guarded two-pass/schema/restore step. Its fail-on-error loop runs two independent passes per edition, with the second also requiring real browser/full restore. Source declares ten native methods including report acceptance and full restore; raw job logs/JUnit numeric totals were not separately retrieved, so no independently observed per-pass numeric totals are claimed.

Authentic safe outcome files show **58/58 passed per edition**, no global error; corrected case022 is passed/error none/final phase teardown in both. Outcome SHA256s are EN `cb489867cd1342a7b8659c14ce48df360379cddf691ddec34eb2b387d8d41037` and CN `490876e310b5b0f0d8483bcd523240d78205a562b152d1d7aede1e5a29cb9c79`. Source retries=0; no failed/skipped/not-run fixed identity. The real built Vue/Spring/disposable-MySQL tests preserve authentic responses, without mocked success. All 152 original reports are retrieved with exact source/run/ZIP/hash/membership checks. These e670 results are historical support; selected 5b18 EN1/CN2 results and remaining freeze scope are recorded below.

## Settled source-dialog capture gate

The e670a7c browser cases and all seven CI jobs passed, but its 390px medical-source screenshots/videos close the dialog during its approximately 300ms opening transition. They cannot establish stable post-transition readability. Diagnosis has not established a production defect or production change. The bounded six-path test-only correction was independently reviewed and published as `5b18a6d9043cf65a34b71eb7644ee5a852b407b2`. Its exact CI 37262008418 is established, using EN1/CN2 successful proof; genuine settled 390px/desktop source PNGs in both editions are now opaque/readable. The e670 candidate records remain historical support. No new commit/run is pending or planned, and no all-mobile/screens-flawless claim is made; final CN export/media reconciliation is complete; only the reviewed documentation commit/publication is separate.

The correction preserves production UI/CSS/API, lockfiles, guard, workflow, budgets, case IDs/titles and screenshot names. Actual browser readiness polls transition classes, opacity/animations/transform, viewport geometry and elementFromPoint for title, exact source cell and close control before capture/dismissal. Local predicate controls are 22/22 per edition; fresh final-source frontend suites are EN 1,401/CN 1,408, both builds and shared scripts 109 pass. Backend was unchanged and not rerun; earlier 595-total local backend (one native skip), packages, 376 combined demo/script and three font results are explicitly reused unchanged-input proof. No new local npm ci/native/browser/layout pass is claimed. A nonblocking regression-regex sensitivity remains: current pre-capture call is verified, but the unit callsite pattern could miss its deletion because close also calls readiness; real pixel verification remains mandatory.

## Same-source CN job retry

Run 37262008418 attempt 1 passed the five standard jobs and [EN required native/browser job](https://github.com/wangdj104/tx-analysis-service/actions/runs/37262008418/job/111610994610), including EN 58/58 outcomes. CN had 57/58, with only case013 failing during initial patient selection before printing (`test_error`, `test-body`, `cn/frontend/e2e/reportBrowser.mjs:22:51`). Its menu repositioned/closed without selection; the exact click and a production root cause are not established. All new settled medical-source checks passed, and the actual CN390px source PNG is now opaque/readable; that does not close the separate failed case.

One bounded rerun of only the [failed CN job](https://github.com/wangdj104/tx-analysis-service/actions/runs/37262008418/job/111610994889) was accepted at 2026-10-05 04:43:47 UTC on the **same 5b18a6d source**, with no code/assertion change. The rerun succeeded. Authentic accepted CN outcomes are 58/58, including case013 and case022; fresh CN IDs/timestamps are selected separately from the failed attempt. First-attempt outcomes and artifact IDs are preserved separately; same artifact names/source SHA do not permit using the failed attempt's files as retry proof. Final CI/native/browser proof now uses EN attempt1 and CN attempt2 on the same runtime. Independent final artifacts/visual/media/source freeze remains required. Playwright retries remain zero; this disclosed workflow-job rerun is separate. GitHub now assigns latest attempt-2 metadata IDs to all seven rows, while six successful jobs keep their original 04:05 start/completion times. These are carried-forward successes, not seven freshly rerun jobs. The current [CN rerun job](https://github.com/wangdj104/tx-analysis-service/actions/runs/37262008418/job/111619160580) started 04:43:51 UTC; the carried-forward [EN result](https://github.com/wangdj104/tx-analysis-service/actions/runs/37262008418/job/111619193666) reflects its original 04:05:24–04:38:53 UTC execution.

## Confirmed accepted run and EN evidence

Run 37262008418 / source `5b18a6d9043cf65a34b71eb7644ee5a852b407b2` / tree `86405f52c3902691ab25b31fbe73a5114e30bb66` has all seven selected required results successful. The same guarded two-pass/native/full-restore commands succeeded in retained EN attempt1 and rerun CN attempt2; raw numeric JUnit totals were not separately retrieved. Authentic safe outcomes are EN58/58 SHA256 `893ae24a110b4f01aa4e7aa29f19980592da207d12f761f35c554afa9f7f5430` and CN58/58 SHA256 `0444a1d47716a83765d5dd22451df485855ad6caac862be7f3fa7b62f9a88f9b`, global error none. Playwright retries=0; one CN job rerun is disclosed above. Ten accepted core ZIPs match actual GitHub byte counts/digests; 50 accepted artifact metadata IDs are source/run-bound, with individual retrieval/inspection scope in the machine record. Both selected font provenance files match the complete official TTF digest.

EN independent export review is complete: 76 originals/content/boundary checks, 20 PDFs/376 page occurrences covered by 92 directly opened new images, 212 exact duplicates and 72 exact baseline-RGB/dimension matches; no visible PDF defect. Sealed review digest `981313d50daf55aecfc46af32f396894c952c0195875627355173e5bec611430`. EN selected media has eight verified ZIPs/766 members, 519 decoded PNGs and 13 fully decoded selected MP4s; 55 named screenshots and 74 core sampled frames were actually viewed. Fully opaque/readable source dialogs are verified at 390px and desktop. This is bounded human viewing, not all-video playback. The dense legacy examination-items Unit header clips at 390px while source fields/close remain readable; full-page screenshot capture introduces transient gray/tiny-image video frames. Preserve those limitations and original captures; prepared delivery clips use contiguous trims, not a claim of raw unedited originals. Accepted EN1/CN2 combined PDF/content/media reconciliation is complete.

## Completed accepted export and all-page review

All 152 selected EN1/CN2 original bytes, independent content checks and supplemental boundary checks pass. All 40 PDFs/752 page occurrences are covered, representing 238 distinct RGB images; the combined review directly opened 92 unmatched CN images, mapped 212 exact duplicates and 448 exact RGB/dimension baseline occurrences, including all 376 previously completed same-runtime EN pages. No EN rerender/reinspection, tolerance or timestamp masking was used. Sealed combined digest `0a1190fc233baf55337300f31df32b5582d11f5b88456916f31b71387df6aea0`; all source/PNG/RGB identities were rechecked. No visible PDF defect, JavaScript, form or actionable URL annotation was found. HTML inspection is structural/content, not separate browser pixels. Original report ZIPs are EN artifact 11326076261 (3,108,374 bytes, SHA256 `f57fc92477aeec1fe35da6216f7549cfd8e8b1d29e2c599a4a584fa61d70e0f1`) and accepted CN2 artifact 11326677949 (3,107,665 bytes, SHA256 `4e6056d7f0f12f3b42571b98def3064772eac4c356b8fb29eefcd6593ecb0933`). Their exact per-file safe hashes and accepted attempts are in the machine record.

## Completed bounded media and source freeze

Accepted EN1/CN2 media has 16 verified ZIPs/1,564 members, 1,038 decoded PNGs and 28 fully decoded selected original MP4s. Human review covered 110 named screenshots, 32 main previews, 34 deeper coordinate crops, 156 core sampled frames, 32 additional CN source/boundary samples and 18 delivery samples. Six opaque native source PNGs are the definitive settled-state proof. CN video frame 120 at 4.80s is only the clearest near-settled sample; frame 118 at 4.72s retains faint residual detail and 4.84–4.92s shows closing translucency. Successful CN2 fresh-print PNGs visibly contain fresh legacy and care sections; failed CN1 is excluded.

The remaining presentation/capture limits are disclosed: dense Unit/单位 header clipping at 390px; transient gray/tiny-page recording frames during full-page capture (some print videos mostly show that capture state); a fixed navigation strip retained in some original mobile full-page PNGs; rapid automated clicks without pointer markers. Review is sampled pixels, not a continuous all-video viewing or accessibility audit. Prepared EN desktop 6.4s and CN mobile 5.0s clips are contiguous H.264 re-encoded trims without cropping/overlays/speed change; selected previews are byte-identical original PNG copies. Final media summary SHA256 `73cfeb1f425f840cd4789f2b6b8d0dd1872a5b928d207f0c22b01313efff0b87`, human-view manifest `f3fe50cfc735e817871f05edef83b029bd15030804d1289485128eb12d93e61a`, delivery-media manifest `6fe3ded431371b2553ff061133c6c6e119fa5ea74dda4f7c96ad70dab3497978`. Private user delivery identifiers are excluded.

Excluding the exact seven documentation paths, the 1,269 original-order mode/type/blob/path entries have SHA256 `969395988c37d5f57288630c6498fe14000f2ac64cbf220342ab5547c2c98e4b`. The tested runtime and reconciled HEAD match every nonexcluded entry. The reviewed seven-file index/commit is checked against that same complete manifest before/after commit; containing documentation SHA is resolved through Git history, and remote publication is verified separately. No runtime/test/CI/lock/deployment/demo source change is included.

## Twenty-two acceptance criteria

Every item is **PASS within its stated layered scope and disclosed limitations** on the same accepted runtime. The machine record applies the mandatory exact-runtime/seven-job/native/browser/report/font/all-page/media/source-equivalence gates globally to every criterion and also lists criterion-specific requirements. In particular, 5 and 21 require native lifecycle/two-pass/restore evidence, and 22 requires the entire final evidence chain. Supporting executed local layers above and reviewed source are distinguished from required native/browser/artifact/visual gates. Java test locators below resolve under `src/test/java/org/familyhealthcare/service/` unless the existing legacy tests use its `impl/` directory; frontend fast tests are under `frontend/tests/` and browser files under `frontend/e2e/`. The CN counterparts are prefixed `cn/`; both editions require their own result. Script paths are shared repository-relative paths. Counts are not substituted for these coverage explanations.

### 1. Current N and unique actions

**PASS scoped evidence.** Projection tests check four current states, stable-ID uniqueness and zero; real role-download fixtures expect four unique actions and 1+1+1+1=4. Empty scopes use page/CSV controls.

Test/inspection anchors: `CareExecutionReportProjectionTest.currentDenominatorAndReviewWait`, `CareExecutionReportRenderTest.questionsAvailabilityAndZeroCurrentCountsRemainExplicit`, `careExecutionReport.spec.mjs`.

Scope limit: Event rows are not current N; no completion or clinical-effectiveness score.

### 2. Provenance and original text

**PASS scoped evidence.** Separate receipt/help/follow-up/return/review snapshots preserve actor, historical role, SELF/ASSISTED, occurred/recorded times and original text. Focused compiled-panel controls verify the corrected OWNER label.

Test/inspection anchors: `CareExecutionReportProjectionTest.boundariesUnknownQuestionStatusesAndAppendIdentityRemainExplicit`, `CareExecutionReportRenderTest.localeNeverTranslatesClinicalNotes`, `careExecutionReportSemantics.test.mjs`.

Scope limit: SELF is a declaration, not independent identity verification; follow-up is administrative.

### 3. Review waiting and deadline semantics

**PASS scoped evidence.** Projection/UI controls check snapshot-based waiting and append-order return/resubmission; native RECEIPT/RETURN mutations and the real role fixture supply further layers.

Test/inspection anchors: `CareExecutionReportProjectionTest.currentDenominatorAndReviewWait`, `CareExecutionReportProjectionTest.latestStateUsesAppendOrderRatherThanRecordedTime`, `CareExecutionReportMysqlAssertions.consistentSnapshotAcrossConcurrentRevision`.

Scope limit: Submitted waiting does not become patient overdue after the deadline; final native execution remains separate.

### 4. Current attention outside the period

**PASS scoped evidence.** Current attention/questions are independent from recorded_at activity filtering. Actual fixture/oracle covers old help/contact, nonzero overdue/supplement and authorized OPEN questions.

Test/inspection anchors: `CareExecutionReportProjectionTest.periodUsesRecordedTimeAndOriginalRevision`, `CareExecutionReportProjectionTest.questionsAndEvidenceAreSeparateScopes`, `scripts/care-report-fixture-oracle.mjs`.

Scope limit: M6 remains a direct-renderer-fixture depth limitation; layered functional evidence does not claim that missing fixture was added.

### 5. Revision and ended-plan history

**PASS scoped evidence.** Original revision instructions/receipts remain; current N uses the current ACTIVE revision. Actual CLOSE/projector controls preserve COMPLETED and PLAN_CLOSED; native REVISION/CANCEL/CLOSE checks fresh state.

Test/inspection anchors: `CareExecutionReportProjectionTest.periodUsesRecordedTimeAndOriginalRevision`, `CareExecutionReportProjectionTest.actualCloseLifecycleProjectsCompletedHistoryWithLocalizedExportLabels`, `CareExecutionReportMysqlAssertions.consistentSnapshotAcrossConcurrentRevision`.

Scope limit: Localized Closed preserves the persisted code and does not imply treatment success.

### 6. Events and distinct actions

**PASS scoped evidence.** Projection controls include repeated submissions, null-action plan events, multiple versions and ended single-plan N=0; actual CSV has independent action/event models.

Test/inspection anchors: `CareExecutionReportProjectionTest.periodUsesRecordedTimeAndOriginalRevision`, `CareExecutionReportRenderTest.csvPreservesTwoRowModels`, `CareExecutionReportMysqlAssertions.consistentSnapshotAcrossConcurrentRevision`.

Scope limit: Main browser publication lies outside its period; those role cases alone do not prove plan-level events. Category distinct counts cannot be summed.

### 7. Draft and internal-plan exclusion

**PASS scoped evidence.** Queries require workflow_version=1, published revisions and the fixed public-event whitelist. Common fixture seeds legacy internal plan #1; access denies it and projection counts inherit it. Draft revision/event exclusion has direct assertions.

Test/inspection anchors: `CarePlanTestFixture.java`, `CareExecutionReportAccessTest.planMustBelongToPatientAndHavePublishedCollaborativeRevision`, `CareExecutionReportProjectionTest.periodUsesRecordedTimeAndOriginalRevision`.

Scope limit: No separately named sentinel assertion for the internal plan in all four actual file formats was located. Exact projection and inspected-file scope provide layered support.

### 8. Time zones dates and recorded boundaries

**PASS scoped evidence.** Fast contracts cover UTC/Asia, 23/25-hour DST, Apia skipped date, leap/max-366/invalid/future bounds; projector covers recorded-time/backfill boundaries. Compiled UI uses real Intl for supported aliases and invalid controls.

Test/inspection anchors: `CareExecutionReportContractsTest.defaultThirtyLocalDates`, `CareExecutionReportContractsTest.dstHalfOpenRange`, `CareExecutionReportContractsTest.skippedApiaDateUsesZoneRules`, `CareExecutionReportContractsTest.strictDatesAndInclusiveMaximum`, `careExecutionReportSemantics.test.mjs`.

Scope limit: Main real-browser downloads use UTC, not an exhaustive browser DST/Asian-zone matrix; backend/browser time-zone databases can differ.

### 9. Legacy unzoned time and mixed text

**PASS scoped evidence.** LEGACY_UNZONED is explicit. Mixed Chinese originals survive English labels, extraction and supported-font raster tests; final font use and all-page human inspection must be tied to the final run.

Test/inspection anchors: `CareExecutionReportProjectionTest.questionsAndEvidenceAreSeparateScopes`, `CareExecutionReportPdfTest.bothLanguagesRenderAndExtractAllText`, `CareExecutionReportRenderTest.localeNeverTranslatesClinicalNotes`.

Scope limit: PDF rejects unsupported glyph/shaping/RTL/combining/supplementary text with HTML fallback; universal Unicode rendering is unverified.

### 10. Real MySQL consistent snapshot

**PASS scoped evidence.** Required native gate uses independent reader/writer connections and real RECEIPT, RETURN, REVISION, CANCEL and CLOSE commits between projector reads, comparing immutable body and subsequent fresh reports in two passes per edition.

Test/inspection anchors: `CarePlanMysqlIntegrationTest.nativeExecutionReportAcceptance`, `CareExecutionReportMysqlAssertions.consistentSnapshotAcrossConcurrentRevision`.

Scope limit: Local guarded native test is SKIPPED; H2, discovery and mock observers do not satisfy this gate.

### 11. Effective isolation and fresh authority

**PASS scoped evidence.** RR connection state is verified for body reads; independent RC authority checks run before rendering and after buffering. Native SNAPSHOT/AFTER_RENDER crosses six BASE/EVIDENCE/QUESTIONS revoke/expire modes.

Test/inspection anchors: `CareExecutionReportProjectionTest.independentReadOnlyRepeatableReadSnapshotAndFirstTableTimestamp`, `CareExecutionReportAccessTest.eachCallUsesFreshReadCommittedConnectionAndRestoresOuterSnapshot`, `CareExecutionReportMysqlAssertions.freshPermissionAfterSnapshotRevocation`.

Scope limit: Native committed-change proof is a required final CI gate; a same-snapshot simulated check is insufficient.

### 12. Cross-patient and role denial

**PASS scoped evidence.** Common backend checks cover disabled/stale actors, patient membership and nurse requirements; real JSON/all-format denial scenarios cover admin/outsider/cross-patient/cross-plan and revocation, plus focused-source guards.

Test/inspection anchors: `CareExecutionReportAccessTest.disabledActorPatientOrOnlyCurrentRoleDeniesWholeReport`, `CareExecutionReportAccessTest.staleDoctorOrAdminCannotReadQuestions`, `careExecutionReport.spec.mjs`, `careExecutionReportSources.spec.mjs`.

Scope limit: Browser does not form an exhaustive Cartesian product of every disabled/stale-role permutation and format; those remaining layers are backend tests.

### 13. READ only and minimum data

**PASS scoped evidence.** READ suffices, nursing needs role+assignment+grant, and report generation calls no writes/notifications. Native SQL checks preview/actions/events at 1 and 50 actions, narrow evidence probes and optimizer keys.

Test/inspection anchors: `CareExecutionReportAccessTest.readGrantIsEnough`, `CareExecutionReportAccessTest.narrowNurseNeverLoadsFullPatient`, `CareExecutionReportProjectionTest.csvLoadsOnlyItsProfile`, `CareExecutionReportMysqlAssertions.reportQueriesStayBounded`.

Scope limit: CARE_PLAN-only does not load unrelated medication/history/question body or count; independent CSV profiles are not broad full-record authority.

### 14. Evidence ownership and prepared-byte revocation

**PASS scoped evidence.** Included-reference/question manifest retains authority requirements, including authorized zero questions. Base loss denies all; optional loss discards prepared output with REPORT_ACCESS_CHANGED. Source opening rechecks matching patient/module.

Test/inspection anchors: `CareExecutionReportAccessTest.reassignmentOrDeletionOfReadableEvidenceInvalidatesPreparedReport`, `CareExecutionReportAccessTest.emptyIncludedQuestionSectionStillRequiresFinalAuthority`, `CareExecutionReportMysqlAssertions.freshPermissionAfterSnapshotRevocation`, `careExecutionReportSources.spec.mjs`.

Scope limit: Final-check/delivered-copy boundary is unavoidable: later revocation cannot recall network or saved copies.

### 15. Question availability and recorded answers

**PASS scoped evidence.** AVAILABLE (including zero), NOT_AUTHORIZED and single-plan exclusion remain distinct. OPEN/ANSWERED/CANCELLED/unknown status and recorded-answer labels preserve current editable legacy semantics; questions are omitted from both CSVs.

Test/inspection anchors: `CareExecutionReportProjectionTest.questionsAndEvidenceAreSeparateScopes`, `CareExecutionReportProjectionTest.boundariesUnknownQuestionStatusesAndAppendIdentityRemainExplicit`, `CareExecutionReportRenderTest.questionsAvailabilityAndZeroCurrentCountsRemainExplicit`, `careExecutionReportUi.test.mjs`.

Scope limit: Recorded answers are not doctor verification or complete historical answers; not every availability state is shown in reviewed media.

### 16. Fail-closed limits and rendering

**PASS scoped evidence.** Tests cover 1001/5001/201 probes, corrupt relations, source/output budgets, worker/queue deadline, serializer/render/font/layout/final-write failures. Browser limited-preview/header-only CSV scenarios check independent exports; fully buffered responses avoid partial successes.

Test/inspection anchors: `CareExecutionReportProjectionTest.currentLimitDoesNotBlockIndependentEventCsv`, `CareExecutionReportProjectionTest.eventLimitDoesNotBlockIndependentActionCsv`, `CareExecutionReportContractsTest.java`, `CareExecutionReportServiceTest.java`, `CareExecutionReportApiTest.java`, `CareExecutionReportPdfTest.java`.

Scope limit: Raw storage cells have a conservative 8 MiB preflight. Test raster 256 MiB total is a postcondition, not a live disk cap; not all failures are live-browser cases.

### 17. Injection and resource safety

**PASS scoped evidence.** Escaped HTML, formula/control-safe CSV, fixed authenticated source routes and PDF external/file-resource rejection have direct controls. Actual attack originals are inspected as text, with strict byte/semantic oracles and annotation checks.

Test/inspection anchors: `CareExecutionReportRenderTest.htmlEscapesEveryDynamicField`, `CareExecutionReportRenderTest.dangerousTextIsLiteral`, `CareExecutionReportRenderTest.safeEvidenceLocationsExcludeExternalAndMismatchedTargets`, `CareExecutionReportPdfTest.networkAndFileResourcesAreDenied`, `scripts/inspect-care-report-content.py`.

Scope limit: CSV safety escaping preserves the source record but is not raw-byte archival or anonymization.

### 18. Metadata and empty CSV exception

**PASS scoped evidence.** Page/HTML/PDF/nonempty rows include actual scope, language, zone, schema and generation facts. Empty downloads retain strict BOM+headers with pre-click 0-row metadata and safe language/time filename.

Test/inspection anchors: `CareExecutionReportRenderTest.csvPreservesTwoRowModels`, `CareExecutionReportApiTest.java`, `careExecutionReportClient.test.mjs`, `frontend/e2e/reportDownloads.mjs`, `scripts/care-report-fixture-oracle.mjs`.

Scope limit: Raw downloads are unmodified. Transport comparison allows exactly one omitted BOM and the same UTC instant as GMT/GMT+00:00; no broad normalization. Empty files lack offline self-contained metadata.

### 19. Context session and print races

**PASS scoped evidence.** Ownership epochs/session/context checks cover A→B→A, options, unmount, scan/pre-click, logout/login, history and source/print races. Fix3 retains actual revoked legacy response and real care HTTP200 with controlled delivery ordering; compiled product controls cover both orders.

Test/inspection anchors: `careExecutionReportClient.test.mjs`, `careExecutionReportIntegration.test.mjs`, `careExecutionRevokedPrint.test.mjs`, `careExecutionReportRaces.spec.mjs`, `careExecutionReportPrint.spec.mjs`, `careExecutionReportSources.spec.mjs`.

Scope limit: Observed HTTP200 is not proof of fully mounted care DOM before held denial release. Client cancellation does not guarantee server cancellation; final case022 live outcome is now passed and recorded separately.

### 20. Roles viewport focus print and visuals

**PASS scoped evidence.** Eight role×viewport cases per edition request both output languages and four formats; four positive language×viewport print cases verify fresh sources, focus, callback and cleanup. Final actual file/page/frame review is an additional evidence layer.

Test/inspection anchors: `careExecutionReport.spec.mjs`, `careExecutionReportPrint.spec.mjs`, `careExecutionReportSources.spec.mjs`, `CareExecutionReportPdfTest.java`.

Scope limit: 390px and desktop scope is bounded; media decode/nonblank hashes do not prove human visual quality or that every video was watched end to end.

### 21. Legacy regression and default off

**PASS scoped evidence.** Full mirrored backend/frontend/build/demo/script gates include legacy export, state machine, timeline, notifications, authority and archive contracts. Native two-pass/full restore and the original ten real-browser cases remain required. M11 keeps denied/unavailable optional Today data visibly incomplete.

Test/inspection anchors: `CareExecutionReportApiTest.featureOffReadsNoClinicalTablesAndConstructsNoExecutor`, `HealthExportWorkflowTest.java`, `ReportPdfFontTest.java`, `CarePlanAuthorizationTest.java`, `CarePlanTimelineTest.java`, `CarePlanNotificationTest.java`, `familyHealthReload.test.mjs`.

Scope limit: Feature defaults false in both editions; no new business table, archive-format change, production migration/activation or refreshed public report demo.

### 22. One final runtime and evidence freeze

**PASS scoped evidence.** Acceptance requires one exact final runtime/tree, seven terminal CI jobs, two 58-case outcomes, actual native/report/font/media proofs and documented visual scope. A later seven-path docs-only commit must retain all other mode/type/blob/path entries.

Test/inspection anchors: `.github/workflows/ci.yml`, `scripts/care-plan-browser-outcomes.mjs`, `scripts/package-care-report-artifacts.mjs`, `scripts/package-care-plan-browser-artifacts.mjs`.

Scope limit: Earlier green collaboration CI and staged file result=passed cannot establish new final report acceptance; failed/not-run history remains explicit.

## Final artifacts visual scope and historical attempts

The selected 5b18 EN1/CN2 original-report/outcome/font ZIP retrieval and integrity are **PASS**. The settled-dialog test-only correction, exact CI and authentic opaque/readable source PNGs are established. EN independent content/all-page/media proof is complete; CN media reconciliation and the nonexcluded source check are complete; documentation publication is separate. The earlier e670 capture gap is historical, not a new-runtime blocker. Expected membership derived from the current catalog is 76 original report files per edition (152 total): per edition 20 PDF, 16 HTML, 20 actions CSV and 20 events CSV. Sixteen role/output-language combinations provide 64 UI files; empty/limited scenarios provide eight CSVs; four renderer-only PDFs supply long/coverage specimens. The accepted EN1/CN2 manifests/archives contain this exact 76-per-edition/152-total membership and all independent content/visual checks passed. The optional-revocation HTML is inspected separately, not an extra staged report entry.

Final records must include verified GitHub artifact IDs/names, run/source SHA, archive byte size/digest, safe report manifest digest and each allowlisted synthetic file's format/edition/output language/scenario/hash/bytes/page or row count. Browser outcomes must validate all 58 fixed identities per edition with terminal status/phase; result=passed on a staged file does not mean the enclosing browser body/teardown passed. Font preparation/license and actual configured digest need their own check. Artifact links may expire under GitHub retention; immutable hashes/identity remain evidence but do not guarantee perpetual download availability.

Historical run [37250920072](https://github.com/wangdj104/tx-analysis-service/actions/runs/37250920072), runtime `122ef3fd1bbecfce8d69fa7ede88526ffb4bd3c8`, has 57/58 browser passes per edition with case022 timedOut at teardown, despite 76 staged files each. Its separate export review passed 152 raw-file hashes/content/semantic checks and 40 PDF/752 page occurrences: 190 distinct images directly opened, 466 occurrences exact duplicates of those, and 96 occurrences exact RGB+dimension reuse of 48 previously inspected images (238 distinct images covered). Those are historical observations, not final-runtime pages. Final files require fresh identity/content checks; only exact RGB+dimension equality may carry over a visual observation, with every changed page examined directly. HTML historical review was structural/content, not separate browser pixel review. The candidate corrected print outcome is passed in both authentic outcome files. Final 5b18 all-page and bounded media checks are separately passed; this older run remains historical.

Earlier runtime `15b59a8ea076329401a28c0ebacbe629e279fdd0` failed both native/browser jobs. Bounded corrections and deterministic probes are supporting diagnosis, not recovered raw failed assertion text or live acceptance. Fix3 corrects a guaranteed-response wait assumption after legitimate sibling cancellation; its controlled real-response order retains denial/no-popup/no-old-body assertions and cleanup. A care HTTP200 event does not independently prove a fully mounted fresh care section before the held legacy denial is released. Older collaboration green run 37137746134 and earlier report checkpoints are not final report proof.

Artifact packaging selects synthetic originals and bounded PNG/WebM/MP4 plus safe manifests/outcomes/font provenance only, never credentials, real health data, raw logs/traces/HAR/errors/storage/request dumps or signed/query URLs. Production 32 MiB output is separate from acceptance 30 MiB staged-payload groups (2 MiB is reserved for ZIP/manifest overhead below the 32 MiB download bound), report-manifest capacity 224 entries/128 KiB, and page/geometry/subprocess limits. The test raster total 256 MiB and per-page 30 MiB checks occur after raster production/read; they are postconditions, not live disk caps.

## Retained nonblocking dispositions

- M4: redundant bounded revision/title/instruction preflight/reads for latest summaries; accepted bounded inefficiency, no established wrong output
- M5: static one-IN-list placeholder replacement is maintenance-sensitive; reviewed current callers bind correctly
- M6: direct renderer fixture for nonzero overdue/supplement/out-of-period attention remains missing; genuine SQL/browser/oracle layers cover the behavior
- M8: dense HTML/CSV helpers retain audit/readability cost; no broad formatting refactor
- M9: GC/finalizer-warning cleanup test is nondeterministic supplemental evidence; deterministic document ownership, failed-write and descriptor controls remain
- Task9 M1: raster byte totals are postconditions, not live storage enforcement; future incremental raster hardening remains outside this release

Closed earlier findings stay closed; M10–M13 are addressed in the reviewed runtime. These limitations are not silently presented as new fixes or exhaustive validation.

## Evidence-only documentation identity

The final runtime remains distinct from the later docs-only commit. Freeze **exactly seven paths**: README.md, cn/README.md, docs/USER_GUIDE.md, cn/docs/USER_GUIDE.md, docs/CARE_EXECUTION_REPORT_RELEASE.md, cn/docs/CARE_EXECUTION_REPORT_RELEASE.md and docs/verification/care-execution-report.json. Old collaboration evidence and all runtime/tests/CI/dependency locks/deployment/demo source are outside those exclusions and must remain unchanged.

Compare every remaining `git ls-tree -r` entry of runtime and documentation commit, retaining mode/type/blob-ID/path, original order, LF joining and a final LF; record count and SHA256. Full tree SHAs legitimately differ because documents change. Documentation commit is located through Git history, then its remote-main identity/source-equivalence is checked after authorized non-force publication. A source-manifest draft computation, local commit or prior remote query is not future remote-publication proof. The exact 1,269-entry source identity and hash above are verified. The containing documentation commit is located through Git history after review; remote publication is separately verified by the controller. This evidence snapshot does not preclaim a future remote state.

## Deployment and verification boundary

Final software and stated export/visual/media acceptance passed on 5b18. Documentation publication is separately checked; exact CI/native/browser and opaque source captures are established. No real-patient workflow, clinical-effectiveness validation, production readiness, backend rollout/migration/activation, new public report demo or real external-provider delivery has been established. Any later deployment or CARE_PLAN_ENABLED activation requires a separately authorized target. Saved exports require user-controlled privacy/retention. Narrow software completion must not be described as completion of the entire healthcare platform.
