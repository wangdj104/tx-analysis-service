# Care Execution Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让个人、授权家属、医生和护理团队生成口径一致、权限受控的就诊准备材料及中英文照护执行报告。

**Architecture:** 从既有已发布计划、行动与追加事件建立一次性只读投影；正文使用独立一致性快照，交付前用新的权限读取检查撤销。HTML/PDF、事项CSV和事件CSV共用明确契约；原就诊摘要与各角色入口接入专用窄权限页面，不扩大既有临床授权。

**Tech Stack:** Java 8-compatible source on JDK 17, Spring Boot 2.5.14, JdbcTemplate/MyBatis-Plus, MySQL 8.0, JUnit 5/Mockito/H2, existing Jackson/OpenHTMLToPDF 1.0.10/PDFBox 2.0.27, Vue 3/Vite/Element Plus, Node 20, Playwright 1.58.2/Chromium.

**Spec:** [已批准规格](../specs/2026-10-04-care-execution-report-design.md)，不可变批准基线 `12a02a4d07d99c4c76445e243bb0749d38102e52`；文件SHA256 `8f4df780222b7e47ceb58f0b002500bfde58e3b18392ef9762c09424fbbc868f`。执行者先完整阅读规格与本计划。

## Global Constraints

- 当前集合只包含范围内 `workflow_version=1`、`lifecycle=ACTIVE` 的计划，其 `current_revision_id` 对应已发布版本的全部行动项
- `N = OPEN + NEEDS_HELP + SUBMITTED + CONFIRMED`；期间统计不用当前 N 作为分母
- 只按 `care_plan_event.recorded_at` 筛选期间活动；当前困难、待补充、待复核和当前就诊问题不受此期间限制
- 日期跨度为1至366个含首尾的日历日；省略日期时取所选时区今天及之前29个日历日；结束日不得晚于今天
- `timeZone` 必填；`language` 仅为 `en`、`zh-CN`；瞬时时间输出UTC ISO 8601，人类显示附所选时区和偏移
- 新接口为 POST `/care-plans/reports/preview` 和 POST `/care-plans/reports/export`，相对于 `/api`；只接受单个JSON正文，不接受查询参数或重复JSON键
- `format=html|pdf|actions_csv|events_csv`；preview返回 `Result<ReportDTO>`，export返回文件；READ权限足以读取和下载
- 当前事项最多1000条、期间公开事件最多5000条、当前问题最多200条；本次输出原文最多8 MiB UTF-8，最终文件最多32 MiB
- 服务端查询、渲染与最终授权总预算30秒；新报告请求客户端45秒；其他请求30秒默认值不变
- 正文使用独立只读REPEATABLE_READ；最终权限读取使用新的READ_COMMITTED/自动提交连接，不从旧快照判断撤销
- 受限证据不输出源ID、标题、值、链接；本期不读取证据正文或附件，原临床自由文本不自动翻译
- 无公开链接、服务器持久报告库、自动发送、报告恢复、真实患者测试或临床效果承诺
- `CARE_PLAN_ENABLED=false`保持默认；本计划不授权部署、生产开启或真实通知
- 所有 `src/...`、`frontend/...`、`pom.xml` 变更与测试在对应 `cn/` 镜像同任务完成；根 `scripts/`、`.github/`、规格和计划共享，不机械复制
- 不加入新的生产依赖，不升级运行栈；不重建已有MySQL/浏览器框架，不覆盖用户未提交修改
- 本计划待用户审阅并选择执行方式；批准规格不等于批准本计划或实现

## Review Focus

1. 默认期间跨当地午夜、DST和整日跳跃：请求期间固定，不能用24小时乘法或服务器默认时区偷换；Task 1/9测试
2. 管理员叠加家属、失效医生叠加有效窄授权、护理历史身份：仍不能经旧完整Patient读取或缓存角色得到问题/病史；Task 2/6/9测试
3. 完整预览超限但单类CSV未超限、零行CSV：较小下载仍可用，空文件不伪造元数据行；Task 4/6/8/9测试
4. PDF原文含emoji、组合字符、补充平面汉字，或样式触发外部资源：要完整显示或明确失败，不能只通过CJK检测；Task 5/9测试
5. 文件响应已到但用户已切换患者/账号、打印窗口等待中失权：旧Blob/DOM不能下载或显示，日志不包含响应或认证配置；Task 7/8/9测试

---

## 0 执行基线与顺序

实现基线为已批准规格提交 `12a02a4...`。本地已核对 `33e12ebc5972a088e37c08bf61e7857f4cc7ff33` 与其树一致；原运行验收提交是 `af06584b655b875ebfd16d0482a9712a99dc6cec`，不能将旧CI当作新报告验收。

先读取最新repo instructions、状态与远端，保留dirty work。用户既定发布目标是当前仓库main，不创建新的公开分支/PR、不force push或reset。执行隔离副本按所选执行方式建立，发布目标不变。每任务包含EN/CN、失败测试、实现、通过测试、独立检查及小提交；完整原生验收集中在可运行节点，不对每个文案提交重新搭建数据库。

实际路径与现有设施：

- 路由在 `frontend/src/main.js`，不存在 `frontend/src/router/index.js`
- 快速后端：`CarePlanTestFixture`、`CarePlanLifecycleTest`；真实HTTP链：`CarePlanApiTest`
- 原报告回归：`src/test/java/org/familyhealthcare/service/impl/HealthExportWorkflowTest.java`、`ReportPdfFontTest.java`和 `src/test/java/org/familyhealthcare/service/DownloadResponseTest.java`；现有字体fixtures及许可证在 `src/test/resources/fonts/`
- 原生MySQL入口：`scripts/verify-care-plan-mysql.sh`，运行 `CarePlanMysqlIntegrationTest`；`CarePlanMysqlSnapshotTest`仅是备份/迁移比较器测试，不是报告事务隔离证明
- 浏览器入口：`scripts/verify-care-plan-browser.mjs`，构建干净的已跟踪Vue源码并启动 `CarePlanBrowserApplication`；配置在 `frontend/playwright.config.mjs`，现有helpers在 `frontend/e2e/helpers.mjs`
- `.github/workflows/ci.yml` 已有EN/CN后端、EN/CN前端、static-demo及两个原生MySQL jobs；原生每语言两次独立临时库验证，第2次要求真实浏览器
- 原生安全条件只允许声明的GitHub临时MySQL服务、loopback端口、生成的非root测试用户和三个独立临时schema；缺少环境时不能伪造 `CI` 或改guard让本机/生产库通过

