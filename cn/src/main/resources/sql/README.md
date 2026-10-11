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
5. `patient_specialty_roles_20260923.sql`，可人工预先执行，但应用启动时仍会再执行

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
