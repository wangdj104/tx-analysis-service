# Contributing

感谢你帮助改进澄心健康。所有贡献都应保护患者隐私和数据所有权，并兼顾无障碍体验与日常照护流程的清晰度。

## Development workflow

1. Create a focused branch and keep unrelated changes separate.
2. Keep both editions aligned and verify changed workflows.
3. Run `make build` and check the changed workflows manually.
4. In the pull request, explain the user problem, implementation, verification, and any database or configuration impact.

将完整表结构、索引和初始字典统一维护在 [init.sql](src/main/resources/sql/init.sql)，执行边界见 [SQL 初始化说明](README.md#database-initialization)。中英文版本保持相同结构，种子显示文字分别本地化。新库仅执行此文件，应用启动不运行结构迁移。已有库先完整备份并比对结构，再执行经核对的差异；演示数据与生产初始化分开。

## Product and security expectations

- Never commit real health data, credentials, tokens, webhook URLs, or provider keys.
- Use fictional, clearly labeled demo data.
- Enforce patient ownership on the server; hidden UI controls are not authorization.
- Keep destructive actions explicit and confirm irreversible operations.
- Preserve keyboard access, readable contrast, responsive layouts, and meaningful empty/error states.
- Keep all public UI text, API messages, documentation, and baseline data in English. Add new languages through the localization layer rather than duplicating pages.