依赖顺序：Task 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10。Task 3接口和测试冻结后，Task 7可与4/5并行，因为它只依赖稳定HTTP/DTO契约且文件不同；其余默认顺序执行。变更公共接口时先暂停消费者并同步计划，不让两条实现各自发明字段。

## 1 文件与公共接口地图

Java新增文件放在 `src/main/java/org/familyhealthcare/service/careplan/`，便于复用现有包内UTC JDBC工具；不暴露 `CarePlanData` 为通用外部API。

| 文件 | 单一职责 |
| --- | --- |
| `CareExecutionReportContracts.java` | 严格请求解析、日期归一、格式枚举和固定限额 |
| `CareExecutionReport.java` | 不可变类型化报告DTO及嵌套值对象 |
| `CareExecutionReportBudget.java` | 单请求deadline、UTF-8/文件字节和行数预算 |
| `CareExecutionReportException.java` | 稳定报告错误及可选限额详情 |
| `CareExecutionReportAccess.java` | 当前CARE_PLAN门槛、窄字段问题权限、已纳入引用的最终复核 |
| `CareExecutionReportProjector.java` | 一次独立RR快照内的数据库事实投影 |
| `CareExecutionReportHtmlRenderer.java` | 两种语言的固定HTML模板与可见文本列表 |
| `CareExecutionReportCsvRenderer.java` | 两种固定行模型、BOM与公式防护 |
| `CareExecutionReportRenderer.java` | 格式调度和文件结果，不访问数据库 |
| `CareExecutionReportService.java` | 受限生成任务、授权次序和交付准备 |
| `src/main/java/org/familyhealthcare/util/ReportPdfRenderer.java` | 复用现有PDF/字体机制，区分旧报告兼容与新报告严格输出 |
| `src/main/java/org/familyhealthcare/controller/CareExecutionReportController.java` | 新HTTP边界，无临床写命令 |
| `frontend/src/api/careExecutionReport.js` | 捕获认证上下文的新只读API |
| `frontend/src/composables/useCareExecutionReport.js` | 请求世代、取消、预览及Blob生命周期 |
| `frontend/src/utils/careExecutionReport.js` | 日期/格式显示、两语标签和安全来源链接 |
| `frontend/src/components/care-plan/ExecutionReportPanel.vue` | 可复用准备表单与报告区域 |
| `frontend/src/views/CareExecutionReportView.vue` | 专用窄权限路由外壳 |

### 公共Java类型与签名

下列类型都由指定文件定义，不使用Java records或可变公开Map。DTO集合防御性复制为不可变集合；时间为 `Instant`，JSON序列化为UTC字符串。下方DTO表格的嵌套类型以 `CareExecutionReport.` 为前缀，Request等服务类型按各自声明归属。

- `CareExecutionReportContracts.Format`：`PREVIEW, HTML, PDF, ACTIONS_CSV, EVENTS_CSV`，PREVIEW仅内部使用
- `CareExecutionReportContracts.Request`：`long patientId; Long planId; LocalDate fromDate,toDate; ZoneId timeZone; String language; Instant rangeStartAt,rangeEndExclusiveAt; Format format`
- `CareExecutionReportContracts.parse(String json, boolean exporting, Instant now) -> Request`：strict重复键检测；exporting=false禁止format，true必须提供四个公开格式之一
- `CareExecutionReportBudget.start(Duration timeout) -> CareExecutionReportBudget`；同类实例方法 `void checkTime()`, `void requireRows(String kind,long count,long limit)`, `void addSourceText(String value)`, `output(OutputStream delegate) -> OutputStream`
- `CareExecutionReportAccess.inspect(long actorId, Request request) -> Access`；`recheck(long actorId, Request request, Manifest manifest) -> void`
- `CareExecutionReportProjector.project(long actorId, Request request, Access access, CareExecutionReportBudget budget) -> Projection`
- `CareExecutionReportHtmlRenderer.render(CareExecutionReport report, CareExecutionReportBudget budget) -> Html`，Html含 `String markup; List<String> visibleText`
- `CareExecutionReportCsvRenderer.render(CareExecutionReport report, Format format, CareExecutionReportBudget budget) -> byte[]`
- `ReportPdfRenderer.renderLegacy(String html,String fontPath) -> byte[]`；`renderCareReport(String html,List<String> visibleText,String fontPath,CareExecutionReportBudget budget) -> byte[]`
- `CareExecutionReportRenderer.render(CareExecutionReport report, Format format, CareExecutionReportBudget budget) -> Export`，Export含 `byte[] content; String contentType,fileName`
- `CareExecutionReportService.preview(long actorId, Request request) -> PreparedPreview`；`export(long actorId, Request request) -> Export`

`Access`、`Manifest`、`EvidenceKey`为 `CareExecutionReportAccess` 中的服务端嵌套值类型：Access含 `boolean questionsAllowed`；Manifest含 `boolean questionsIncluded; Set<EvidenceKey> readableEvidence`；EvidenceKey含 `String sourceType; long sourceId`。`Projection`由 `CareExecutionReportProjector` 定义，含 `CareExecutionReport report; Manifest manifest`。它们不作为JSON或文件输出。`Html`为HtmlRenderer嵌套类型，`Export`为Renderer嵌套类型。`PreparedPreview`为Service嵌套类型，含 `CareExecutionReport report; byte[] json`，json已经按Result包装完成序列化并通过预算及最终授权检查；它不增加wire字段。

### DTO字段冻结

