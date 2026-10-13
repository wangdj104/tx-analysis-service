# Contributing

Thank you for helping improve Chengxin Health. Contributions should preserve patient privacy, data ownership, accessibility, and the clarity of everyday care workflows.

## Development workflow

1. Create a focused branch and keep unrelated changes separate.
2. Keep both editions aligned and verify changed workflows.
3. Run `make build` and check the changed workflows manually.
4. In the pull request, explain the user problem, implementation, verification, and any database or configuration impact.

Keep the complete schema, indexes and initial dictionaries in [init.sql](src/main/resources/sql/init.sql), and document behavior in the [SQL initialization guide](README.md#database-initialization). Both editions must define the same structure with localized seed labels. New databases run this single file; application startup performs no schema migration. Compare existing schemas before applying reviewed differences and keep a complete backup. Optional demo data remains outside production initialization.

## Product and security expectations

- Never commit real health data, credentials, tokens, webhook URLs, or provider keys.
- Use fictional, clearly labeled demo data.
- Enforce patient ownership on the server; hidden UI controls are not authorization.
- Keep destructive actions explicit and confirm irreversible operations.
- Preserve keyboard access, readable contrast, responsive layouts, and meaningful empty/error states.
- Keep system-owned UI text, validation/errors, accessibility labels, and generated labels consistent with the edition: English in the root application/demo and Chinese in `cn/`. Reports must follow the explicitly selected output language. Localize known presentation fields, never recursively translate API payloads or overwrite clinical prose, patient input, medication names, actor identities, or source values. Retain necessary medical abbreviations and stable machine-readable codes.
