# Operator handoff: database and deployment

These are operator-run instructions, not evidence that a database was migrated or a server deployed. Run commands from the repository root. The backend image needs JDK 17 for its build; its bytecode target remains Java 8. Docker builds fetch dependencies and distribution fonts, so an air-gapped server needs images built and transferred through your approved process first.

## 1. Back up and prepare MySQL

Use MySQL 8.0. Back up the **complete database**, retain the current application image/configuration, and rehearse restore on an isolated database before changing an existing installation. Include separately managed attachment/object storage, if any. The in-app family archive is not a complete backup. `scripts/RunSqlMigration.java` is a historical care-platform helper: it copies only six legacy tables and verifies older structures. Do not use its success message as proof of a complete backup or a collaboration migration.

For a new empty database, run these SQL files in order with your authorized migration account; use the MySQL client's password prompt or your approved credential mechanism, never a password in command-line arguments:

1. `src/main/resources/sql/init.sql`
2. `src/main/resources/sql/doctor_workspace_20260921.sql`
3. `src/main/resources/sql/care_platform_upgrade_20260921.sql`
4. `src/main/resources/sql/care_plan_collaboration_20261003.sql`
5. `src/main/resources/sql/patient_specialty_roles_20260923.sql`

The fifth script also runs automatically at every MySQL/MariaDB application startup. Running it manually does **not** disable that behavior. The runtime account still requires the application's data privileges plus `CREATE`, `SELECT`, `INSERT`, and `UPDATE` for that startup script; table creation with foreign keys can also require `REFERENCES` on the referenced tables. Restrict privileges to the application schema. There is no switch in this release to disable the startup migration. All other schema upgrades remain operator-run.

For an existing database, inspect its release/schema and apply only missing applicable upgrades in the dependency order above; do not rerun `init.sql` as a reset. Older installations may also need `functional_review_repair_20260922.sql` (import/alert/medication-safety tables) and `notification_language_preference_20260922.sql` (user preferences). These are already included in the current fresh baseline. Review `role_permission_cleanup_20260922.sql` separately: it changes role/menu assignments and removes obsolete roles, so it is not a blanket additive upgrade. Never import `demo-data.sql` into production.

No report-specific migration is required beyond the current collaboration schema. Migration creates neither nurse-account assignments nor patient access grants, and does not publish legacy plans. Keep `CARE_PLAN_ENABLED=false` during preparation.

## 2. Configure the existing-database deployment

Copy `.env.production.example` to `.env.production` with owner-only permissions, then edit it locally. Do not commit it or share rendered Compose output. Replace the database hostname/name/user/password, JWT secret and HTTPS origin. The database hostname must match its TLS certificate and its CA must be trusted by the API JVM; configure your private-CA truststore if needed. The template uses `sslMode=VERIFY_IDENTITY`, which verifies both certificate trust and hostname; do not fix a TLS error by disabling verification. See [Connector/J security settings](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-security.html).

```bash
umask 077
cp .env.production.example .env.production
# Edit .env.production locally before continuing.
docker compose --env-file .env.production -f docker-compose.production.yml config --quiet
docker compose --env-file .env.production -f docker-compose.production.yml build
```

The template's `WEB_PORT=127.0.0.1:8080` binds HTTP to the host loopback interface. Configure your existing host reverse proxy with the final domain and valid TLS certificate, forwarding `/` (including `/api/`, `/cn/`, `/demo/` and `/cn/demo/`) to `http://127.0.0.1:8080`. Preserve the Host header, allow 100 MB requests and at least 120-second upstream reads. Keep port 8080 private; redirect public HTTP to HTTPS. Set `CORS_ALLOWED_ORIGINS` to that exact HTTPS origin. A containerized external proxy needs its own private-network/binding arrangement instead of host loopback. The separate `deploy/nginx.conf.example` is for host-served static files plus a directly running API, not this outer Compose proxy.

## 3. First administrator, only if none exists

