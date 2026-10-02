# Patient workflow safety and verification review 2026-10-02

[简体中文](../cn/docs/QUALITY_REVIEW_20261002.md) · [English demo](https://wangdj104.github.io/tx-analysis-service/) · [中文演示](https://wangdj104.github.io/tx-analysis-service/cn/)

## Result

The current bilingual release fixes patient-context, interrupted-save, upload-lifecycle and normal patient-profile editor defects, strengthens nutrition assessment access checks, and corrects a demo chat-scroll issue found during screen recording. Clinical thresholds, medication dose logic and clinical guidance were not changed.

Starting version: `c269992202a08ebe568a1cc1f331ae9137983b5a`. Current reviewed application code: `a12cf0951cc0e24c6a2aac0e5efdcba5161cbd74`; tree: `b8ad9df697d7a0491f51768364478b4cfbceb273`.

The requested review window was **01:21–11:21 UTC on October 2**. Measured observations and final checks are timestamped separately below; this is not a claim of ten hours of uninterrupted testing.

## Changes and regression coverage

1. **Patient-bound medical and medication drafts** — [e81aead](https://github.com/wangdj104/tx-analysis-service/commit/e81aeadb6eb64176c62379e92e1802273b97f7fb)
   - Invalidate stale medical and medication uploads/OCR after patient changes, including A→B→A and superseded requests
   - Refresh medication/log choices for the selected patient; guard their list responses and patient-specific medication dialogs
   - Snapshot the originating patient's clinical values; guard duplicate saves and prevent late completion from clearing a replacement draft or releasing its save lock
   - Preserve failed-save drafts for retry; lock the originating editor during submission

2. **Upload queue and resource lifecycle** — `e81aead`
   - Keep visible file removal and submitted bytes in sync, including actual Element Plus queued callbacks, multi-selection and removal before a pending callback
   - Ignore obsolete camera/album picker and compression results, retain selected file order, enforce the ten-file limit and wait for pending preparation
   - Reuse preview object URLs and release them on removal/reset/unmount; cancel obsolete saves before transmission during attachment conversion

3. **Nutrition assessment editing and authorization** — `e81aead`
   - Bind drafts, previews, validation and saves to the current patient and dialog; reject stale previews and repeated submissions while preserving retryable drafts
   - Authorize reads/updates against the stored record, preserve its owner, reject patient reassignment and avoid inserting a missing update target
   - Apply the existing assessment permission to actual `/api/nutrition` routes using Spring MVC's matched route, including matrix-parameter variants
   - H2/MyBatis and MockMvc regressions cover assigned/unassigned doctors, read/write or revoked grants, owner preservation, patient binding and route permissions

4. **Clinical profile, reminders and backup recovery** — `e81aead`
   - Open the clinical dialog in an explicit loading state; failed, stale or mismatched reads cannot become a blank-profile overwrite
   - Guard reminder validation/save and row actions against duplicate clicks and stale patient/dialog completions
   - Require a successful preview of the selected backup before restore; keep download/restore single-flight and allow explicit retry after failure

5. **Literal demo text and incoming replies** — `e81aead`, [639e3de](https://github.com/wangdj104/tx-analysis-service/commit/639e3de1c1de5beaea8ab9baefa9e8fce53bedd5)
   - Render care-plan and handover input as literal text, including markup-like characters
   - Follow incoming replies when already near the transcript bottom; preserve earlier-history scroll position, unsent draft, focus and selection
   - Opening the room and sending a message show the latest transcript; regressions cover both language editions

6. **Normal patient-profile Add/Edit** — [a12cf09](https://github.com/wangdj104/tx-analysis-service/commit/a12cf0951cc0e24c6a2aac0e5efdcba5161cbd74)
   - Separate specialty-catalog and selected-role readiness so catalog completion cannot enable Save before the patient's roles arrive
   - Use dialog epochs to reject cancelled Add validation and stale same-patient reopen responses; old saves cannot close or unlock a replacement draft
   - Copy the create/update operation and payload, and lock before validation to prevent duplicate dispatch
   - Independent review caught and corrected an explicit Element Plus `disabled=false` overriding the parent form lock; regression coverage uses compiled templates, Vue SSR and the actual disabled-state hook
   - Only the bilingual normal-profile view logic and paired tests changed; the separate clinical-dialog flow is byte-identical to `639e3de`

## Recorded release verification

Recorded passing runs cover **1,171 tests** across five suites:

- English backend: 233/233
- Chinese backend: 233/233
- English frontend: 298/298
- Chinese frontend: 305/305
- Static demo: 102/102 combined, including both editions
- Both production frontend builds passed; existing large-chunk warnings remain

The safety batch passed its exact-commit [CI](https://github.com/wangdj104/tx-analysis-service/actions/runs/36957523480) and [Pages deployment](https://github.com/wangdj104/tx-analysis-service/actions/runs/36957523429). The chat follow-up passed [CI](https://github.com/wangdj104/tx-analysis-service/actions/runs/36958416396) and [Pages deployment](https://github.com/wangdj104/tx-analysis-service/actions/runs/36958416310). The profile fix passed exact-commit [CI](https://github.com/wangdj104/tx-analysis-service/actions/runs/36963900070). Published `a12cf09` has the same tree as locally tested `a0dcaf6`; the original observation retains its local commit identity. No demo bytes changed from `639e3de`, so the existing Pages deployment and recordings remain unchanged-flow evidence; no new Pages run was expected. The final full regression on `a12cf09` ran at **11:15:18.168–11:15:41.949 UTC**: all 1,171 tests, both production builds, demo syntax/parity and clean-tree/source-integrity checks passed. This report adds documentation only. See the [machine-readable verification summary](verification/20261002.json) for exact per-check times, counts and source hashes.

## Actual browser and recording evidence

Four continuous, real-time screen recordings were encoded as H.264 at 15 fps, with browser-window cropping and appended captions. They were not assembled from screenshots. Recordings are retained privately rather than published in this repository.

- English care-plan and family-handover text retained literal `<…>` and `&` characters
- Chinese measurement entry rejected 90/100, kept the dialog available for correction, then accepted 126/78; patient-without-consultation and synthetic-reply flows were exercised
- Narrow layout, large text, visible validation errors and Cancel were exercised in a **508 × 848 browser window**. This is a window size, not a claimed 390-pixel CSS viewport or physical device
- Clips 1–3 show `e81aead`. Clip 2 preserves the discovery of a reply arriving below the visible transcript; clip 4 checks the `639e3de` fix
- Supplemental browser checks confirmed near-bottom following and earlier-history preservation; the send-and-scroll action finished in 576 ms, before the simulated reply's 850 ms delay

## Core segmented synthetic stability observation

The probe executes actual English Vue script bodies and Vue reactivity for medical records, consultations and doctor workflows with native timers, including 1,800 ms message and 6,000 ms list polling. APIs, OCR/compression, FileReader, DOM, media and other platform bindings are synthetic adapters. Components are recreated each cycle; this is repeated workflow/lifecycle observation, not one continuously mounted browser session.

- Segment 1: **02:51:23.645–03:02:50.376 UTC**, 17 completed cycles and 1,062 assertions including the partial cycle; measured runtime **686.731 seconds**. Cycle 18 stopped on a harness timing assertion when a 6,250 ms native timeout resumed fractionally early. Preceding product checks passed; the failed partial-cycle snapshot retained one live Vue scope before the process exited
- The original failure evidence was preserved. A condition-based monotonic deadline corrected the harness without changing product code or polling delays
- Segment 2 ran **03:04:30.051–11:15:00.001 UTC**, after a **99.675-second gap**. It completed 736 cycles / 44,896 assertions in **29,429.950 seconds (8 h 10 m 29.950 s)**, with zero product or harness failures and no recorded internal observation gaps. Monitored source hashes match across the segments

Across both core segments: **753 completed cycles, 45,958 assertions including the partial cycle, zero product failures and one harness-only failure**. Summed measured runtime is **30,116.681 seconds (8 h 21 m 56.681 s)**, excluding the 99.675-second gap. All 753 completed-cycle boundaries had zero tracked timers, pending adapters, Vue scopes and preview URLs.

Segment 2 RSS increased from 57,225,216 to 72,458,240 bytes and heap used from 10,905,888 to 19,777,264 bytes; sampled peaks were 72,843,264 and 19,808,936 bytes respectively. Final tracked cleanup counters were zero. These samples report observed growth without diagnosing a product leak or asserting leak freedom.

The interruption remains part of the result. Completed-cycle resource checks do not justify a blanket zero-resource or leak-free claim, and the two segments must not be described as a ten-hour uninterrupted soak.

## Separate profile-editor stability observation

The later profile change received its own **04:04:30.971–04:14:31.026 UTC** observation: **600.055 seconds**, 289 batches / 578 edition-scenario cycles, 3,496 assertions and 1,954 synthetic API calls. All eight scenario families passed: cancelled validation, same-patient reopening, old save/new draft, duplicate-save snapshots, catalog/selection overlap, stale read/finally handling, deliberate retry, and old finally/new save.

The same Vue script state and effect scope per language were reused throughout. Final tracked pending requests, timers and scopes were all zero after cleanup. APIs and platform bindings were synthetic; this was not browser/backend end-to-end coverage. This observation overlapped the core probe and has separate totals: core-probe time does not retrospectively cover the new profile code, and the two durations must not be added as elapsed coverage.

## Boundaries and remaining limitations

- Actual browser work covers the backend-free synthetic demo, not a production browser/server/database end-to-end session
- Frontend regressions execute component logic and actual Element Plus handlers with controlled adapters; they do not establish real camera behavior, OCR accuracy or compression performance
- Backend checks use synthetic data and controlled H2/MyBatis/MockMvc dependencies; no production data or real patients were involved
- Browser recordings are desktop window checks, not real-device testing
- Stability resource values are sampled checkpoints and tracked-resource counts, not continuous peak measurements or proof that all memory leaks are absent