| 值对象 | 字段与类型 |
| --- | --- |
| 顶层 | `int reportSchemaVersion=1; Patient patient; Scope scope; Metadata metadata; CurrentSummary currentSummary; List<CurrentAction> currentActions,currentAttention; ActivitySummary activitySummary; List<PeriodEvent> periodEvents; String questionsAvailability; List<Question> questions; String completeness=COMPLETE` |
| Patient / Scope | `long id; String displayName` / `Long planId; String label`（ALL_PLANS或SINGLE_PLAN） |
| Metadata | Request的日期/时区/语言与UTC边界，`Instant currentAsOf,generatedAt`；时区wire值为IANA字符串 |
| CurrentSummary | `long total,open,needsHelp,submitted,confirmed,overdue,needsSupplement`；`total=open+needsHelp+submitted+confirmed`，overdue/needsSupplement为子集 |
| ActivitySummary | `long eventCount,distinctActionCount; Map<String,Long> eventTypeCounts`，映射键为固定事件码，不能含自由文本 |
| CurrentAction | `long planId,revisionId,actionId,assignedUserId; int revisionNo; String planTitle,instructions,instruction,status; Instant dueAt,reviewWaitingSince; boolean overdue,needsSupplement,assigneeAvailable; EventSummary latestReceipt,latestReturn,latestReview,latestHelp,latestFollowUp; List<Evidence> evidence` |
| EventSummary | `long eventId,actorId; String eventType,actorName,actorRole,actorRelation,entryMode,note,followUpKind; Instant recordedAt,occurredAt; List<Evidence> evidence`；不存在的字段为空 |
| PeriodEvent | EventSummary字段加 `long planId,revisionId; Long actionId; int revisionNo; String planTitle,instructions,actionInstruction,actionStatusAfterEvent,planLifecycleAtGeneration; boolean revisionIsCurrentAtGeneration` |
| Evidence | `boolean restricted`；受限时只有此字段，标题/类型/ID/链接均不序列化；可读时加 `String sourceType,title,detailPath; Long sourceId` |
| Question | `long id; String title,status,description,answer,followUp,actorName; Long actorId; String createdAtLocal,updatedAtLocal,eventAtLocal,timeBasis=LEGACY_UNZONED`；可空旧时间保持原文格式 |

字段为未来实现的固定契约，不表示它们已存在。JSON仅preview包含全部区域；CSV的投影按格式只读取所需区域，未读取部分不伪造完整计数且不被Renderer访问。`questionsAvailability`仅为 `AVAILABLE, NOT_AUTHORIZED, NOT_INCLUDED_IN_PLAN_SCOPE`；CSV无需输出它。

### Task 1: 固定请求、DTO与预算契约

**Files:** Create上述Contracts、Report、Budget、Exception四个Java文件；Create `src/test/java/org/familyhealthcare/service/CareExecutionReportContractsTest.java`、`CareExecutionReportTestData.java`；保留EN/CN镜像。

**Interfaces:** 产出上一节的Request/Format/DTO/Budget。测试数据类提供 `exampleReport(String language)`，含2个OPEN、1个NEEDS_HELP、2个SUBMITTED、1个CONFIRMED及原文/代录示例，仅合成数据。

- [ ] **Step 1 写失败测试**：命名 `defaultThirtyLocalDates`, `dstHalfOpenRange`, `rejectDuplicateUnknownAndPartialFields`, `defensiveDtoAndExactBudgets`，断言：

```java
assertEquals(LocalDate.of(2026,9,5), request.getFromDate()); // now=2026-10-04T12:00:00Z, UTC
assertEquals(23, Duration.between(springStart, springEnd).toHours()); // America/New_York, 2026-03-08
assertEquals(25, Duration.between(fallStart, fallEnd).toHours()); // America/New_York, 2025-11-02
assertEquals(6L, CareExecutionReportTestData.exampleReport("en").getCurrentSummary().getTotal());
assertThrows(UnsupportedOperationException.class, () -> report.getCurrentActions().clear());
```

补充366天接受/367天拒绝、日期未成对、未来日、IANA Asia/Shanghai及Pacific/Apia日界、数字ID小数/溢出、重复JSON patientId、预览夹带format、1000/1001行、8 MiB/32 MiB临界与多字节中文。示例时间变量由parse结果UTC边界取得，不手写与函数无关的值。

- [ ] **Step 2 运行确认失败**：`mvn -B -DskipTests=false -Dtest=CareExecutionReportContractsTest test`；预期缺少目标类型或目标行为断言失败，不能因工具/依赖错误算RED。
- [ ] **Step 3 实现最小契约**：严格Jackson parser只用于新请求；IANA可用ID核验加UTC，显式拒绝CST/EST/PST等含糊缩写和裸offset；用LocalDate.atStartOfDay(ZoneId)归一；单调时钟计deadline，溢出安全的long预算；DTO不带完整Patient实体。固定错误码/限额kind与规格一致。
- [ ] **Step 4 运行通过**：根与 `cn` 分别执行同一命令；所有新边界断言通过，无时区依赖。
- [ ] **Step 5 提交并检查**：只暂存本任务文件及镜像，`git diff --cached --check`；提交 `feat(care): define execution report contracts`，确认diff没有产品外改动。

### Task 2: 实现当前权限与最小字段问题门槛

**Files:** Create `CareExecutionReportAccess.java`；Create `src/test/java/org/familyhealthcare/service/CareExecutionReportAccessTest.java`；复用 `CarePlanAuthorizationService`，不改它的现有规则。

**Interfaces:** 消费Request；产出Access、Manifest、EvidenceKey值类型及inspect/recheck。每次inspect/recheck在独立READ_COMMITTED事务执行，不接受调用者的旧RR快照。

- [ ] **Step 1 写失败测试**：使用CarePlanTestFixture，命名 `readGrantIsEnough`, `narrowNurseNeverLoadsFullPatient`, `staleDoctorOrAdminCannotReadQuestions`, `revocationChangesFinalDecision`：

```java
assertFalse(access.inspect(NURSE, request).isQuestionsAllowed());
assertEquals("ACCESS_DENIED", deniedMissingAssignment.getErrorCode());
assertEquals("REPORT_ACCESS_CHANGED", revokedEvidence.getErrorCode());
assertFalse(sqlStatements.stream().anyMatch(sql -> sql.matches("(?is).*select\\s+\\*.*") || sql.toLowerCase(Locale.ROOT).contains("medical_history")));
```

另测owner、有效医生、READ家属、护理历史身份、家属+admin、失效doctor+有效CARE_PLAN-only、显式撤销覆盖旧membership、多个有效grant、过期、错患者planId、草稿ID统一403；没有问题权时不查询care_item正文或COUNT。

