# Health platform 50 round quality review 2026-10-02

[简体中文](../cn/docs/QUALITY_REVIEW_20261002_50_ROUNDS.md) · [English demo](https://wangdj104.github.io/tx-analysis-service/) · [中文演示](https://wangdj104.github.io/tx-analysis-service/cn/)

## Result

**All 50 distinct review objectives are complete.** The requested review window was **13:52–21:52 UTC on October 2, 2026**. The verified releases repair patient/editor ownership, asynchronous cleanup, local-date and zero-value presentation, reminders, preferences, polling and chart readability; they add a strict appointment-label index, context-aware guides and bounded demo storage recovery. The fresh final regression passed **2,476 tests**, both production builds and integrity checks. Exact-commit CI, Pages, backend packaging and canonical demo assets were reconfirmed. Measured observation totaled **6 h 16 m 41.606 s across two segments**, with the interruption and first-segment cleanup limit preserved. Final regression and report reconciliation followed the 21:52 boundary; the work window is not eight hours of continuous runtime.

Starting version: `1f0c35a6c5ca3ecdd1bebb46096c8c2c16e4222f`. Final reviewed runtime code: [`2fc8c21e9f74897c4139d8337a585f6e89efff4c`](https://github.com/wangdj104/tx-analysis-service/commit/2fc8c21e9f74897c4139d8337a585f6e89efff4c); tree: `a8844aa300ed3b4563c8e2974a03b85b71b84645`. All source links in this report are pinned to that runtime release.

Clinical thresholds, medication dose logic, clinical interpretation and backend access policies were not changed. A review round is a distinct objective with its own evidence; repeated tests and synthetic observation cycles are not additional review rounds.

## Changes and regression coverage

- **Patient and editor ownership** — [9e3a01b](https://github.com/wangdj104/tx-analysis-service/commit/9e3a01b53840d5f5917f289ddf612eaf305ba51f)
  - Reject obsolete list/detail/analysis/report results, including same-patient reopening and A→B→A transitions
  - Snapshot originating payloads and create/update operations; block duplicate dispatch and prevent old completions from closing, clearing or unlocking replacement drafts
  - Preserve intentional independent medical-record filters/edit selectors and the selected dialysis history view; correct repeated-query navigation and return-to-entry loading
- **Uploads and report resources** — 9e3a01b
  - Bind medical Edit reads, actual queued upload callbacks and attachment preparation to the open dialog; invalidate at close start, before its leave animation ends
  - Keep controls locked during preparation/save, preserve failure retries, fall back to the original file after compression exceptions and release preview/export resources
  - Keep report identity, format and filenames aligned; skip obsolete summary rendering. Already-dispatched server writes cannot be undone by closing a client dialog
- **Dates and value display** — [64a419d](https://github.com/wangdj104/tx-analysis-service/commit/64a419d5d613b87ddd6cc651c8206e0229206863)
  - Use the local calendar day for diary defaults and clear validation without restoring old mounted values
  - Display valid zero values, and bind the English BP-history date column to its actual data field; keep historic dates, raw values and units unchanged
- **Reminder lifecycle and presentation accessibility** — 64a419d
  - Validate reminder session/patient/name/permission context and retain successful-delivery history in memory when persistence fails, bounded to the mounted instance
  - Add localized icon labels and guide focus/Escape behavior; correct selected-kind chart empty states and graphical contrast
  - Respect live reduced-motion preferences without resetting chart zoom/legend state; release preference listeners on disposal
- **Preferences and overlapping reads** — [f50fd19](https://github.com/wangdj104/tx-analysis-service/commit/f50fd19e7956977a49350b593117c2ef4c08753b)
  - Recover obsolete table-column preferences and selected convenience-storage failures without changing valid user selections
  - Reject obsolete medical-list/trend/item-name reads; preserve local and global patient-selector independence
  - Coalesce redundant automatic Monitoring reads and share Family medication-intake record ownership while preserving explicit refresh priority
- **Public demo readability and build checks** — f50fd19
  - Size charts to their rendered width with a fixed 240 px canvas height, explicit normal/large label sizes and resize-listener cleanup; correct the English singular review-count label
  - Defer the existing html2canvas chunk to its lazy route and add backend packaging after backend tests in the root CI matrix
- **Appointment labels and context-aware guide states** — [78de6e3](https://github.com/wangdj104/tx-analysis-service/commit/78de6e345a221f8d3a839b3a0975a7e3e608f627)
  - Replace per-question appointment scans with a computed ID index that preserves strict equality, the first duplicate, NaN non-matching, original visibility and title fallback; reactive collection, ID and title updates remain visible
  - Give the demo guide truthful targets and instructions when the patient has no consultation, the review queue is empty, or no pending one-click care task exists
  - Refresh guide context after patient changes, completion while open and successful measurement-save rendering; guard queued positioning, retain the current step and preserve action/close focus
  - Dedicated source/reactivity and independent checks passed; affected live guide flows were verified in both public locales at 17:39–17:47 UTC

- **Demo convenience-storage recovery** — [2fc8c21](https://github.com/wangdj104/tx-analysis-service/commit/2fc8c21e9f74897c4139d8337a585f6e89efff4c)
  - Let Finish close the guide, clear pending positioning/highlights and return focus even if completion persistence fails
  - Complete a coherent fresh-session Reset even if saved-brand removal fails; warn that the old saved brand may return on reload
  - Apply valid branding for the current visit even if saving fails, preserving the previously stored value and clearly reporting that the change was not saved
  - Preserve invalid-input validation, normal success behavior, existing storage keys, appearance choice and unrelated stored values. Unavailable-storage checks are synthetic; real-browser checks cover normal storage only

## Recorded release verification

The five published code batches have the following recorded passing local checks. Totals are suite test cases, not counts of separate defects, independent review assertions or observation cycles.

| Code batch | EN backend | CN backend | EN frontend | CN frontend | Demo combined | Total | Both builds |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 9e3a01b | 233 | 233 | 709 | 716 | 115 | 2,006 | Pass |
| 64a419d | 233 | 233 | 817 | 824 | 123 | 2,230 | Pass |
| f50fd19 | 233 | 233 | 891 | 898 | 145 | 2,400 | Pass |
| 78de6e3 | 233 | 233 | 902 | 909 | 183 | 2,460 | Pass |
| 2fc8c21 | 233 | 233 | 902 | 909 | 199 | 2,476 | Pass |

The latest release-batch local verification ran **18:05:24.195–18:05:52.705 UTC**. After observation reached the requested boundary, a fresh complete check ran **21:52:14.792–21:52:44.970 UTC** on 2fc8c21: **233 English backend + 233 Chinese backend + 902 English frontend + 909 Chinese frontend + 199 demo = 2,476 passing tests**. Both production builds, demo syntax/parity and diff-integrity checks passed. All **982 full-source fingerprints** were unchanged during the final run and matched the published release-batch snapshot. The runtime checkout was clean. Existing large-chunk advisories remain.

- 9e3a01b: exact-commit [CI passed](https://github.com/wangdj104/tx-analysis-service/actions/runs/37024704086)
- 64a419d: exact-commit [CI passed](https://github.com/wangdj104/tx-analysis-service/actions/runs/37031331153) and [Pages passed](https://github.com/wangdj104/tx-analysis-service/actions/runs/37031331160)
- f50fd19: exact-commit [CI passed](https://github.com/wangdj104/tx-analysis-service/actions/runs/37037548266) and [Pages passed](https://github.com/wangdj104/tx-analysis-service/actions/runs/37037548153). Both new **Package backend** matrix steps passed after their test steps; this is package verification, not backend deployment
- 78de6e3: exact-commit [CI passed](https://github.com/wangdj104/tx-analysis-service/actions/runs/37041945360) and [Pages passed](https://github.com/wangdj104/tx-analysis-service/actions/runs/37041945300). Both **Package backend** matrix steps passed after their test steps; no backend deployment is implied
- 2fc8c21: exact-commit [CI passed](https://github.com/wangdj104/tx-analysis-service/actions/runs/37045903409) and [Pages passed](https://github.com/wangdj104/tx-analysis-service/actions/runs/37045903175). Both **Package backend** matrix steps passed after their test steps; no backend deployment is implied
- At **18:15:20.409–18:16:11.897 UTC**, all 12 sampled public demo files returned HTTP 200 and matched 2fc8c21. The final canonical check at **21:52:42.538–21:54:03.977 UTC** fetched each locale’s ordinary HTML URL and the exact script/style URLs it declared: **12/12 HTTP 200, 12/12 byte matches**. This confirms sampled public static-demo assets, not authenticated application deployment

At **21:52:42.289 UTC**, main still pointed to 2fc8c21; its existing successful CI/Pages runs and both backend package steps were freshly reconfirmed. These were status rechecks of the published runs, not new CI executions. Final local verification and canonical-asset reconciliation were completed after the requested observation boundary.

The [machine-readable verification summary](verification/20261002_50_rounds.json) records the verified releases, completed final checks, measured segments and coverage limits.

The permanent test links below are pinned to 2fc8c21. Paired application suites also exist under `cn/frontend/tests`; demo suites exercise both editions where stated. Focused and independent counts overlap the aggregate coverage and must not be added to 2,476.

## Independent integration review

The combined implementation and final storage-recovery change passed **28 independently authored scenario groups / 490 assertions**, with no introduced regression or remaining blocking finding identified in the reviewed source/runtime scope:

- Fourteen paired-locale component scenarios / 238 assertions combine patient/editor ownership, date resets, filters, polling, post-write refreshes and the appointment index
- Two compiled chart scenarios / 66 assertions mount the real chart components with ECharts and check empty-state transitions, motion preferences, retained zoom/legend state, resize and disposal
- Twelve storage-recovery scenarios / 186 assertions verify guide completion, coherent reset and valid live branding with controlled persistence failures; the permanent [storageRecovery.test.mjs][demo-storage] suite also passed 16/16

The review reproduced three pre-existing demo persistence failures and rechecked their published fixes. API, host-tree, storage and media-query boundaries are controlled adapters; the results are not a browser/backend end-to-end or actual storage-disable test. The scenario/assertion counts are separate from aggregate test counts, review-round counts and measured observation time. The final observation and end-of-window checks are recorded below.

## The 50 review objectives

Each of the 50 distinct objectives is complete within its stated scope. The final verification followed the window boundary; completion does not remove the limits in each row or convert synthetic checks into browser/backend validation.

| Round | Objective | Status | Result and durable evidence |
| ---: | --- | --- | --- |
| 1 | Baseline source integrity and five-suite regression | Complete | Clean baseline: 1,268 tests and both production builds passed; demo syntax/parity passed. The latest release totals are listed above. |
| 2 | Navigation availability and specialty routes | Complete | Thirty focused EN/CN checks passed for specialty-menu filtering and route availability; no new menu-availability defect was established. [patientSpecialtyNavigation.test.mjs][specialty-en]. |
| 3 | Back and forward navigation | Complete | Repeated tab query parameters no longer throw on Vue Router array values. Eighteen checks cover history updates, malformed values and existing tabs. [healthAnalysisNavigation.test.mjs][nav-en]. |
| 4 | Unmounted views and timer lifecycle | Complete | Obsolete reads, saves, file callbacks and guide callbacks become inert after disposal. Component checks passed; duration coverage is separately tracked in round 45. [medicalEditorLifecycle.test.mjs][edit-en]; [accessibilityControls.test.mjs][a11y-en]. |
| 5 | Loading error and retry transitions | Complete | Request ownership prevents old responses, errors or finalizers from ending a newer load; failed medical-editor reads stay unsaveable and retryable. [medicalEditorLifecycle.test.mjs][edit-en]; [medicalReadLifecycle.test.mjs][read-en]. |
| 6 | Latest response on patient switching | Complete | Dialysis, BP and diary lists reject obsolete success/error/loading effects after a patient change. The medical list’s independent all-patient filter is preserved. [dialysisPatientContext.test.mjs][dialysis-en]; [bpPatientContext.test.mjs][bp-en]; [nutritionDiaryContext.test.mjs][diary-en]. |
| 7 | Patient round trips and same-record reopening | Complete | Operation generations cover A→B→A, identical-content replacements and same-record reopening; patient-ID equality alone no longer accepts an old completion. [careEditorLifecycle.test.mjs][care-en]; [medicalEditorLifecycle.test.mjs][edit-en]. |
| 8 | Read and derived-state invalidation | Complete | Dialysis analysis origins, BP details and report previews retain their originating context or are cleared; obsolete content cannot populate a replacement context. [dialysisPatientContext.test.mjs][dialysis-en]; [bpPatientContext.test.mjs][bp-en]; [healthReportContext.test.mjs][report-en]. |
| 9 | Pending saves and replacement editors | Complete | Old saves cannot clear, close or unlock replacement diary, dialysis, BP, care or medical drafts. Payload and operation snapshots preserve the original write. [nutritionDiaryContext.test.mjs][diary-en]; [dialysisPatientContext.test.mjs][dialysis-en]; [bpPatientContext.test.mjs][bp-en]; [careEditorLifecycle.test.mjs][care-en]; [medicalEditorLifecycle.test.mjs][edit-en]. |
| 10 | Empty or removed current patient | Complete | Default, preserved, removed and empty selections passed existing selector and clear-context checks. Deliberate local all-patient medical editing remains supported. [currentPatient.test.mjs][patient-en]; [medicalEditorLifecycle.test.mjs][edit-en]. |
| 11 | Empty zero and numeric form boundaries | Complete | Real Element Plus reset behavior no longer restores a previous patient’s cached glucose. Independent mounted checks rejected 64 invalid saves and accepted six valid controls; numeric rules are unchanged. [bpPatientContext.test.mjs][bp-en]. |
| 12 | Date time and locale parsing | Complete | Local-day defaults replace UTC slicing; reset uses the current day rather than the mounted form’s old day. Sixty-two paired cases cover midnight, year/leap-day boundaries and DST controls. [diaryDateAndDisplay.test.mjs][date-en]. |
| 13 | Repeated submission and stable payloads | Complete | Validation-to-network locks, per-row ownership and detached payloads prevent duplicate actions and replacement-data dispatch. Actual Element Plus disabled-state checks cover the dialysis selector. [dialysisPatientContext.test.mjs][dialysis-en]; [bpPatientContext.test.mjs][bp-en]; [careEditorLifecycle.test.mjs][care-en]; [medicalEditorLifecycle.test.mjs][edit-en]. |
| 14 | Cancel close and back lifecycle | Complete | Close-start invalidation covers header X/Escape before dialog leave finishes; old afterLeave callbacks cannot close replacements. Guide Pause and return-to-entry navigation were also checked. [medicalEditorLifecycle.test.mjs][edit-en]; [accessibilityControls.test.mjs][a11y-en]; [dialysisPatientContext.test.mjs][dialysis-en]. |
| 15 | Validation and server-failure recovery | Complete | Controlled validation/API failures preserve current retryable drafts, release only the owning lock and suppress obsolete feedback. Deliberate retries pass without duplicate dispatch. [nutritionDiaryContext.test.mjs][diary-en]; [healthReportContext.test.mjs][report-en]; [careEditorLifecycle.test.mjs][care-en]; [bpPatientContext.test.mjs][bp-en]. |
| 16 | Upload type count and size boundaries | Complete | Ten additional paired synthetic checks covered supported PDF/images, the ten-file queue, capacity recovery and 413/timeout retry. Configured backend size limits were read, not exercised or verified as deployed. [medicalUploadLifecycle.test.mjs][upload-en]; [MedicalRecordManager.vue][upload-source-en]. |
| 17 | Queued selection and removal ordering | Complete | Fifty existing paired lifecycle cases passed with actual Element Plus queued callbacks, removal before preparation, out-of-order compression and rapid reset/patient changes. [medicalUploadLifecycle.test.mjs][upload-en]. |
| 18 | OCR parsing and supersession | Complete | Six paired item-preservation checks and upload lifecycle checks preserve distinct/conflicting items and reject obsolete OCR results. OCR recognition accuracy was not evaluated. [medicalRecordItems.test.mjs][ocr-en]; [medicalUploadLifecycle.test.mjs][upload-en]. |
| 19 | Preview URLs and compression cleanup | Complete | Twenty-two paired tests verify finish-once original-file fallback and URL cleanup after decoder/canvas exceptions; later files can continue. Real camera/codec performance was not measured. [imageCompression.test.mjs][compress-en]; [medicalUploadLifecycle.test.mjs][upload-en]. |
| 20 | Upload retry and disabled controls | Complete | Session-bound callbacks, keyed uploader replacement and preparation/save locks passed 122 new medical-editor cases, 56 existing medical cases and 60 independent close-timing checks. [medicalEditorLifecycle.test.mjs][edit-en]; [medicalUploadLifecycle.test.mjs][upload-en]. |
| 21 | Nutrition assessment empty and failed states | Complete | Existing assessment checks passed for cleared context, stale previews, delayed validation, failed-save retry and unmount. No assessment implementation change or new backend authorization review was included. [nutritionPatientContext.test.mjs][nutrition-en]. |
| 22 | Nutrition diary dates and totals | Complete | Explicit local-day reset survives real form validation and patient round trips. Historic dates, entered quantities, totals and units remain unchanged. [diaryDateAndDisplay.test.mjs][date-en]; [nutritionDiaryContext.test.mjs][diary-en]. |
| 23 | Medication reminders and scheduling | Complete | Eighty-six permanent paired tests plus 54 independent cases cover stale session/patient/name results, permission changes and persistence failures. Due, snooze, status and dose semantics are unchanged. [notificationLifecycle.test.mjs][notify-en]. |
| 24 | Missing versus zero health values | Complete | Valid diary fluid intake 0 displays as 0 ml and BP standard deviation 0 remains visible. Compiled table-slot checks preserve missing/invalid placeholders, raw precision and units. [diaryDateAndDisplay.test.mjs][date-en]. |
| 25 | Care journey details and actions | Complete | Prescription, appointment, plan and rehabilitation saves preserve replacement drafts, including identical appointment reselection. New paired cases and independent adversarial checks passed; clinical/emergency handlers are unchanged. [careEditorLifecycle.test.mjs][care-en]. |
| 26 | Chart chronology and empty data | Complete | Six additional paired source-executing checks preserve sort order, aligned series, stable ties, missing-time handling and source input. Selected measurement kinds have accurate empty states. [chartAccessibility.test.mjs][chart-en]; [VitalsTrendPanel.vue][vitals-source-en]. |
| 27 | Chart legends labels and units | Complete | Application chart/legend colors agree and selected-kind availability is explicit; raw readings, unit conversion and reference series remain unchanged. Actual ECharts engine checks passed. [chartAccessibility.test.mjs][chart-en]; [appearanceChart.test.mjs][appearance-en]. |
| 28 | Table sorting filters and pagination scope | Complete | Medical lists, local trends and item-name reads reject stale results/errors/finalizers while preserving local/global selector independence. Sorting controls passed; administrative audit pagination was not exercised. [medicalListLifecycle.test.mjs][list-en]; [medicalReadLifecycle.test.mjs][read-en]. |
| 29 | Chart resizing and disposed instances | Complete | Actual ECharts checks preserve zoom and legend state through live motion changes, sparse replacement and clear/reused IDs. Listeners and export-created charts/nodes have targeted cleanup coverage. [chartAccessibility.test.mjs][chart-en]; [healthReportContext.test.mjs][report-en]. |
| 30 | Print and export appearance | Complete | Four additional paired snapshot/popup checks and 82 paired appearance/export regressions passed. Source/component checks cover print styling and cancellation cleanup; paper pagination and actual authenticated downloads remain untested. [healthReportContext.test.mjs][report-en]; [appearanceChart.test.mjs][appearance-en]. |
| 31 | Six palettes and semantic contrast | Complete | Amber chart graphics meet 3:1 on tested white/sand card surfaces and remain readable in dark mode. All six themes were visually sampled; no full theme/locale/size cross-product or accessibility certification is claimed. [chartAccessibility.test.mjs][chart-en]; [healthAppearance.test.mjs][healthappearance-en]. |
| 32 | Keyboard controls and focus return | Complete | Localized icon labels, guide step focus, Escape precedence and safe focus return passed paired/component checks. Public EN/CN guide keyboard flows were observed separately. [accessibilityControls.test.mjs][a11y-en]; [accessibleInteractions.test.mjs][demo-a11y]. |
| 33 | Dialog semantics and accessible names | Complete | The guide has non-modal semantics, real modal Escape precedence and inert late callbacks; column controls use matching visible names. Screen-reader behavior was not exercised. [accessibilityControls.test.mjs][a11y-en]. |
| 34 | Large text and reduced motion | Complete | Post-fix EN/CN public charts passed desktop/narrow normal/large-text checks. Reduced-motion preference changes and listener cleanup passed component tests; real OS motion settings were not exercised. [chartAccessibility.test.mjs][chart-en]; [accessibilityControls.test.mjs][a11y-en]; [reducedMotion.test.mjs][demo-motion]; [chartResponsive.test.mjs][demo-chart]. |
| 35 | Appearance preference persistence | Complete | Targeted preference reads/writes tolerate failure, live large text still applies, and a failed convenience write cannot leave a successfully saved measurement draft open. Fully storage-disabled authenticated use is not established. [preferenceRecovery.test.mjs][preferences-en]. |
| 36 | Narrow-window navigation | Complete | At measured CSS 502×757, EN/CN navigation and large-text controls fit; horizontal navigation reaches the final item and keyboard routing focuses main content. No physical-phone or narrower browser claim. [responsive.test.mjs][demo-responsive]; [accessibleInteractions.test.mjs][demo-a11y]. |
| 37 | Narrow forms and date pickers | Complete | Public measurement/date dialogs fit; invalid saves were blocked and Cancel/Escape restored focus. One fictional reading per locale added one visible row and changed the chart; reset restored baseline. Hidden totals were not verified. [accessibleInteractions.test.mjs][demo-a11y]; [responsive.test.mjs][demo-responsive]. |
| 38 | Narrow table access | Complete | Observed EN/CN recent-reading tables showed all four columns at 429 px width/scrollWidth without clipping inside the 502 px viewport. This does not cover every authenticated application table. [responsive.test.mjs][demo-responsive]. |
| 39 | Narrow chart readability | Complete | The original approximately 7 px rendered labels were corrected. Post-fix charts measured 421×240 narrow and 815×240 desktop; 14/18 px fonts produced 16/20 px glyph heights with contained labels and no page overflow. [chartResponsive.test.mjs][demo-chart]. |
| 40 | EN and CN application/demo parity | Complete | Bilingual chart/singular-count checks and 38 focused/56 independent guide checks passed. Live EN/CN guide QA verified absent consultation, empty review queue, no one-click tasks, patient changes, completion, measurement rerender, connected targets and focus return. Fixtures were reset afterward. This is affected-demo coverage, not authenticated application end-to-end validation. [auditGuideParity.test.mjs][demo-parity]; [statsPlural.test.mjs][demo-plural]; [guideConsultationContext.test.mjs][demo-guide-context]. |
| 41 | Production bundle loading cost | Complete | Isolating html2canvas from the eager vendor graph defers 47,225 gzip bytes EN / 47,231 CN. Total application gzip grows 615/567 bytes. Static-import resolution and independent rebuilds passed; browser load time was not measured. [buildChunking.test.mjs][chunk-en]. |
| 42 | Polling overlap and visibility | Complete | Monitoring coalesces redundant automatic reads; Family full/task medication-intake reads share result ownership. Explicit manual/patient/range/post-write refreshes retain priority. Fifty-four permanent paired and 118 independent cases passed. [pollingOwnership.test.mjs][poll-en]. |
| 43 | Corrupt or unavailable browser storage | Complete | Obsolete column selections fall back without changing valid selection semantics; preference failures preserve live UI and same-instance notification history. Demo Finish/Reset/valid branding now also recover from synthetic persistence failures with truthful warnings. Actual browser checks cover normal storage only. [preferenceRecovery.test.mjs][preferences-en]; [notificationLifecycle.test.mjs][notify-en]; [storageRecovery.test.mjs][demo-storage]. |
| 44 | Large synthetic lists and repeated operations | Complete | The baseline repeated linear scan is replaced by a computed index preserving strict first-match/NaN/visibility semantics and Vue reactivity. At 2,000 appointments/questions, 2,001,000 baseline comparisons become 2,000 first-build ID reads plus keyed lookups; cached reads do not rebuild the index. Twenty-two dedicated cases and independent review passed. Browser/real-list performance is untested. [careAppointmentLookup.test.mjs][lookup-en]; [CareCenter.vue][care-source-en]. |
| 45 | Measured observation and tracked resources | Complete | Two segments measured 6 h 16 m 41.606 s, 1,506 cycles and 99,785 assertions. Segment 2 ended naturally at 21:52:00.007 UTC with final tracked teardown verified. The earlier interruption, 29 m 41.601 s gap and unverified first-segment final teardown remain explicit. No continuous eight-hour or universal leak-free claim. |
| 46 | Independent final code review | Complete | Cross-batch integration and the final storage delta passed 28 independent scenario groups / 490 assertions, including actual compiled chart integration; no introduced regression or remaining blocker was identified in scope. Three pre-existing demo storage failures were repaired and rechecked. The separate final regression, source/release reconciliation and observation also completed, as recorded below. [storageRecovery.test.mjs][demo-storage]; [chartAccessibility.test.mjs][chart-en]. |
| 47 | Final five-suite regression | Complete | Fresh final verification ran 21:52:14.792–21:52:44.970 UTC on 2fc8c21. All 2,476 tests passed: 233 + 233 backend, 902 + 909 frontend and 199 demo; no failed, canceled or skipped cases. |
| 48 | Final builds and source identity | Complete | Both final production builds and demo syntax/parity/diff checks passed. All 982 source fingerprints were unchanged during the run and matched the release snapshot; runtime commit/tree were 2fc8c21/a8844aa and the checkout was clean. Existing large-chunk warnings remain. |
| 49 | Exact-commit CI Pages and deployed QA | Complete | Main, successful exact-commit CI/Pages and both package steps were rechecked at 21:52:42.289 UTC. Canonical HTML and its declared assets matched 2fc8c21 in all 12 cases through 21:54:03.977 UTC. Earlier affected-flow browser checks retain their measured limits. |
| 50 | Delayed release observation and reconciliation | Complete | Observation reached the original 21:52 cutoff and ended naturally at 21:52:00.007 UTC. Fresh regression and canonical release checks then passed; all 50 objectives were reconciled against their evidence. Measured duration excludes the recorded gap and does not equal the entire eight-hour work window. |

## Actual browser evidence

The real browser checks used the public, backend-free demo in desktop Chromium with fictional fixtures. The first pass at **16:10–16:20 UTC** and form/save addendum at **16:27–16:32 UTC** covered measured CSS viewports **502×757** and **1180×757**. The corresponding native window sizes were 510×848 and 1188×848; window size is not viewport size.

- Narrow navigation, large-text controls, measurement/detail dialogs, required-date validation and the native date-picker popup fit. Cancel/Escape and guide flows returned focus to their openers
- One valid fictional measurement per locale added one visible recent-reading row and changed the chart. Reset restored the visible table and chart exactly. The seven-row window does not establish a hidden total count. The CSV download-event wait timed out and returned no file path; no actual browser download error was observed, so file completion/content remain unverified rather than a confirmed export failure
- All six English themes were visually inspected at desktop, and all six Chinese themes at narrow size with large text. Additional dark/warm-beige checks were made, but the full six-theme × two-locale × two-size matrix was not exercised
- The initial narrow chart labels rendered at approximately 7 CSS px despite large-text mode. On the f50fd19 follow-up, **both locales × desktop/narrow × normal/large text** passed: charts were 421×240 narrow and 815×240 desktop, matching their viewBox; label fonts were 14/18 px with measured glyph heights 16/20 px; point markers were 7×7 px. Labels stayed contained and no page-level horizontal overflow was observed
- The chart follow-up also passed 7/30-day ranges, patient redraws, route-away/back, reset and focus flows; the English singular-count issue was resolved
- **Guide follow-up on 78de6e3, 17:39–17:47 UTC:** both public locales passed the affected flows. Patient/Doctor guidance for a patient without a conversation selected the existing patient control; switching patients while the guide stayed open restored the correct message-field target without creating a conversation or sending a message
- Clearing the fictional review queue and completing the available one-click care tasks updated guide text and its sole connected target while preserving the current step. A pending measurement remained correctly distinguished from a one-click task. One additional fictional measurement per locale reanchored the guide after rendering, returned focus to the measurement opener and completed the fixture’s remaining task
- Both locales passed rapid Next/Previous plus patient changes, Pause/Escape cleanup, restart and focus return. Narrow large-text coverage used CSS 502×757, with desktop spot-checks at 1180×757. A tall task group remained vertically scrollable while guide controls stayed visible; this was not an exhaustive guide/theme/viewport matrix
- Reset restored each locale’s fictional baseline after the guide checks, including three pending reviews, task count 1/4 and latest reading 126/82. Final browser state was desktop 1180×757, dark theme, normal text and no highlighted guide target. No new product defect was observed in the affected-guide scope; the observation and final verification are separately recorded in this report

- **Normal-storage smoke on 2fc8c21, 18:15–18:19 UTC:** both locales completed all five guide steps with Finish, removed highlights, returned focus and restarted successfully. Reset during an open guide restored the default role/patient, overview and fixtures while retaining the immediately selected theme and text size
- Valid fictional branding applied immediately and persisted across ordinary reload in both locales; Reset followed by reload restored the default brand/title. Other branding fields stayed unchanged; invalid-input validation is covered by the separate synthetic checks. Branding rerender left focus on the document body; no stronger branding-form focus claim is made
- Narrow Chinese normal/large-text confirmations were contained at CSS 502×757. A targeted narrow chart check retained 421×240 geometry, seven markers and readable 14 px labels. These checks did not repeat the full chart/theme matrix
- All fictional branding and fixtures were restored. Final browser state was desktop 1180×757, default branding, dark theme, normal text, hidden guide and no highlighted target. **Unavailable browser storage was not exercised in this smoke; its failure/recovery evidence remains synthetic**

These observations do not cover a physical phone, actual 320/390 px browser viewport, authenticated Vue application, real camera, notification delivery, screen reader or operating-system reduced-motion setting. Source-level narrow-size and motion tests are not represented as those browser/device observations. Screenshots support the observations; no new screen-recording deliverable is claimed.

## Measured segmented stability observation

The component observation ran the actual EN/CN diary, health-report and BP-entry logic with Vue reactivity. Diary/report instances were retained across cycles and deliberately replaced after lifecycle checks. BP used its production entry template with real Element Plus form/validation behavior and a synthetic host tree. APIs, report DOM/canvas/URL bindings, leaf controls and test data were controlled adapters. The 15-second cadence was a synthetic workload rate, not a model of human use or a browser/server load test.

- **Segment 1:** **15:05:36.800–15:20:07.000 UTC**, measured **870.200 seconds**, **58 completed cycles** and **3,830 assertions**. It was interrupted during a source-update break. Its last idle boundary passed resource checks, but **final teardown was not verified**; four Vue scopes, two BP mounts and the observation heartbeat remained in that last running snapshot
- **Gap:** **1,781.601 seconds (29 m 41.601 s)** from the last verified observation to resumption, excluded from measured runtime
- **Segment 2:** **15:49:48.601–21:52:00.007 UTC**, measured **21,731.405 seconds (6 h 02 m 11.405 s)**, **1,448 cycles** and **95,955 assertions**. It ended naturally at the requested boundary and passed final in-process teardown checks

Across both segments: **22,601.606 measured seconds (6 h 16 m 41.606 s), 1,506 completed cycles and 99,785 assertions**. Totals use unrounded monotonic measurements. There were **zero unexpected product, test-invariant or cleanup-assertion failures**, with the first interruption recorded separately. Neither segment recorded an observation-heartbeat gap over 60 seconds; the longest intervals were 30.079 and 30.075 seconds. No monitored source change was detected within either segment, and all eight segment-2 source hashes matched the final runtime code. Earlier runtime does not retrospectively cover changed code or every application component, and these were not eight uninterrupted hours.

The workloads covered reordered reads, interrupted saves, immutable payloads, replacement-lock ownership, failed-save retry, duplicate suppression, report conversion interruption, obsolete summary rejection and URL/chart cleanup. They included **296 pending-work unmount checks** and **1,002 expected chart-render-failure cleanup checks** across the measured segments. Short preflight runs are excluded from duration, cycle and assertion totals.

Segment 2’s final tracked balances were:

- Timers: **185,637 created = 135,833 fired + 49,804 canceled**; the separate observation heartbeat was also cleared
- API/validation operations: **72,972 started / 72,972 completed**
- Vue scopes: **292 created / 292 stopped**; BP mounts: **146 / 146**
- Export URLs: **2,896 created / 2,896 released**; chart instances and temporary chart nodes: **964 / 964** each
- Final live tracked timers, API operations, scopes and BP mounts: **zero**; URL/chart/node balances were also zero

This verifies teardown for segment 2 only; it does not erase segment 1’s unverified teardown. Idle boundaries intentionally retained the live component scopes/mounts before final cleanup.

Memory was sampled after explicit garbage collection. Segment 1’s heap was **25.63→27.31 MiB**, with sampled RSS **106.39–111.20 MiB**. Segment 2’s heap was **25.67→25.65 MiB**, range **25.25–28.32 MiB**; sampled RSS was **106.84–113.67 MiB**, ending at **113.55 MiB**. These process samples and tracked balances are diagnostic, not a production performance benchmark or proof of universal leak freedom.

## Performance measurements and remaining opportunities

- **Deferred capture dependency:** for the f50fd19 capture-chunk change, controlled archived-source before/after builds and an independent static-import analysis reproduced initial JS/CSS gzip reductions of **47,225 bytes EN** (533,181→485,956) and **47,231 bytes CN** (542,125→494,894). The lazy dialysis closure retains the capture dependency, and all emitted static imports resolve. Whole-application gzip increases **615/567 bytes** due to splitting. These are sums of per-file level-9 gzip bytes, not HTTP transfer, FCP, LCP or browser-speed measurements. [Permanent chunk tests][chunk-en]
- **Remaining bundle size:** existing Element Plus/ECharts large-chunk advisories remain. Further import/tree-shaking work is an optional measured optimization, not required by a demonstrated loading failure
- **Large-list lookup:** the original expression-only audit established 5,050 / 500,500 / 12,502,500 comparisons for equal 100/1,000/5,000-row synthetic lists. The published computed index removes repeated linear scans. A controlled 2,000-row before/after check records 2,001,000 baseline comparisons versus 2,000 first-build ID reads, 2,000 each Map has/set/get operations, and one NaN-exclusion check per appointment; unchanged cached reads perform zero ID reads and 2,000 keyed gets. Map internals were not instrumented
  - First duplicate wins, including an empty title; ID types/identity are not coerced, NaN is excluded, +0/-0 match, and original link visibility/fallback remain. Reactive replacement, insertion/removal/reordering, ID/kind changes, question links and title updates passed 22 dedicated checks plus independent review. [Permanent lookup tests][lookup-en]; [Current lookup source][care-source-en]
  - In the final Node benchmark, fresh 2,000-row reactive runs measured median 429.407→4.335 ms EN and 420.268→3.392 ms CN; cached runs measured 426.855→0.596 ms EN and 424.588→0.658 ms CN. There was one warm-up and three samples per condition. These final measurements include computed projections/index construction and must not be equated with the earlier expression-only audit’s timings. No browser/DOM/frame-time, actual list-size distribution or user-visible speedup claim is made
- **Preference and polling scope:** an unsupported saved care-mode string can still leave neither role selected; normalization is a remaining bounded usability opportunity. CareCenter hidden-tab polling remains unchanged because no naturally repeating request overlap was demonstrated
- **Broader validation:** real-device sizes, screen readers, printer pagination, actual export downloads, live backend integration and notification delivery require separate environment-specific checks

## Boundaries and remaining limitations

- The published Pages site is a synthetic, backend-free demonstration; the authenticated Vue application and backend are separate. No backend service was deployed by this review
- Backend regression/package results use test dependencies and synthetic data; they are not a new backend authorization audit, penetration test, security certification or clinical validation
- No real patient data was used. No clinical thresholds, dose rules, diagnoses, AI interpretation or clinical advice were changed or validated
- Client request ownership suppresses obsolete UI effects. It does not undo a server write already dispatched
- Actual browser widths are the measured CSS sizes above. Smaller source-test fixtures do not establish actual 320/390 px or physical-phone coverage
- Storage recovery is bounded to the tested preferences and same-instance notification history. No fully storage-disabled authenticated session, cross-tab or reload deduplication guarantee is claimed
- **Completion timing:** observation reached the original **21:52 UTC** boundary and ended at **21:52:00.007 UTC**. The fresh final regression ended at **21:52:44.970 UTC** and canonical assets were verified through **21:54:03.977 UTC**; final evidence reconciliation followed. All 50 objectives are complete with the scope limits above

[nav-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/healthAnalysisNavigation.test.mjs
[patient-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/currentPatient.test.mjs
[specialty-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/patientSpecialtyNavigation.test.mjs
[dialysis-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/dialysisPatientContext.test.mjs
[bp-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/bpPatientContext.test.mjs
[diary-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/nutritionDiaryContext.test.mjs
[report-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/healthReportContext.test.mjs
[care-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/careEditorLifecycle.test.mjs
[edit-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/medicalEditorLifecycle.test.mjs
[upload-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/medicalUploadLifecycle.test.mjs
[ocr-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/medicalRecordItems.test.mjs
[compress-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/imageCompression.test.mjs
[nutrition-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/nutritionPatientContext.test.mjs
[date-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/diaryDateAndDisplay.test.mjs
[notify-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/notificationLifecycle.test.mjs
[chart-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/chartAccessibility.test.mjs
[appearance-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/appearanceChart.test.mjs
[healthappearance-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/healthAppearance.test.mjs
[a11y-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/accessibilityControls.test.mjs
[preferences-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/preferenceRecovery.test.mjs
[list-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/medicalListLifecycle.test.mjs
[read-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/medicalReadLifecycle.test.mjs
[poll-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/pollingOwnership.test.mjs
[chunk-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/buildChunking.test.mjs
[care-source-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/src/views/CareCenter.vue
[upload-source-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/src/views/MedicalRecordManager.vue
[vitals-source-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/src/components/VitalsTrendPanel.vue
[demo-responsive]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/responsive.test.mjs
[demo-chart]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/chartResponsive.test.mjs
[demo-a11y]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/accessibleInteractions.test.mjs
[demo-motion]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/reducedMotion.test.mjs
[demo-parity]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/auditGuideParity.test.mjs
[demo-plural]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/statsPlural.test.mjs
[lookup-en]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/frontend/tests/careAppointmentLookup.test.mjs
[demo-guide-context]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/guideConsultationContext.test.mjs
[demo-storage]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/storageRecovery.test.mjs
