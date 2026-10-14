# Operator handoff: database and deployment

These are operator-run instructions, not evidence that a database was migrated or a server deployed. Run commands from the repository root. The backend image needs JDK 17 for its build; its bytecode target remains Java 8. Docker builds fetch dependencies and distribution fonts, so an air-gapped server needs images built and transferred through your approved process first.

## 1. Back up and prepare MySQL

The new follow-up targets MySQL **5.7.18** as well as 8.0. Actual 5.7.18 execution is still NOT_RUN; H2, extracted predicates and source inspection are not a database acceptance pass. The [release record](ALERT_WORKFLOW_RELEASE.md) describes the measured layers. Commands below are examples for an authorized operator, not actions performed by this change.

### Choose one edition and one installation path

Run from the repository root. Choose `SQL_DIR=src/main/resources/sql` for English or `SQL_DIR=cn/src/main/resources/sql` for Chinese, never both against one database. Each complete init source contains **86 CREATE TABLE statements**; the alert workflow owns **22 `aw57_` guards**. These source counts do not measure your database. Inventory actual tables, columns, indexes, foreign keys, triggers, routines, engine/row format, SQL modes and version before choosing a path. Unknown, conflicting or partial objects require review, not replacement.

Before any change, stop all application writers/schedulers and take a **complete database**, attachment/object-storage, image and configuration backup. The operator must verify an isolated restore and record baseline counts/schema/history. The family archive is not a complete backup; `scripts/RunSqlMigration.java` copies only six old tables. In this source, medical attachment bytes are in MySQL `medical_record_attachment.file_content`; any additional storage needs its own backup. Preserve the backup and completed-step record until postcheck/recovery acceptance.

Review installer privileges with the DBA: schema-local CREATE/ALTER/INDEX/REFERENCES/TRIGGER plus required reads; the older foundation has its own additional routine/seed requirements. Binary logging may impose additional existing trigger-creation restrictions. If policy/privileges are insufficient, **stop for the DBA**. Do not grant SUPER, change `log_bin_trust_function_creators`, disable security checks, or change server settings to force an installation through. The running application needs no startup-migration DDL privileges.

### Empty database: complete init only

Use a newly created, verified-empty schema. A 5.7-compatible example collation is `utf8mb4_unicode_ci`; do not use an 8.0-only `_0900_` collation. An authorized operator selects the schema and runs exactly one edition's complete [init.sql](../src/main/resources/sql/init.sql):

```bash
SQL_DIR=src/main/resources/sql  # Or cn/src/main/resources/sql, one edition only
mysql --host=DB_HOST --user=SCHEMA_INSTALLER --password --database=APP_DB < "$SQL_DIR/init.sql"
mysql --host=DB_HOST --user=SCHEMA_INSPECTOR --password --database=APP_DB < "$SQL_DIR/alert_workflow_postcheck_20261013.sql"
```

Replace the uppercase host/user/schema placeholders with approved values. Use the password prompt or your approved credential mechanism, never a password in a command or repository. Inspect initialization and postcheck output, expected objects and zero blockers. Do not run an upgrade after fresh init. Do not rerun init against a populated or partially initialized schema. Init does not create an administrator, assign nurses, grant patient access, publish old plans or activate either workflow. Demo data is optional isolated-local data only and must not enter production.

### Existing installation: preflight → dated upgrade → postcheck

Never run init as an upgrade or reset. First verify the existing foundation. If its required tables/keys/layout are missing, stop and separately assess the existing `mysql57_preflight_20261012.sql`, `mysql57_schema_upgrade_20261012.sql` and `mysql57_postcheck_20261012.sql` in an isolated clone. Do not concatenate foundation repair, init and the alert upgrade or assume either preflight covers the other.

The alert path is **single-use, fail-closed**. Its standalone preflight and the identical embedded upgrade preflight reject prior/partial workflow objects, reserved markers, incompatible keys/types and unknown triggers/routines. A previously installed workflow does not pass this install path; compare exact definitions and seek a reviewed recovery/forward migration instead of deleting objects or retrying blindly.

```bash
SQL_DIR=src/main/resources/sql  # Or cn/src/main/resources/sql
mysql --host=DB_HOST --user=SCHEMA_INSTALLER --password --database=APP_DB < "$SQL_DIR/alert_workflow_preflight_20261013.sql"
# STOP unless every preflight succeeds and the selected schema/version/privileges are approved.
mysql --host=DB_HOST --user=SCHEMA_INSTALLER --password --database=APP_DB < "$SQL_DIR/alert_workflow_upgrade_20261013.sql"
# Run only after the preceding command fully succeeds; inspect every result.
mysql --host=DB_HOST --user=SCHEMA_INSPECTOR --password --database=APP_DB < "$SQL_DIR/alert_workflow_postcheck_20261013.sql"
```

Use a client that supports DELIMITER. Execute and inspect one command at a time, with **no `mysql --force`**. Run preflight as the approved schema installer whose trigger eligibility it inspects; its read-only effect does not mean a different inspection account proves installer privileges. The DBA must resolve an unsuccessful privilege review before proceeding; missing information is not approval. DDL implicitly commits and the entire batch is not atomic. After any error, stop writers, record completed DDL and inspect actual objects; do not assume transaction rollback, drop unknown objects or rerun over a partial install. Recovery/restore uses the operator's verified backup and an approved plan.

