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
- Keep all public UI text, API messages, documentation, and baseline data in English. Add new languages through the localization layer rather than duplicating pages.
