# Open-source Release Checklist

## Release blockers

- [ ] No real patient data, names, phone numbers, identifiers, addresses, reports, tokens, webhook URLs, or provider keys are present in tracked files or Git history.
- [ ] All credentials observed during development have been rotated.
- [ ] `.env`, IDE files, generated builds, uploads, logs, and local database files are ignored.
- [ ] `init.sql` creates a new database successfully and contains no default account or real business data.
- [ ] Optional demo data is fictional, labeled, and excluded from production instructions.
- [ ] Backend authorization validates patient ownership for every read and write.
- [ ] All user-facing text, API messages, baseline data, and public documentation are English.

## Automated and manual checks

- [ ] `mvn clean test`
- [ ] `npm test` in `frontend/`
- [ ] `npm run build` in `frontend/`
- [ ] `node --test demo/tests/*.test.mjs`
- [ ] Desktop and mobile smoke tests cover sign-in, navigation, patient switching, CRUD, uploads, exports, notifications, and sign-out.
- [ ] Static demo has no external network request and uses fictional data only.
- [ ] README links, setup commands, screenshots, and demo instructions are current.
- [ ] Dependency and secret scans pass.

## Production configuration

- [ ] HTTPS, exact CORS origins, secure proxy headers, rate limits, and upload-size limits are configured.
- [ ] JWT, database, AI, OCR, webhook, and bootstrap secrets are stored outside source control.
- [ ] Bootstrap administrator creation is disabled after initialization.
- [ ] Backups are encrypted and a restore drill has succeeded.
- [ ] Large attachments use protected object storage with malware scanning and retention rules.
- [ ] Operators have reviewed `SECURITY.md` and applicable privacy and health-data obligations.