Postcheck's schema/index/foreign-key/guard listings and zero blocker counts are necessary, not sufficient. Compare legacy row counts, values, history and attachments with the baseline; verify the 22 owned guards and all expected definitions without overwriting unknown objects. Then validate native parser/guard rejection, indexes/query plans, locking, current-role authorization and full restore on the actual target engine. Neither these scripts nor application startup enable features, send notifications, create patient grants or convert old alerts into completed workflow work. Keep both feature flags false during preparation.

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

### Measurement follow-up: user-applied environment override

The current production Compose file forwards `CARE_PLAN_ENABLED` but **does not forward `ALERT_WORKFLOW_ENABLED`**. Setting only `.env.production` therefore cannot enable follow-up. For a future authorized isolated acceptance, the operator may create this minimal local `compose.alert-workflow.yml`; no override file or server change was made by this checkpoint:

```yaml
services:
  api:
    environment:
      ALERT_WORKFLOW_ENABLED: "${ALERT_WORKFLOW_ENABLED:-false}"
```

Initially keep `ALERT_WORKFLOW_ENABLED=false` in `.env.production`. The override defaults false even when the variable is absent. It changes only this API environment variable; it does not create grants, assignments, providers, database objects or care-plan authority. From the repository root, the operator validates without printing rendered secrets:

```bash
docker compose --env-file .env.production -f docker-compose.production.yml -f compose.alert-workflow.yml config --quiet
```

Only after the chosen empty-init/postcheck or existing preflight/upgrade/postcheck succeeds, the verified backup/restore is available, and the environment owner authorizes synthetic acceptance, may that isolated environment use `ALERT_WORKFLOW_ENABLED=true` and recreate the API with the same override. Production must stay false until native/runtime/browser/role/restore gates pass and deployment/activation is separately approved. `CARE_PLAN_ENABLED` remains independent and may stay false for measurement-only nursing:

```bash
# Future operator action only, after the preceding prerequisites and authorization.
docker compose --env-file .env.production -f docker-compose.production.yml -f compose.alert-workflow.yml up -d api
# Use an existing short-lived authorized synthetic-user session, on a trusted terminal without shell tracing.
curl --fail --silent --show-error --header "Authorization: Bearer $HEALTH_ACCEPTANCE_TOKEN" "$HEALTH_ORIGIN/api/alert-workflows/capabilities"
```

Set `HEALTH_ORIGIN` to the approved HTTPS origin and obtain the existing session token through your normal sign-in process; never paste tokens into documents/logs or commit them. An authenticated response must explicitly show `data.enabled: true` for enabled acceptance. An anonymous request, health endpoint or menu visibility cannot establish this. Verify source save/read, named acceptance, assisted feedback, owner/nurse clinical-close denial, doctor closure, current revocation and an uncertain-result recovery using fictional data. Real browser/HTTP checks have not been executed here.

To disable follow-up, set `ALERT_WORKFLOW_ENABLED=false` and recreate the API using **the same override arguments**. Retain schema and history. Disabling does not restore the database or remove tracked-source protection. Never use `down -v` as recovery. Do not assume other Compose commands include your override automatically.

To disable collaboration/reports, set the flag back to `false` and recreate the API. Retain all schema/history; disabling is not a database rollback. Do not use `down -v` to troubleshoot a database with records. Medical attachments in this source are embedded in MySQL `medical_record_attachment.file_content`; the empty `/app/uploads` directory is not their storage. Configure host/container stdout log retention separately. Any additional external attachment storage introduced by your deployment needs its own backup policy.

### Source-revision qualification metadata / 来源版本资格信息

The same Phase A init and additive upgrade now include the server-only
`alert_source_revision.action_versions_json` column. It records affected care-plan action
versions atomically with a correction, including receipts not yet attached to a follow-up.
The immutable revision guards protect it; source history, plan-event payloads, legacy
views and format-1 family archives do not expose it. A server-created `{}` is known-empty;
NULL, missing or malformed metadata is unknown and blocks qualification. Never repair
unknown history by filling it with `{}`. Postcheck's `unknown_source_action_qualification`
must have zero blockers. This is not native MySQL validation.

The fail-closed preflight still rejects any prior/partial workflow tables, including an
older candidate schema without this column. Do not rerun this additive script over a
partial/prior installation and do not add the column ad hoc to bypass preflight. Preserve
history/backups and obtain a reviewed, version-specific reconciliation plan before any
operator change. No current database was inspected or changed here.

空库初始化与增量脚本同步增加服务端专用 `action_versions_json`，在同一更正事务内记录受影响
计划行动版本，未附加到随访的回执也纳入。版本不可变守卫保护该信息；普通历史、计划事件、
旧界面和家庭格式 1 导出不暴露它。服务端写入的 `{}` 才表示确知为空；NULL、缺失或无效内容
表示未知并阻断资格，不得以补 `{}` 的方式“修复”。后检对应项应为零。已有／部分安装仍被
预检拒绝，需保留备份和历史后制定专门的版本核对方案，不得绕过预检。本次没有操作数据库。