- [ ] **Step 2 运行确认失败**：`mvn -B -DskipTests=false -Dtest=CareExecutionReportAccessTest,CarePlanAuthorizationTest test`。
- [ ] **Step 3 实现**：基础权只调用中央requireRead；计划验证只取归属/workflow/发布元数据；问题资格只查必要标量与当前角色/分配/授权，复制原完整读取及显式授权优先语义，禁止调用会加载完整Patient的DataScopeHelper方法。recheck基础权丢失403、已纳入问题或可读证据失效409，新授权不回填旧DTO。
- [ ] **Step 4 运行通过**：同命令根/CN；用JDBC代理记录SQL列名/表名证明窄读取，不记录值或凭据。
- [ ] **Step 5 提交并检查**：限定本任务及镜像，提交 `feat(care): authorize execution report scopes`；核对未放宽原护理/证据权限。

### Task 3: 建立一次性一致快照投影

**Files:** Create `CareExecutionReportProjector.java`；Create `src/test/java/org/familyhealthcare/service/CareExecutionReportProjectionTest.java`；扩展 `CareExecutionReportTestData.java` 的合成数据库种子方法 `seedReportScenario(JdbcTemplate jdbc, Instant now)`，返回计划/事项/事件ID的测试映射。

**Interfaces:** 消费Request、Access、Budget；产出Projection。project显式开启 `PROPAGATION_REQUIRES_NEW + ISOLATION_REPEATABLE_READ + readOnly`，校验连接实际设置；首次表读取连同DB UTC时间确定currentAsOf。无写状态机调用。

- [ ] **Step 1 写失败测试**：`currentDenominatorAndReviewWait`, `periodUsesRecordedTimeAndOriginalRevision`, `questionsAndEvidenceAreSeparateScopes`, `csvLoadsOnlyItsProfile`, `incompleteRelationsFailClosed`：

```java
assertEquals(6L, report.getCurrentSummary().getTotal());
assertEquals(2L, report.getCurrentSummary().getSubmitted());
assertFalse(submitted.isOverdue());
assertTrue(report.getCurrentAttention().stream().anyMatch(a -> a.getActionId()==olderHelpId));
assertEquals(oldRevisionId, historicalReceipt.getRevisionId());
assertNull(restrictedEvidence.getSourceId());
```

覆盖计划关闭/取消/新版不复制旧完成、退回OPEN待补充、再次提交等待起点、actual-time与recorded-time分离、重复事件计数、未知状态/缺失版本、单计划问题NOT_INCLUDED、无权与零问题不同、字段不可变；SQL监听证明actions_csv不加载期间全表、events_csv不加载当前完整事项/问题。

- [ ] **Step 2 运行确认失败**：`mvn -B -DskipTests=false -Dtest=CareExecutionReportProjectionTest test`。
- [ ] **Step 3 实现**：有限列、参数化批量查询；当前所有行动与每类最近事件摘要分别批量取，禁止逐事项调用detail加载完整历史。period依据公开事件白名单和recorded_at半开区间、原revision快照；用limit+1检测，不先加载无限列表。原文预算累加实际纳入文本，授权证据最小化；Manifest只记录真正输出的可读引用。当前N和明细同一快照，pending子集不受期间筛选。
- [ ] **Step 4 运行通过**：根/CN同命令加 `CarePlanTimelineTest,CarePlanReceiptTest`；检查现有patient/time、plan/action索引查询计划。原生并发证明留到Task 9，H2通过不标成原生通过。
- [ ] **Step 5 提交并检查**：提交 `feat(care): project consistent execution report snapshots`；无新业务表/临床写入。确需索引变更时先报告证据并补充版本化迁移审批，不能运行时DDL。

### Task 4: 实现HTML与两类CSV

**Files:** Create `CareExecutionReportHtmlRenderer.java`、`CareExecutionReportCsvRenderer.java`；Create `src/test/java/org/familyhealthcare/service/CareExecutionReportRenderTest.java`；Create `src/test/resources/reports/care-execution-actions-en.csv` 与 `care-execution-events-zh-CN.csv` 合成golden文件。

**Interfaces:** 消费Task 1 DTO；产出Html(markup,visibleText)及CSV字节。Renderer不查询数据库，不依赖线程LocaleContextHolder；仅使用request.language。

- [ ] **Step 1 写失败测试**：`htmlEscapesEveryDynamicField`, `csvPreservesTwoRowModels`, `dangerousTextIsLiteral`, `emptyCsvHasHeaderOnly`, `localeNeverTranslatesClinicalNotes`：

```java
assertFalse(html.getMarkup().contains("<script>alert"));
assertTrue(html.getMarkup().contains("&lt;script&gt;"));
assertEquals(expectedGoldenCsv, new String(csv, StandardCharsets.UTF_8));
assertTrue(new String(csv, StandardCharsets.UTF_8).startsWith("\uFEFF"));
assertEquals(originalNote, report.getPeriodEvents().get(0).getNote());
```

golden含逗号、双引号、CR/LF、前导空白后的=+−@（ASCII减号另测）、TAB、控制字符、中文原文和安全数字；不能只断言contains逗号。空CSV无虚构行，元数据例外与规格一致；非空每行包含生成/范围/稳定码。样例文件不含真实人名或凭据。

- [ ] **Step 2 运行确认失败**：`mvn -B -DskipTests=false -Dtest=CareExecutionReportRenderTest test`。
- [ ] **Step 3 实现**：固定词典、模板次序与字段列序；所有动态HTML转义；CSV文本危险开头统一前置单引号作为显式文本保护并做RFC双引号转义，数值列以类型输出；原DTO不改写。只提供已验证的应用内相对详情路径，受限Evidence对象只输出restricted。
- [ ] **Step 4 运行通过**：根/CN同命令；测试生成合成CSV至 `target/care-execution-report-qa/`，用 `python3 -c 'import csv,sys; print(len(list(csv.reader(open(sys.argv[1],encoding="utf-8-sig",newline="")))))' <csv-path>` 实际解析行数，区分字段内换行；该命令只打印行数。
- [ ] **Step 5 提交并检查**：提交 `feat(care): render localized execution HTML and CSV`；检查系统标签双语、原文不翻译、受限字段及样例数据安全。

### Task 5: 复用PDF并验证完整文本与像素

