# Contributing

Thank you for helping improve Chengxin Health. Contributions should preserve patient privacy, data ownership, accessibility, and the clarity of everyday care workflows.

## Development workflow

1. Create a focused branch and keep unrelated changes separate.
2. Update or add tests for behavior changes.
3. Run `make test` and `make build`.
4. In the pull request, explain the user problem, implementation, verification, and any database or configuration impact.

Before the first public release, schema changes must update `src/main/resources/sql/init.sql` so new installation remains a single, reliable path. After public releases begin, provide reviewed versioned migrations for deployed databases. Never modify production tables silently at application startup.

## Product and security expectations

- Never commit real health data, credentials, tokens, webhook URLs, or provider keys.
- Use fictional, clearly labeled demo data.
- Enforce patient ownership on the server; hidden UI controls are not authorization.
- Keep destructive actions explicit and confirm irreversible operations.
- Preserve keyboard access, readable contrast, responsive layouts, and meaningful empty/error states.
- Keep system-owned UI text, validation/errors, accessibility labels, and generated labels consistent with the edition: English in the root application/demo and Chinese in `cn/`. Reports must follow the explicitly selected output language. Localize known presentation fields, never recursively translate API payloads or overwrite clinical prose, patient input, medication names, actor identities, or source values. Retain necessary medical abbreviations and stable machine-readable codes.
