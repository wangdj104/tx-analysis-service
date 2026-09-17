# Security Policy

## Report a vulnerability

Do not open a public issue for a vulnerability that could expose accounts, health data, secrets, or deployment infrastructure. Contact the maintainer privately and include the affected version, impact, reproduction steps, and any suggested mitigation. Allow reasonable time for investigation and a coordinated fix before public disclosure.

## Deployment responsibilities

Operators must rotate all bundled or previously used credentials, configure an unpredictable JWT secret, use a least-privilege database account, restrict CORS to exact trusted HTTPS origins, and disable bootstrap administrator creation after first setup.

Treat database backups, uploaded reports, OCR payloads, AI prompts, webhook URLs, and bot secrets as sensitive health information or credentials. Encrypt data in transit and at rest, restrict access, define retention, and test recovery procedures.

This project is not a medical device and does not provide diagnosis or treatment decisions. Organizations deploying it are responsible for local privacy, health-data, medical-device, employment, accessibility, and breach-notification requirements.

## Supported versions

Security fixes target the latest release. The current Java 8 / Spring Boot 2.5 compatibility baseline is not a long-term supported security stack; upgrade the runtime and framework before a sustained public production deployment.