**Files:** Create `src/main/java/org/familyhealthcare/util/ReportPdfRenderer.java`；Modify `src/main/java/org/familyhealthcare/util/PdfFontSupport.java`、`src/main/java/org/familyhealthcare/service/impl/HealthReportServiceImpl.java` 的renderPdf/字体小段；Create `src/test/java/org/familyhealthcare/service/CareExecutionReportPdfTest.java`；保留/扩展 `src/test/java/org/familyhealthcare/service/impl/ReportPdfFontTest.java` 与字体fixtures README/许可证。

**Interfaces:** 消费Html及Budget，产出renderCareReport/renderLegacy。旧HealthReportServiceImpl的私有renderPdf保留兼容委托，原测试和原图表数据URL行为不变；新严格方法禁外部/任意本地资源。

- [ ] **Step 1 写失败测试**：`bothLanguagesRenderAndExtractAllText`, `missingNonCjkGlyphFailsWithoutSilentLoss`, `networkAndFileResourcesAreDenied`, `multiPageRowsStayReadable`：

```java
assertEquals("%PDF", new String(pdf,0,4,StandardCharsets.US_ASCII));
assertTrue(new PDFTextStripper().getText(document).contains("患者测试"));
assertEquals("REPORT_RENDER_UNAVAILABLE", unsupportedEmoji.getErrorCode());
assertEquals(0, externalResourceRequests.get());
assertTrue(new PDFRenderer(document).renderImage(0).getWidth()>100);
```

覆盖英文标签+中文原文、组合字符/emoji/补充汉字、缺字体、CFF不兼容与后备TTF、长段落跨页、表头和分页；使用现有子集字体证明支持/不支持两条路径。若补充合成测试字形，保留来源/许可证，不从未知站下载字体。

- [ ] **Step 2 运行确认失败**：`mvn -B -DskipTests=false -Dtest=CareExecutionReportPdfTest,ReportPdfFontTest,HealthExportWorkflowTest test`。
- [ ] **Step 3 实现**：提取既有字体选择/渲染，给新报告验证全部visibleText可呈现性（格式控制符与可见字形区分）；不支持时明确503和HTML建议。新渲染分支拒绝网络/file等资源、限制输出字节，并检查预算。不得让旧报告客户端图表图片被新禁令意外删除。
- [ ] **Step 4 运行通过并查看像素**：根/CN同命令，PDFBox为合成EN/CN长报告每页渲染PNG到 `target/care-execution-report-qa/{en,zh-CN}/`；逐页实际查看，确认无缺字、重叠、裁切、空白溢页。文本抽取或图片宽度断言不能替代这一步。
- [ ] **Step 5 提交并检查**：提交 `feat(care): produce safe readable execution PDFs`；只提交源/必要授权字体fixture，不提交target产物。

### Task 6: 串联限时生成、HTTP与最终授权

**Files:** Create `CareExecutionReportRenderer.java`、`CareExecutionReportService.java`、`src/main/java/org/familyhealthcare/controller/CareExecutionReportController.java`；Modify `src/main/java/org/familyhealthcare/controller/CarePlanExceptionAdvice.java`、`src/main/java/org/familyhealthcare/interceptor/AuditLogInterceptor.java`；Create `src/test/java/org/familyhealthcare/service/CareExecutionReportServiceTest.java`、`CareExecutionReportApiTest.java`；从现有CarePlanApiTest抽取最小公共测试上下文至 `src/test/java/org/familyhealthcare/service/CarePlanWebTestSupport.java`，原测试继续使用同一链。

**Interfaces:** 消费Task 1–5签名；Controller方法 `preview(HttpServletRequest request) -> ResponseEntity<byte[]>`（application/json，字节内容仍为Result<ReportDTO>包装）、`export(HttpServletRequest request) -> ResponseEntity<?>`。经session取actor，不接收actor字段；仅新请求读取有界JSON正文并parse。

- [ ] **Step 1 写失败测试**：`freshAuthAfterRenderDiscardsBytes`, `boundedTimeoutAndNoPartialResponse`, `reportHttpAndErrorEnvelope`, `featureOffReadsNoClinicalTables`, `readPostDoesNotMutateCareState`：

```java
mvc.perform(reportPreviewWithReadGrant).andExpect(status().isOk()).andExpect(jsonPath("$.data.reportSchemaVersion").value(1));
mvc.perform(tooManyEvents).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.data.limitKind").value("PERIOD_EVENTS")).andExpect(jsonPath("$.data.limit").value(5000));
assertFalse(deniedResponse.getContentAsString().contains("Synthetic private note"));
assertTrue(deniedResponse.getContentType().contains("application/json")); // 只有错误包装，无PDF/CSV片段
assertEquals(beforeCareEvents, afterCareEvents);
```

另测重复JSON、非对象body、查询参数、输出MIME/安全文件名/no-store/nosniff、blob失败JSON、丢失基础权403与证据变化409、完整预览失败但actions_csv成功、可选问题不查询、未知错误无输入回显、既有CarePlan错误仅errorCode不变。用拦截器/日志捕获器断言无令牌、临床note或响应体。

- [ ] **Step 2 运行确认失败**：`mvn -B -DskipTests=false -Dtest=CareExecutionReportServiceTest,CareExecutionReportApiTest,CarePlanApiTest,DownloadResponseTest test`。
- [ ] **Step 3 实现编排与边界**：生成过程为fresh inspect → RR project → fresh recheck → render → fresh recheck →返回完整buffer。preview使用应用ObjectMapper将 `Result.ok(report)` 序列化到同一个有界内存预算，再最终recheck，返回PreparedPreview；控制器仅发送准备好的JSON字节，不能让MVC在最终授权之后才无界序列化DTO。使用每实例2 worker、最多2排队的有界执行器，禁止CallerRunsPolicy/无界队列；任务总deadline从服务入口开始，排队也计入30秒，超时取消且不交付。JDBC查询使用剩余秒数超时、循环/输出流协作检查；拒绝队列过载返回REPORT_RENDER_UNAVAILABLE。取消不能声称强制终止库内部计算；不再接受无限新工作，销毁时关闭执行器。

