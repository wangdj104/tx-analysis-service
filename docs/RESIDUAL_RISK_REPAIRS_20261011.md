# 十二项遗留风险修复与发布验证 2026年10月11日

> 2026-10-13: Automated test files and runners have been removed. Test paths, commands and results below describe historical revisions and are not current executable instructions.

本轮针对[历史审查列出的十二项遗留风险](SQL_BUSINESS_AUDIT_20261011.md#已发现但本轮没有顺手改掉的问题)，从基线 `d28b8fdae2dcdff6fa11a9c182334f4268379130` 修复现有链路。十二项实现已在英文及中文版本集成，包含追加备份运行指针修正的独立整包源码复核及最终本地集成回归、打包均通过。准确发布提交的远端 CI、原生 MySQL 和真实浏览器验收仍待完成，当前不能据此宣布发布验收完成。

执行环境重置后，本轮重新建立源码、合成回归与复核证据。重置前失效的测试成绩不计入本记录；分支定向通过也不替代最终集成全量。未访问真实患者数据库、运行生产迁移或部署服务，未请求真实 AI 或通知渠道。临床公式、阈值、既有缺失值约定及患者原始文字保持不变。

## 十二项实现与验收矩阵

下表列出当前实现、已集成的回归用例和剩余边界。测试文件存在不等于最终验收通过；实际执行结果见后面的发布验证记录。源码链接指向英文版本，`cn/` 下有对应实现及回归。

| 项目 | 实际修复 | 关键回归依据 | 剩余边界 |
| --- | --- | --- | --- |
| 1 AI 失败正文 | [AI 服务](../src/main/java/org/familyhealthcare/service/impl/AiAnalysisServiceImpl.java) 改为类型化异常；拒绝 HTTP 非 2xx、错误 JSON 形状、非字符串及空正文。合法回答原样返回，生成失败不保存新草稿、不发通知；独立客户端禁重试和重定向 | [失败回归](../src/test/java/org/familyhealthcare/service/impl/AiAnalysisFailureRegressionTest.java)、[客户端回归](../src/test/java/org/familyhealthcare/service/impl/AiHttpClientLifecycleTest.java)、[自动任务结果回归](../src/test/java/org/familyhealthcare/service/AutomationOutcomeRegressionTest.java)：超时、错误状态、302 伪成功、解析/空正文、原文保持 | 超时等外部结果不明时进入不确定状态；不保证 AI 内容临床正确，也不承诺提供方只处理一次 |
| 2 导入逐行事务 | [导入协调器](../src/main/java/org/familyhealthcare/service/ClinicalImportService.java) 暂停外层事务；[独立行写入器](../src/main/java/org/familyhealthcare/service/ClinicalImportRowWriter.java) 通过真实代理使用 REQUIRES_NEW，提交返回后才增加成功数 | [持久化回归](../src/test/java/org/familyhealthcare/service/ClinicalImportDialysisPersistenceTest.java)：有效/失败/有效三行最终保存两行；内层 mapper 失败、提交失败及外层回滚不虚增成功数 | 保留逐行部分成功语义；稳定来源身份的重试依赖数据库唯一键；合成提交故障不代表已覆盖所有数据库通信不确定性 |
| 3 透析局部更新 | [透析服务](../src/main/java/org/familyhealthcare/service/impl/DialysisRecordServiceImpl.java) 锁定并合并已存输入，保留所有者和省略的间隔，再按原公式重算；元数据或同值重量更新保留已存导入脱水量 | [持久化回归](../src/test/java/org/familyhealthcare/service/ClinicalImportDialysisPersistenceTest.java)：单重量、备注、患者/日期、INCOMPLETE、已存 NULL 间隔、重载 NORMAL/INCOMPLETE 导入记录及并发稀疏更新 | null 请求仍表示未修改；不新增临床清空/缺失值规则，不用未持久化的 textImport 推断历史脱水量来源 |
| 4 明确写入 SQL NULL | [病历 mapper](../src/main/java/org/familyhealthcare/mapper/MedicalRecordMapper.java) 显式清审核身份、时间及备注；[透析 mapper](../src/main/java/org/familyhealthcare/mapper/DialysisRecordMapper.java) 显式写入派生列，包括 SQL NULL | [病历完整性回归](../src/test/java/org/familyhealthcare/service/MedicalRecordIntegrityTest.java) 与[透析持久化回归](../src/test/java/org/familyhealthcare/service/ClinicalImportDialysisPersistenceTest.java) 更新后实际查询字段，覆盖 ORM 默认忽略 null 的缺口 | 不把任意请求 null 解释为临床字段删除；真实 MySQL 结果仍须准确提交的原生验收确认 |
| 5 附件失败回滚 | [病历服务](../src/main/java/org/familyhealthcare/service/impl/MedicalRecordServiceImpl.java) 移除清空 fileContent 后重试；插入失败向外抛出，正文、指标及附件同事务回滚 | [病历完整性回归](../src/test/java/org/familyhealthcare/service/MedicalRecordIntegrityTest.java)：超限、缺列、插入约束失败，新建不留空壳，替换恢复旧正文/指标/附件字节 | 失败须由调用者处理；本轮未操作真实附件或对象存储，生产备份与恢复仍由维护者负责 |
| 6 审核绑定内容版本 | 正文/指标/附件修改原子增 content_version 并清审核；verification_note 独立存备注。两入口要求正且 JavaScript 安全整数版本，条件更新拒绝过时审核。预览使用独立一致性快照，并在进入及返回前通过[当前权限门](../src/main/java/org/familyhealthcare/service/MedicalRecordReviewAccess.java) 复核绑定与授权；[审核对话框](../frontend/src/components/MedicalRecordReviewDialog.vue) 先展示源内容再明确确认 | [病历完整性回归](../src/test/java/org/familyhealthcare/service/MedicalRecordIntegrityTest.java)、[真实组件回归](../frontend/tests/medicalReviewDialog.test.mjs)：两会话竞争、旧版本 409 且无审核日志，来源/角色/授权撤销，快照一致性，零及非法版本，双入口、重复点击、切换/关闭/退出及迟到结果 | 预览只给附件元数据，不自动下载字节；读取权不等于审核权。旧客户端必须升级；内容版本不保证同版只能由一名审核人作决定 |
| 7 自动任务持久认领 | [运行存储](../src/main/java/org/familyhealthcare/service/HealthAnalysisRunStore.java) 持久化 token、设置快照、阶段、数据库时间、草稿链接及渠道结果；手动/调度共用认领，短事务与外部调用分离，草稿及链接原子提交，配置/运行字段分开更新并 fence 迟到结果。[备份恢复](../src/main/java/org/familyhealthcare/service/FamilyBackupService.java) 创建独立禁用任务，清 active_run_id 并将 config_version 归零；运行指针必须绑定同一任务，执行 fence 复核任务/所有者，跨任务或不存在的指针拒绝处理 | [真实 JDBC 运行回归](../src/test/java/org/familyhealthcare/service/HealthAnalysisRunStoreTest.java)、[重叠回归](../src/test/java/org/familyhealthcare/service/AutomationOverlapRegressionTest.java)、[结果回归](../src/test/java/org/familyhealthcare/service/AutomationOutcomeRegressionTest.java)、[恢复 UI 回归](../frontend/tests/automationRecovery.test.mjs)：竞争、崩溃阶段、配置并发、删除/token、逐渠道结果、候选饥饿、精确确认及同患者刷新迟到响应。新增[备份/指针回归](../src/test/java/org/familyhealthcare/service/HealthAnalysisRunReferenceTest.java)及[共享断言](../src/test/java/org/familyhealthcare/service/HealthAnalysisRunReferenceAssertions.java)覆盖真实归档恢复、跨所有者/同所有者异任务、8 种运行阶段及缺失指针，已接入原生 helper | 15 分钟以数据库时钟判断陈旧；GENERATING/NOTIFYING 转 UNKNOWN/NOTIFY_UNKNOWN 并阻止新认领。明确确认有草稿时返回原草稿，不再生成或通知；恢复副本不能确认其他任务的运行，原 UNKNOWN 阻塞及日志/草稿保持不变。不承诺外部严格只执行一次 |
| 8 来源隔离去重 | [行写入器](../src/main/java/org/familyhealthcare/service/ClinicalImportRowWriter.java) 按患者 + 精确来源 + 外部 ID 去重；[complete initialization](../src/main/resources/sql/init.sql) defines source-scoped unique keys; existing databases require a duplicate check before applying schema differences | [持久化回归](../src/test/java/org/familyhealthcare/service/ClinicalImportDialysisPersistenceTest.java) 强制两个真实事务竞争唯一键；[SQL 守卫](../scripts/tests/sql-catalog.test.mjs) 及已选中的[原生验收](../src/test/java/org/familyhealthcare/service/CarePlanMysqlIntegrationTest.java) 覆盖唯一约束、跨来源同 ID、重跑保留数据 | 缺少 FHIR Observation.id 仍生成 UUID，不承诺跨次重试幂等；历史重复需维护者核验，不能自动删行通过升级 |
| 9 AI 菜单实际路径 | [权限拦截器](../src/main/java/org/familyhealthcare/interceptor/PermissionInterceptor.java) 将现有 AI 菜单权限应用到 /api/ai，并保留 /api/ai-analysis；按路由边界匹配 | [真实 MVC 回归](../src/test/java/org/familyhealthcare/service/SecurityBoundaryMvcTest.java) 与[患者边界回归](../src/test/java/org/familyhealthcare/service/PatientSecurityBoundaryTest.java)：授权/拒绝、子路径、矩阵参数、相邻路径及实际患者范围 | 菜单权限与患者授权均须满足；没有扩大跨患者读取或写入范围 |
| 10 Webhook 网络边界 | 保存和发送分别验证公网 HTTPS；[目标策略](../src/main/java/org/familyhealthcare/service/WebhookTargetPolicy.java) 检查全部 DNS 地址；[真实传输](../src/main/java/org/familyhealthcare/service/WebhookTransport.java) 固定已批准地址并保留原 Host/TLS 主机校验，禁重定向、隐式重试和地址回退 | [地址回归](../src/test/java/org/familyhealthcare/service/WebhookTargetPolicyTest.java)、[连接回归](../src/test/java/org/familyhealthcare/service/WebhookTransportConnectionTest.java)、[通知结果回归](../src/test/java/org/familyhealthcare/service/NotificationSecurityOutcomesTest.java)：IPv4/IPv6 特殊范围、混合 DNS、重绑定、真实连接目标、重定向及严格 ACK | HTTP/内网历史地址会被拒绝；只使用模拟 DNS、传输和套接字，未验证真实渠道收件或实时 TLS 握手，也不替代部署出站限制 |
| 11 改密撤销 JWT | [JWT 凭据戳](../src/main/java/org/familyhealthcare/util/JwtUtil.java) 用服务端密钥 HMAC 绑定账号与当前 BCrypt 密文；认证重新读取当前有效账号并比对，包含 /api/auth/info | [真实 MVC 回归](../src/test/java/org/familyhealthcare/service/SecurityBoundaryMvcTest.java) 与[JWT 回归](../src/test/java/org/familyhealthcare/util/JwtUtilTest.java)：自改密、管理员重置/编辑后旧 token 拒绝，新 token 可用，旧无戳/停用/删除账号拒绝 | 旧 JWT 上线后需重新登录；token 不含密码或 BCrypt 密文，无数据库迁移；不改变真实账号或密钥 |
| 12 敏感下载禁缓存 | [早期响应过滤器](../src/main/java/org/familyhealthcare/config/SensitiveResponseFilter.java) 与既有控制器守卫在认证/路由拒绝前后为传统报告、CSV、备份及护理报告加 no-store, private 与 nosniff | [真实 MVC 回归](../src/test/java/org/familyhealthcare/service/SecurityBoundaryMvcTest.java) 与[护理报告回归](../src/test/java/org/familyhealthcare/service/CareExecutionReportApiTest.java)：成功、400/401/403/405/500、矩阵/尾斜线及相邻路径不误命中 | 防护限定已列出的敏感路由；未证明真实代理曾缓存数据，不代表浏览器历史文件或所有其他接口均已受同等保护 |

## 独立复核及已修正的追加问题

AI/任务、导入/透析、病历/附件/审核、安全边界及 SQL 的独立源码复核已通过。复核中发现并修正了通知 ACK 数字强制转换可能把小数/溢出值记为成功、路由选择前 405 缺隐私响应头、审核预览容许版本 0，以及任务状态同患者刷新/迟到响应问题。新增回归保留原断言并覆盖这些边界。

整包复核随后确认备份恢复可能把新任务的 active_run_id 指向原任务日志。追加修正清除恢复任务的运行指针并重置配置版本，运行存储拒绝跨任务/所有者或缺失指针。真实归档、服务和 JDBC 存储回归覆盖 8 种运行阶段，要求原 UNKNOWN 状态/认领 token、渠道结果及已有草稿不被改写，且不能触发原任务重放；共享断言由既有原生验收 helper 调用。同一独立审查者复核该修正后确认 I1 已解决，规格及质量均通过，审查范围内无未解决的严重、重要或轻微问题；双版最终全量与打包已重新通过。

通知成功只接受协议规定的实际整数码；重复键、尾随 JSON、错误类型、非整数和溢出 ACK 保持 UNKNOWN，不自动重发。每渠道结果持久化失败会阻止后续发送。医疗预览和提交分别复核当前账号、角色、患者、绑定及授权，不使用旧内容快照中的权限替代当前权限。这些结论仅覆盖本次审查范围，不表示所有漏洞已消失。

## 线下迁移与兼容性

维护者按[SQL 执行目录的临床一致性升级说明](../README.md#database-initialization)、[英文部署说明](OFFLINE_DEPLOYMENT.md)及[中文部署说明](../cn/docs/OFFLINE_DEPLOYMENT.md)准备和执行升级。本文件不重复 SQL 操作步骤。

- 旧库完整备份数据库、附件、应用版本及配置，隔离演练恢复，确认前置结构并停止其他写入者后执行手动升级；没有新增启动或 Compose 自动迁移入口
- 历史重复来源身份会阻止升级，不能删除/合并患者记录或用 mysql --force 忽略错误；临时报错过程的权限、失败遗留及重跑清理见目录说明
- MySQL DDL 可能隐式/部分提交，存在检查不修复任意历史类型、默认值、可空性或半完成表，执行前后须实际核验
- 审核客户端须提交所见版本；旧 JWT 需重新登录；HTTP/内网 Webhook 失效；未知 AI/通知结果须明确确认，已有草稿不自动重发

## 发布验证记录

以下只记录重建后当前集成树的结果。新增病历及任务 UI 回归使用真实 Vue 组件和请求边界，仍属于组件测试，不能替代真实浏览器验收。

| 验证项 | 当前结果 |
| --- | --- |
| 英文及中文后端全量与 Maven 打包 | 每版报告 767 项，实际执行通过 766 项，0 失败、0 错误；各明确跳过 1 项 CarePlanMysqlIntegrationTest 原生验收，两版 clean package 均成功。首次导入测试夹具接线故障已修复；上述结果来自追加备份指针修正后的完整重跑 |
| 英文前端全量及生产构建 | 1492/1492，0 失败、0 跳过；构建成功 |
| 中文前端全量及生产构建 | 1483/1483，0 失败、0 跳过；构建成功 |
| 完整脚本测试 | 168/168，0 失败、0 跳过 |
| 双版演示测试合计 | 306/306，0 失败、0 跳过 |
| 准确发布 SHA 的远端 CI | 待最终发布提交的 7/7 结果及链接，旧提交绿色结果不计入 |
| 两版原生 MySQL 8.0 | 已接入既有受保护的临时数据库验收：每版两轮新库/旧库升级/重跑/备份恢复，并执行真实导入、病历和任务持久化 helper；待最终发布提交实际结果，本机 SKIP/H2/静态守卫不计入 |
| 两版真实浏览器验收 | 每版既有 58 个案例待最终发布提交的实际结果；新增组件测试另列，不用演示或旧提交结果代替 |

前端构建仍有既有的大分包提示。准确发布 SHA、CI 链接和待验收项应在交付时逐项核对；任何失败或未运行项必须如实保留。维护者实际迁移和生产部署不属于本轮完成项。
