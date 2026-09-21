# Pre-release Product Review

## Release baseline achieved

- A unified family-care workspace connects daily tasks, medications, appointments, symptoms, questions, handovers, records, and health trends.
- Patient switching and server-side data scopes reduce accidental cross-patient access.
- Medication check-ins update inventory idempotently and preserve movement history.
- Medical records support attachments, optional OCR, structured values, abnormal-result review, and trends.
- Dialysis, dry weight, nutrition, alerts, reports, data export, backup, notifications, and audit logs form a credible specialist workflow.
- The repository includes a full installation baseline, optional fictional demo data, CI, Docker examples, a static demo, security guidance, and user documentation.
- The interface, API messages, baseline menu data, demo, and public documentation use English, with a localization foundation for future languages.
- The Clinical Workbench unifies prioritized attention items, source-aware data review, medication safety screening, dialysis quality metrics, emergency handoff, and controlled FHIR/device imports.
- Safety-critical automation is gated: alert scans deduplicate by source observation, alert transitions retain an audit trail, AI schedules create review-required drafts, imports require preview and confirmation, and recurring dialysis schedules are never inferred silently.

## Deliberate simplifications

- Historical pre-release migration scripts were removed because there are no deployed legacy users. `init.sql` is the only installation baseline.
- The static demo does not imitate authentication, OCR, AI, notification delivery, or persistent storage. It demonstrates local product interaction without misleading users.
- No default production account is created. Bootstrap administration requires explicit configuration.

## Competitive roadmap

- Upgrade to supported Java and Spring Boot versions and maintain dependency vulnerability scanning.
- Move attachments to encrypted object storage with malware scanning and lifecycle controls.
- Add Redis-backed distributed rate limits, session revocation, and task locking for multi-instance deployments.
- Introduce Flyway or Liquibase when post-release database upgrades become necessary.
- Add full browser end-to-end tests, automated accessibility checks, and real-device mobile regression.
- Continue reducing large frontend chunks through route-level and component-level loading.
- Add supported inbound WeCom integration through a custom application or long-connection bot. Current webhooks provide outbound notifications only.
- Add additional language packs through the localization layer after the English baseline stabilizes.
- Expand the curated medication interaction catalogue with a licensed drug knowledge base and pharmacist governance.
- Add standards-conformance testing for additional FHIR resources and authenticated device connectors.
