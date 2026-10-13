# Operator handoff: database and deployment

These are operator-run instructions, not evidence that a database was migrated or a server deployed. Run commands from the repository root. The backend image needs JDK 17 for its build; its bytecode target remains Java 8. Docker builds fetch dependencies and distribution fonts, so an air-gapped server needs images built and transferred through your approved process first.

## 1. Back up and prepare MySQL

MySQL 8.0 is recommended. The single complete [init.sql](../src/main/resources/sql/init.sql) defines all tables, indexes, roles and initial dictionaries; see the [SQL initialization guide](../README.md#database-initialization). For a new empty database, execute that file with an authorized initialization account using the MySQL password prompt or an approved credential mechanism.

Before modifying an existing installation, back up the **complete database**, attachments/object storage, application image and configuration, then rehearse restore in isolation. The in-app family archive is not a complete backup. The historical `scripts/RunSqlMigration.java` copies only six old tables and is not a complete backup tool.

Compare existing tables, column types/defaults/nullability, indexes and permissions with the current definitions, then apply only reviewed differences. `CREATE TABLE IF NOT EXISTS` does not repair existing columns. MySQL DDL implicitly commits: after a failure, inspect each completed change instead of assuming a transaction rolled everything back. Historical upgrades have been consolidated and removed. Application startup no longer runs specialty or other schema migrations, so it needs no DDL privileges for startup migration.

Import identity is `(patient_id, source_type, source_external_id)`; check existing duplicates before adding unique keys and never delete patient rows to pass an upgrade. Optional `demo/sql/demo-data.sql` is for isolated local demos only and must not enter production. Initialization does not assign nurses, grant patient access or publish old plans. Keep `CARE_PLAN_ENABLED=false` during preparation.

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

To disable collaboration/reports, set the flag back to `false` and recreate the API. Retain all schema/history; disabling is not a database rollback. Do not use `down -v` to troubleshoot a database with records. Medical attachments in this source are embedded in MySQL `medical_record_attachment.file_content`; the empty `/app/uploads` directory is not their storage. Configure host/container stdout log retention separately. Any additional external attachment storage introduced by your deployment needs its own backup policy.
