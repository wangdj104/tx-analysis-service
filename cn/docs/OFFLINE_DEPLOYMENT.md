# 运维交接：自行执行数据库与部署操作

本文是供运维人员执行的步骤，不代表数据库已迁移或服务器已上线。命令均从仓库根目录运行。后端镜像使用 JDK 17 构建，字节码目标仍为 Java 8。Docker 构建需要下载依赖及发行版字体；隔离网络服务器应先按自己的受控流程构建、转移镜像。

## 1. 备份与 MySQL 准备

以下包含报告审核的完整安装要求使用 **MySQL 5.7.18**。部署前须在实际目标引擎上验证完整初始化及运行时结构契约。

### 先选择一版及一条安装路径

命令从仓库根目录运行。英文选择 `SQL_DIR=src/main/resources/sql`，中文选择 `SQL_DIR=cn/src/main/resources/sql`，同一个库只能选一版。先检查实际表、字段、索引、外键、触发器、过程、引擎／行格式、SQL模式及版本；未知、冲突或部分对象必须停下审查，不能覆盖。

变更前停止全部应用写入与调度，备份**完整数据库**、附件／对象存储、镜像及配置，由运维验证隔离恢复并记录原有数量、结构及历史基线。家庭归档不是完整灾备，`scripts/RunSqlMigration.java`只复制六张旧表。本源码医疗附件位于MySQL的`medical_record_attachment.file_content`；额外存储须单独备份。保留备份与逐步执行记录，直到后检及恢复验收完成。

由DBA审查初始化账号在指定schema内的CREATE／ALTER／INDEX／REFERENCES／TRIGGER及所需读取权限；完整初始化还需要对应的过程及基础数据写入权限。开启二进制日志时还可能有既有触发器创建限制。权限或策略不足就**停下交DBA处理**，不要授予SUPER、修改`log_bin_trust_function_creators`、关闭安全校验或改服务器设置来强行通过。运行账号无需启动迁移DDL权限。

### 空库：只用完整初始化

必须是新建且核实为空的 MySQL 5.7.18 schema，使用 `utf8mb4_unicode_ci` 等兼容排序规则。只执行本语言版的[完整初始化脚本](../src/main/resources/sql/init.sql)，其中包含系统表、索引、外键、工作流触发器及必要基础数据，包括报告审核。

```bash
SQL_DIR=cn/src/main/resources/sql  # 英文改为 src/main/resources/sql，只选一版
mysql --host=DB_HOST --user=SCHEMA_INSTALLER --password --database=APP_DB < "$SQL_DIR/init.sql"
```

将大写主机／账号／库占位符换成批准值，使用密码提示或既有安全凭据机制。使用支持 `DELIMITER` 的客户端，检查每项结果，遇错停止，不使用 `mysql --force`。不要对有数据或部分初始化的库重跑 init。初始化不创建管理员、不分配护理、不授权患者、不启用工作流；演示数据仅用于本地环境。

### 已有库：比对结构，只补缺失差异

历史日期升级及其他初始化文件已合并到 `init.sql` 后删除，不能把该空库脚本用于升级或重置。完成并验证备份后，将实际表、字段、索引、外键和触发器定义与所选语言版的完整初始化及运行时 `*-schema.json` 契约比对，只执行缺失且兼容的变更；冲突或部分安装单独审查，保留原有业务数据、历史和附件。

DDL 会隐式提交。变更期间停止应用写入和调度，记录已完成语句，出错后检查实际对象。重新启动前核验最终定义及原有数据。安装结构和应用启动不会自动启用功能或发送通知。

<a id="medical-review-setup"></a>

### 报告审核安装

唯一的 `init.sql` 已包含五张 `medical_review_*` 表及其前置结构，已有库按上面的结构比对流程补齐。`MEDICAL_REVIEW_API_ENABLED`、`MEDICAL_REVIEW_ENABLED` 与 `CARE_PLAN_ENABLED` 保持默认 `false`，通过结构及应用检查后再配置对应环境开关；生产 Compose 已从 `.env.production` 转发这些变量。专项计划类型使用现有 `VARCHAR` 字段，无需新增表。

<a id="isolated-restore"></a>

### 验证隔离完整恢复

停止应用写入与排程后，从仓库根目录运行 `scripts/verify-full-restore.mjs`。源与目标须为引擎版本一致、`server_uuid` 不同的两台 MySQL 服务；目标 `event_scheduler` 为 OFF 或 DISABLED，且尚无源数据库同名库，工具会在独立服务器上按原库名恢复。

分别准备仅操作者可读的 MySQL 选项文件，在 `[client]` 段填写 host、port、user、password。账号须具备完整导出／恢复权限，包含存储过程、事件和触发器；密码不进入命令行。