Skip this step if an administrator already exists. Production Compose deliberately forces normal bootstrap off. After schema preparation and image build, choose a unique username that does not exist: bootstrap would otherwise grant that existing account administrator rights without replacing its password. Run this temporary API container with **no published service ports**; it is a normal server process, not an automatic one-shot command. [Compose run documentation](https://docs.docker.com/reference/cli/docker/compose/run/) describes the port and environment behavior.

```bash
read -r -p 'New administrator username: ' BOOTSTRAP_ADMIN_USERNAME
read -r -s -p 'New password (at least 12 characters): ' BOOTSTRAP_ADMIN_PASSWORD
printf '\n'
export BOOTSTRAP_ADMIN_USERNAME BOOTSTRAP_ADMIN_PASSWORD
docker compose --env-file .env.production -f docker-compose.production.yml run --rm --no-deps \
  -e BOOTSTRAP_ADMIN_ENABLED=true -e BOOTSTRAP_ADMIN_USERNAME -e BOOTSTRAP_ADMIN_PASSWORD api
# In another terminal, verify the chosen account has the admin role in MySQL.
# Then stop this temporary foreground process with Ctrl+C.
unset BOOTSTRAP_ADMIN_USERNAME BOOTSTRAP_ADMIN_PASSWORD
```

Run this on a trusted operator terminal with shell tracing disabled; environment values remain visible to privileged host/container administrators. Do not put bootstrap secrets in the persistent env file. Verify sign-in once normal service is started, change the initial password, and confirm only the normal bootstrap-disabled API remains running.

## 4. PDF fonts and optional override

The image installs the complete distribution `fonts-wqy-microhei` font. Leave `REPORT_PDF_FONT_PATH` empty to use installed candidates. For a custom font, this setting must name a readable **container** file, not a host-only path. Use a complete licensed TrueType-outline font covering all actual report characters; do not use test subsets or assume CFF fonts work.

For example, create a local `compose.font.yml` override after replacing the host path with an existing file:

```yaml
services:
  api:
    volumes:
      - type: bind
        source: /absolute/host/path/complete-report-font.ttf
        target: /app/fonts/report.ttf
        read_only: true
        bind:
          create_host_path: false
```

Set `REPORT_PDF_FONT_PATH=/app/fonts/report.ttf` in `.env.production` and add `-f compose.font.yml` after the main `-f` argument to every Compose command. Make the file readable by container UID 10001. The same mapping is available in root/CN local Compose and `.env` templates. Maven/IDE runs require real process environment variables and a local filesystem font path.

The CI-only `scripts/prepare-care-report-font.py` is not an operator installer; do not bypass its guard. The accepted CI font is a complete extracted TTF, whereas the default Docker font is the installed TTC. Verify actual downloads in your own image. A configured strict report font is authoritative: an invalid/incomplete override fails visibly instead of falling back. Test EN and CN PDF exports with mixed-language names and original notes. HTML preview does not prove PDF rendering. Unsupported shaping/characters remain a visible limitation with an HTML alternative.

## 5. Start, accept, then opt in

```bash
docker compose --env-file .env.production -f docker-compose.production.yml up -d
curl --fail --silent --show-error https://health.example.com/api/health
```

Replace the sample hostname. Liveness alone is insufficient: verify sign-in, a patient page and language switching. Then, only after your migration/backup and acceptance checks, set `CARE_PLAN_ENABLED=true` in the env file and recreate the API with the same `docker compose ... up -d` command. Both collaboration and execution reports use this one flag. Authenticated `GET /api/care-plans/capabilities` must return `data.enabled: true`; an anonymous request cannot verify it.

Use authorized synthetic accounts to check doctor assignment, publication, patient/family execution, nurse assignment **plus** explicit `CARE_PLAN` grant, doctor review, and EN/CN HTML/PDF/two-CSV export. An administrator role is not clinical authority. Follow the [user guide](USER_GUIDE.md#44-collaborative-doctor-plan-tasks) for access rules. Provider delivery and clinical effectiveness are separate from software acceptance.

To disable collaboration/reports, set the flag back to `false` and recreate the API. Retain all schema/history; disabling is not a database rollback. Do not use `down -v` to troubleshoot a database with records. Medical attachments in this source are embedded in MySQL `medical_record_attachment.file_content`; the empty `/app/uploads` directory is not their storage. Configure host/container stdout log retention separately. Any additional external attachment storage introduced by your deployment needs its own backup policy.
