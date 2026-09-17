# Clarity Health

An open-source family health management platform for organizing care tasks, vital signs, medications, medical records, dialysis data, health analytics, notifications, and family collaboration in one workspace.

[![Backend CI](https://img.shields.io/badge/backend-Spring%20Boot-6DB33F)](pom.xml)
[![Frontend CI](https://img.shields.io/badge/frontend-Vue%203-42B883)](frontend/package.json)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> This project helps people record and organize health information. It does not diagnose conditions or replace professional medical advice.

## Product capabilities

- Family-care workspace with patient switching, daily tasks, handovers, shared caregivers, symptom tracking, and visit questions.
- Medication catalog, prescription history, reminders, dose check-ins, inventory, restocking, and low-stock warnings.
- Blood-pressure and glucose tracking, personal targets, health timeline, and CSV export.
- Medical-record upload, attachments, optional OCR, structured results, abnormal-result review, and trend charts.
- Dialysis records, schedules, dry-weight management, text import, statistics, and AI-assisted analysis.
- Nutrition diary and assessment, complication tracking, alert rules, reports, and scheduled analysis.
- WeCom/DingTalk webhook notifications, browser notifications, delivery channels, and audit logs.
- Account, role, menu, permission, privacy-masking, backup, and restore controls.

## Try the static demo

The repository includes a backend-free interactive demo with fictional data:

- Source: [demo/index.html](demo/index.html)
- Local preview: serve the `demo` directory with any static HTTP server.
- Public GitHub Pages deployment: enable the included [workflow](.github/workflows/demo-pages.yml) after pushing the project to GitHub. The final public URL depends on your GitHub account and repository name.

The demo supports patient switching, care-task completion, medication inventory updates, blood-pressure entry, trend ranges, sample records, CSV export, and reset. It sends no network requests and stores no data.

## Full local demo with Docker

Requirements: Docker Engine 24+ and Docker Compose v2.

```bash
cp .env.example .env
docker compose up --build
```

Open `http://localhost:8088`. The optional Docker demo data creates this local-only account:

```text
Username: demo
Password: Demo@123456
```

Change all secrets before exposing a deployment to any network. The Docker demo is intended for evaluation, not production.

## Technology stack

- Backend: Java 8, Spring Boot 2.5, MyBatis-Plus, MySQL 8, JWT.
- Frontend: Vue 3, Vite, Vue Router, Element Plus, ECharts.
- Testing: JUnit 5, Mockito, H2 MySQL mode, Node test runner.
- Deployment: Docker Compose, Nginx, GitHub Actions, GitHub Pages for the static demo.

## Local development

### Requirements

- JDK 8
- Maven 3.8+
- Node.js 20+
- MySQL 8.0+

### 1. Create the database

```sql
CREATE DATABASE family_health CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

```bash
mysql -u root -p family_health < src/main/resources/sql/init.sql
```

This is the first public release. Run [init.sql](src/main/resources/sql/init.sql) once for a new database; no migration scripts are required. It contains the complete schema and baseline configuration, creates no user account, and includes no real business data. [demo-data.sql](src/main/resources/sql/demo-data.sql) is optional, local-only demo data and must not be imported into production.

### 2. Configure environment variables

Copy `.env.example` and set at least:

```dotenv
DB_URL=jdbc:mysql://127.0.0.1:3306/family_health?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
DB_USERNAME=family_health
DB_PASSWORD=replace-with-a-strong-password
JWT_SECRET=replace-with-at-least-32-random-bytes
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_USERNAME=admin
BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-strong-password
```

The bootstrap administrator is created only when explicitly enabled and only if no account exists. Disable it after first sign-in and change the password immediately.

### 3. Start the backend

```bash
mvn spring-boot:run
```

The API listens on `http://localhost:9090` by default.

### 4. Start the frontend

```bash
cd frontend
npm ci
npm run dev
```

Vite prints the local URL. Set `VITE_API_BASE_URL` when the API is not available through `/api` on the same origin.

## Key configuration

| Variable | Purpose | Production guidance |
| --- | --- | --- |
| `DB_URL` | MySQL connection | Use TLS and a least-privilege database user |
| `DB_USERNAME` / `DB_PASSWORD` | Database credentials | Store in a secret manager |
| `JWT_SECRET` | Token signature | At least 32 random bytes; rotate deliberately |
| `JWT_EXPIRATION` | Token lifetime | Use a short, risk-appropriate duration |
| `APP_CORS_ALLOWED_ORIGINS` | Browser origins | List exact HTTPS origins |
| `BOOTSTRAP_ADMIN_*` | First administrator | Enable once, then disable |
| `OCR_VISION_*` | Optional OCR provider | Keep API keys server-side |
| `AI_*` | Optional AI analysis | Review privacy and retention policies first |
| `VITE_API_BASE_URL` | Frontend API prefix | Usually `/api` behind a reverse proxy |

See [.env.example](.env.example) and [application.yml](src/main/resources/application.yml) for the complete list.

## Notifications

The platform can send proactive notifications through configured WeCom or DingTalk bot webhooks and browser notifications. A public IP is not required for outbound webhook delivery; the server only needs outbound HTTPS access.

Receiving and processing inbound WeCom messages is a separate integration. It requires a WeCom custom application or a supported long-connection bot, signature verification, replay protection, permission scoping, and message-processing rules. A callback-based deployment normally needs a public HTTPS endpoint; a supported long-connection mode can avoid that requirement.

Webhook URLs and bot secrets are sensitive. They are masked in API responses and must never be committed to source control.

## Database policy

- `init.sql` is the single baseline for a new installation and currently defines 39 tables.
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
│   ├── init.sql                    # Complete baseline for a new database
│   └── demo-data.sql               # Optional local demo data
├── .env.example                    # Configuration template without real secrets
└── docker-compose.yml              # Local full-stack demo
```

## License

Released under the [MIT License](LICENSE).
