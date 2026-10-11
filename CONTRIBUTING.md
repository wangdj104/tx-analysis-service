# Contributing

Thank you for helping improve Chengxin Health. Contributions should preserve patient privacy, data ownership, accessibility, and the clarity of everyday care workflows.

## Development workflow

1. Create a focused branch and keep unrelated changes separate.
2. Update or add tests for behavior changes.
3. Run `make test` and `make build`.
4. In the pull request, explain the user problem, implementation, verification, and any database or configuration impact.

Keep fresh-install definitions and reviewed upgrades consistent, and document dependencies in the [SQL execution catalog](src/main/resources/sql/README.md). A complete new database requires init, doctor workspace, care platform, care-plan collaboration and `clinical_integrity_upgrade_20261011.sql` in that order. Existing databases need a complete backup and only applicable upgrades; init is not a reset tool. The current `patient_specialty_roles_20260923.sql` runs on every MySQL/MariaDB application startup, including after a manual run, and has no disable switch in this release. Document this existing write behavior and required schema-scoped privileges; do not add unreviewed startup migrations. Review role-permission cleanup separately because it deletes roles and assignments.

## Product and security expectations

- Never commit real health data, credentials, tokens, webhook URLs, or provider keys.
- Use fictional, clearly labeled demo data.
- Enforce patient ownership on the server; hidden UI controls are not authorization.
- Keep destructive actions explicit and confirm irreversible operations.
- Preserve keyboard access, readable contrast, responsive layouts, and meaningful empty/error states.
- Keep system-owned UI text, validation/errors, accessibility labels, and generated labels consistent with the edition: English in the root application/demo and Chinese in `cn/`. Reports must follow the explicitly selected output language. Localize known presentation fields, never recursively translate API payloads or overwrite clinical prose, patient input, medication names, actor identities, or source values. Retain necessary medical abbreviations and stable machine-readable codes.
