# 十二项遗留风险修复方案与实施计划

基线：`d28b8fdae2dcdff6fa11a9c182334f4268379130`；依据：`docs/SQL_BUSINESS_AUDIT_20261011.md` 的十二行遗留风险。仅修改代码、合成数据测试及维护者线下执行的升级脚本，不操作真实患者数据库或生产部署。临床公式、阈值、原始患者文字不变；英文和中文版本同步。

## 选择的方案

修复现有链路，不重写平台。导入维持界面已有的逐条成功/失败语义；审核采用明确内容版本；后台运行采用持久认领和不确定结果状态。另一条可选路线是整批原子导入、停用自动任务及所有 webhook，改动较少但明显改变现有功能，因此不采用。仅做校验不做真实持久化/并发回归也不足以解决本报告风险。

## 十二项验收矩阵

| # | 问题与约定 | 主要文件（均有 cn 镜像） | 必须验证 |
|---|---|---|---|
| 1 | AI HTTP/解析/空响应抛出类型化异常，不再返回错误字符串；所有生成调用者一致处理，合法正文原样保留 | AiAnalysisServiceImpl、新 AiAnalysisException、调用者 | HTTP/超时/JSON/空正文失败均无草稿、无通知；合法内容不改变 |
| 2 | 导入每行独立事务，成功计数只在真实提交后增加；一个失败不会回滚其他成功行 | ClinicalImportService、新 ClinicalImportRowWriter | 真实 Spring 代理+mapper：有效行/内层失败/有效行，最终数据库为两条，错误一条；稳定来源 ID 重试不重复 |
| 3 | 透析 局部更新 先合并已存输入，再按原公式重算；省略的字段/间隔保留，所有者不能被请求覆盖 | DialysisRecordServiceImpl、DialysisRecordMapper | 只改一个重量、只改备注、日期/患者变更、INCOMPLETE 转换、已有间隔保持 |
| 4 | 对确实需要清空的病历审核信息和透析派生列使用显式 null 更新，不能依赖默认忽略 null 的 ORM 策略 | MedicalRecordServiceImpl、DialysisRecordServiceImpl、相应 mapper/entity | 真实 mapper 更新后重新查询，核验 verifiedBy/verifiedAt 和派生列为 SQL NULL |
| 5 | 附件插入失败直接回滚，不删除文件内容再重试；替换失败恢复旧附件和旧正文 | MedicalRecordServiceImpl | 超限/缺列/插入异常；新建与替换事务回滚，既有字节不丢 |
| 6 | medical_record.content_version 加版本、verification_note 独立存审核备注；正文/指标/附件修改原子增版并清审核；审核必须携带看到的版本，条件更新拒绝过时审核 | MedicalRecord/entity/service/mapper/controller；DoctorWorkspaceService；新 MedicalRecordReviewDialog；两个 Workspace/API | 两会话竞争、旧版本拒绝且无审核日志/正文变化；两入口均先查看一致的源内容快照，冲突必须重新打开并确认 |
| 7 | 自动任务持久认领，记录运行 token、设置快照、阶段/时间/草稿链接；多实例和手动运行共用认领。运行状态与用户配置分列更新 | HealthAnalysisAutomationService、新运行存储/entity/mapper；ClinicalWorkbench 状态显示 | 双实例/手动重叠、配置同时编辑、保存草稿/通知崩溃边界；外部调用结果不明绝不自动重发，明确显示需人工确认 |
| 8 | 导入去重范围改为患者+来源+外部 ID；补齐旧库缺少的唯一键，不删除历史重复记录 | ClinicalImportService/RowWriter；升级脚本 | FHIR/DEVICE 同 ID 均可保存，同来源重复跳过；数据库唯一键并发兜底；旧库重复数据阻止升级并给维护说明 |
| 9 | 现有 AI 菜单权限作用到实际 /api/ai 路径，保留旧别名匹配且防止相邻路径误命中 | PermissionInterceptor、路由测试 | 已授权/无菜单权限、子路径和矩阵参数；患者范围保护仍然有效 |
| 10 | webhook 仅允许公网 HTTPS；保存和发送均验证 URL，检查全部 DNS 地址，并将验证结果用于真实连接；禁止重定向与隐式重试 | NotificationChannelController、NotificationDeliveryService、新 WebhookTargetPolicy/transport、pom | IPv4/IPv6 私网/环回/链路本地/保留地址、混合 DNS、DNS 重绑定、重定向；仅使用模拟 DNS/传输 |
| 11 | JWT 加服务端密钥 HMAC 凭据戳，绑定用户及当前 BCrypt 密文；每次认证对照最新账户，不把密码/密文放入 token | JwtUtil、JwtInterceptor、SysUserServiceImpl | 自改密码/管理员重置/编辑密码后旧 token 立即失效，新 token 可用；没有数据库迁移 |
| 12 | 在认证前统一为传统报告、CSV、备份响应加 no-store/private 和 nosniff，包含失败响应 | WebMvcConfig、已有敏感响应保护器 | 真实 MVC 成功、400/401/403/500，所有相关路由均覆盖 |

