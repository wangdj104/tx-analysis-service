# Chengxin Health

**English** · [简体中文](cn/README.md) · [Live demo](https://wangdj104.github.io/tx-analysis-service/) · [中文演示](https://wangdj104.github.io/tx-analysis-service/cn/)

Chengxin Health is an open-source continuous-care platform that connects clinicians, patients, and families around one longitudinal health record. It combines clinical review, care plans, daily measurements, medication adherence, medical records, dialysis, nutrition, alerts, and family coordination without confusing decision support with medical diagnosis.

[![Backend CI](https://img.shields.io/badge/backend-Spring%20Boot-6DB33F)](pom.xml)
[![Frontend CI](https://img.shields.io/badge/frontend-Vue%203-42B883)](frontend/package.json)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> This project helps people record and organize health information. It does not diagnose conditions or replace professional medical advice.

## Why Chengxin Health

Health management often breaks down between visits: measurements live in one place, medication plans in another, family updates in chat messages, and clinical decisions in disconnected records. Chengxin Health turns those fragments into a shared, permission-aware care journey.

- **For clinicians:** reduce repetitive collection work, surface exceptions first, review imported or AI-assisted content before it reaches patients, and keep follow-up work measurable.
- **For patients:** understand today's plan, record health data with less friction, keep appointments and instructions together, and retain a portable longitudinal record.
- **For families and caregivers:** coordinate medication, measurements, appointments, and handovers without losing accountability or exposing unrelated private information.
- **For healthcare organizations:** standardize continuous-care workflows, improve follow-up completion and data quality, preserve an audit trail, and apply the organization's own brand without rebuilding the product.

The platform is designed around **human-in-the-loop healthcare**. Automation creates reminders, detects threshold breaches, assembles trends, and prepares review queues; clinicians remain responsible for diagnosis, treatment decisions, prescriptions, and approval of AI-assisted output.

## One platform, role-specific experiences

| Role | Primary workspace | Typical outcomes |
| --- | --- | --- |
| Doctor | Assigned patients, risk queue, clinical review, care plans, schedules, reports | Spend attention on exceptions, document decisions, and coordinate follow-up |
| Patient | Daily plan, measurements, medication check-ins, visits, consultation, records | Know what to do next and keep a continuous personal health history |
| Family / caregiver | Shared tasks, delegated recording, reminders, handovers, emergency card | Support care safely with explicit access boundaries |
| Administrator | Accounts, roles, menus, branding, notification channels, audit logs | Operate a configurable, traceable platform across teams |

## Product capabilities

- Role-specific workspaces, menus, and data scopes for doctors, patients, family caregivers, and administrators.
- Doctor workspace with assigned-patient scope, priority alerts, clinical notes, care plans, and human review of imported records and AI-assisted analysis.
- Family-care workspace with patient switching, daily tasks, handovers, shared caregivers, symptom tracking, and visit questions.
- Medication catalog, prescription history, reminders, dose check-ins, inventory, restocking, and low-stock warnings.
- Blood-pressure and glucose tracking, personal targets, health timeline, and CSV export.
- Medical-record upload, attachments, optional OCR, structured results, abnormal-result review, and trend charts.
- Dialysis records, schedules, dry-weight management, text import, statistics, and AI-assisted analysis.
- Nutrition diary and assessment, complication tracking, alert rules, reports, and scheduled analysis.
- WeCom/DingTalk webhook notifications, browser notifications, delivery channels, and audit logs.
- Account, role, menu, permission, privacy-masking, backup, and restore controls.

## End-to-end care scenarios

- **Daily chronic-care management:** blood pressure, glucose, oxygen saturation, weight and heart-rate entry; threshold alerts; trend review; medication lists; adherence check-ins; missed-dose escalation; recurring follow-up tasks.
- **Appointments and follow-up:** clinician availability, booking, rescheduling and cancellation; shared schedules; post-visit summaries; prescription records; recurring follow-up reminders; visit timeline.
- **Remote consultation:** three-party text consultation with near-real-time message refresh, voice/video WebRTC sessions, structured symptom intake, record attachments, archived transcripts and specialty recommendations.
- **Hospital and recovery:** treatment plans, procedure and medication schedules, rehabilitation check-ins, wound and drainage observations, discharge instructions and abnormal-symptom escalation.
- **Home care and emergency readiness:** multiple caregivers with graded permissions, shift handovers, care logs, medication inventory, emergency calls and an offline-readable critical medical card.
- **Special populations:** child growth observations, vaccination plans, maternity timelines, multiple guardians, mental-health questionnaires and private visibility controls.
- **Governance and operations:** patient-controlled sharing, multi-clinician authorization, masked exports, operation audit, clinician scheduling, patient grouping and completion/adherence/target-rate reporting.

## Product principles

1. **One longitudinal record:** measurements, treatment events, consultations and documents remain connected to the patient timeline.
2. **Exception-first workflows:** dashboards prioritize overdue work, abnormal readings, incomplete records and review-required AI output.
3. **Explicit consent and least privilege:** role permissions and patient-level grants are separate, visible and revocable.
4. **Automation with confirmation:** safe administrative steps can run automatically; clinically meaningful changes retain a human approval point.
5. **Traceability:** sensitive reads and writes, review decisions and notification delivery are auditable.
6. **Deployable branding:** logo, platform name, organization name, ownership text and page background can be configured without a source-code fork.

## Try the static demo

The repository includes a backend-free, bilingual product tour with fictional data:

- **English:** [open the live demo](https://wangdj104.github.io/tx-analysis-service/) · [source](demo/index.html)
- **中文:** [打开中文演示](https://wangdj104.github.io/tx-analysis-service/cn/) · [source](cn/demo/index.html)
- Local preview: serve the `demo` directory with any static HTTP server.
- GitHub Pages deploys both languages from `main` through the included [workflow](.github/workflows/demo-pages.yml).

Switch among doctor, patient, family, and administrator roles to explore simulated examples of 40 workflows across chronic-disease monitoring, appointments, remote consultation, recovery, home care, emergency information, child and maternity care, mental health, privacy, and operations. Actions update tab-local demo state and audit events; no network request or real clinical processing occurs. Demo coverage is not evidence that every production workflow has been verified; see the [functional audit and verification boundaries](docs/FUNCTIONAL_AUDIT_20260922.md).

## Language layout

The repository root is the English edition. The complete Chinese edition lives in [`cn/`](cn/), including backend, frontend, SQL, documentation, and demo files. When adding a feature, update both editions in the same pull request; shared protocol fields, permission keys, table names, and API paths should remain identical while user-facing text is localized.

## Full local demo with Docker

Requirements: Docker Engine 24+ and Docker Compose v2.

```bash
cp .env.example .env
# Set fresh MYSQL_PASSWORD, MYSQL_ROOT_PASSWORD and JWT_SECRET values in .env.
docker compose up --build
```

Open `http://localhost:8080/` (English) or `http://localhost:8080/cn/` (Chinese). The Docker demo data creates this local-only account:

```text
Username: demo
Password: Demo@123456
```

Change all secrets before exposing a deployment to any network. The Docker demo is intended for evaluation, not production.

On an empty MySQL volume, Compose runs `init.sql`, `doctor_workspace_20260921.sql`, `care_platform_upgrade_20260921.sql`, then `demo-data.sql`. Existing volumes are not reinitialized; apply required upgrades manually after a backup. Do not delete a volume containing records to force initialization.

## Bilingual production deployment

The production image serves the English application at `/`, the Chinese application at `/cn/`, and preserves the current route when the language control in the top-right corner is used. Both applications use the same API, accounts, sessions, and database.

```bash
cp .env.production.example .env.production
# Edit .env.production with the existing database and public origin.
docker compose --env-file .env.production -f docker-compose.production.yml up -d --build
```

Open `http://your-server:8080/` or `http://your-server:8080/cn/`. The same image also exposes the standalone demonstrations at `/demo/` and `/cn/demo/`. This production compose file does not create MySQL and does not import `demo-data.sql`; it connects to the existing database configured by `DB_URL`.

Run the database scripts below before first startup; upgrades for an existing database must be applied separately. Production Compose keeps administrator bootstrap disabled, so provision an administrator deliberately before first use. Check `/api/health` through the public origin after startup; it is a liveness check, so also verify sign-in and a patient page before accepting the release.

Chinese PDF downloads require a CJK font on the backend host. The backend Docker images install Noto CJK. For a non-Docker/systemd installation on Debian or Ubuntu, install `fonts-noto-cjk` with the distribution package manager, then verify `/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc` is readable by the service account. Other distributions must provide a font at one of the paths recognized by `HealthReportServiceImpl` (Noto CJK or WenQuanYi Micro Hei). Restart the application after installing the font and verify a Chinese PDF, including patient names; HTML previews alone do not verify PDF font support.

## Technology stack

- Backend: Java 8, Spring Boot 2.5, MyBatis-Plus, MySQL 8, JWT.
- Frontend: Vue 3, Vite, Vue Router, Element Plus, ECharts.
- Testing: JUnit 5, Mockito, H2 MySQL mode, Node test runner.
- Deployment: Docker Compose, Nginx, GitHub Actions, GitHub Pages for the static demo.

## Local development

### Requirements

- JDK 17 recommended (source/bytecode target remains Java 8; CI and Docker use 17)
- Maven 3.8+
- Node.js 20+
- MySQL 8.0+

### 1. Create the database

```sql
CREATE DATABASE family_health CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

```bash
mysql -u root -p family_health < src/main/resources/sql/init.sql
mysql -u root -p family_health < src/main/resources/sql/doctor_workspace_20260921.sql
mysql -u root -p family_health < src/main/resources/sql/care_platform_upgrade_20260921.sql
```

New databases require all three scripts in this order: [base schema](src/main/resources/sql/init.sql), [doctor workspace](src/main/resources/sql/doctor_workspace_20260921.sql), then [care platform](src/main/resources/sql/care_platform_upgrade_20260921.sql). `init.sql` alone does not contain the care-journey tables. Existing installations apply only the applicable idempotent upgrade scripts after backing up data; do not rerun the baseline as a reset. [demo-data.sql](src/main/resources/sql/demo-data.sql) is optional, local-only demo data and must not be imported into production.

The additive [patient specialty role upgrade](src/main/resources/sql/patient_specialty_roles_20260923.sql) runs on MySQL/MariaDB application startup. Back up the database before deploying this release; the database account needs `CREATE` and `INSERT` permissions. Existing patients are not assigned a specialty role automatically. Assign the dialysis specialty role in the patient editor to enable dialysis navigation for an individual patient.

### 2. Configure environment variables

Copy `.env.example` and set at least the following. Compose reads `.env`, but Maven/Spring Boot does not automatically load it: set these values in your shell or IDE run configuration before starting the backend.

```dotenv
DB_URL=jdbc:mysql://127.0.0.1:3306/family_health?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
DB_USERNAME=family_health
DB_PASSWORD=replace-with-a-strong-password
JWT_SECRET=replace-with-at-least-32-random-bytes
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_USERNAME=admin
BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-strong-password
```

Bootstrap runs only when explicitly enabled. Use a dedicated administrator username: if that username already exists, bootstrap grants it the administrator role. Disable bootstrap after setup and change the initial password immediately.

### 3. Start the backend

```bash
mvn spring-boot:run
```

The API listens on `http://localhost:8082` by default; its anonymous liveness endpoint is `http://localhost:8082/api/health`.

### 4. Start the frontend

```bash
cd frontend
npm ci
npm run dev
```

Vite normally serves `http://localhost:5174` and proxies `/api` to `http://localhost:8082`. Set `DEV_PROXY_TARGET` in `frontend/.env.local` to change the development proxy. The frontend uses the fixed `/api` prefix; production Nginx must proxy that prefix to the backend. For the Chinese development frontend, run the same commands in `cn/frontend` and open `/cn/`.

## Key configuration

| Variable | Purpose | Production guidance |
| --- | --- | --- |
| `DB_URL` | MySQL connection | Use TLS and a least-privilege database user |
| `DB_USERNAME` / `DB_PASSWORD` | Database credentials | Store in a secret manager |
| `JWT_SECRET` | Token signature | At least 32 random bytes; rotate deliberately |
| `JWT_EXPIRATION_MS` | Token lifetime in milliseconds | Use a short, risk-appropriate duration |
| `CORS_ALLOWED_ORIGINS` | Browser origins | List exact HTTPS origins |
| `BOOTSTRAP_ADMIN_*` | First administrator | Enable once, then disable |
| `OCR_API_KEY` / `OCR_BASE_URL` / `OCR_MODEL` | Optional OCR provider | Keep API keys server-side |
| `DEEPSEEK_API_KEY` / `DEEPSEEK_API_URL` / `DEEPSEEK_API_MODEL` | Optional AI analysis | Review privacy and retention policies first |
| `DEV_PROXY_TARGET` | Vite development API upstream | Not used by production; production proxies `/api` |

See [.env.example](.env.example) and [application.yml](src/main/resources/application.yml) for the complete list.

## Notifications

The platform can send proactive notifications through configured WeCom or DingTalk bot webhooks and browser notifications. A public IP is not required for outbound webhook delivery; the server only needs outbound HTTPS access.

Receiving and processing inbound WeCom messages is a separate integration. It requires a WeCom custom application or a supported long-connection bot, signature verification, replay protection, permission scoping, and message-processing rules. A callback-based deployment normally needs a public HTTPS endpoint; a supported long-connection mode can avoid that requirement.

Webhook URLs and bot secrets are sensitive. They are masked in API responses and must never be committed to source control.

## Database policy

- New installations run the base schema, doctor-workspace migration and care-platform migration in the documented order (currently 70 application tables across the scripts).
- `demo-data.sql` is strictly optional and must never be used in production.
- The application does not silently create or alter production tables at startup.
- `init.sql` is not an upgrade or reset tool for a populated database. Back up data before any manual database operation.
- After the first public release, use versioned Flyway or Liquibase migrations for schema upgrades.

## Test and build

```bash
mvn clean test
cd frontend
npm test
npm run build
cd ../
node --test demo/tests/*.test.mjs
```

Convenience commands are also available through the [Makefile](Makefile):

```bash
make test
make build
```

## Production checklist

- Rotate all database, JWT, AI, webhook, and bootstrap credentials.
- Disable bootstrap administrator creation after initial setup.
- Use HTTPS, exact CORS origins, secure reverse-proxy headers, and request-size limits.
- Use a managed MySQL backup policy and test restore procedures.
- The in-app family archive is a scoped export, not disaster recovery: it covers the record types listed in its preview, excludes consultations/chat, care-journey and doctor-workspace records, access grants and system accounts, and includes only database-embedded attachments. Restore creates independent copies with medication reminders and automated analysis disabled; notification channels and caregivers must be configured again. Back up the database and attachment storage separately.
- Store large attachments in access-controlled object storage with malware scanning and lifecycle rules.
- Review the [security policy](SECURITY.md), [release checklist](docs/OPEN_SOURCE_RELEASE_CHECKLIST.md), and [product review](docs/PRODUCT_REVIEW.md).
- This Java 8 / Spring Boot 2.5 baseline favors compatibility. Upgrade to a supported runtime and framework before a long-lived public production deployment.

## Documentation

- [Platform User Guide](docs/USER_GUIDE.md)
- [Family Care Guide](docs/family-care-guide.md)
- [Everyday Health Workflows](docs/family-health-workflows.md)
- [Open-source Release Checklist](docs/OPEN_SOURCE_RELEASE_CHECKLIST.md)
- [Product Review](docs/PRODUCT_REVIEW.md)

## Project structure

```text
├── deploy/                         # Backend image and Nginx examples
├── demo/                           # Standalone interactive static demo
├── docs/                           # User, deployment, and product documentation
├── frontend/                       # Vue 3 web application
├── src/main/java/                  # Spring Boot API
├── src/main/resources/sql/
│   ├── init.sql                    # Base schema; also run the required migrations
│   └── demo-data.sql               # Optional local demo data
├── .env.example                    # Configuration template without real secrets
├── .env.production.example         # Existing-database production template
├── docker-compose.production.yml   # Bilingual production deployment
└── docker-compose.yml              # Local full-stack demo
```

## License

Released under the [MIT License](LICENSE).
