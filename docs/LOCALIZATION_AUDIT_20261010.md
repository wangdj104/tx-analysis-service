# Language-consistency audit — 10 October 2026

## Contract

- The root application and public demo use English interface text; `cn/` uses Simplified Chinese.
- Explicit report-language selection controls report labels, dates and exported presentation. It does not translate clinical source content.
- The combined production deployment serves both frontends from one root API. `Accept-Language` must therefore reach system-message and newly generated-content boundaries in that API; maintaining a separate Chinese source tree alone is insufficient.
- Scheduled analysis uses the record owner's saved language and restores the worker thread's previous locale after each run.
- Patient input, clinician notes, names, medication names, source observations, provenance identities and editable configuration are preserved. Necessary abbreviations, units and stable protocol codes remain available where appropriate.

## Corrected areas

### Response boundaries and source preservation

The Chinese client previously recursively translated every string in ordinary JSON responses. Dictionary collisions could change a person's name or clinical text such as `Normal`, `Doctor`, or `Patient`. Translation now targets the API envelope and identified system-owned display fields. Editable data and medical prose remain unchanged. The backend similarly distinguishes ordinary string data from explicitly generated acknowledgement messages.

Manual timeline entries and consultation/visit text remain source content. Built-in navigation labels, system-generated event descriptions and fixed warnings are localized at their presentation boundary.

### Interface and accessibility copy

Corrected concatenated English words across patient, medication, medical-record, dialysis, nutrition, blood-pressure, reporting and administration views. Reviewed placeholders, validation messages, dialog titles, table controls, tooltips, image/frame descriptions and navigation accessibility labels. Language-sensitive sorting and date formatting use explicit edition locales instead of the browser's incidental locale.

The adjacent dialysis AI-result HTML renderer escapes source text and uses the existing DOMPurify dependency with a restricted formatting allowlist. A hosted Chromium check renders both editions’ actual DialysisManager component through Vite with synthetic API data, real DOMPurify, malicious HTML, ordinary mixed-language narrative and old/new conclusion headings at desktop and mobile widths. Stored text is unchanged.

Visit-summary print documents carry the correct language and title. Care-execution reports retain their chosen output language and original clinical text, while system timestamp/provenance labels are human-readable.

### System generation

Expanded known system-message coverage for validation, permissions, imports and notifications. Generated blood-pressure summaries, complication context and dashboard copy use the request locale.

AI/OCR prompts explicitly distinguish generated prose from original clinical data. New analysis follows the request language; both historic structured-analysis key sets remain parseable. An absent structured conclusion is marked unavailable for review rather than inventing a negative adjustment decision or a zero amount. General-health analysis does not append a dialysis-only conclusion. These changes are tested with synthetic mocked provider responses; they do not establish the quality of a live model's medical output.

### Public demo

Lifecycle, event, follow-up and self/assisted-entry labels are localized for display. Internal state codes and the synthetic care model are unchanged. Mixed-language user-entered notes remain exact. Public-page scripts are cache-versioned for this update.

The static-demo CI job now runs the real Playwright collaboration and report flows in both editions at 1440px and 390px. It checks rendered language, unchanged source notes, roles, dialog flows, horizontal overflow and absence of business network calls, and retains only synthetic PNG screenshots for seven days.

## Verification

Regression tests cover readable UI copy, transport/source preservation, locale defaults, print language, provenance labels, known API system messages, interceptor errors, generated metadata and AI/OCR contracts. Use the Actions run for the exact delivered commit as the authoritative hosted result.

Local commands:

```sh
node --test scripts/tests/*.test.mjs
(cd frontend && npm test && npm run build)
(cd cn/frontend && npm test && npm run build)
node --test demo/tests/*.test.mjs cn/demo/tests/*.test.mjs
node --check demo/app.js
node --check cn/demo/app.js
```

Hosted checks also run both Maven suites, both builds, disposable MySQL/native-browser acceptance and real demo-browser checks. The audit executor has no Maven launcher and cannot start Chromium because its sandbox denies the required socket. Selected Java localization classes can be compiled through the installed compiler module, but those checks are not a full Maven suite or a real-browser pass.

## Deliberate boundaries

- Existing stored medical narratives are not retrotranslated or rewritten. A Chinese interface may correctly display an English source note, drug name, person's name, unit, acronym or original observation.
- API fields, database codes and machine-readable export contracts remain stable. Human-facing labels are distinct from those identifiers.
- No production database migration, production service deployment, real notification or live AI-provider request is part of this audit. Production deployment remains owner-managed.
