# Seventeen UI workflow improvements

Baseline: `d845b993153cb595ca2f0d63b768d0209c39c187`.

This change preserves clinical thresholds, server authorization, data ownership and existing source records. It does not deploy, migrate a database, change grants, or publish to a remote repository.

## Acceptance coverage

1. Today prioritizes quick recording and pending work; completed care history remains available in collapsed sections
2. Latest blood pressure and glucose summaries retain independent timestamps regardless of the history filter
3. Patient choices and submission contexts identify the authorized record by name and record ID
4. Monitoring, readings and nurse tasks distinguish loading, failed, empty and stale-success states, with visible recovery
5. Doctor AI review opens the original authorized analysis and evidence before any decision
6. Care forms and consultation drafts protect unsaved input during dismissals, navigation and context changes; authorization loss clears sensitive content
7. Required care fields show persistent, associated errors and focus the first invalid input
8. Monitoring alerts expose shown/loaded counts and a full authorized alert entry; the existing snapshot cap is 100, so the denominator is not an uncapped total
9. Nurses receive a role-specific guide; mobile search targets the visible control, and unavailable targets are explained
10. History editing focuses the corresponding form and restores the origin after save/cancel, respecting reduced motion
11. Execution receipts use a date/time control with explicit offset and authorized, recognizable existing-record choices
12. Visit summary opens the existing Summary tab directly, retaining current patient and permission fallback
13. Trend data can be read without pointer hover, with the same time scope, units and original values
14. Exports show patient, inclusive dates, data type and privacy scope; filenames use dates rather than patient identifiers
15. Lab trends distinguish not queried, empty, failed/retry and populated results with query context
16. Feature search supports Up/Down and selected-result Enter while retaining IME protection and ordinary keyboard links
17. English action wording and corresponding Chinese terminology are corrected in changed workflows

## Automated verification

Final integrated frontend suites pass: English 1,636/1,636 and Chinese 1,625/1,625, with no skipped tests. Both production builds pass; Vite retains its non-fatal bundle-size warning for chunks above 500 kB. Independent task and whole-branch code reviews pass with no outstanding scoped findings. Care review passed44 independent checks; monitoring/clinical passed21 independent probes plus191 targeted checks per edition; whole-branch integration passed92 mirrored checks. Browser visual acceptance remains blocked below.

Backend regression suites: 766 executed passes per edition, no failures/errors; one existing `CarePlanMysqlIntegrationTest` is skipped per edition. The first unbounded EN JVM was killed with exit 137; the full bounded-heap retry passed. Both backend packages build successfully.

## Browser verification limitation

Fresh desktop/mobile screenshots, six-theme visual checks and live assistive-technology verification have not passed in this environment. Local Chromium cannot create its required socket; the supported cloud browser cannot reach the isolated localhost app server. Synthetic browser harnesses are preserved for a permitted environment. No public server was exposed to work around the boundary.

Do not treat source inspection, deterministic component tests or a successful build as a visual pass. Run the preserved browser workflow in an authorized environment before visual acceptance.
