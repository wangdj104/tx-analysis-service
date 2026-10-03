# Care Plan Collaboration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通医生发布计划、个人/家庭执行、护理跟进和医生复核的双语协作闭环。

**Architecture:** 在既有 doctor_care_plan 上增加显式发布版本和独立行动项/事件，不复用 HANDOVER 的自由编辑状态机。先实现数据库、统一授权、事务和可验证 API，再接入角色页面、最小权限时间线与持久通知；已有预约和用药流程保持独立。

**Tech Stack:** Java 8-compatible source on JDK 17, Spring Boot 2.5.14, JdbcTemplate/MyBatis-Plus, MySQL 8.0, JUnit 5/Mockito/H2, Vue 3/Vite/Element Plus, Node 20 test runner, Chromium/Playwright browser tests.

**Spec:** [已批准设计规格](../specs/2026-10-03-care-plan-collaboration-design.md)，批准基线远端提交 `a2db8839cd2602eeade34a15e60e3d8738154386`。

## Global Constraints

- 每个版本 1–50 个一次性行动项：说明、期限、责任人、可选既有记录引用
- 首期单条回执为 1–2000 字，困难/退回说明为 1–1000 字；客户端提示与服务端校验一致
- 不支持新文件上传；每回执最多 5 个既有测量/医疗记录引用，保存时和读取时分别校验归属与权限
- 护理人员只处理同时满足“有效分配 + 有效模块授权”的患者；未满足时不返回患者正文或任务标题
- 查看旧快照也要求当前权限；历史作者身份不是永久访问权
- 新流程使用带偏移的 ISO 8601 输入和 UTC 持久化，显示浏览器本地时间并注明时区
- 兼容说明：上述UTC约定用于新增照护计划/护理分配表；既有 care_access_grant.expires_at 保持原有本地 LocalDateTime API与存储，所有角色统一以数据库 CURRENT_TIMESTAMP 检查有效期，不将部分旧表行单独解释成UTC
- 外部消息只包含通用通知文字及受登录保护的相对页面定位，不含患者姓名、诊断、任务正文或指标
- 对明确可重试失败最多总计 3 次，首次后约 1 分钟和 5 分钟重试；每次重新校验权限。UNKNOWN 不自动重试，人工操作需提示可能重复
- 所有示例与测试使用合成数据，不向真实患者发送测试通知
- 保留原 API/旧 ACTIVE 记录语义；不自动发布旧计划，不自动授予 nurse 或 CARE_PLAN 权限
- EN/CN 协议、schema、功能和测试保持一致，用户可见文案分别本地化
- 本文仅为待审实施计划；须经用户审阅并选择执行方式后实施

## Review Focus

1. 夏令时重复/不存在的本地时刻：必须明确时区/偏移，不能默默改变截止时间；Task 1/8 测试
2. 服务器提交成功但客户端超时/重开编辑器：同一命令重试不重建记录，旧结果不覆盖新草稿；Task 4/8 测试
3. 带原幂等键重放时用户已失权：不能返回旧敏感响应，即使原动作成功；Task 2/4 测试
4. 医生退回已按时提交的回执：保留原提交时刻，分别显示原始及时提交与当前待补充/逾期，不隐瞒历史；Task 5/10 测试
5. 通知发送后进程在保存成功前崩溃：重启不能盲重发，标记 UNKNOWN 并保留可审计结果；Task 6/12 测试

---

## 0. 执行前的实际状态与约定

- `frontend/src/main.js` 定义路由，不存在 `frontend/src/router/index.js`
- 现有 Java 测试在 `src/test/java/org/familyhealthcare/service/`，H2 MySQL 模式；前端为 `node --test tests/*.test.mjs`
- 现有 `CarePlanWorkflowTest` 测试的是家庭用药/预约流程，不能覆盖或冒充新计划测试
- 当前环境可用 Chromium、JDK/Maven 工具；未发现 docker/mysql 命令。执行时重查；缺少真实 MySQL 时在隔离 CI 服务验证，不能宣称 H2 就等于 MySQL
- 现有 `scripts/RunSqlMigration.java` 仅备份固定旧表，不是本里程碑的完整备份工具；不得直接用于生产迁移并宣称覆盖完整
- 现有 CI 对双后端/双前端运行测试和打包；Pages 仅在 demo 相关文件变动时发布。后端打包成功不代表已部署后端
- 本地 `66ca75d` 与远端 `a2db8839` 为相同规格内容的不同提交；已通过非破坏性 merge `8edc788` 统一历史，无源代码变化、无 reset/force push
- 实施开始时读取 AGENTS/适用技能和最新远端；保留用户未提交修改。用户已选 direct-main 发布，不新建公开分支/PR；隔离工作副本可在执行时使用，不改变发布目的地

### 文件镜像规则

下文所有 `src/...`、`frontend/...`、`pom.xml`、`demo/...` 路径均包括对应 `cn/` 镜像；每任务在同一提交中完成双语。`scripts/`、根 `.github/` 和本规格/计划是共享基础设施，不机械复制。计划中的测试命令若只展示根目录，必须再以 `cd cn && ...` 执行后端，或 `cd cn/frontend && ...` 执行前端。不复制构建产物或真实数据。新增生产依赖须单独说明必要性；本计划仅加入测试用浏览器依赖。

### 共同协议与数值约定

沿用项目 `Result<T>` 包装；成功 code=200。新控制器的坏请求/未授权/冲突同时具有 HTTP 400/403/409 和一致 body.code；客户端兼容 axios HTTP 错误，不修改旧接口错误语义。

新服务返回 `Map<String,Object>` / `List<Map<String,Object>>`，与现有 JdbcTemplate 风格一致；字段固定如下，不允许后续任务自创别名：

