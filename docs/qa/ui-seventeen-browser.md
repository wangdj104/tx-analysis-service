# Seventeen-workflow browser acceptance

> 2026-10-13: Automated test files and runners have been removed. Test paths, commands and results below describe historical revisions and are not current executable instructions.

The dedicated `ui-seventeen-browser` CI job supplements existing backend, component, demo and care-plan browser checks. The older care-plan browser suite does not establish these seventeen UI acceptance items.

## Coverage

Four portable harnesses render the shipped Vue components with the shipped styles and synthetic API fixtures only:

- `frontend/tests/careDraftDialogBacklog.browser.mjs`: Today hierarchy, patient identity, unsaved care/consultation input and persistent required errors (items 1, 3, 6, 7)
- `frontend/tests/uiSeventeenMonitoring.browser.mjs`: independent latest readings, loading/error recovery, alert counts, history focus, accessible trend values and wording (2, 4, 8, 10, 13, 17)
- `frontend/tests/uiSeventeenClinical.browser.mjs`: patient identity, full AI source, receipt controls, lab query states and wording (3, 5, 11, 15, 17)
- `frontend/tests/uiSeventeenShell.browser.mjs`: patient identity, nurse/mobile onboarding, summary deep link, export scope, keyboard search and wording (3, 9, 12, 14, 16, 17)

Each harness covers English and Chinese at 1365px and 375px, in platform, white, blue, mint, sand and dark themes. API fixtures do not establish live backend authorization correctness; those remain separately tested. No real patient data, credentials, database dump, trace or HAR belongs in the artifacts.

## Running

Install each edition's locked dependencies (`npm ci --prefix frontend` and `npm ci --prefix cn/frontend`) and official Playwright Chromium (`cd frontend && npx --no-install playwright install --with-deps chromium`). Set `UI_SEVENTEEN_EVIDENCE_DIR` to an output directory, run the four scripts from repository root, then run:

```
node scripts/verify-ui-seventeen-browser.mjs "$UI_SEVENTEEN_EVIDENCE_DIR" "$(git rev-parse HEAD)"
```

The collector requires fresh full-SHA evidence, actual `browser` mode, successful DOM assertions, matching source/harness/screenshot hashes, real viewport-sized PNG pixels for each recorded case, no fatal/unexecuted markers, and all 408 item/language/viewport/theme cells. Missing, failed, stale or compile-only results fail the job. A failed harness does not prevent the other three CI steps from providing diagnostics; failures are never converted to a successful job.

Use `--compile-only` for harness build validation when browser execution is unavailable. This is not browser acceptance. The isolated local environment used to prepare these tests could not launch Chromium because socket creation was denied. No browser execution or visual approval is inferred from local compile checks.

## Review

CI uploads synthetic PNGs and per-case JSON under `ui-seventeen-<full SHA>`. Review the actual fresh screenshots for overflow, clipping, focus visibility, native controls and theme contrast before claiming visual acceptance. Automated DOM/screenshot generation is not live assistive-technology testing and does not imply a screen-reader pass. The full evidence must refer to the same checkout as the CI run.
