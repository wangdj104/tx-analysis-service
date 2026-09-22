# 澄心健康

[English](../README.md) · **简体中文** · [英文演示](https://wangdj104.github.io/tx-analysis-service/) · [中文演示](https://wangdj104.github.io/tx-analysis-service/cn/)

澄心健康是一个连接医生、患者与家属的开源连续健康管理平台。平台围绕同一份纵向健康档案，整合医生复核、照护计划、日常指标、用药依从、医疗记录、透析、营养、预警和家庭协作，同时明确区分 AI 辅助信息与医生诊断。

[![后端](https://img.shields.io/badge/backend-Spring%20Boot-6DB33F)](pom.xml)
[![前端](https://img.shields.io/badge/frontend-Vue%203-42B883)](frontend/package.json)
[![许可证：MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> 本项目用于记录和整理健康信息，不提供疾病诊断，也不能替代专业医疗建议。

## 为什么选择澄心健康

很多健康管理问题并不是发生在医院内，而是发生在两次就诊之间：指标散落在不同设备和表格中，用药计划与实际执行脱节，家属通过聊天工具交接，医生难以及时看见真正需要关注的变化。澄心健康把这些碎片连接成一条可共享、可授权、可追溯的连续照护链路。

- **对医生：**减少重复收集信息的时间，优先呈现异常和逾期事项，对导入记录及 AI 辅助结果进行人工复核，并量化随访执行情况。
- **对患者：**清楚知道今天该做什么，低负担记录健康数据，统一保存预约、医嘱、问诊和报告，形成属于自己的连续健康档案。
- **对家属与照护者：**在明确授权范围内协同用药、测量、预约和交接，既能及时参与，又不会看到不应开放的隐私内容。
- **对医疗机构：**沉淀标准化院外管理流程，提高随访完成率和数据质量，保留完整操作痕迹，并以机构自己的品牌对外提供服务。

平台坚持**医疗专业人员在环**：系统可以自动生成提醒、识别阈值异常、汇总趋势和整理复核队列，但诊断、治疗调整、处方决策以及 AI 内容发布仍由医生负责。

## 一个平台，四类角色

| 角色 | 核心工作台 | 主要价值 |
| --- | --- | --- |
| 医生 | 分配患者、风险队列、临床复核、照护计划、排班与报表 | 把时间用于异常处置、临床判断和持续随访 |
| 患者 | 今日计划、指标记录、服药打卡、预约、问诊与档案 | 明确下一步行动，持续积累个人健康记录 |
| 家属／照护者 | 共享任务、代录、提醒、交接与急救卡 | 在清晰权限边界内安全协同照护 |
| 管理员 | 账号、角色、菜单、品牌、通知渠道与审计 | 运营可配置、可追踪的多角色健康平台 |

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

## 覆盖的完整医疗照护场景

- **日常慢病管理：**血压、血糖、血氧、体重、心率等指标录入，阈值预警、趋势复核、用药清单、服药打卡、漏服升级和周期随访任务。
- **预约与复诊：**医生可预约时段、预约、改约、取消、三方共享日程、诊后小结、电子处方留档、复诊提醒和历史就诊时间轴。
- **远程问诊：**医生、患者、家属三方图文实时刷新，语音／视频 WebRTC 会话，结构化症状描述、报告附件、问诊归档和转科建议。
- **住院与术后康复：**治疗计划、检查和用药安排、康复打卡、伤口与引流记录、出院医嘱和异常症状升级。
- **居家照护与急救：**多照护者分级权限、轮班交接、照护日志、用药库存、一键呼救，以及可离线查看的关键医疗信息卡。
- **儿童、孕产与心理健康：**生长记录、疫苗计划、孕产时间轴、多监护人协作、心理量表、定期推送和私密可见范围。
- **隐私与运营：**患者自主授权、多医生访问控制、脱敏导出、操作审计、医生排班、患者分组，以及随访完成率、服药依从率和指标达标率统计。

## 产品设计原则

1. **一份连续健康档案：**指标、治疗事件、问诊和文档都关联到同一患者时间轴。
2. **异常优先：**工作台优先呈现逾期任务、异常指标、不完整记录和等待审核的 AI 内容。
3. **授权明确、最小权限：**系统角色权限与患者级授权相互独立，患者可查看并撤销授权。
4. **自动化但不越过临床边界：**提醒、汇总和任务生成可以自动执行，重要临床变更保留人工确认。
5. **全过程留痕：**敏感数据查看与修改、审核决定和通知投递均可追溯。
6. **品牌可配置：**无需复制代码即可配置平台 Logo、平台名称、机构名称、所有权文字和页面背景色。

## 静态演示

仓库内提供使用虚构数据、无需后端的双语交互式产品演示：

- **中文：**[在线体验](https://wangdj104.github.io/tx-analysis-service/cn/) · [源文件](demo/index.html)
- **English:** [Live demo](https://wangdj104.github.io/tx-analysis-service/) · [source](../demo/index.html)

演示可切换医生、患者、家属和管理员身份，以模拟数据展示 40 项工作流示例，包括慢病、预约、问诊、康复、照护、急救、儿童孕产、心理健康、权限和运营。操作仅更新当前标签页的演示状态与审计事件，不发送网络请求，不执行真实临床处理；模拟展示不代表正式系统所有场景均已验收。请查看[功能审查报告与验证边界](../docs/FUNCTIONAL_AUDIT_20260922.md)。

## 中英文目录约定

仓库根目录是英文版，`cn/` 是完整中文版，包含后端、前端、SQL、文档和静态演示。迭代功能时请在同一个提交中同步更新两套版本；协议字段、权限标识、表名和 API 路径保持一致，仅对用户可见文本进行本地化。

## 使用 Docker 启动完整演示

要求：Docker Engine 24+、Docker Compose v2。

```bash
cp .env.example .env
# 以下 Docker 命令从仓库根目录运行（不是 cn/）；先填写全新的数据库密码和 JWT_SECRET。
docker compose up --build
```

打开 `http://localhost:8080/`（英文）或 `http://localhost:8080/cn/`（中文）。Docker 演示数据会创建仅供本地使用的账号：

```text
用户名：demo
密码：Demo@123456
```

将服务暴露到任何网络前，请更换全部密钥。Docker 演示仅用于评估，不应用于生产环境。

空 MySQL 数据卷会按顺序执行 `init.sql`、`doctor_workspace_20260921.sql`、`care_platform_upgrade_20260921.sql`、`demo-data.sql`。已有数据卷不会再次初始化，应先备份再手动应用升级脚本，切勿删除有数据的卷来强制初始化。`cn/docker-compose.yml` 仅供独立中文版使用，双语部署请使用根目录编排。

## 中英文生产部署

生产镜像会在 `/` 提供英文正式系统，在 `/cn/` 提供中文正式系统。用户通过页面右上角切换语言时会保留当前业务页面；两套界面共用同一个 API、账号、登录状态和数据库。

```bash
cp .env.production.example .env.production
# 编辑 .env.production，填写现有数据库和正式访问地址。
docker compose --env-file .env.production -f docker-compose.production.yml up -d --build
```

访问 `http://服务器地址:8080/` 或 `http://服务器地址:8080/cn/`。同一镜像还在 `/demo/` 和 `/cn/demo/` 提供静态演示。生产编排不会创建 MySQL，也不会导入 `demo-data.sql`，只会连接 `DB_URL` 指定的现有数据库。

首次启动前必须完成下方数据库初始化，已有库需单独执行适用升级。生产编排关闭管理员自动初始化，首次使用前应明确创建管理员。部署后检查公开地址的 `/api/health`；它仅检查进程存活，还应验证登录与患者页面再验收。以上双语生产命令均从仓库根目录执行。

中文 PDF 下载要求后端主机安装中文字体。后端 Docker 镜像已安装 Noto CJK；非 Docker/systemd 部署在 Debian/Ubuntu 上需通过系统包管理器安装 `fonts-noto-cjk`，并确认服务账号可读取 `/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc`。其他发行版需提供 `HealthReportServiceImpl` 已识别路径中的 Noto CJK 或文泉驿微米黑字体。安装后重启应用，并实际下载中文 PDF 检查正文与患者姓名；仅检查 HTML 预览不能确认 PDF 字体正常。

## 技术栈

- 后端：Java 8、Spring Boot 2.5、MyBatis-Plus、MySQL 8、JWT。
- 前端：Vue 3、Vite、Vue Router、Element Plus、ECharts。
- 测试：JUnit 5、Mockito、H2 MySQL 模式、Node.js 测试运行器。
- 部署：Docker Compose、Nginx、GitHub Actions、GitHub Pages。

## 本地开发

环境要求：推荐 JDK 17（源码和字节码目标兼容 Java 8，CI/Docker 使用 17）、Maven 3.8+、Node.js 20+、MySQL 8.0+。

### 1. 创建数据库

```sql
CREATE DATABASE family_health CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

```bash
mysql -u root -p family_health < src/main/resources/sql/init.sql
mysql -u root -p family_health < src/main/resources/sql/doctor_workspace_20260921.sql
mysql -u root -p family_health < src/main/resources/sql/care_platform_upgrade_20260921.sql
```

新库必须依次执行[基础表](src/main/resources/sql/init.sql)、[医生工作台](src/main/resources/sql/doctor_workspace_20260921.sql)、[照护平台](src/main/resources/sql/care_platform_upgrade_20260921.sql)三个脚本，不能仅执行 `init.sql`。已有库先备份，再应用适用的幂等升级脚本，不要把基础脚本当作重置工具。[demo-data.sql](src/main/resources/sql/demo-data.sql) 仅用于本地演示，禁止导入生产环境。

### 2. 配置环境变量

复制 `.env.example`，至少填写以下变量。Compose 会读取 `.env`，但 Maven/Spring Boot 不会自动加载此文件；本地启动前请在终端进程或 IDEA 运行配置中设置环境变量。

```dotenv
DB_URL=jdbc:mysql://127.0.0.1:3306/family_health?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
DB_USERNAME=family_health
DB_PASSWORD=replace-with-a-strong-password
JWT_SECRET=replace-with-at-least-32-random-bytes
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_USERNAME=admin
BOOTSTRAP_ADMIN_PASSWORD=replace-with-a-strong-password
```

仅在显式启用时执行管理员初始化。请使用独立管理员用户名：如果同名账号已存在，初始化会给它授予管理员角色。完成初始化后关闭此功能，并修改初始密码。

### 3. 启动后端

```bash
mvn spring-boot:run
```

API 默认监听 `http://localhost:8082`，无需登录的存活检查地址为 `http://localhost:8082/api/health`。

### 4. 启动前端

```bash
cd frontend
npm ci
npm run dev
```

Vite 通常监听 `http://localhost:5174`，将 `/api` 代理到 `http://localhost:8082`。开发时可在 `frontend/.env.local` 设置 `DEV_PROXY_TARGET` 修改代理目标；生产界面固定使用 `/api`，需由 Nginx 转发。开发中文版时在 `cn/frontend` 运行相同命令并访问 `/cn/`。

## 关键配置

| 变量 | 用途 | 生产建议 |
| --- | --- | --- |
| `DB_URL` | MySQL 连接 | 启用 TLS，并使用最小权限数据库账号 |
| `DB_USERNAME` / `DB_PASSWORD` | 数据库凭据 | 存放在密钥管理服务中 |
| `JWT_SECRET` | 令牌签名 | 至少 32 个随机字节，并有计划地轮换 |
| `JWT_EXPIRATION_MS` | 令牌有效期（毫秒） | 使用符合风险要求的较短时长 |
| `CORS_ALLOWED_ORIGINS` | 浏览器来源 | 仅填写明确的 HTTPS 来源 |
| `BOOTSTRAP_ADMIN_*` | 初始管理员 | 仅启用一次，随后关闭 |
| `OCR_API_KEY` / `OCR_BASE_URL` / `OCR_MODEL` | 可选 OCR 服务 | API 密钥仅保存在服务端 |
| `DEEPSEEK_API_KEY` / `DEEPSEEK_API_URL` / `DEEPSEEK_API_MODEL` | 可选 AI 分析 | 先审查隐私和数据保留策略 |
| `DEV_PROXY_TARGET` | Vite 开发代理目标 | 生产不使用此值，由 Nginx 转发 `/api` |

完整配置请查看 [.env.example](.env.example) 和 [application.yml](src/main/resources/application.yml)。

## 通知与数据安全

平台可通过企业微信或钉钉机器人 Webhook 及浏览器通知主动发送消息。Webhook 地址和机器人密钥属于敏感信息，API 返回时会脱敏，严禁提交到版本库。

新安装必须按顺序执行基础表、医生工作台和照护平台脚本（目前合计 70 张业务表）；`demo-data.sql` 只用于本地演示。应用启动时不会静默创建或修改生产表。对已有数据库执行人工操作前必须备份数据；后续结构升级应使用 Flyway 或 Liquibase 等版本化迁移工具。

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
- 应用内家庭记录备份不是全平台灾备：仅覆盖预览列出的记录类型，不包含问诊聊天、照护全流程和医生工作台记录、授权及系统账号；附件仅包含数据库内嵌内容。恢复创建独立副本，并关闭用药提醒与自动分析；通知渠道和照护成员需重新配置。数据库与附件存储仍需单独备份。
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
│   ├── init.sql                    # 基础表，仍需执行配套升级脚本
│   └── demo-data.sql               # 可选本地演示数据
├── .env.example                    # 不含真实密钥的配置模板
├── .env.production.example         # 连接现有数据库的生产配置模板
├── docker-compose.production.yml   # 中英文生产部署
└── docker-compose.yml              # 本地全栈演示
```

## 许可证

本项目采用 [MIT 许可证](LICENSE) 发布。
