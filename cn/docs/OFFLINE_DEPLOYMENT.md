# 运维交接：自行执行数据库与部署操作

本文是供运维人员执行的步骤，不代表数据库已迁移或服务器已上线。命令均从仓库根目录运行。后端镜像使用 JDK 17 构建，字节码目标仍为 Java 8。Docker 构建需要下载依赖及发行版字体；隔离网络服务器应先按自己的受控流程构建、转移镜像。

## 1. 备份与 MySQL 准备

使用 MySQL 8.0。升级已有系统前，备份**完整数据库**、保留当前镜像与配置，并在隔离库演练恢复；如另外配置了附件或对象存储，也须独立备份。应用内家庭备份不是完整灾备。`scripts/RunSqlMigration.java` 是历史照护平台辅助工具，只复制六张旧表并检查旧结构，不能凭它的成功提示认定完整备份或协作迁移已完成。

新空库由获授权的迁移账号依次执行以下脚本。通过 MySQL 客户端密码提示或自己的安全凭据机制登录，勿把密码放进命令行参数：

1. `src/main/resources/sql/init.sql`
2. `src/main/resources/sql/doctor_workspace_20260921.sql`
3. `src/main/resources/sql/care_platform_upgrade_20260921.sql`
4. `src/main/resources/sql/care_plan_collaboration_20261003.sql`
5. `src/main/resources/sql/patient_specialty_roles_20260923.sql`

第五个脚本在每次 MySQL/MariaDB 应用启动时还会自动执行。手动执行**不会**关闭此行为。运行账号除业务数据权限外，仍需启动脚本用到的 `CREATE`、`SELECT`、`INSERT`、`UPDATE`；创建外键还可能需要被引用表的 `REFERENCES` 权限。权限限定在应用库中。本版本没有关闭自动专病迁移的开关，其他结构升级由运维执行。

已有库应先核对版本与结构，再按依赖顺序补充适用升级，不能重跑 `init.sql` 当作重置。较旧系统还可能需要 `functional_review_repair_20260922.sql`（导入、告警、用药安全表）及 `notification_language_preference_20260922.sql`（用户偏好）；当前新库基线已包含这些内容。`role_permission_cleanup_20260922.sql` 会改动角色和菜单分配并删除旧角色，须独立审查，不能当作通用纯增量脚本直接执行。生产环境禁止导入 `demo-data.sql`。

执行报告不需要当前协作结构之外的新迁移。迁移不会为护理账号分配患者、授予患者权限或发布旧计划。准备期间保留 `CARE_PLAN_ENABLED=false`。

## 2. 配置现有数据库部署

将 `.env.production.example` 复制为仅文件所有者可读写的 `.env.production`，在本机填写数据库主机、库名、用户名、密码、JWT 密钥及正式 HTTPS 来源。不要提交此文件或分享展开后的 Compose 配置。数据库主机名须匹配 TLS 证书，证书 CA 须被 API JVM 信任；私有 CA 需配置自己的信任库。模板的 `sslMode=VERIFY_IDENTITY` 同时验证证书信任与主机名，不能通过关闭校验来处理 TLS 错误。详见 [Connector/J 安全配置](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-security.html)。

```bash
umask 077
cp .env.production.example .env.production
# 先在本机编辑 .env.production，再继续。
docker compose --env-file .env.production -f docker-compose.production.yml config --quiet
docker compose --env-file .env.production -f docker-compose.production.yml build
```

模板的 `WEB_PORT=127.0.0.1:8080` 只在宿主机回环地址提供 HTTP。用宿主机现有反向代理配置正式域名及有效 TLS 证书，将 `/`（含 `/api/`、`/cn/`、`/demo/`、`/cn/demo/`）转发到 `http://127.0.0.1:8080`。保留 Host 头，允许 100 MB 请求及至少 120 秒上游读取时间。8080 不对外公开，公网 HTTP 重定向至 HTTPS；`CORS_ALLOWED_ORIGINS` 填写该精确 HTTPS 来源。外部代理若运行在另一个容器中，应自行配置私有网络和绑定方式，不能照用宿主机回环地址。`deploy/nginx.conf.example` 用于宿主机静态文件加直接运行的 API，并非此 Compose 的外层代理配置。

## 3. 仅在没有管理员时创建初始账号

