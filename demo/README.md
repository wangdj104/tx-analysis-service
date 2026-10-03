# Interactive Static Demo

Open [index.html](index.html) locally or use the [public demo](https://wangdj104.github.io/tx-analysis-service/). The demo requires no account, application server, or database. It uses fictional data and is separate from the full Vue application.

Switch among doctor, patient, family, nurse, and administrator roles. The demo mirrors the full platform's menu and workflow boundaries: patient assignment and clinical review; care plans and daily tasks; measurements, medication, medical records, dialysis and dry weight; nutrition and health analytics; appointments and handovers; notification channels; users, roles, menus, auditing, automation, and scoped data export. Care-plan input stays in the current tab and is cleared on refresh or Reset. Existing branding and appearance preferences use separate browser storage.

Controls perform visible local state changes and write demo audit events. File recognition, AI analysis, channel delivery, and server-side scheduling are safely simulated because this static build has no backend; its Content Security Policy blocks network connections. The workflow and review gates match the real product, but no real clinical processing is claimed.

## Care-plan collaboration walkthrough

Use fictional patient **Aihua Zhang** (patient 1), whose demo family and nurse permissions are seeded independently. The nurse has its own limited follow-up queue; a nursing assignment alone does not grant access. Role switching is an illustration of a fictional team, not secure authentication.

1. Doctor → **Care plans** → **Collaboration draft**. The compact demo editor has two one-time actions, one assigned to the patient account and one to the family account. Check the explicit ISO 8601 deadlines with offsets, then save. The draft is clinician-only.
2. Doctor → **Review and publish**. Check content, deadlines, recipients and the publication confirmation.
3. Patient → **Care plan** (also visible under Today) → **Submit receipt**. ASSISTED is the default; choosing SELF is an account declaration, not identity verification. The item becomes **SUBMITTED**, awaiting a doctor.
4. Family → **Care plan** → submit the other receipt. Family/nurse entries always record **ASSISTED**. **Report difficulty** moves an open item to **NEEDS_HELP**.
5. Nurse → **Care-plan follow-up** → **Record follow-up**. Contacted/awaiting information/doctor-follow-up records never publish clinical instructions, confirm a receipt or close a plan.
6. Doctor → **Return for detail** with a reason. Submit another receipt as patient/family; the first submission and original timing remain in history. Doctor → **Confirm review** for each item → **Close plan** only when all current items are confirmed.
7. Before closing, **Create revision draft** → edit → **Review and publish**. The publication dialog shows old/new content and affected action IDs. Existing nonterminal actions become SUPERSEDED; confirmed history stays confirmed. Every new action starts OPEN, with no copied completion.
8. Expand receipt/revision history, then **Reset demo**. Care-plan inputs and open dialog fields are discarded. Refresh also resets collaboration state. Switching role/patient discards unsubmitted dialog input.

This is static, synthetic coordination. Notification output always says **Demo result only**, and the CSP blocks network connections. No real patient data, clinical recommendations, doses, new uploads, real evidence references, backend persistence, credentials or webhook delivery are added. Legacy internal plans remain separate and are never silently published. The domain model validates 1–50 actions; the compact walkthrough editor intentionally shows two. Timestamps accept explicit offsets and millisecond precision; the production database precision/concurrency contract is verified separately.

## Publish with GitHub Pages

The repository includes a [GitHub Pages workflow](../.github/workflows/demo-pages.yml).

1. Push the project to the target GitHub repository.
2. Open **Settings → Pages → Build and deployment → Source** and select **GitHub Actions**.
3. Run **Actions → Publish static demo → Run workflow**. Later changes under `demo/` or `cn/demo/` on `main` deploy automatically.
4. Open the English site at the Pages root and the Chinese site under `/cn/`.

Only the static page, scripts, styles, and demo images are uploaded. Backend configuration, SQL files, tests, and the rest of the repository are excluded.

GitHub reference: [Using custom workflows with GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).

## Verify

```bash
node --test demo/tests/*.test.mjs cn/demo/tests/*.test.mjs
node --check demo/app.js
node --check demo/model.js
node --check cn/demo/app.js
node --check cn/demo/model.js
```

For browser QA, switch all five roles and patients; approve or reject a review; add a care plan; complete a medication task and verify inventory; add a blood-pressure reading and verify the table, chart, and task state; run a module action and verify its audit event; toggle a demo user; open a record; export CSV; then reset.

Images under `assets/` are copied from project-owned assets so the demo can deploy independently. Update the corresponding demo asset whenever the product logo or illustration changes.

Optional genuine-browser QA (requires installed Playwright and an environment that allows Chromium processes):

```bash
node demo/tests/carePlanCollaboration.browser.mjs
```

The browser script exercises both editions at 1440px and 390px and writes synthetic screenshots to `/tmp/care-plan-demo-qa` (override with `DEMO_QA_OUTPUT`). It is separate from the dependency-free Node suite. In the implementation environment Chromium exited before page navigation because socket creation was prohibited, so no browser screenshots or passing browser result were obtained. This demo verification is not backend end-to-end acceptance.