新controller常驻并通过ObjectProvider取得按care-plan.enabled条件注册的服务，沿用现有开关先于正文解析的门槛；关闭时不构建执行器或查询临床表。控制器正文最大4096 UTF-8字节（合法字段远小于此，过量/不可解码输入400，用REPORT模式的UTF-8 decoder拒绝错误字节），不让完整输入先无限读入内存；严格JSON配置只对报告生效；解析/渲染异常只映射固定安全错误码与文案，不回显异常中的JSON片段或临床文本。422添加报告专属错误详情分支，其他错误结构不动。AuditLog将两条只读POST标为VIEW/EXPORT且只记技术元数据；允许原有访问审计，不写计划、command、notification表。已提交错误响应不可包含先前生成的部分文件。

- [ ] **Step 4 运行通过**：根/CN同命令；用可控渲染替身和latch证明授权撤销后未写文件头/字节、30秒预算不被重置；测试执行器满载及取消后没有无限队列。真实MySQL撤销证明由Task 9完成。
- [ ] **Step 5 提交并检查**：提交 `feat(care): expose bounded authorized report endpoints`；检查全部handler在默认关闭时不读取新表、不绕过现有permission/JWT链。

### Task 7: 建立可取消的前端报告客户端

**Files:** Create `frontend/src/api/careExecutionReport.js`、`frontend/src/composables/useCareExecutionReport.js`、`frontend/src/utils/careExecutionReport.js`；Modify `frontend/src/utils/request.js` 的错误日志及新报告专用二进制返回分支；Create `frontend/tests/careExecutionReportClient.test.mjs`、`careExecutionReportDisplay.test.mjs`；复用carePlanClient和healthReportContext测试模式。

**Interfaces:** API `previewExecutionReport(body,options)` 返回Result包装，`exportExecutionReport(body,options)` 返回 `{blob:Blob,fileName:String}`；options含expectedAuth/signal，timeout固定45000。export设置新报告专用 `returnExportResponse:true`，request.js只对该标记返回blob及Content-Disposition/Content-Type两个白名单响应头，由API解析安全文件名；其他调用仍返回原Blob。Composable `useCareExecutionReport(contextRef)` 返回 `state, preview(), download(format), clear(), dispose()`；context含patientId/planId/fromDate/toDate/timeZone/language。state含phase/report/error；download内部在当前context/session中创建URL并下载，返回 `{status:"downloaded"|"stale"|"failed"}`，不把旧Blob交给调用页面继续处理。

- [ ] **Step 1 写失败测试**：`lateAResponseCannotReplaceNewA`, `oldAccountBlobNeverDownloads`, `formatAndContextChangesInvalidatePreview`, `blobErrorKeepsLimitMetadata`, `noPayloadInConsole`：

```javascript
assert.equal(config.timeout, 45000)
assert.deepEqual(config.expectedAuth, capturedAuth)
assert.equal(client.state.report, null) // A→B→A 后旧A返回
assert.equal(downloadClicks.length, 0) // 退出/换账号后旧文件返回
assert.equal(activeObjectUrls.size, 0) // clear/dispose后
assert.equal(JSON.stringify(consoleCalls).includes('Synthetic private note'), false)
```

另测精确POST路径/body、JSON错误Blob的422/409、双击只一请求、Abort静默、timeZone/language切换、旧finally不释放新请求busy、400/403/404清理敏感上下文。显示函数用稳定状态码映射两语，旧QUESTION时间不追加伪UTC。

- [ ] **Step 2 运行确认失败**：`cd frontend && node --test tests/careExecutionReportClient.test.mjs tests/careExecutionReportDisplay.test.mjs`。
- [ ] **Step 3 实现**：沿用captureAuthSession及adapter dispatch前会话核验，另以单调epoch验证patient/参数；AbortController仅作资源优化，不代替结果归属。所有Blob URL最终revoke，文件名仅接受固定报告前缀、语言、生成时间及允许扩展名，拒绝控制符/路径；异常响应头用固定安全名回退。将request.js现有两处console.error(error对象)改为固定事件名和非敏感status/errorCode，不打印headers/config/body；网络/认证行为不改，旧请求timeout不改。
- [ ] **Step 4 运行通过**：EN/CN同命令，加 `tests/carePlanClient.test.mjs tests/carePlanDispatch.test.mjs tests/healthReportContext.test.mjs`；完整预览错误不会禁止直接调用较小CSV。
- [ ] **Step 5 提交并检查**：提交 `feat(ui): isolate execution report request contexts`；不得使用localStorage保存报告或将正文写URL。

### Task 8: 接入个人家庭、医生护理及旧报告入口

**Files:** Create `frontend/src/views/CareExecutionReportView.vue`、`frontend/src/components/care-plan/ExecutionReportPanel.vue`；Modify `frontend/src/main.js`、`frontend/src/utils/workspaceAccess.js`、`frontend/src/utils/workspaceNavigation.js`、`frontend/src/components/care-plan/PlanTaskList.vue`、`frontend/src/components/care-plan/PlanDetail.vue`、`frontend/src/views/DoctorWorkspace.vue`、`FamilyHealthManager.vue`、`HealthReportManager.vue`、`DataExportManager.vue`；仅在必要位置修改 `frontend/src/views/CareCenter.vue`、`frontend/src/views/NurseWorkspace.vue` 容器；Create `frontend/tests/careExecutionReportUi.test.mjs`，扩展 `carePlanRouteGuards.test.mjs`、`healthReportContext.test.mjs`。

**Interfaces:** 专用路由 `/care-plans/reports`，query仅允许patientId和可选planId正整数；不允许临床正文或任意redirect。ExecutionReportPanel props为 `patientId:Number, planId:Number|null`，内部管理日期/时区/language；结果展示只消费DTO，绝不自行重算临床状态。

- [ ] **Step 1 写失败测试**：`careOnlyRouteDoesNotOpenBroadMenus`, `currentAndPeriodLabelsStaySeparate`, `freshPrintCannotUseOldDom`, `allEntryPointsReachCorrectScope`：

```javascript
assert.equal(canAccessWorkspace('/care-plans/reports?patientId=1', [], ['nurse']), true)
assert.equal(canAccessWorkspace('/health-report', [], ['nurse']), false)
assert.equal(reportRows.submitted.some(row => row.patientOverdue), false)
assert.equal(oldSummaryPrintedAfterRevocation, false)
assert.equal(csvButtonDisabledOnlyBecauseFullPreviewLimit, false)
```

