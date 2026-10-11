# Contributing

感谢你帮助改进澄心健康。所有贡献都应保护患者隐私和数据所有权，并兼顾无障碍体验与日常照护流程的清晰度。

## Development workflow

1. Create a focused branch and keep unrelated changes separate.
2. Update or add tests for behavior changes.
3. Run `make test` and `make build`.
4. In the pull request, explain the user problem, implementation, verification, and any database or configuration impact.

Keep fresh-install definitions and reviewed upgrades consistent, and document dependencies in the [SQL execution catalog](src/main/resources/sql/README.md). A complete new database requires init, doctor workspace, care platform and care-plan collaboration in that order. Existing databases need a complete backup and only applicable upgrades; init is not a reset tool. The current `patient_specialty_roles_20260923.sql` runs on every MySQL/MariaDB application startup, including after a manual run, and has no disable switch in this release. Document this existing write behavior and required schema-scoped privileges; do not add unreviewed startup migrations. Review role-permission cleanup separately because it deletes roles and assignments.

## Product and security expectations

- Never commit real health data, credentials, tokens, webhook URLs, or provider keys.
- Use fictional, clearly labeled demo data.
- Enforce patient ownership on the server; hidden UI controls are not authorization.
- Keep destructive actions explicit and confirm irreversible operations.
- Preserve keyboard access, readable contrast, responsive layouts, and meaningful empty/error states.
- Keep all public UI text, API messages, documentation, and baseline data in English. Add new languages through the localization layer rather than duplicating pages.