```bash
node scripts/verify-full-restore.mjs \
  --source-defaults /secure/source.cnf \
  --target-defaults /secure/isolated-target.cnf \
  --database family_health \
  --output /secure/new-rehearsal-directory \
  --writers-stopped
```

客户端不在 PATH 时增加 `--mysql /path/to/mysql` 和 `--mysqldump /path/to/mysqldump`。使用外部附件时增加 `--attachments-directory /path/to/attachments`，数据库中的每个文件引用必须指向该目录内的真实文件。输出目录必须尚不存在且与附件目录分离；缺失文件与符号链接会被拒绝。Windows 带空格路径须加引号，多行示例使用 Bash 语法。

输出包含 SQL、附件副本和 `verification.json`，记录结构、数据及附件内容核验结果；这些文件应保存在仓库外的受保护备份目录。`VERIFIED_QUARANTINED` 表示核验通过，但目标 `restore_quarantine` 标记仍生效，阻止应用启动及排程。工具没有解除隔离选项；启用前须另行核对当前授权、待执行工作、通知队列、全新运行时密钥／配置及附件存储。部署镜像、外部密钥、对象存储历史版本和供应商状态须单独保留，不在工具备份范围内。

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

请在实际镜像中检查中英文 PDF 下载，确认配置的完整字体可读且覆盖所需字符。

## 5. 启动、验收，再显式启用

```bash
docker compose --env-file .env.production -f docker-compose.production.yml up -d
curl --fail --silent --show-error https://health.example.com/api/health
```

替换示例域名。存活检查不能替代验收，还应检查登录、患者页面和语言切换。迁移、备份及验收通过后，才将 env 文件中的 `CARE_PLAN_ENABLED` 改为 `true`，用相同的 `docker compose ... up -d` 重建 API。协作计划和执行报告共用此开关。登录后的 `GET /api/care-plans/capabilities` 应返回 `data.enabled: true`；匿名请求不能验证开关。

用获授权的合成账号验证医生分配、发布、患者／家属执行、护理分配**以及**明确的 `CARE_PLAN` 授权、医生复核和中英文 HTML/PDF/两种 CSV 导出。管理员角色不等于临床权限。权限细则见[用户指南](USER_GUIDE.md)。真实通知送达和临床效果不属于软件验收结论。

### 启用测量随访

生产 Compose 已从 `.env.production` 转发 `ALERT_WORKFLOW_ENABLED`，准备期间保持 `false`。验证配置时不打印含密钥的展开内容：

```bash
docker compose --env-file .env.production -f docker-compose.production.yml config --quiet
```

结构、备份／恢复及应用检查通过后，设置 `ALERT_WORKFLOW_ENABLED=true` 并重建 API。`CARE_PLAN_ENABLED` 独立，仅测量护理可保持其关闭：

```bash
docker compose --env-file .env.production -f docker-compose.production.yml up -d api
# 使用已有的短期授权虚构账号会话，在可信终端关闭shell跟踪。
curl --fail --silent --show-error --header "Authorization: Bearer $HEALTH_ACCEPTANCE_TOKEN" "$HEALTH_ORIGIN/api/alert-workflows/capabilities"
```

`HEALTH_ORIGIN`设为获批准HTTPS来源，会话令牌经正常登录流程获取，不粘贴进文档／日志或提交仓库。启用验收须由已登录响应明确返回`data.enabled: true`；匿名请求、健康接口或菜单可见都不能证实。用虚构数据验证保存／读取、实名接手、协助反馈、拒绝所有者／护士临床关闭、医生关闭、当前权限撤销及不确定结果恢复。本次没有执行真实浏览器或HTTP验收。

停用时设 `ALERT_WORKFLOW_ENABLED=false` 并重建 API，保留结构及历史；停用不恢复数据库、不解除已追踪来源保护。禁止用 `down -v` 处理有数据环境。

停用时将开关改回 `false` 并重建 API，保留全部结构和历史；停用不是数据库回滚。不能用 `down -v` 排查有记录的数据库。当前源码的病历附件存储在 MySQL 的 `medical_record_attachment.file_content` 中，空的 `/app/uploads` 目录不是附件存储。另行配置宿主机／容器标准输出日志的保留策略；部署另加的外部附件存储须独立备份。

### 来源版本资格信息

完整初始化包含服务端专用 `alert_source_revision.action_versions_json` 字段，在同一更正事务内记录受影响计划行动版本，未附加到随访的回执也纳入。版本不可变守卫保护该信息；普通来源历史、计划事件、旧界面和家庭格式 1 导出不暴露它。服务端写入的 `{}` 才表示确知为空；NULL、缺失或无效内容表示未知并阻断资格，不得以补 `{}` 的方式“修复”。比对已有库时须一起核对该字段及守卫，并保留原有历史和备份。