同时测明确患者身份/期间/时区、无当前N、NOT_AUTHORIZED不是0、旧问题答复标签、单计划标题、正常空CSV提示、屏幕阅读错误区、44px控件、一次打印重新请求两源且患者一致。

- [ ] **Step 2 运行确认失败**：`cd frontend && node --test tests/careExecutionReportUi.test.mjs tests/carePlanRouteGuards.test.mjs tests/healthReportContext.test.mjs`。
- [ ] **Step 3 实现**：新页按当前/期间两个区域展示，source链接仅白名单应用路由；默认浏览器IANA时区和当前语言，可显式改变。护理/个人从PlanTaskList与PlanDetail进入，医生从选中患者入口进入；菜单资格不授予临床范围。旧HealthReport/DataExport新增选项进入同一报告组件，不调用旧全档案加载路径。FamilyHealth摘要显示新区域，打印前重取旧摘要+新projection；所有响应所属上下文匹配才打开打印视图；若显示server HTML仅用无脚本sandbox。清除旧DOM、Blob和旧授权内容，说明每次下载重新生成。
- [ ] **Step 4 运行通过**：EN/CN上述命令及 `npm test && npm run build`；构建无未定义字典/路由，旧页面和菜单测试不弱化。
- [ ] **Step 5 提交并检查**：提交 `feat(ui): integrate care execution visit preparation`；检查没有新公开链接、自动发送或更改用户原临床状态机。

### Task 9: 扩展既有原生与真实浏览器验收

**Files:** Modify `src/test/java/org/familyhealthcare/service/CarePlanMysqlIntegrationTest.java`；Create `src/test/java/org/familyhealthcare/service/CareExecutionReportMysqlAssertions.java`；Create `frontend/e2e/careExecutionReport.spec.mjs`、`frontend/e2e/carePlanSessions.mjs`（从旧spec提取一次角色session fixture）；Modify `frontend/e2e/carePlanCollaboration.spec.mjs`、`frontend/e2e/helpers.mjs`、`src/test/resources/sql/care-plan-e2e-fixture.sql`（合成QUESTION与分离报告患者）；Modify `scripts/verify-care-plan-browser.mjs`、`scripts/tests/care-plan-browser-guards.test.mjs`，仅必要时修改既有artifact packager及其测试；Modify `.github/workflows/ci.yml` 为PDF验收提供官方字体资源及报告证据处理，保留全部原生安全guard。两语Java/E2E镜像。

**Interfaces:** 新原生帮助类 `CareExecutionReportMysqlAssertions.verify(DataSource source) -> void` 由现有MySQL集成测试在迁移/角色fixture准备后、最终恢复比较前调用；它创建独立合成患者范围并清理或纳入既有完整快照，不能改坏原有种子。扩展该测试的既有NativeServices Spring上下文注册报告beans，并复用proxy datasource/连接标识/latch；报告当前请求与种子使用测试运行时的数据库UTC基准，不依赖硬编码发布日期。浏览器沿用现有ids/login/api/helpers、实际Spring应用、built Vue与base-path helper，不建另一启动器。

- [ ] **Step 1 添加会失败的验收**：原生测试命名 `consistentSnapshotAcrossConcurrentRevision`, `freshPermissionAfterSnapshotRevocation`, `reportQueriesStayBounded`；两个独立真实MySQL连接/latch暂停投影中段，另一连接提交回执、修订、关闭或撤销。断言明细/计数只来自一个版本，最终recheck看到已提交撤销，任何失败不输出原文。不得把备份比较器测试或H2测试标成此项通过。

浏览器新增个人/家属/医生/护理的桌面与390px实际操作：生成中文/英文报告；下载HTML/PDF/两类CSV并检查非空、MIME、内容和状态；先提交再跨期限仍待医生；只CARE_PLAN家属/护理不能看到问题和原证据；以真实权限变更验证旧预览/下载拒绝；A→B→A、更改语言/时区、登录退出、关闭后晚到结果、Back/Forward、打印焦点与重复点击。API辅助用于准备合成状态和授权变化，不代替实际UI按钮验收，不mock核心成功响应。

- [ ] **Step 2 先确认RED与安全guard**：本地跑新增快速测试及 `bash -n scripts/verify-care-plan-mysql.sh`、`node --test scripts/tests/care-plan-mysql-guards.test.mjs scripts/tests/care-plan-browser-guards.test.mjs scripts/tests/care-plan-browser-artifacts.test.mjs`；真正原生测试只经既有CI job中 `bash scripts/verify-care-plan-mysql.sh`。若本地无被guard认可的临时服务，记录未运行并等待CI，不能把skip当pass。
- [ ] **Step 3 做最小验收链增量**：现有wrapper运行 `mvn -B -Dtest=CarePlanMysqlIntegrationTest -DcarePlanMysqlRequired=true -DskipTests=false test` 已覆盖新assertions，无需第二runner。Playwright新spec自然被现有testDir收集；六角色session fixture从旧spec提取一次供两者使用，禁止复制第二套登录/退出逻辑。现有api() helper强制JSON解析，新增有界二进制download assertion helper，不能让PDF走response.json()。修正必要的合成种子，维持退出/回到本人后仍有OPEN任务可用于A→B→A，不依赖旧测试关闭的任务。

下载的PDF/CSV必须在测试内实际读取/解析或渲染，再删除原下载或放进不上传的临时目录；现有test-results严格允许 `.webm/.mp4/.png/acceptance-evidence.json`，不能简单放宽到所有JSON/附件或上传认证trace。生成PDF截图放允许PNG区域，保留两语CSV解析结果的非敏感断言。若需提供CJK全字形字体，只从官方发行版package安装并配置 `REPORT_PDF_FONT_PATH`，保留现有缺字体负测。

