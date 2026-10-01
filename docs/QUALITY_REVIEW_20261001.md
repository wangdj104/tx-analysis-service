# Ten-round functional and UI review — 2026-10-01

[简体中文](../cn/docs/QUALITY_REVIEW_20261001.md) · [English demo](https://wangdj104.github.io/tx-analysis-service/) · [中文演示](https://wangdj104.github.io/tx-analysis-service/cn/)

## Result

Ten bounded review rounds are complete. Nine rounds produced evidence-backed code fixes; round 10 rechecked the combined release and records the results. No extra product change was made merely to fill the final round.

Starting code: `ad10338`. Final reviewed application code: `f62197911b0ab7a974279ea678a2ee2ccec363d7`.

Both language editions were updated together. Main was advanced without force pushes. Each code batch received independent review and passed its exact-commit CI. Demo-changing commits were published through GitHub Pages and the final bilingual release was checked live.

## Round results

1. **Patient context isolation** — [71d0ce5](https://github.com/wangdj104/tx-analysis-service/commit/71d0ce5200947cbb933a31bdf43afb03ca1d5d2b)
   - Clear unsaved quick measurements when changing patients
   - Reject stale invitation/history responses, including A→B→A and reverse completion order
   - Preserve a new patient's draft when an old save completes; reload doctor chart context when opening another patient's note/plan

2. **Interrupted consultations and dialogs** — [34e788e](https://github.com/wangdj104/tx-analysis-service/commit/34e788e0f411c71b96ac112049162ddf13751063)
   - Latest room-open/create intent wins in both response orders
   - Newly created room URL stays in sync; failed creation preserves active-room polling
   - Stale note/plan saves cannot close newer dialogs; repeated saves are guarded

3. **Medication inventory** — [6a95d2e](https://github.com/wangdj104/tx-analysis-service/commit/6a95d2e00492eb2a10ad60d8353703633bcf3a03)
   - Deduct the current intake dose instead of an old prescription quantity after reminder edits
   - Preserve current medication/unit matching, explicit actual quantities, multi-word units and idempotence
   - Real database workflows reproduced the old 2-tablet intake deducting only 1 tablet

4. **Glucose units and measurement validation** — [62820f1](https://github.com/wangdj104/tx-analysis-service/commit/62820f171a7874876c8115eec441eec57f43374b)
   - Compare and chart equivalent mg/dL/mmol/L values consistently without altering stored values
   - Fix unit-aware editor limits, missing/type-specific values, positive glucose validation and post-meal aliases/low readings
   - Preserve partial updates and missing/whitespace-only legacy units; unsupported units remain unassessable rather than falsely normal
   - Existing clinical thresholds remain unchanged

5. **Patient summaries and emergency details** — [4a34a6d](https://github.com/wangdj104/tx-analysis-service/commit/4a34a6d9ba63d9c818b1c5008fa7b08c8a5ee70f)
   - Scope appointments/notes and patient/family review counts; show localized empty states
   - Exclude expired appointment dates from upcoming cards; preserve the doctor's global review queue
   - Prevent obsolete emergency detail responses replacing the latest selected event

6. **Keyboard access and draft preservation** — [bc612e1](https://github.com/wangdj104/tx-analysis-service/commit/bc612e1e8c281e0d02c84a976b822addbd9b4cff)
   - Incoming demo replies update the transcript without replacing an unsent draft, focus or selection
   - Make the interactive tour coherently non-modal, with heading focus, Escape and opener restoration
   - Restore logical keyboard focus after rerenders and dialog closure

7. **Charts and branding controls** — [92962e0](https://github.com/wangdj104/tx-analysis-service/commit/92962e087ccd7f4b197cd3ac188d2aba78ae9828)
   - Keep valid extreme 260/30 readings inside the plot
   - Use actual 7/30-day windows, including multiple readings per day and explicit empty periods
   - Synchronize background picker/hex fields and give controls separate accessible labels

8. **Chinese PDF export** — [9555be7](https://github.com/wangdj104/tx-analysis-service/commit/9555be7f74ae714325c9c86ce52e3987cf953c96)
   - Reproduce and fix PDFBox failure on installed CFF-based Noto CJK collections
   - Select compatible TrueType fonts with required CJK glyph coverage; skip unsafe mixed-coverage collections
   - Install WenQuanYi Micro Hei in backend images, document optional `REPORT_PDF_FONT_PATH`, and return an explicit setup error if required glyphs are unavailable
   - Licensed portable fixtures verify Chinese text extraction and rendered pixels; the complete official WQY font was also exercised successfully

9. **Demo audit, tour and bilingual release integrity** — [f621979](https://github.com/wangdj104/tx-analysis-service/commit/f62197911b0ab7a974279ea678a2ee2ccec363d7)
   - Record successful core care/review actions with actor, patient and target; rejected/no-op actions do not create success entries
   - Restrict tour steps to each role's available pages and handle role/reset interruption safely
   - Version changed scripts and run the separate Chinese demo tests in CI and Pages

10. **Integrated release review**
    - Recheck the whole change range, rerun complete suites/builds, verify the published versions and live workflows
    - No additional critical or important integration issue found; this report records the verification and limits

## Final verification

Fresh final-code runs all passed: **771 tests** across five suites:

- English backend: 203/203
- Chinese backend: 203/203
- English frontend: 133/133
- Chinese frontend: 140/140
- Static demo: 92/92 combined, including both editions
- Both production frontend builds passed
- JavaScript syntax, bilingual parity and `git diff --check` passed

The final application commit passed [CI](https://github.com/wangdj104/tx-analysis-service/actions/runs/36879145242) and [Pages deployment](https://github.com/wangdj104/tx-analysis-service/actions/runs/36879145114).

Live cloud Chromium checks used measured CSS viewports of **375, 390 and 768 pixels** in both languages through ordinary window resizing/browser zoom. They covered patient-specific empty summaries, received-reply draft/focus retention, tour focus/Escape/role interruption, measurement dialog save/cancel, extreme chart coordinates, large text, branding synchronization, visible audit entries and updated `20261001-r9` scripts. No page-wide horizontal overflow was observed in the checked views.

## Boundaries and remaining limitations

- These were cloud Chromium viewport checks, not physical-phone testing
- The live site is the synthetic, backend-free demo. A production server/browser/database end-to-end session was not run
- Backend suites used JDK 17, H2 and controlled test dependencies. No production database, real health data, credentials, paid AI calls or permission/schema changes were involved
- Docker recipes were updated; a full Docker image build was not available in this environment
- Existing frontend large-chunk warnings remain
- Inventory runout estimates still follow the active prescription; deductions follow the current intake. Ambiguous units do not trigger inferred stock deduction unless an explicit quantity is supplied
- A consultation creation already sent to the server is not rolled back when the user navigates elsewhere; stale UI activation is suppressed. No new backend idempotency API was introduced