已有管理员时跳过。生产 Compose 的常规启动明确关闭 bootstrap。完成结构初始化和镜像构建后，选择数据库中不存在的独立用户名；否则 bootstrap 会给已有同名账号授予管理员权限，并且不替换其原密码。以下临时 API 容器**不发布服务端口**；它仍是普通服务器进程，不会创建账号后自动退出。端口与环境覆盖行为见 [Compose run 文档](https://docs.docker.com/reference/cli/docker/compose/run/)。

```bash
read -r -p '新管理员用户名：' BOOTSTRAP_ADMIN_USERNAME
read -r -s -p '新密码（至少 12 个字符）：' BOOTSTRAP_ADMIN_PASSWORD
printf '\n'
export BOOTSTRAP_ADMIN_USERNAME BOOTSTRAP_ADMIN_PASSWORD
docker compose --env-file .env.production -f docker-compose.production.yml run --rm --no-deps \
  -e BOOTSTRAP_ADMIN_ENABLED=true -e BOOTSTRAP_ADMIN_USERNAME -e BOOTSTRAP_ADMIN_PASSWORD api
# 在另一个终端通过 MySQL 确认此账号已获得 admin 角色。
# 然后按 Ctrl+C 停止临时前台进程。
unset BOOTSTRAP_ADMIN_USERNAME BOOTSTRAP_ADMIN_PASSWORD
```

在可信运维终端执行，并关闭 shell 跟踪。特权宿主机或容器管理员仍可查看环境变量。不要将初始密码放入长期 env 文件。正常启动后验证登录、更换初始密码，并确认只保留 bootstrap 关闭的常规 API。

## 4. PDF 字体与可选自定义挂载

镜像安装完整的发行版 `fonts-wqy-microhei` 字体。`REPORT_PDF_FONT_PATH` 留空时使用已安装候选字体。自定义时必须填写**容器内**可读文件路径，不能只填宿主机路径。选择有合法许可、TrueType 轮廓、覆盖实际报告全部字符的完整字体；不能使用测试子集，也不能假定 CFF 字体兼容。

例如创建本地 `compose.font.yml`，先把宿主机路径替换为实际存在的字体文件：

```yaml
services:
  api:
    volumes:
      - type: bind
        source: /absolute/host/path/complete-report-font.ttf
        target: /app/fonts/report.ttf
        read_only: true
        bind:
          create_host_path: false
```

在 `.env.production` 设置 `REPORT_PDF_FONT_PATH=/app/fonts/report.ttf`，后续每条 Compose 命令都在主 `-f` 参数后加上 `-f compose.font.yml`。文件需允许容器 UID 10001 读取。根目录及独立中文版本地 Compose/.env 也支持相同映射。Maven/IDE 启动应配置真实进程环境变量，并填写本机字体路径。

`scripts/prepare-care-report-font.py` 仅供 CI 使用，不是运维安装器，不能绕过其保护。已验收 CI 使用完整提取的 TTF，默认 Docker 使用已安装的 TTC，实际镜像须自行验收下载。严格报告渲染以指定字体为准，无效或缺字时明确报错，不会偷偷换字体。检查中英文 PDF，包括混合语言姓名和原文备注；HTML 预览不能证明 PDF 正常。不支持的复杂排版或字符仍会明确报错，可改用 HTML。

## 5. 启动、验收，再显式启用

```bash
docker compose --env-file .env.production -f docker-compose.production.yml up -d
curl --fail --silent --show-error https://health.example.com/api/health
```

替换示例域名。存活检查不能替代验收，还应检查登录、患者页面和语言切换。迁移、备份及验收通过后，才将 env 文件中的 `CARE_PLAN_ENABLED` 改为 `true`，用相同的 `docker compose ... up -d` 重建 API。协作计划和执行报告共用此开关。登录后的 `GET /api/care-plans/capabilities` 应返回 `data.enabled: true`；匿名请求不能验证开关。

用获授权的合成账号验证医生分配、发布、患者／家属执行、护理分配**以及**明确的 `CARE_PLAN` 授权、医生复核和中英文 HTML/PDF/两种 CSV 导出。管理员角色不等于临床权限。权限细则见[用户指南](USER_GUIDE.md)。真实通知送达和临床效果不属于软件验收结论。

停用时将开关改回 `false` 并重建 API，保留全部结构和历史；停用不是数据库回滚。不能用 `down -v` 排查有记录的数据库。当前源码的病历附件存储在 MySQL 的 `medical_record_attachment.file_content` 中，空的 `/app/uploads` 目录不是附件存储。另行配置宿主机／容器标准输出日志的保留策略；部署另加的外部附件存储须独立备份。