- [ ] **Step 4 验证实际结果和像素**：两个语言原生job均两次临时库pass，第2次browser必需；下载角色录屏/截图和Task 5全部PDF页逐页查看，报告检查范围。保留真实MP4/webm并用现有ffprobe完整解码检查，PNG不能冒充录屏；保持旧主场景标题及PRIMARY_SCENARIO_DIRECTORY契约；若改媒体分组，连同packager测试显式更新。packager单组payload上限30 MiB，预留低于32 MiB下载限制的容器开销，不使用一份超大包；不要求无关流程重新录制。CSV用UTF-8-sig标准解析器核对行列/公式转义/字段内换行，零行只有表头；PDF抽取文本与截图均检查。队列容量/字节边界由自动化精确断言，不用大量UI页面代替。
- [ ] **Step 5 提交并检查**：提交 `test(care): verify execution reports on native workflows`；原来7个CI jobs不能被缩成只跑报告单测，原生安全、全库恢复和旧10个浏览器场景仍保留。

### Task 10: 全量回归、文档和最终提交验收

**Files:** Update `README.md`、`cn/README.md`、`docs/USER_GUIDE.md`、`cn/docs/USER_GUIDE.md`；Create `docs/CARE_EXECUTION_REPORT_RELEASE.md`、`cn/docs/CARE_EXECUTION_REPORT_RELEASE.md`、`docs/verification/care-execution-report.json`。若默认开关或备份警告无需变化，不为本里程碑改它们。

**Interfaces:** 交付软件行为、限定的验收证据和运行提交身份；机器清单仅含提交/树SHA、命令或CI链接、测试结果、合成媒体hash及检查范围，不含令牌、URL query凭据、数据库密码、真实健康数据或raw trace。

- [ ] **Step 1 建立最终验收清单**：按下表逐项标记实际测试/截图/视频来源，先保留未运行状态；未通过的项不能写完成。核查默认关闭、权限未扩大、当前/期间和导出不能恢复等用户说明。
- [ ] **Step 2 执行完整本地回归**：仓库根 `make test && make build`；再 `cd cn && mvn -B -DskipTests=false test && mvn -B -DskipTests package`；`cd cn/frontend && npm ci && npm test && npm run build`。同时执行 `node --check demo/app.js && node --check cn/demo/app.js`、`node --test demo/tests/*.test.mjs cn/demo/tests/*.test.mjs scripts/tests/*.test.mjs`。根make build的npm ci按锁文件，不更新依赖锁以掩盖失败。区分本地原生skip与CI必需pass。
- [ ] **Step 3 修复并整体检查**：任何修复先写失败用例，再运行相关测试和被影响的全量门槛。独立最终检查规格覆盖、权限/并发/字节泄露和双语实际页面；不以测试总数代替覆盖说明。
- [ ] **Step 4 提交发布与跟踪**：按既定main流程发布最终运行提交，核对远端确切SHA；跟踪该SHA的所有必需CI jobs直到全部终态。失败要读取对应job日志、修复授权范围内问题、重新取得新SHA并重验；禁止引用较早绿CI或未运行的browser替代最终结果。不force push、部署或打开开关。
- [ ] **Step 5 冻结可核对证据**：记录最终runtime SHA/tree、各EN/CN结果、实际PDF页/CSV/浏览器检查、默认关闭与生产边界，再做docs-only证据提交。若文档晚于运行CI，清楚区分两者并逐文件验证运行树未变；将不可变报告链接、最终CI链接、已验证与未验证范围交付用户。功能完成不等于临床效果或整个健康平台完成。

## 2 规格22项验收到任务的映射

| 规格项 | 主任务与证据 |
| --- | --- |
| 1 当前分母、零项 | 1/3/8；contracts/projection/UI断言 |
| 2 自报代录及跟进身份 | 3/4/9；事件字段与四角色实际流程 |
| 3 待医生不归患者逾期 | 3/8/9；等待起点及跨期限样例 |
| 4 当前待处理不受期间隐藏 | 3/8/9；旧HELP/OPEN问题样例 |
| 5 修订、取消、关闭历史 | 3/9；原版本及并发原生查询 |
| 6 事件/事项数量区分 | 1/3/4；重复提交与计划级事件 |
| 7 草稿/旧内部计划不导出 | 2/3/6/9；每格式权限断言 |
| 8 日期与366天 | 1/9；DST/跨日/补录/边界 |
| 9 旧问题时间与混合语言 | 1/4/5/8；字形及LEGACY_UNZONED |
| 10 MySQL一致性 | 3/9；双连接竞争而非H2替代 |
| 11 新鲜权限连接 | 2/6/9；RR外撤销与实际隔离级别 |
| 12 六类身份交叉权限 | 2/6/9；真实JWT/菜单链与角色UI |
| 13 READ、护理及CARE_PLAN-only | 2/6/8/9；窄列SQL和无正文检查 |
| 14 证据及交付前撤销 | 2/3/6/9；最终check丢弃buffer |
| 15 问题可用性与非医生证明 | 2/3/4/8/9；状态及标签 |
| 16 异常/容量/无部分文件 | 1/3/5/6/9；N+1、字节、字体、deadline |
| 17 注入/资源访问 | 4/5/6/9；golden CSV及资源trap |
| 18 元数据与原文 | 1/4/5/9；非空字段、空CSV例外 |
| 19 A→B→A等时序 | 7/8/9；客户端deferred与真实浏览器 |
| 20 双语、390px、像素 | 5/8/9；全部PDF页与角色截图/视频 |
| 21 旧功能与默认关闭 | 5/6/8/9/10；旧全套回归与删除新表feature-off测试 |
| 22 最终相同运行提交 | 10；远端SHA、完整CI、证据manifest |

## 3 计划自审与执行选择

自审以规格15节与22项验收逐条对应任务：数据契约、权限、事务快照、最终授权、所有格式、资源上限、日志、双语、实际像素/CSV、原生并发、真实浏览器及最终提交身份均有任务。测试失败/通过命令、文件位置和公共签名已固定；Task 9明确复用已有框架，不把备份snapshot测试误认为读取一致性验证。五项Review Focus均有命名测试和所属任务。

推荐逐任务实现并独立检查：每个任务通过规格与代码检查后再继续，最后再做整体检查。该功能跨授权、数据库快照、文件交付和患者切换，错误可能泄露健康材料，分段独立检查值得保留。仅在Task 3公共契约冻结后对不同文件的渲染与客户端工作并行；不同时修改共享授权、DTO或验收框架。

较快的另一方式是在同一执行上下文顺序完成全部任务，再做一次独立整体检查；它减少切换，但中间阶段缺少独立检查。本计划尚未获准执行，等待用户审阅并选择方式。
