# Interactive Static Demo

Open [index.html](index.html) in a browser. The demo requires no account, Node.js service, or database. It uses fictional data to demonstrate the core family-care workflow and is separate from the full Vue application.

The demo includes family-member switching, care tasks, medication check-ins with inventory updates, blood-pressure entry, 7/30-reading trends, sample medical records, CSV export, and one-click reset. State stays in the current page and is cleared on refresh. Notifications, OCR, uploads, AI, and family invitations never contact a real service. The Content Security Policy blocks all network connections.

## Publish with GitHub Pages

The repository includes a [GitHub Pages workflow](../.github/workflows/demo-pages.yml).

1. Push the project to the target GitHub repository.
2. Open **Settings → Pages → Build and deployment → Source** and select **GitHub Actions**.
3. Run **Actions → Publish static demo → Run workflow**. Later changes under `demo/` on `main` or `master` deploy automatically.
4. When the workflow completes, open its **Open live demo** link or the repository's `github-pages` deployment URL.
5. Add that public URL to the main README once the final GitHub repository name is known.

Only the static page, scripts, styles, and demo images are uploaded. Backend configuration, SQL files, tests, and the rest of the repository are excluded.

GitHub reference: [Using custom workflows with GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).

## Verify

```bash
node --test demo/tests/*.test.mjs
node --check demo/app.js
```

For browser QA, switch family members; complete a medication task and verify inventory; add a blood-pressure reading and verify the table, chart, and task state; open a record; export CSV; then refresh or reset and verify that fictional data returns to its initial state.

Images under `assets/` are copied from project-owned assets so the demo can deploy independently. Update the corresponding demo asset whenever the product logo or illustration changes.
