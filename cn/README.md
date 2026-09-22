# 澄心健康

[English](../README.md) · **简体中文** · [英文演示](https://wangdj104.github.io/tx-analysis-service/) · [中文演示](https://wangdj104.github.io/tx-analysis-service/cn/)

澄心健康是一个连接医生、患者与家属的开源连续健康管理平台。平台围绕同一份纵向健康档案，整合医生复核、照护计划、日常指标、用药依从、医疗记录、透析、营养、预警和家庭协作，同时明确区分 AI 辅助信息与医生诊断。

[![后端](https://img.shields.io/badge/backend-Spring%20Boot-6DB33F)](pom.xml)
[![前端](https://img.shields.io/badge/frontend-Vue%203-42B883)](frontend/package.json)
[![许可证：MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> 本项目用于记录和整理健康信息，不提供疾病诊断，也不能替代专业医疗建议。

## 产品能力

- 医生、患者、家属和管理员拥有不同的工作台、菜单与数据范围。
- 医生工作台支持已分配患者、重点风险、临床笔记、照护计划，以及对导入记录和 AI 辅助分析的人工复核。
- 家庭照护工作台：患者切换、每日任务、照护交接、共享照护、症状跟踪和就诊问题。
- 用药管理：药品库、用药记录、提醒、服药确认、库存、补货和低库存预警。
- 血压血糖：日常记录、个人目标、健康时间线和 CSV 导出。
- 医疗记录：报告上传、附件、可选 OCR、结构化结果、异常复核和趋势图。
- 透析管理：透析记录、排班、干体重、文本导入、统计和 AI 辅助分析。
- 营养日记与评估、并发症跟踪、预警规则、健康报告和定时分析。
- 企业微信／钉钉机器人通知、浏览器通知、通知渠道和操作审计。
- 账号、角色、菜单、权限、隐私脱敏、备份和恢复。

## 静态演示

仓库内提供使用虚构数据、无需后端的双语交互式产品演示：

- **中文：**[在线体验](https://wangdj104.github.io/tx-analysis-service/cn/) · [源文件](demo/index.html)
- **English:** [Live demo](https://wangdj104.github.io/tx-analysis-service/) · [source](../demo/index.html)

演示可切换医生、患者、家属和管理员身份。“照护全流程”页面完整映射本次 40 项需求，覆盖慢病管理、预约复诊、远程问诊、住院康复、居家照护、急救、儿童孕产、心理健康、权限隐私和系统运营。操作会更新当前标签页中的演示状态和审计事件，不会发送网络请求或执行真实临床处理。

## 中英文目录约定

仓库根目录是英文版，`cn/` 是完整中文版，包含后端、前端、SQL、文档和静态演示。迭代功能时请在同一个提交中同步更新两套版本；协议字段、权限标识、表名和 API 路径保持一致，仅对用户可见文本进行本地化。

## 使用 Docker 启动完整演示

要求：Docker Engine 24+、Docker Compose v2。

```bash
cp .env.example .env
docker compose up --build
```

打开 `http://localhost:8088`。可选演示数据会创建仅供本地使用的账号：

```text
用户名：demo
密码：Demo@123456
```

将服务暴露到任何网络前，请更换全部密钥。Docker 演示仅用于评估，不应用于生产环境。

## 中英文生产部署

生产镜像会在 `/` 提供英文正式系统，在 `/cn/` 提供中文正式系统。用户通过页面右上角切换语言时会保留当前业务页面；两套界面共用同一个 API、账号、登录状态和数据库。

```bash
cp .env.production.example .env.production
# 编辑 .env.production，填写现有数据库和正式访问地址。
docker compose --env-file .env.production -f docker-compose.production.yml up -d --build
```

访问 `http://服务器地址:8080/` 或 `http://服务器地址:8080/cn/`。同一镜像还在 `/demo/` 和 `/cn/demo/` 提供静态演示。生产编排不会创建 MySQL，也不会导入 `demo-data.sql`，只会连接 `DB_URL` 指定的现有数据库。

## 技术栈

- 后端：Java 8、Spring Boot 2.5、MyBatis-Plus、MySQL 8、JWT。
- 前端：Vue 3、Vite、Vue Router、Element Plus、ECharts。
- 测试：JUnit 5、Mockito、H2 MySQL 模式、Node.js 测试运行器。
- 部署：Docker Compose、Nginx、GitHub Actions、GitHub Pages。

## 本地开发

环境要求：JDK 8、Maven 3.8+、Node.js 20+、MySQL 8.0+。

### 1. 创建数据库

```sql
CREATE DATABASE family_health CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

```bash
mysql -u root -p family_health < src/main/resources/sql/init.sql
```

原始基线使用 [init.sql](src/main/resources/sql/init.sql)。已有数据库可依次执行幂等的[医生工作台升级脚本](src/main/resources/sql/doctor_workspace_20260921.sql)和[照护平台升级脚本](src/main/resources/sql/care_platform_upgrade_20260921.sql)；后者新增全流程照护、授权、排班、问诊、康复、急救、专项健康、心理健康、审计和通知投递表。[demo-data.sql](src/main/resources/sql/demo-data.sql) 仅用于本地演示，禁止导入生产环境。

### 2. 配置环境变量

复制 `.env.example`，至少填写以下变量：

```dotenv
DB_URL=jdbc:mysql://127.0.0.1:3306/family_health?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
DB_USERNAME=family_health
DB_PASSWORD=replace-with-a-strong-password
JWT_SECRET=replace-with-at-least-32-random-bytes
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_USERNAME=admin
BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-strong-password
```

仅当显式启用且数据库中没有账号时，系统才会创建初始管理员。首次登录并修改密码后，请关闭该功能。

### 3. 启动后端

```bash
mvn spring-boot:run
```

API 默认监听 `http://localhost:9090`。

### 4. 启动前端

```bash
cd frontend
npm ci
npm run dev
```

Vite 会输出本地访问地址。当 API 不通过同源 `/api` 提供时，请配置 `VITE_API_BASE_URL`。

## 关键配置

| 变量 | 用途 | 生产建议 |
| --- | --- | --- |
| `DB_URL` | MySQL 连接 | 启用 TLS，并使用最小权限数据库账号 |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库凭据 | 存放在密钥管理服务中 |
| `JWT_SECRET` | 令牌签名 | 至少 32 个随机字节，并有计划地轮换 |
| `JWT_EXPIRATION` | 令牌有效期 | 使用符合风险要求的较短时长 |
| `APP_CORS_ALLOWED_ORIGINS` | 浏览器来源 | 仅填写明确的 HTTPS 来源 |
| `BOOTSTRAP_ADMIN_*` | 初始管理员 | 仅启用一次，随后关闭 |
| `OCR_VISION_*` | 可选 OCR 服务 | API 密钥仅保存在服务端 |
| `AI_*` | 可选 AI 分析 | 先审查隐私和数据保留策略 |
| `VITE_API_BASE_URL` | 前端 API 前缀 | 反向代理后通常为 `/api` |

完整配置请查看 [.env.example](.env.example) 和 [application.yml](src/main/resources/application.yml)。

## 通知与数据安全

平台可通过企业微信或钉钉机器人 Webhook 及浏览器通知主动发送消息。Webhook 地址和机器人密钥属于敏感信息，API 返回时会脱敏，严禁提交到版本库。

`init.sql` 是新安装的唯一基线；`demo-data.sql` 只用于本地演示。应用启动时不会静默创建或修改生产表。对已有数据库执行人工操作前必须备份数据；后续结构升级应使用 Flyway 或 Liquibase 等版本化迁移工具。

## 测试与构建

```bash
mvn clean test
cd frontend
npm test
npm run build
cd ../
node --test demo/tests/*.test.mjs
```

也可使用 [Makefile](Makefile) 执行 `make test` 和 `make build`。

## 生产检查

- 轮换数据库、JWT、AI、Webhook 和初始管理员凭据。
- 首次初始化后关闭初始管理员创建功能。
- 启用 HTTPS、精确 CORS 来源、安全反向代理头和请求大小限制。
- 制定 MySQL 备份策略并定期验证恢复流程。
- 大型附件应存放在带访问控制、恶意软件扫描和生命周期策略的对象存储中。
- 上线前审查 [安全策略](SECURITY.md)、[开源发布检查表](docs/OPEN_SOURCE_RELEASE_CHECKLIST.md) 和 [产品评审](docs/PRODUCT_REVIEW.md)。
- Java 8 / Spring Boot 2.5 基线以兼容性为主；长期生产部署前应升级到受支持的运行时和框架。

## 项目结构

```text
├── deploy/                         # 后端镜像和 Nginx 示例
├── demo/                           # 独立静态交互演示
├── docs/                           # 用户、部署和产品文档
├── frontend/                       # Vue 3 Web 应用
├── src/main/java/                  # Spring Boot API
├── src/main/resources/sql/
│   ├── init.sql                    # 新数据库完整基线
│   └── demo-data.sql               # 可选本地演示数据
├── .env.example                    # 不含真实密钥的配置模板
├── .env.production.example         # 连接现有数据库的生产配置模板
├── docker-compose.production.yml   # 中英文生产部署
└── docker-compose.yml              # 本地全栈演示
```

## 许可证

本项目采用 [MIT 许可证](LICENSE) 发布。
