# Operator handoff: database and deployment

These are operator-run instructions, not evidence that a database was migrated or a server deployed. Run commands from the repository root. The backend image needs JDK 17 for its build; its bytecode target remains Java 8. Docker builds fetch dependencies and distribution fonts, so an air-gapped server needs images built and transferred through your approved process first.

## 1. Back up and prepare MySQL

The complete medical-review installation below requires **MySQL 5.7.18**. Validate the complete initializer and runtime schema contracts against the actual target engine before deployment.

### Choose one edition and one installation path

Run from the repository root. Choose `SQL_DIR=src/main/resources/sql` for English or `SQL_DIR=cn/src/main/resources/sql` for Chinese, never both against one database. Inventory actual tables, columns, indexes, foreign keys, triggers, routines, engine/row format, SQL modes and version before choosing a path. Unknown, conflicting or partial objects require review, not replacement.

Before any change, stop all application writers/schedulers and take a **complete database**, attachment/object-storage, image and configuration backup. The operator must verify an isolated restore and record baseline counts/schema/history. The family archive is not a complete backup; `scripts/RunSqlMigration.java` copies only six old tables. In this source, medical attachment bytes are in MySQL `medical_record_attachment.file_content`; any additional storage needs its own backup. Preserve the backup and completed-step record until postcheck/recovery acceptance.

Review installer privileges with the DBA: schema-local CREATE/ALTER/INDEX/REFERENCES/TRIGGER plus required reads; the initializer also needs its routine and seed-data permissions. Binary logging may impose additional existing trigger-creation restrictions. If policy/privileges are insufficient, **stop for the DBA**. Do not grant SUPER, change `log_bin_trust_function_creators`, disable security checks, or change server settings to force an installation through. The running application needs no startup-migration DDL privileges.

### Empty database: complete init only

Use a newly created, verified-empty MySQL 5.7.18 schema with a compatible collation such as `utf8mb4_unicode_ci`. Run exactly one edition's [complete initialization script](../src/main/resources/sql/init.sql). It includes the system tables, indexes, foreign keys, workflow triggers and required seed data, including medical review.

```bash
SQL_DIR=src/main/resources/sql  # Or cn/src/main/resources/sql, one edition only
mysql --host=DB_HOST --user=SCHEMA_INSTALLER --password --database=APP_DB < "$SQL_DIR/init.sql"
```

Replace the uppercase host/user/schema placeholders with approved values and use the password prompt or your approved credential mechanism. Use a client that supports `DELIMITER`, inspect every result, and stop on any error without `mysql --force`. Do not rerun init against a populated or partially initialized schema. Init creates no administrator and does not assign nurses, grant patient access or enable workflows. Demo data is optional local data only.

### Existing installation: compare and apply missing changes

Historical dated upgrades and alternative initializers have been consolidated into `init.sql` and removed. Never use that empty-database script as an upgrade or reset. After a verified backup, compare actual tables, columns, indexes, foreign keys and trigger definitions with the selected edition's complete initializer and runtime `*-schema.json` contracts. Apply only missing, compatible changes; review conflicting or partial definitions separately and preserve existing rows, histories and attachments.

DDL implicitly commits. Keep application writers and schedulers stopped during changes; record completed statements and inspect actual objects after any error. Verify the final definitions and existing data before restarting. Schema installation and application startup do not enable features or send notifications.

<a id="medical-review-setup"></a>

### Medical review setup

The sole `init.sql` includes all five `medical_review_*` tables and their prerequisite storage. An existing database needs the same schema comparison above. Keep `MEDICAL_REVIEW_API_ENABLED`, `MEDICAL_REVIEW_ENABLED` and `CARE_PLAN_ENABLED` at their default `false` until schema and application checks pass, then configure the corresponding flags in the deployment environment. Production Compose forwards these variables from `.env.production`. Specialty plan types use existing `VARCHAR` columns and need no additional tables.

<a id="isolated-restore"></a>

### Verify an isolated full restore

Run `scripts/verify-full-restore.mjs` from the repository root after stopping application writers and schedulers. Use two MySQL servers with matching versions and different `server_uuid` values. The target must have `event_scheduler=OFF` or `DISABLED` and no existing schema with the source database name; the tool restores that same name on the separate server.

Prepare separate owner-readable MySQL option files with `[client]` host, port, user and password values. Use accounts authorized for complete dumps/restores, including routines, events and triggers; keep passwords out of command arguments.

```bash
node scripts/verify-full-restore.mjs \
  --source-defaults /secure/source.cnf \
  --target-defaults /secure/isolated-target.cnf \
  --database family_health \
  --output /secure/new-rehearsal-directory \
  --writers-stopped
```