| 对象 | 字段与类型 |
| --- | --- |
| CommandMeta | commandKey:String(UUID), expectedVersion:Long(非负) |
| DraftBody | patientId:Long, title:String(1–160), instructions:String(1–4000), planType:String(FOLLOW_UP/MEDICATION/DIALYSIS/NUTRITION), actions:List<ActionInput>, legacySourceId:Long? |
| ActionInput | ordinal:Integer(从1连续), instruction:String(1–2000), dueAt:String(ISO带偏移), assignedUserId:Long, evidence:List<EvidenceRef>?(最多5条) |
| ReceiptBody | note:String, occurredAt:String(ISO带偏移), entryMode:String(SELF/ASSISTED), evidence:List<EvidenceRef> |
| EvidenceRef | sourceType:String(MEASUREMENT/MEDICAL_RECORD), sourceId:Long |
| NurseAssignmentBody | patientId,nurseUserId:Long, expiresAt:String?(ISO带偏移)；创建为ACTIVE，撤销单独动作 |
| ReviewBody | decision:String(CONFIRM/RETURN), note:String?，RETURN 必填 |
| FollowUpBody | kind:String(CONTACTED/AWAITING_INFORMATION/DOCTOR_NOTIFIED), note:String(1–1000) |
| PlanView | id,patientId:Long; workflowVersion:Integer; lifecycle,title,instructions,planType:String; revisionId:Long; revisionNo:Integer; revisionStatus:String; currentRevisionId,draftRevisionId:Long?; version:Long; actions:List<ActionView>; allowedActions:List<String> |
| ActionView | id:Long?(草稿为null); planId,revisionId,patientId,assignedUserId:Long; ordinal:Integer; version:Long; instruction,status,dueAt:String; overdue:Boolean; firstSubmittedAt:String?; latestSubmittedAt:String?; reviewWaitingSince:String?; events:List<EventView>; evidence:List<EvidenceView> |
| EventView | id,actorId:Long; actorName,actorRole,entryMode,eventType,recordedAt:String; occurredAt,note:String?; evidence:List<EvidenceView> |
| EvidenceView | sourceType:String,sourceId:Long,restricted:Boolean；仅有原模块读权限时增加受控 title/detailLink |
| CommandResult | planId:Long,actionId:Long?,eventId:Long,version:Long,lifecycle:String,actionStatus:String? |
| PageView | items:List<Map<String,Object>>, nextCursor:String?；默认50，最大100 |

