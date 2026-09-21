# Interactive Static Demo

Open [index.html](index.html) locally or use the [public demo](https://wangdj104.github.io/tx-analysis-service/). The demo requires no account, application server, or database. It uses fictional data and is separate from the full Vue application.

Switch among doctor, patient, and family roles. The demo covers assigned-patient scope, clinician review, care plans, care tasks, medication check-ins and inventory, blood-pressure trends, medical records, dialysis, nutrition, appointments, handovers, CSV export, and reset. State stays in the current tab and is cleared on refresh. Simulated review and AI labels never contact a real service; the Content Security Policy blocks network connections.

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
node --test demo/tests/*.test.mjs
node --check demo/app.js
```

For browser QA, switch all three roles and patients; approve or reject a review; add a care plan; complete a medication task and verify inventory; add a blood-pressure reading and verify the table, chart, and task state; add a handover; open a record; export CSV; then reset.

Images under `assets/` are copied from project-owned assets so the demo can deploy independently. Update the corresponding demo asset whenever the product logo or illustration changes.
