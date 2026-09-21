# Interactive Static Demo

Open [index.html](index.html) locally or use the [public demo](https://wangdj104.github.io/tx-analysis-service/). The demo requires no account, application server, or database. It uses fictional data and is separate from the full Vue application.

Switch among doctor, patient, family, and administrator roles. The demo mirrors the full platform's menu and workflow boundaries: patient assignment and clinical review; care plans and daily tasks; measurements, medication, medical records, dialysis and dry weight; nutrition and health analytics; appointments and handovers; notification channels; users, roles, menus, auditing, automation, and scoped data export. State stays in the current tab and is cleared on refresh.

Controls perform visible local state changes and write demo audit events. File recognition, AI analysis, channel delivery, and server-side scheduling are safely simulated because this static build has no backend; its Content Security Policy blocks network connections. The workflow and review gates match the real product, but no real clinical processing is claimed.

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

For browser QA, switch all four roles and patients; approve or reject a review; add a care plan; complete a medication task and verify inventory; add a blood-pressure reading and verify the table, chart, and task state; run a module action and verify its audit event; toggle a demo user; open a record; export CSV; then reset.

Images under `assets/` are copied from project-owned assets so the demo can deploy independently. Update the corresponding demo asset whenever the product logo or illustration changes.