所有时间返回 UTC `Z`；前端展示当地时间与偏移。输入转换到 UTC 后须落在 `1000-01-01T00:00:00Z` 至 `9999-12-31T23:59:59.499999Z`（含端点），且纳秒值能被 1000 整除；拒绝越界或亚微秒值，不静默舍入/截断。这是 [MySQL 8.0 DATETIME(6) 文档](https://dev.mysql.com/doc/refman/8.0/en/date-and-time-type-syntax.html) 的保守存储合同，Task 12 仍验证原生 UTC 往返。长度按 Unicode code point 计数，去首尾空白后验证；拒绝未知状态/类型、空白文本、非整数 ID、负版本与非有限数字。SELF 只表示账号所有者声明本人录入，不视为患者身份认证；默认选择 ASSISTED，账号管理其他成员时显示代录并要求明确选择，不从 owner ID 推断自然人同一性。

证据 MEASUREMENT 首期仅映射 `health_measurement`，MEDICAL_RECORD 映射 `medical_record`；不把多套测量表的同号 ID 混在一个 sourceType。只存原 ID，不复制临床正文。未授权引用不泄露额外标题。行动项的可选 evidence 与回执使用同一引用合同：同一列表内拒绝重复 sourceType+sourceId，不同 sourceType 的同号 ID 允许；行动引用随 ordinal 保存在 revision draft_json 并在发布时冻结，后续保存/发布/读取服务分别验证当前患者归属及原模块权限。

### 公共 Java 接口（由所属任务创建）

- `CarePlanAuthorizationService.requireRead(long actorId,long patientId)`、`requireClinical(long actorId,long patientId)`、`requireRecord(long actorId,long patientId)`、`requireNursing(long actorId,long patientId)` → void
- `CarePlanAuthorizationService.canReadEvidence(long actorId,long patientId,String sourceType,long sourceId)` → boolean
- `CarePlanQueryService.list(long actorId,Long patientId,String queue,String cursor,int limit)` → PageView map；`detail(long actorId,long planId)` → PlanView map；`listRevisions(long actorId,long planId,String cursor,int limit)` → PageView map（仅版本元数据）；`revision(long actorId,long planId,long revisionId)` → PlanView map（指定版本）
- `CarePlanService.createDraft(long actorId,Map<String,Object> body,String commandKey)` → PlanView map
- `CarePlanService.saveDraft(long actorId,long planId,long revisionId,Map<String,Object> body,String commandKey,long expectedVersion)` → PlanView map
- `CarePlanService.createRevision(long actorId,long planId,String commandKey,long expectedVersion)` → PlanView map
- `CarePlanService.publish(long actorId,long planId,long revisionId,Map<String,Object> confirmation,String commandKey,long expectedVersion)` → CommandResult map
- `CarePlanService.transitionPlan(long actorId,long planId,String action,String reason,String commandKey,long expectedVersion)` → CommandResult map；action=CANCEL/CLOSE
- `CarePlanActionService.submit(long actorId,long actionId,Map<String,Object> body,String commandKey,long expectedVersion)`、`help(...)`、`followUp(...)`、`review(...)` → CommandResult map；四者同参数形状，body 按上述合同
- `CarePlanCommandStore.execute(long actorId,String commandKey,String canonicalPayload,long planId,long expectedVersion,Supplier<Map<String,Object>> change)` → map；仅在服务已开启、锁定计划和重新授权的事务内调用
- `CarePlanEventStore.append(long patientId,long planId,Long revisionId,Long actionId,long actorId,String eventType,Map<String,Object> payload)` → long eventId
- `CarePlanNotificationWorker.enqueue(long eventId)` → void(同业务事务)；`tick(Instant now)` → void
- `CarePlanTimelineProjector.list(long actorId,long patientId,LocalDate from,LocalDate to,int limit)` → List<HealthEvent>

发布 confirmation={currentRevisionId:Long?,supersededActionDigest:String?}；首次发布二者为空，修订时二者必填。详情默认选择当前已发布版本；尚未发布的初始草稿仅当前分配医生可见。医生通过 revision() 明确读取草稿。非临床用户的 draftRevisionId 为 null，不返回 revisionImpact，版本列表只包含已发布版本。版本元数据字段为 id/planId:Long、revisionNo:Integer、status/createdAt/publishedAt:String?；按 createdAt+id 稳定分页。草稿行动没有持久 action 行：id=null、status=DRAFT、version=0，引用按 ordinal 从 draft_json 投影，逐次检查原模块权限。

仅临床医生的详情另返回 revisionImpact={currentRevisionId,actionIds,digest}，服务端生成确定性摘要供发布重验。负责人候选由 `CarePlanQueryService.assignees(long actorId,long patientId):List<Map>` 提供，仅返回具备录入权限的 userId/displayName/role；GET `/care-plans/assignees?patientId=` 与 `listAssignees(patientId)` 为对应API。

新协作计划聚合的所有变更事务必须显式使用 READ_COMMITTED，不修改全局数据源设置。服务在授权查询及变更前检查同数据源连接的实际事务隔离级别；若已经加入 DEFAULT→实际 REPEATABLE_READ 或显式 REPEATABLE_READ 等不兼容外层事务，必须在变更前拒绝，不因 Spring 事务注解加入已有事务而静默继承快照。命令/事件存储使用同一守卫；等待幂等命令后仍重新授权并逐次投影证据权限。Task4/5/6 的后续计划写入和事务测试须沿用此合同。

actorId 均来自服务端认证或通知队列内部上下文；HTTP body 中的 actorId 一律拒绝，不能覆盖会话。`help` body={note}；`followUp`=FollowUpBody；`review`=ReviewBody。

## Task 1: 建立数据库契约与幂等升级

**Files:** Create `src/main/resources/sql/care_plan_collaboration_20261003.sql`, `src/main/java/org/familyhealthcare/service/careplan/CarePlanContracts.java`, `src/test/java/org/familyhealthcare/service/CarePlanSchemaTest.java`, `src/test/java/org/familyhealthcare/service/CarePlanTestFixture.java`; Modify `docker-compose.yml`, `README.md`, `cn/README.md`。

**Interfaces:** Produces approved new tables/columns and shared validators `CarePlanContracts.validateDraft(Map)`, `validateReceipt(Map)`, `parseOffsetInstant(String):Instant`。测试 fixture 用合成账号/患者/角色和真实 JdbcTemplate；提供 `jdbc()`, `as(long userId)`, `now():Instant`, `advance(Duration)`，初始化 SQL 不接触外部数据库。

- [ ] 写 `legacyPlansRemainPrivateAfterRepeatMigration`、`schemaRejectsDuplicateRevisionAndActionOrdinal`、`validatesExactLimitsAndOffsetTime`：分别断言旧行 workflow_version=0、没有新任务/通知；唯一键拒绝重复；0/51项、2001字、6证据和无偏移时间拒绝，1/50项、2000字、5证据接受。包含 emoji code point 边界，两个不同偏移的重复本地时刻转换为不同 Instant。
- [ ] 测试核心断言包含 `assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_action", Integer.class));` 与 `assertEquals(0, jdbc.queryForObject("SELECT workflow_version FROM doctor_care_plan WHERE id=1", Integer.class));`；复用fixture的legacy行ID1。
- [ ] 运行 `mvn -B -Dtest=CarePlanSchemaTest -DskipTests=false test`，确认红灯由缺少迁移/验证能力导致。
- [ ] 实现八类对象：旧表增量元数据及 revision/action/event/evidence/nurse_assignment/notification/command。UTC 用 DATETIME(6) 按 UTC 明确读写；外键不级联删除历史；事件身份为快照，不随用户软删除丢失。角色/menu 幂等创建，不给用户自动绑 nurse。先新增旧表列、再建版本表、最后添加 current_revision_id 外键，避免循环建表顺序失败。
- [ ] 在医生旧表保留 status 默认 ACTIVE；新 lifecycle 可空且 workflow_version 默认0。新服务显式写1。draft_json 保存未发布行动列表，发布后冻结并生成 action；发布旧版本不覆盖 snapshot。新通知允许 channel_id 空以表示 NO_CHANNEL，unique dispatch_key 用应用生成非空字符串避免 NULL 唯一约束漏洞。
- [ ] 把 SQL 加到空库安装顺序、Compose 演示数据之前；使用 information_schema 条件升级沿用现有格式，不改原基线语义。H2 fixture 只作兼容处理，完整原 SQL 留待 Task 12 MySQL 验证。
- [ ] 双后端定向测试通过，检查 schema 镜像同协议；提交 `feat: add care plan collaboration schema`。

## Task 2: 实现统一权限与护理分配/授权

**Files:** Create `service/careplan/CarePlanAuthorizationService.java`, `service/careplan/CareNurseAssignmentService.java`, `controller/CareNurseAssignmentController.java`, `service/careplan/CarePlanException.java`, `controller/CarePlanExceptionAdvice.java`, `src/test/java/org/familyhealthcare/service/CarePlanAuthorizationTest.java`；Modify `service/CareJourneyService.java` 的 saveGrant、`interceptor/PermissionInterceptor.java`。所有无前缀 Java 路径位于 `src/main/java/org/familyhealthcare/`。

**Interfaces:** Produces四个授权方法及证据授权；护理服务 `assign(long adminId,Map body):Map`, `revoke(long adminId,long assignmentId):void`, `list(long actorId,long patientId):List<Map>`。新异常携带 HTTP status 与稳定错误码；advice 限定新控制器，不重写旧错误策略。

- [ ] 写 `nurseNeedsRoleAssignmentAndGrant`、`adminAloneCannotReadClinicalPlan`、`revocationDoesNotFallBackToMembership`、`crossPatientEvidenceAndDisabledActorDenied`：三条件逐一缺失均 `assertThrows(CarePlanException.class,...)`；只读仅读；WRITE/PROXY 可录不可临床；有效医生分配才可发布。额外断言家属持有不含 CARE_PLAN 的限定授权不可访问新模块。
- [ ] 测试核心断言：`assertThrows(CarePlanException.class, () -> auth.requireClinical(nurseId, patientId));`；授予READ后 `auth.requireRead(familyId, patientId);` 成功而 `auth.requireRecord(familyId, patientId)`抛403。
- [ ] 运行 `mvn -B -Dtest=CarePlanAuthorizationTest -DskipTests=false test` 确认缺失服务导致红灯。
- [ ] 实现显式 role/module/assignment 查询，不调用会给 admin 自动全通的 shortcut。新CARE_PLAN家属/护理访问仅接受当前有效care_access_grant显式包含CARE_PLAN；空白“全部模块”的旧grant或care_member不会自动扩展到新模块，care_member仅用于关系显示。有显式 grant 时按现有覆盖旧 membership 的规则；非owner的 nurse 分支优先，不能靠同时持有 family 身份绕过护理分配。患者 owner 可访问自己发布记录，但不可假冒医生。
- [ ] 在 saveGrant 允许 NURSE 角色但验证有效 nurse；未分配到患者的护士不能接受此模块的新授权。护理分配管理员写入、患者/医生只读最小必要信息。暴露 `/care-nurse-assignments` GET/POST 与 `/{id}/revoke` POST；新正文服务再次授权，不仅 menu 检查。
- [ ] 测试同账号多角色、到期边界、停用、撤销、跨患者 ID 全部通过；运行原 `NotificationAudienceScopeTest,PlatformPermissionRegressionTest` 无回归；提交 `feat: scope nurse and care plan permissions`。

## Task 3: 草稿、版本查询与旧记录兼容

**Files:** Create `service/careplan/CarePlanProperties.java`, `service/careplan/CarePlanQueryService.java`, `service/careplan/CarePlanService.java`, `service/careplan/CarePlanEventStore.java`, `service/careplan/CarePlanCommandStore.java`, `src/test/java/org/familyhealthcare/service/CarePlanDraftTest.java`；Modify `src/main/java/org/familyhealthcare/service/DoctorWorkspaceService.java` 旧列表、`src/main/resources/application.yml`，仅增加 workflowVersion 标记与新旧统计区分。

**Interfaces:** Consumes Task1/2，produces createDraft/saveDraft/createRevision 和 list/detail；Task4 补充发布终态。queue 只允许 TODAY/REVIEW/HELP/OVERDUE/HISTORY，游标由排序键+ID编码并验证，不接收 SQL 片段。

- [ ] 写 `draftHiddenFromPatientFamilyAndNurse`、`legacyCopyCreatesDraftWithoutTasks`、`draftSaveRequiresCurrentVersion`、`moduleOnlyReadDoesNotLoadFullCareContext`；断言未知/越权 ID 同一403响应，不返回作者/患者名。草稿创建/save 的重复 commandKey 仅一行/一次版本增长。
- [ ] 测试核心断言：`assertEquals("DRAFT", draft.get("lifecycle"));`；`assertTrue(((List<?>) query.list(patientIdOwner, patientId, "TODAY", null, 50).get("items")).isEmpty());`。
- [ ] 运行 `mvn -B -Dtest=CarePlanDraftTest -DskipTests=false test`，记录预期红灯。
- [ ] 实现 map 合同、授权最小投影、草稿JSON验证、legacySourceId 同患者约束；旧 ACTIVE 只能显式复制成新草稿。创建命令先按 actor+key 原子占位，避免尚无 planId 时并发重复 INSERT；保存/修订按已有计划锁顺序。
- [ ] 新增 `care-plan.enabled` 默认false，在application.yml中显式配置；关闭时新service/worker不查询新表，新入口通过登录后capabilities结果隐藏，已有功能继续可用。合成测试明确启用，上线顺序为迁移验证后启用。修改旧医生列表在新功能启用时增加兼容标记但保留原内部可见范围；新列表不计 legacy，旧POST不自动发布。新聚合的旧字段仅保存本地化通用标记与空说明，旧 status 永久为保留值 COLLABORATION；真实临床正文仅在版本表。旧列表始终用既有 status 列排除保留值，即使功能关闭且未迁移也可运行；旧 POST 拒绝该保留值。启用时旧列表增加 workflowVersion=0，旧 ACTIVE 统计保持不变；新增 draftPlans/activeCollaborativePlans 只按当前临床授权计数，不复用管理员全局范围。查询有稳定分页和同患者约束，详情不连带全档案、家属或药物列表。
- [ ] 双后端定向测试及旧 CarePlanWorkflowTest 通过；提交 `feat: add private care plan drafts and queries`。

## Task 4: 发布、修订、取消和关闭的事务语义

**Files:** Modify Task3 的 CarePlanService、CarePlanEventStore、CarePlanCommandStore；Create `service/careplan/CarePlanNotificationQueue.java`， `src/test/java/org/familyhealthcare/service/CarePlanLifecycleTest.java`, `src/test/java/org/familyhealthcare/service/CarePlanConcurrencyTest.java`。

**Interfaces:** Produces publish/transitionPlan；append 返回稳定事件 ID；通知入队先以可替换事务接口 `CarePlanNotificationQueue.enqueue(long eventId):void` 声明，并由 Task6 完成实现。生产启动不允许空实现，Task4 在测试中注入捕获队列。

- [ ] 写 `publishCreatesOneFrozenRevisionAndActionSet`、`revisionSupersedesPendingButRetainsConfirmedHistory`、`closeRequiresAllCurrentActionsConfirmed`、`cancelWinsAgainstLateReceipt`；断言 DRAFT→ACTIVE，旧待办 SUPERSEDED，旧 CONFIRMED 不变，无新自动完成。并发用两条数据库连接和屏障，不用串行调用假装竞态。
- [ ] 写 `sameKeySameBodyReturnsSameResult`、`sameKeyDifferentBodyReturns409`、`replayAfterRevocationReturns403`；断言恰好一组 action/event/queue，重复成功不再次通知，失权重放不返回历史正文。
- [ ] 测试核心断言：`assertEquals(first, replay);`；`assertEquals("ACTIVE", first.get("lifecycle"));`；事务强制异常后 `assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM care_plan_action", Integer.class));`。
- [ ] 运行 `mvn -B -Dtest=CarePlanLifecycleTest,CarePlanConcurrencyTest -DskipTests=false test`，确认状态机/并发能力尚未实现而失败。
- [ ] 实现所有变化按 plan 行锁→授权→命令幂等检查→expectedVersion→变更/事件/队列 的顺序。幂等匹配 payload 为排序字段/规范化时间后的确定性 JSON 摘要；不同动作/对象也纳入摘要。事务失败整个回滚。关闭/取消只能 ACTIVE；取消原因必填，关闭不接受患者或纯管理员。
- [ ] 修订发布要求客户端携带当前 revisionId 与确认被替代项清单的摘要；服务端重算不一致返回409，防止预览后有新回执仍误作无变化确认。只允许一个未发布修订草稿。
- [ ] 双后端红转绿；检查锁顺序、死锁后的安全重试不生成第二命令；提交 `feat: make care plan lifecycle atomic`。

## Task 5: 自报、代录、困难、跟进和医生复核

**Files:** Create `service/careplan/CarePlanActionService.java`, `src/test/java/org/familyhealthcare/service/CarePlanReceiptTest.java`；Modify query/event store、`src/test/java/org/familyhealthcare/service/CarePlanConcurrencyTest.java`。

**Interfaces:** Produces submit/help/followUp/review；所有写入先由 action 查 plan/patient，不能信 body.patientId；CommandResult 与 Task4一致。

- [ ] 写 `proxyCannotForgeSelfOrActor`、`nurseCannotConfirmOrClose`、`returnRetainsFirstSubmissionAndCreatesNewReceipt`、`evidenceIsScopedAtWriteAndRead`；断言家属/护理 entryMode=ASSISTED，跨患者/受限原始记录拒绝，RETURN 后原事件未删、firstSubmittedAt 保留、最新待补充状态独立显示。
- [ ] 写 `receiptBoundsAndOccurrenceTimeValidated`：2000/2001 code points、5/6引用、未来时间、无偏移、空白 note；用注入 `Clock` 不用依赖真实墙钟的易变断言。医生管理跟进不是 SELF 回执；账号所有者 SELF 只标“账号自报”，不声称身份已核实。
- [ ] 测试核心断言：`assertEquals("SUBMITTED", submitted.get("actionStatus"));`；`assertEquals("ASSISTED", event.get("entryMode"));`；退回再提交后 `assertEquals(originalFirstSubmittedAt, action.get("firstSubmittedAt"));`。
- [ ] 运行 `mvn -B -Dtest=CarePlanReceiptTest,CarePlanConcurrencyTest -DskipTests=false test` 确认红灯。
- [ ] 实现精确状态迁移：OPEN→NEEDS_HELP；OPEN/NEEDS_HELP→SUBMITTED；SUBMITTED→CONFIRMED/OPEN；终态拒绝。护理/医生 followUp 只追加记录，不清除困难、不直接完成。记录 actorName/role/time 快照不可被 body 覆盖。
- [ ] 同一 action 锁和 plan 锁兼容 Task4；提交与取消/修订并发只有一个有效结果。保存证据仅存 type/ID，查询时再次授权，失权后不回退历史副本。
- [ ] 双后端测试通过，提交 `feat: record and review care plan receipts`。

## Task 6: 持久通知、去重、发送结果与重启恢复

**Files:** Create `service/careplan/CarePlanNotificationWorker.java`, `src/main/java/org/familyhealthcare/service/careplan/CarePlanNotificationTransport.java`, `task/CarePlanNotificationTask.java`, `src/test/java/org/familyhealthcare/service/CarePlanNotificationTest.java`；Modify `src/main/java/org/familyhealthcare/service/NotificationDeliveryService.java` 仅新增可分类发送结果，不改变原 boolean API；Modify `src/main/java/org/familyhealthcare/service/NotificationMessageLocalizer.java` 新事件文案。

**Interfaces:** Worker implements enqueue/tick；transport `send(long channelId,String eventKey,String title,String relativePath):DeliveryOutcome`；新 enum outcome=DELIVERED/FAILED/UNKNOWN/NO_CHANNEL。旧调用仍使用原契约。DeliveryOutcome 作为 CarePlanNotificationTransport 的嵌套enum定义。新worker显式调用 CarePlanAuthorizationService，不直接复用未知事件的旧宽泛收件人逻辑。

- [ ] 写 `deliveryOutcomesRemainDistinct`、`retriesStopAtThreeAndRespectRevocation`、`twoWorkersClaimOneJob`、`crashAfterSendBecomesUnknown`、`payloadContainsNoClinicalDetails`；断言 attempt=1/2/3的时刻，UNKNOWN 不自动重发，姓名/标题/指标不存在于 outbound body。
- [ ] 测试核心断言：`assertEquals("UNKNOWN", job.get("status"));`、`verify(transport, times(1)).send(channelId, eventKey, title, relativePath);`；UNKNOWN后推进时钟不增加send次数。
- [ ] 运行 `mvn -B -Dtest=CarePlanNotificationTest -DskipTests=false test` 红灯。
- [ ] 同业务事务按 event+recipient+channel 生成 dispatch_key；无渠道单独记 NO_CHANNEL。队列仅当前可见用户；due 通知按 action+due instant 只生成一次，已失效版本不催办。医生待审和护理困难的 in-app 队列不以 outbound 成功为前提。
- [ ] 实现非阻塞数据库领取（短事务标 lease/claimedAt 后发送），不能持锁等待网络。领取时与发送前重查权限；崩溃/超时的已尝试任务 UNKNOWN，不自动抢回重发；明确未发送/明确可重试失败按1分钟、5分钟共3次。
- [ ] 提供管理员只读通用投递状态及显式人工 retry，UNKNOWN 必须携带 duplicateRiskAcknowledged=true；人工重试再授权且另记审计，不能拿“管理员”读取临床 payload。第一版只支持已有用户自有渠道，不新增第三方数据目的地。
- [ ] 双后端通过；原 NotificationDeliveryServiceTest/NotificationMessageLocalizerTest 通过；提交 `feat: deliver care plan notifications reliably`。

## Task 7: 对外API、错误语义与安全时间线

**Files:** Create `controller/CarePlanController.java`, `service/careplan/CarePlanTimelineProjector.java`, `src/test/java/org/familyhealthcare/service/CarePlanApiTest.java`, `src/test/java/org/familyhealthcare/service/CarePlanTimelineTest.java`；Modify `src/main/java/org/familyhealthcare/service/HealthTimelineService.java`, `src/main/java/org/familyhealthcare/interceptor/PermissionInterceptor.java`, 新 exception advice；只新增不重写既有 family timeline API。

**Interfaces:** GET `/care-plans/capabilities`返回仅{enabled:Boolean}并要求登录、无临床数据；禁用时其余新接口返回404且不访问新表。GET `/care-plans?patientId=&queue=&cursor=&limit=`；GET `/{id}`；POST root、`/{id}/revisions`、`/{id}/revisions/{revisionId}/save`、`.../publish`、`/{id}/cancel`、`/{id}/close`；POST `/actions/{actionId}/receipts|help|follow-ups|reviews`。命令 body 统一携带 commandKey/expectedVersion；详情 URI 使用 ID，不把敏感值编码进 URL。

- [ ] 写 MockMvc `httpAndEnvelopeCodesAgree`、`forgedActorRejected`、`revokedTimelineDoesNotRevealSnapshot`、`sameEventIdInDifferentSourceDoesNotCollide`；403/409需 HTTP 与 JSON 一致；时间线仅sourceType=CARE_PLAN_EVENT、sourceId=eventId，不插重复 health_event。
- [ ] MockMvc核心断言 `.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))`；失权timeline断言 `assertTrue(events.stream().noneMatch(e -> "CARE_PLAN_EVENT".equals(e.getSourceType())));`。
- [ ] 运行 `mvn -B -Dtest=CarePlanApiTest,CarePlanTimelineTest -DskipTests=false test` 确认红灯。
- [ ] 控制器从 CurrentUserUtil 取actorId，委托新service；新advice只捕获 CarePlanException，不用字符串猜权限错误。Map 参数白名单拒绝 body.actorId、任意 status 和未知 action。
- [ ] 查询投影器检查当前 CARE_PLAN 权限；通用timeline授权拒绝此模块时只省略该类事件，不删其他授权事件。添加新 `/care-plans/{id}/events` 精简事件入口供模块-only用户，无需解锁整个 family timeline。published/revised/submit/review/cancel/close 的摘要不含受限证据正文。
- [ ] 双后端/API 测试通过，提交 `feat: expose scoped care plan APIs and timeline`。

## Task 8: 前端协议层、患者上下文和时间处理

**Files:** Create `frontend/src/api/carePlan.js`, `frontend/src/composables/useCarePlan.js`, `frontend/src/utils/carePlanTime.js`, `frontend/tests/carePlanClient.test.mjs`, `frontend/tests/carePlanTime.test.mjs`；不新建页面。

**Interfaces:** API `getCarePlanCapabilities()`, `listPlans(params)`, `getPlan(id)`, `createDraft(data)`, `saveDraft(id,revisionId,data)`, `publishPlan(id,revisionId,data)`, `revisePlan(id,data)`, `transitionPlan(id,action,data)`, `submitReceipt(id,data)`, `requestHelp(id,data)`, `followUp(id,data)`, `reviewReceipt(id,data)`, `listPlanEvents(id)`, `listAssignees(patientId)`。护理API同文件导出 `listNurseAssignments(patientId)`, `assignNurse(data)`, `revokeNurseAssignment(id)`，对应Task2接口。composable `useCarePlan(patientIdRef)` 返回 state/load/open/runCommand/reset；时间 helper `toOffsetIso(localDateTime,offsetMinutes):string`、`formatPlanTime(iso,locale):string`。

- [ ] 写 `lateReadCannotReplaceNewPatientOrEditor`、`timeoutRetryReusesCommandKey`、`conflictPreservesInput`、`logoutClearsSensitiveState`；模拟A→B→A、同患者重开、旧finally、服务成功但网络失败、HTTP409。同命令重试 payload 不变，不在新编辑器重用旧key。
- [ ] 写时间重复/缺失本地时刻、UTC跨日和非法偏移测试；不存在的本地时间需用户纠正，重复时刻展示明确偏移供确认，不能 JS Date 自动正规化后悄悄保存。
- [ ] 测试核心断言 `assert.equal(retry.commandKey, first.commandKey)`、`assert.equal(state.patientId, newPatientId)`、`assert.equal(state.draft.note, unsavedNote)`；过期响应settle后再次断言不变。
- [ ] 运行 `cd frontend && node --test tests/carePlanClient.test.mjs tests/carePlanTime.test.mjs` 红灯。
- [ ] 实现 API 薄封装、epoch/session捕获、一次性command生命周期、失败保留输入；用原 request 客户端，读取 `error.response?.status || error.code` 处理409，不能把关闭弹窗当服务端撤销。状态仅内存，不将临床草稿写入localStorage。
- [ ] 两种前端测试通过，提交 `feat: add safe care plan client state`。

## Task 9: 医生计划编辑与复核工作台

**Files:** Create `frontend/src/components/care-plan/PlanEditor.vue`, `frontend/src/components/care-plan/PlanDetail.vue`, `frontend/src/components/care-plan/PlanReviewQueue.vue`, `frontend/tests/carePlanDoctorUi.test.mjs`；Modify `frontend/src/views/DoctorWorkspace.vue`、其现有计划加载逻辑。

**Interfaces:** PlanEditor props patientId/planId/revisionId，emit saved/closed；PlanDetail props planId，emit changed；PlanReviewQueue props patientId(可空)，emit open(planId)。全部使用Task8，不另写请求层。

- [ ] 写 `saveDraftDoesNotPublish`、`revisionRequiresCurrentImpactConfirmation`、`legacyCopyRemainsPrivate`、`reviewCannotBeConfusedWithPatientCompletion`；断言只有明确发布动作调用publish，旧列表仍可看，所有权限来自server allowedActions且错误分支保留输入。
- [ ] 测试核心断言 `assert.equal(publishCalls.length, 0)` 在仅保存草稿后成立；明确确认发布后 `assert.equal(publishCalls.length, 1)`，携带服务器给定修订影响摘要。
- [ ] 运行 `cd frontend && node --test tests/carePlanDoctorUi.test.mjs` 红灯。
- [ ] 实现1–50项编辑、负责人最小列表、明确截止时间/时区、通过listAssignees加载合法负责人、草稿保存、发布范围与修订差异确认。医生点击“已复核记录”而非“治疗成功”；RETURN和CANCEL必填原因；CLOSE按钮仅所有当前项confirmed时展示且服务端再验。
- [ ] 精简原DoctorWorkspace为容器，保留原reviews/notes/consultation；共用PlanDetail，不向原大型组件继续塞全部状态机。
- [ ] 双语测试与 `npm run build`通过，提交 `feat: connect clinician care plan workflow`。

## Task 10: 个人/家庭执行、护理队列与授权管理界面

**Files:** Create `frontend/src/components/care-plan/PlanTaskList.vue`, `frontend/src/components/care-plan/ReceiptDialog.vue`, `frontend/src/components/care-plan/NurseAssignments.vue`, `frontend/src/views/NurseWorkspace.vue`, `frontend/tests/carePlanParticipantUi.test.mjs`, `frontend/tests/carePlanNurseUi.test.mjs`；Modify `frontend/src/views/CareCenter.vue`, `frontend/src/views/CareJourneyManager.vue` privacy表单、`frontend/src/views/PatientManager.vue`管理员分配入口、`frontend/src/views/FamilyHealthManager.vue`事件深链、`frontend/src/main.js`, `frontend/src/App.vue`, `frontend/src/utils/workspaceAccess.js`, `frontend/src/utils/workspaceNavigation.js`。

**Interfaces:** PlanTaskList props patientId/mode，独立加载 `/care-plans`；ReceiptDialog props action，emit submitted；NurseWorkspace 使用 queue=HELP/REVIEW，不调用整个DoctorWorkspace；新增 `/nurse-workspace` 与 `/care-plans/:id`(复用PlanDetail路由容器)。管理员护士分配与患者 CARE_PLAN grant 为两个独立动作。

- [ ] 写 `moduleOnlyCareWorksWhenFullContextDenied`、`readOnlyCannotSubmit`、`assistedEntryShowsActualActor`、`nurseNavigationDoesNotGrantDoctorAccess`、`returnedReceiptRetainsOriginalTimeliness`；模拟纯 CARE_PLAN 用户旧care/context返回403但新任务可用，双语都覆盖。
- [ ] 测试核心断言 `assert.equal(submitted.entryMode, "ASSISTED")`、`assert.equal(clinicalCalls.length, 0)`，只读按钮和API拒绝同时测试，不能只测源码包含disabled字符串。
- [ ] 运行 `cd frontend && node --test tests/carePlanParticipantUi.test.mjs tests/carePlanNurseUi.test.mjs tests/workspaceNavigation.test.mjs` 红灯。
- [ ] 今日页面单独挂PlanTaskList，不把新列表加载成功与旧全档案请求绑定；缺原模块权限的旧区块显示受限，不能阻止新入口。展示已提交/待复核与困难，不把已提交显示为已确认；代录默认ASSISTED并展示账号身份。
- [ ] Privacy增加NURSE及CARE_PLAN选项，护士候选仅已分配给当前患者且有效的账号；管理员分配不自动授权，分配后清楚提示还需患者授权。Task2服务端约束与UI保持一致。
- [ ] 护理可联系/记录跟进/协助录入，不显示临床发布或复核；正文被撤销后立即清空。时间线只链接新安全详情，保留旧事件。
- [ ] EN/CN 390px、44px触控、键盘、焦点、关闭/返回/前进场景通过；提交 `feat: connect patient family and nurse care tasks`。

## Task 11: 双语公开演示呈现同一真实产品边界

**Files:** Modify `demo/model.js`, `demo/app.js`, `demo/index.html`, `demo/style.css`, `demo/README.md`；Create `demo/tests/carePlanCollaboration.test.mjs`；同步cn/demo；必要时Modify `.github/workflows/demo-pages.yml` 打包新资源。

**Interfaces:** 在现有demo模型添加合成 carePlanRevisions/actions/events/nurseAssignments 和角色 nurse；动作API保持model现有风格，不另造网络后端。角色切换是在同一虚构场景演示协作，不宣称安全登录。

- [ ] 写 `demoShowsDraftPublishAssistReviewSequence`、`demoNurseCannotPublishOrConfirm`、`demoRevisionKeepsHistory`、`demoResetDoesNotRetainPrivateRealInput`；中文英文同状态序列，演示提示始终存在，无实际fetch/webhook调用。
- [ ] 测试核心断言 `assert.equal(action.status, "SUBMITTED")` 在个人提交后成立；医生确认后为CONFIRMED；`assert.equal(networkCalls.length, 0)` 全演示流程保持成立。
- [ ] 运行 `node --test demo/tests/carePlanCollaboration.test.mjs cn/demo/tests/carePlanCollaboration.test.mjs` 红灯。
- [ ] 添加可点击演示路径：医生草稿→发布→个人/家属回执→护理跟进→医生确认；一次性行动与新版状态一致。虚构未配置通知显示“演示结果”，不能显示真实送达；输入只在当前演示会话。
- [ ] 运行两套 `node --check .../app.js` 和两套全部demo测试，检查实际打包资源清单；提交 `feat: demonstrate collaborative care plans bilingually`。

## Task 12: 真实MySQL升级/恢复与合成全栈端到端验证

**Files:** Create `src/test/java/org/familyhealthcare/service/CarePlanMysqlIntegrationTest.java`, `src/test/resources/sql/care-plan-e2e-fixture.sql`, `scripts/verify-care-plan-mysql.sh`, `scripts/verify-care-plan-browser.mjs`, `frontend/playwright.config.mjs`, `frontend/e2e/carePlanCollaboration.spec.mjs`；Modify `frontend/package.json`/lock、`.github/workflows/ci.yml`、`deploy/build-bilingual-frontend.sh`(仅如测试入口需要)。后端test fixture使用现有Bcrypt，生成隔离测试账号；不改生产种子或bootstrap默认值。

**Interfaces:** MySQL验证只接受以 `care_plan_test_` 开头的数据库名、loopback/CI service host 和显式 `CARE_PLAN_TEST_ONLY=true`；缺参数立即拒绝，不默认现有开发/生产库。测试任务 `mvn -Dtest=CarePlanMysqlIntegrationTest -DcarePlanMysqlRequired=true -DskipTests=false test` 缺配置必FAIL，普通本地全测未配置可明确skip该类并记录边界。

- [ ] 写 `freshAndExistingDatabaseApplyMigrationTwice`、`mysqlConcurrentCommandsAreIdempotent`、`restorePreservesAllPlanRelationships`；空库脚本链、含legacy旧库、重复升级、真实行锁并发均断言；snapshot比较所有新表行数/主外键/事件摘要，不只健康检查。
- [ ] 测试核心断言 `assertEquals(beforeRestore, afterRestore)` 比较所有新表排序后的行/关联摘要；并发发布后 `assertEquals(1, publishedRevisionCount)`，重复升级后legacy记录原字段完全相同。
- [ ] 加MySQL8.0隔离CI service与同一commit双后端测试矩阵；脚本创建测试库、只导入合成数据，用mysqldump完整备份到临时文件，再恢复另一个新测试库并比较。凭据仅CI临时env，不进入日志/仓库；不是调用旧RunSqlMigration的有限表备份。
- [ ] 安装仅测试所需官方Playwright包并锁定版本、使用可信Chromium；现在编写计划不安装。脚本通过隔离API端口和合成库启动真实Spring应用，AI/OCR/外部通知禁用或stub。分别运行EN/CN构建站点，不只route-mock APIs。
- [ ] 浏览器测试覆盖四类主体和跨患者拒绝：草稿不可见→发布→本人/代录→困难→护理跟进→医生退回→再提交→确认→关闭；另测修订、撤销、模块-only、重复按钮、A→B→A、账号退出、390px键盘交互。不得自动向真实渠道发送测试消息。
- [ ] 运行 `bash scripts/verify-care-plan-mysql.sh`、`node scripts/verify-care-plan-browser.mjs`；预期所有显式断言通过、无页面脚本异常。环境缺依赖时由CI完成同一脚本，记录本地未运行与CI证据，不能放宽断言或静默跳过必需验收。
- [ ] 两次独立fresh/upgrade流程均通过后提交 `test: verify care plan migration and end to end flows`。

## Task 13: 发布文档、独立审查、main与部署证据

**Files:** Modify `README.md`, `cn/README.md`, `docs/PRODUCT_REVIEW.md`, `cn/docs/PRODUCT_REVIEW.md`, `docs/family-health-workflows.md`, `cn/docs/family-health-workflows.md`, `docs/USER_GUIDE.md`, `cn/docs/USER_GUIDE.md`, `src/main/java/org/familyhealthcare/service/FamilyBackupService.java`及cn镜像的备份范围警告；Create `docs/verification/care-plan-collaboration.json`、`docs/CARE_PLAN_COLLABORATION_RELEASE.md`及中文发布说明。

**Interfaces:** 发布说明记录精确commit、每个验收结果/证据、未运行检查、渠道与临床边界；现有家庭备份version=1不改变，warning明确不覆盖协作新表，全库恢复另有Task12证据。

- [ ] 先写 `CarePlanReleaseContractTest`(新增 `src/test/java/org/familyhealthcare/service/CarePlanReleaseContractTest.java`) 校验旧备份警告说明新范围、旧格式未变；文档静态脚本确认安装顺序包含新SQL、demo入口与权限说明一致。修复旧文档“init.sql已足够”的过时表述。
- [ ] 发布契约测试断言 `assertEquals(1, archive.getInteger("version"));` 与 `assertTrue(warnings.contains("care plan"))`（英文归一化warning），中文断言含“协作计划”；归档格式不增加未声明表。
- [ ] 运行双后端全部测试+打包、双前端全部测试+构建、双demo全部测试+语法、Task12真实DB及E2E；每组都记录实际数目，不沿用2476为当前结果。
- [ ] 请求独立全分支审查，重点核对权限绕过、草稿快照、跨患者证据、竞态、重放失权、护士scope、admin shortcut、通知UNKNOWN和旧接口兼容；问题修复后重跑受影响测试及最终全量，不凭审查意见直接宣布通过。
- [ ] 按既有授权direct-main流程先fetch并核对远端变化，只提交本里程碑已审查文件；禁止force push、自动覆盖他人提交和新开公开分支。生产数据库迁移/后端上线如无本次明确环境与授权，停在可发布工件并说明需要的具体步骤，不推断Pages授权等于后端部署授权。
- [ ] 推送后验证远端main精确SHA、该SHA全部required CI和Pages完成；如果新提交替代部署，转而验证实际目标SHA，不能拿旧绿色结果充数。失败先诊断并在授权范围修复/重验，不以一次排队或进行中当作完成。
- [ ] 从标准公开EN/CN demo URL读取HTML及实际声明的脚本/样式，核对HTTP200与提交字节；浏览器抽查新版角色闭环。后端仅在已授权部署目标验证迁移版本、登录合成账号与关键接口；否则明确“后端打包通过，未部署”。
- [ ] 发布机器可读验收证据与双语简报，提交 `docs: record care plan collaboration release evidence`，并核对文档提交后的main状态。用户收到的是实际完成能力、验证范围与剩余人工/环境决策，不是仅测试计数。

### 最终验证的固定命令

在已批准实施完成后，从仓库根目录运行；以下不是本次文档阶段已经执行的命令。

```bash
set -e
mvn -B -DskipTests=false test
mvn -B -DskipTests package
(cd cn && mvn -B -DskipTests=false test && mvn -B -DskipTests package)
(cd frontend && npm test && npm run build)
(cd cn/frontend && npm test && npm run build)
node --check demo/app.js
node --check cn/demo/app.js
node --test demo/tests/*.test.mjs
node --test cn/demo/tests/*.test.mjs
bash scripts/verify-care-plan-mysql.sh
node scripts/verify-care-plan-browser.mjs
git diff --check
```

预期：每条退出码0；MySQL和browser验收产生对应commit与测试环境证据，不能用跳过计作通过。MySQL脚本和browser脚本是Task12新建的交付物，实施之前不存在；本计划未声称现在可运行。

## 推荐执行方式与依赖

推荐 **Subagent-driven**：每个任务由新的实现者完成并由新的审查者检查，再进行全分支审查。原因是权限、版本与幂等错误可能跨角色泄露医疗信息，独立审查价值高于只追求最快修改。

串行主干：1 → 2 → 3 → 4 → 5 → 7。Task6在Task4事件/队列契约固定后可与Task5并行；Task8可在Task7 API契约审查固定后启动。Task9与Task10在共享前端契约冻结后可分工，但都修改共享导航时由一个整合者负责。Task11可在模型状态机冻结后独立进行。Task12从Task1就准备测试，但完整E2E依赖6–10；Task13必须等待全部任务与验收。

同一物理工作区不能让两个实现者并发编辑相同文件；镜像EN/CN随每个任务一起提交，不拆成两个可能漂移的产品实现。父任务负责接口变更广播和冲突整合；重大设计变更先回到规格审批。

备选 **Native**：由一个执行者顺序完成全部任务，最后由独立审查者检查整个分支。上下文切换较少，但独立反馈较晚。

## 自审与规格覆盖

- 规格1–2定位/边界 → 全局约束与Task1/11/13；没有引入诊断、周期任务重写或新临床阈值
- 规格3权限/护理 → Task2/7/10/12；admin默认放行、nurse冒用family、模块-only聚合失败均有测试
- 规格4版本/legacy → Task1/3/4/9；旧POST仍legacy，旧ACTIVE无自动发布
- 规格5回执 → Task5/10；SELF声明与身份认证分开，历史回执不可删除
- 规格6事务/时间 → Task1/4/5/8/12；并发是真数据库多连接测试，H2不替代MySQL
- 规格7通知 → Task6/12；未知投递不盲重试、已投递不等于已读
- 规格8–9UI/API/历史 → Task7–10；源事件ID稳定，正文按当前授权重查
- 规格10迁移/恢复 → Task1/12/13；新增全库合成恢复，不把局部家庭备份当灾备
- 规格11–12验收/边界 → Task11–13；公开演示、应用实现、部署、临床验证分别陈述
- 五项Review Focus均落到具体测试任务；公共方法名、字段名与上下游消费者一致

待用户审阅本计划并选择执行方式。此时仅发布设计与计划文档；所有任务复选框保留未完成，不能把计划中命令或预期结果当作已执行证据。