If the clients are outside PATH, add `--mysql /path/to/mysql` and `--mysqldump /path/to/mysqldump`. For external attachments, add `--attachments-directory /path/to/attachments`; every database reference must resolve to a file inside that directory. Use a new output directory outside the attachment directory; missing files and symlinks are rejected. Quote Windows paths containing spaces. The multiline examples use Bash syntax.

The output contains the SQL dump, copied attachments and `verification.json`, which records checks of database definitions, rows and attachment contents. Keep these files in protected backup storage outside the repository. A `VERIFIED_QUARANTINED` result leaves the target's `restore_quarantine` marker active, preventing application startup and scheduled jobs. The tool has no unlock option: before activation, separately review current authorization, pending work, notification queues, fresh runtime secrets/configuration and attachment storage. Preserve deployment images, external secrets, object-store versions and provider state separately; the tool does not back them up.

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

Verify actual downloads in your own image. A configured strict report font is authoritative: an invalid/incomplete override fails visibly instead of falling back. Test EN and CN PDF exports with mixed-language names and original notes. HTML preview does not prove PDF rendering. Unsupported shaping/characters remain a visible limitation with an HTML alternative.

## 5. Start, accept, then opt in

```bash
docker compose --env-file .env.production -f docker-compose.production.yml up -d
curl --fail --silent --show-error https://health.example.com/api/health
```

Replace the sample hostname. Liveness alone is insufficient: verify sign-in, a patient page and language switching. Then, only after your migration/backup and acceptance checks, set `CARE_PLAN_ENABLED=true` in the env file and recreate the API with the same `docker compose ... up -d` command. Both collaboration and execution reports use this one flag. Authenticated `GET /api/care-plans/capabilities` must return `data.enabled: true`; an anonymous request cannot verify it.

Use authorized synthetic accounts to check doctor assignment, publication, patient/family execution, nurse assignment **plus** explicit `CARE_PLAN` grant, doctor review, and EN/CN HTML/PDF/two-CSV export. An administrator role is not clinical authority. Follow the [user guide](USER_GUIDE.md#44-collaborative-doctor-plan-tasks) for access rules. Provider delivery and clinical effectiveness are separate from software acceptance.

### Enable measurement follow-up

Production Compose forwards `ALERT_WORKFLOW_ENABLED` from `.env.production`; keep it `false` during preparation. Validate configuration without printing rendered secrets:

```bash
docker compose --env-file .env.production -f docker-compose.production.yml config --quiet
```

After schema, backup/restore and application checks pass, set `ALERT_WORKFLOW_ENABLED=true` and recreate the API. `CARE_PLAN_ENABLED` remains independent and may stay false for measurement-only nursing:

```bash
docker compose --env-file .env.production -f docker-compose.production.yml up -d api
# Use an existing short-lived authorized synthetic-user session, on a trusted terminal without shell tracing.
curl --fail --silent --show-error --header "Authorization: Bearer $HEALTH_ACCEPTANCE_TOKEN" "$HEALTH_ORIGIN/api/alert-workflows/capabilities"
```

Set `HEALTH_ORIGIN` to the approved HTTPS origin and obtain the existing session token through your normal sign-in process; never paste tokens into documents/logs or commit them. An authenticated response must explicitly show `data.enabled: true` for enabled acceptance. An anonymous request, health endpoint or menu visibility cannot establish this. Verify source save/read, named acceptance, assisted feedback, owner/nurse clinical-close denial, doctor closure, current revocation and an uncertain-result recovery using fictional data. Real browser/HTTP checks have not been executed here.

To disable follow-up, set `ALERT_WORKFLOW_ENABLED=false` and recreate the API. Retain schema and history. Disabling does not restore the database or remove tracked-source protection. Never use `down -v` as recovery.

To disable collaboration/reports, set the flag back to `false` and recreate the API. Retain all schema/history; disabling is not a database rollback. Do not use `down -v` to troubleshoot a database with records. Medical attachments in this source are embedded in MySQL `medical_record_attachment.file_content`; the empty `/app/uploads` directory is not their storage. Configure host/container stdout log retention separately. Any additional external attachment storage introduced by your deployment needs its own backup policy.

### Source-revision qualification metadata

The complete initializer includes the server-only `alert_source_revision.action_versions_json` column. It records affected care-plan action versions atomically with a correction, including receipts not yet attached to a follow-up. Immutable revision guards protect it; source history, plan-event payloads, legacy views and format-1 family archives do not expose it. A server-created `{}` is known-empty; NULL, missing or malformed metadata is unknown and blocks qualification. Never fill unknown history with `{}`. When reconciling an existing database, review its column definition and guards together while preserving history and backups.