## 关键实现边界

- 审核预览仅使用当前已获授权的源记录、指标和附件元数据；进入预览和提交时均复核当前权限。通过同一一致性快照返回正文及版本，不扩大医生/护士/家属的档案读取范围；不自动下载或转发附件字节
- 透析更新在事务中锁定待更新行，先合并非空 局部更新 输入，再显式更新需要清空的派生字段；保留现有 null 输入表示未修改的约定，不顺手设计新的临床缺失值规则
- 任务运行记录 health_analysis_run 保存 automation_id、claim_token、state、scheduled/scheduled_for、settings_json、claimed_at/updated_at、analysis_record_id 和 last_error。阶段为 CLAIMED、GENERATING、DRAFT_SAVED、NOTIFYING、COMPLETE/FAILED；外部结果不明转 UNKNOWN/NOTIFY_UNKNOWN。短事务内认领和保存草稿，不在持锁期间访问外部服务。删除任务和旧 token 的迟到结果须被 fence 拦截，不复活任务
- 透析文本导入的 textImport 标识未持久化，不能用它判断旧记录的脱水量来源：局部更新 的 onWeight/offWeight/recordType 未实际变化时保留已存 ufAmount，包括导入的 NORMAL/INCOMPLETE 记录；元数据修改不重解释该值。添加两类导入记录重新读取后仅改备注的真实 mapper 回归；创建及文本导入计算不变
- 审核备注单独存 verification_note/既有审核日志，不再拼入患者 remark；历史备注原文不改写，内容变更时与审核身份/时间一并清空审核元数据
- 运行认领按 automation_id 原子互斥；草稿插入、运行结果链接及相应状态同事务提交。默认 15 分钟未推进视为陈旧，使用数据库时钟。AI 独立客户端保留连接 5 秒/读取 30 秒默认值，并将可配置上限分别限定为 10 秒/120 秒；不修改共享 OCR 客户端。通知沿用有界超时。CLAIMED 可安全重新认领；DRAFT_SAVED 仅在从未进入 NOTIFYING 时继续通知。GENERATING/NOTIFYING 陈旧后置为 UNKNOWN/NOTIFY_UNKNOWN，并阻止所有新的自动和手动认领，直到用户明确确认：无草稿时允许新一次生成；有草稿时返回已有草稿并清阻塞，不再次生成/通知。每渠道结果保留已失败/已成功/不确定的区别，超时不能降级成可重试普通失败
- 调度计算公式不变。定时认领原子预留下一时间；手动成功运行仅在下一执行时间及相关配置仍与认领快照相符时条件调整，失败手动运行保留原时间。已保存草稿的未知通知状态经用户确认后返回同一草稿，不重新生成或自动重发

## 需要明确接受的兼容性变化

- HTTP 或内网 webhook 将被拒绝。仓库只描述公网企业微信/钉钉 HTTPS，用代码默认拒绝未知内网目标；本轮不提供宽泛绕过开关，也不修改真实网络设置
- 上线后旧 JWT 需要重新登录一次；之后改密/重置会立即撤销旧登录
- 审核接口必须传看到的内容版本；两版内置界面同步改造。旧客户端需要升级，否则会收到明确错误，不会静默审核新内容
- 外部 AI/通知结果不明的中断任务显示需确认，不承诺跨外部服务严格只执行一次，不盲目重试
- 去重承诺限于稳定来源 ID；缺少 FHIR Observation.id 时仍沿用现有生成 ID 行为，不宣称跨次重试幂等，也不在本轮擅自重定义临床记录身份
- 新升级脚本由维护者备份、隔离演练后线下执行；不增加启动时自动迁移。发现旧库重复外部 ID 不自动删数据

## 实施顺序与文件边界

1. 从准确基线创建独立工作树；先补能够在原代码复现的失败测试并保留 RED 证据
2. 并行三组：病历/附件/审核；认证/权限/webhook/下载；AI/任务。独立第四组处理导入、透析、SQL 汇总。共享 init.sql 按表分别修改，升级 SQL 和目录由集成者统一整理
3. 保留现有公式、缺失值政策和原文；定向修复每组，双版 GREEN，真实事务/mapper测试优先于 mock 表象
4. 合并后独立规格/质量审查；新增迁移纳入现有原生 MySQL 临时数据库链路，检查新库/旧库/重跑/保留数据，保留原有 24 列回归与所有守卫
5. 双版后端全量+打包、前端全量+构建、演示和脚本测试。修复受到本次变更影响的夹具，不降低断言或把失败改跳过
6. 更新十二项实际证据矩阵和维护说明；远端 main 新鲜读取、非强制原子 SHA 提交。持续等到准确提交 CI 7/7、双版原生 MySQL 与每版 58 个浏览器案例完成；新增审核/任务 UI 测试另列，不拿旧提交或本地 SKIP 当验收

## 完成定义

逐项有复现、最小修复、持久化/接口/并发证据和剩余边界，独立审查通过；准确 main 提交和该提交 CI 全绿。不能声称所有漏洞均已消失。数据库迁移和生产部署仍由维护者线下完成。
