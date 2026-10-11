# SQL 执行目录

这里按用途说明现有脚本，不会自动执行 SQL。两版保留同名文件及原加载路径；请在对应版本目录操作，不要混用两版带语言文字的字典或演示数据。需要人工执行的脚本由部署负责人在线下处理。

## 先确认边界

- 升级前备份完整数据库和附件/对象存储，保留当前应用镜像与配置，并在隔离数据库做恢复演练。应用内家庭档案导出不是完整备份；历史 `scripts/RunSqlMigration.java` 只复制六张旧表，不是完整库备份工具
- MySQL DDL 会隐式提交。`START TRANSACTION` 不能让建表、加列与其他数据写入一起回滚；脚本中途失败时，应检查已完成的步骤及结构，不要假设全部撤销
- `CREATE TABLE IF NOT EXISTS` 不能修复已有表的全部字段。条件加列只检查列是否存在，不会修复已有列的类型、默认值或可空性错误，也不保证补齐旧索引；执行前后都需核对结构
- 运行账号权限限定在应用 schema。通过 MySQL 客户端密码提示或已批准的凭据机制登录，不在命令行、截图或报告里放密码
- 文件名带日期是历史命名，不是可直接按日期排序的迁移工具；不要将权限清理、演示数据、测试夹具拼进通用启动脚本

## 文件清单

| 文件 | 分类 | 用途、前提和重复执行影响 |
| --- | --- | --- |
| [init.sql](init.sql) | 新库基础 | 新建空库的基础表和初始字典。不是重置工具；条件建表不会升级已有表，种子写入可能覆盖字典、菜单或初始角色 |
| [doctor_workspace_20260921.sql](doctor_workspace_20260921.sql) | 旧库升级 | 基础表后补医生分配、临床草稿、审核及工作台。会更新角色/菜单及绑定；存在检查不等于不写数据 |
| [care_platform_upgrade_20260921.sql](care_platform_upgrade_20260921.sql) | 旧库升级 | 基础表、医生工作台后补照护平台及临床兼容列。24 个旧列按 AFTER 前置列顺序恢复；只检查存在，不修复错误类型，菜单/绑定仍会更新 |
| [care_plan_collaboration_20261003.sql](care_plan_collaboration_20261003.sql) | 旧库升级 | 照护平台后补协作版本、行动、事件和通知。重复执行仍会补回缺失的 nurse 角色、2 个菜单及 nurse/admin 角色菜单绑定，删除过的绑定可能恢复。保留旧 ACTIVE 为内部 workflow_version=0，不自动发布、不分配护理账号、不授予 CARE_PLAN 患者权限；时间使用显式 UTC DATETIME(6) |
| [clinical_integrity_upgrade_20261011.sql](clinical_integrity_upgrade_20261011.sql) | 旧库升级 | 仅人工线下执行；先完整来源重复预检，重复时 SIGNAL 45000/1644 阻止任何临床表或索引变更，不删历史行。补内容版本、审核备注、来源唯一键及持久任务日志；依赖已有 automation 旧字段，重复执行保留数据，临时过程失败后可能遗留，详见下文 |
| [functional_review_repair_20260922.sql](functional_review_repair_20260922.sql) | 旧库升级 | 旧库按需补临床导入、告警与用药安全表及初始规则，当前 init 已包含。INSERT IGNORE 重复执行仍会补回缺失的 6 条初始用药安全规则，已有同一药物对不覆盖；删除过的初始规则可能按默认启用状态（enabled=1）恢复。不修复全部旧字段 |
| [notification_language_preference_20260922.sql](notification_language_preference_20260922.sql) | 旧库升级 | 缺 user_preference 的旧库按需补表。当前 init 已包含；不填用户偏好、不修复已有定义 |
| [patient_specialty_roles_20260923.sql](patient_specialty_roles_20260923.sql) | 专病角色 | 基础患者、角色和菜单存在后补专病表与角色/菜单关系。MySQL/MariaDB 每次启动都会执行，不自动推断患者分配；手工执行不能关闭启动行为 |
| [role_permission_cleanup_20260922.sql](role_permission_cleanup_20260922.sql) | 权限整理 | 人工检查角色绑定后，迁移 clinician 为 doctor、补疾病标签账号的 patient 绑定，再删除旧角色/关联并缩减菜单权限。不是纯加表升级，重复执行不恢复已删权限 |
| [demo-data.sql](demo-data.sql) | 演示数据 | 仅隔离本地 Docker 演示。固定演示 ID 的 upsert 会改写记录；所有身份和健康数据为虚构，禁止导入生产或真实业务库 |
| [care-plan-e2e-fixture.sql](../../../test/resources/sql/care-plan-e2e-fixture.sql) | 测试夹具 | 隔离浏览器/服务测试的合成协作数据，依赖测试初始化及完整结构；禁止用于业务库 |
| [care-plan-mysql-legacy.sql](../../../test/resources/sql/care-plan-mysql-legacy.sql) | 测试夹具 | disposable MySQL 验收的合成患者和内部旧计划；在协作迁移前使用，不代表所有真实历史库 |
| [care-plan-mysql-relationships.sql](../../../test/resources/sql/care-plan-mysql-relationships.sql) | 测试夹具 | disposable MySQL 验收的合成版本、事件、通知和外键关系；在协作迁移后使用，不对外发消息 |

## 新建空库的顺序

1. `init.sql`
2. `doctor_workspace_20260921.sql`
3. `care_platform_upgrade_20260921.sql`
4. `care_plan_collaboration_20261003.sql`
5. `clinical_integrity_upgrade_20261011.sql`，维护者手动核验；当前 init 已含其结构，Compose 不自动运行本升级
6. `patient_specialty_roles_20260923.sql`，可人工预先执行，但应用启动时仍会再执行

只跑 init 不足以建立完整照护平台。当前新库基础已包含 functional repair 和 notification preference 的表，不需要为了文件日期再跑一遍。角色清理单独审核，演示数据不属于生产安装步骤。完成结构迁移也不会自动启用护理计划协作，仍须按部署文档配置 CARE_PLAN_ENABLED 和业务权限。

## 已有库怎样选择

先确认运行版本、表、列、索引和角色绑定，再选择缺失且适用的脚本，遵守上述结构依赖。不要重跑 init 作为“修复全部字段”，也不要把所有 SQL 一次性执行。含错误类型的旧列、缺少的旧索引和未知历史定制必须单独核实；本目录不承诺任何历史库都能无条件重复升级。

权限清理涉及真实权限删除，执行前核对 clinician、ESRD-7、DM-2、HBP 以及账号/菜单关系，执行后检查保留角色和原合法访问。角色或菜单存在不等于有当前患者数据授权。需要恢复时按完整备份方案处理，不靠反复执行脚本。

## 实际自动执行入口

- 对应版本 `docker-compose.yml` 的演示 MySQL 空数据卷依次加载基础、医生、照护平台、协作和演示数据。已有数据卷不会再次初始化；不要删除真实数据卷来强制重跑。Compose 演示不代表生产部署
- `src/main/java/org/familyhealthcare/config/PatientSpecialtyMigration.java` 在每次 MySQL/MariaDB 应用启动执行专病角色脚本。本版本没有关闭这个行为的开关，手工跑过后仍然执行。运行账号除业务权限外需要该脚本使用的 CREATE、SELECT、INSERT、UPDATE，建立外键还可能需要 REFERENCES，均限定于应用库
- 除上述专病脚本外，其余结构升级由操作人员按需执行；本目录不增加任何新的自动入口

## 验证应说清楚

目录与两版条件 ALTER 比较是静态守卫，不是数据库执行证据。原生旧库验收仅由已有 guarded disposable CI 执行原 MySQL 8.0 脚本，先制造并证明 24 列缺失，再核对类型/默认值/可空性、旧数据、重复执行与受影响索引的恢复。H2 和未配置的本地 SKIP 都不能称为 MySQL 验收通过。


## 2026-10-11 临床一致性升级

`clinical_integrity_upgrade_20261011.sql` 仅由维护者在线下执行，不在应用启动或 Compose 自动初始化中加载。当前 `init.sql` 已包含相同的新字段与运行表。旧库先备份完整数据库、附件及配置并隔离演练，完成所需照护平台/旧库修复，确认 `health_analysis_automation` 旧字段完整，再停止应用和其他写入者执行。该表缺失或字段不全时，脚本最先执行的 SELECT 会在任何 DDL 之前失败；不从未知历史结构静默猜测设置。

来源身份由 `(patient_id, source_type, source_external_id)` 组成。先分别只读检查下面查询；不得删除、合并或自行改写真实患者数据来通过升级。发现重复后由维护者核验来源身份和原始材料，决定保留全部记录的修正方案，必要时先暂停上线。

```sql
SELECT patient_id, source_type, source_external_id, COUNT(*) AS duplicate_count
FROM bp_self_monitor_record
WHERE patient_id IS NOT NULL AND source_type IS NOT NULL AND source_external_id IS NOT NULL
GROUP BY patient_id, source_type, source_external_id HAVING COUNT(*) > 1;
SELECT patient_id, source_type, source_external_id, COUNT(*) AS duplicate_count
FROM medical_record
WHERE patient_id IS NOT NULL AND source_type IS NOT NULL AND source_external_id IS NOT NULL
GROUP BY patient_id, source_type, source_external_id HAVING COUNT(*) > 1;
```

脚本也执行完整重复预检；SQLSTATE 45000/错误 1644 会在任何临床表/索引变更前阻止升级。不要用 `mysql --force` 或忽略错误继续。预检通过后补齐缺失键，或原子替换同名但不符合完整来源范围的旧键；并发数据库唯一冲突 1062 仍须正常处理。停止其他写入者很重要，预检不承诺跨其他进程的无限期锁定。

MySQL 不支持预编译 SIGNAL，因此脚本在只读依赖核验后创建保留名称 `clinical_integrity_abort_20261011` 的单语句临时过程，仅用于条件报错，不修改临床数据。维护账号需在当前应用 schema 内具备 CREATE ROUTINE、ALTER ROUTINE、EXECUTE 以及建表/加列/索引权限；运行应用账号不必因为本升级增加这些过程权限。该名称不能另作业务用途。失败可能遗留过程；原因经维护者处理后，原脚本重跑会先删除并重建它，成功结束时删除它。只读查看过程和核验业务表，不必清空数据或数据库。

加列/表只检查存在，不修复错误类型、默认值或可空性；执行前后核验 `medical_record.content_version BIGINT NOT NULL DEFAULT 1`、可空 `verification_note TEXT`、任务 `config_version BIGINT NOT NULL DEFAULT 0`/可空 `active_run_id BIGINT`，以及 `health_analysis_run` 的唯一 token、阶段/时间/设置快照/草稿链接/渠道结果。DDL 隐式提交，中途失败应逐项核对结构。历史患者 remark 原文、附件字节、已有审核身份时间与任务设置不重写。

发布兼容性：旧审核客户端必须升级并提交看到的内容版本；旧 JWT 首次需重新登录；Webhook 只接受公网 HTTPS，内网与 HTTP 会被拒绝。未知 AI/通知阶段须由用户明确确认，不自动重发；确认已有草稿只返回同一草稿。维护者仍负责实际数据库迁移及生产部署。
