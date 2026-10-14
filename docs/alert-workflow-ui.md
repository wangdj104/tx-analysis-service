# Measurement follow-up workspace / 测量随访工作区

## Scope and access / 范围与权限

The English and Chinese editions use the same workflow protocol. The independent
`ALERT_WORKFLOW_ENABLED` gate remains **off by default**; this UI does not enable it.
Current server capabilities, permitted actions, assignments and patient grants are
authoritative. A name in a patient selector or a menu entry is not clinical access.
An unavailable capability check pauses writes; only an explicit disabled response
permits the existing untracked measurement-entry path.

英文版与中文版使用相同协议。独立的 `ALERT_WORKFLOW_ENABLED` 开关仍默认关闭；
界面不会启用开关。当前服务端能力、允许操作、团队指派和患者授权决定访问权限。
患者选择器中的姓名或菜单入口不代表临床访问权。无法核验功能状态时暂停写入；
只有服务端明确返回关闭状态，才可使用原有的未追踪测量录入路径。

## Working through a follow-up / 随访操作路径

1. Select the current named patient. Use Care Journey, BP/Glucose Self-monitoring,
   or the Care Center's quick measurement form. For workflow entry, confirm the
   occurrence wall time and its explicit UTC offset. Historical naive times are
   never silently relabeled as UTC.
2. A successful save yields an identity receipt. The UI separately loads the
   current authorized assessment and any follow-up links. No follow-up in the
   receipt does **not** mean normal. Unknown context remains unclassifiable;
   authorized users can explain why a manual follow-up is needed.
3. Open a follow-up from the measurement receipt, Care Center, Care Journey,
   Alerts, Doctor Workspace or Nurse Workspace. Lists use server cursors;
   “Active follow-ups” is the protocol's `TODAY` queue, not a calendar-day filter.
4. Use only the actions currently returned by the server. Assignment shows named
   eligible people. Acceptance acknowledges the exact assignee and deadline,
   rather than changing the deadline. Missing eligible people are shown explicitly.
5. Record progress and submit either typed evidence references (source type and
   ID, requiring current access/confirmation) or an explicitly self-reported
   contact relationship and outcome. Entered IDs are not validated evidence until
   the server accepts them. Feedback records SELF only when permitted, otherwise
   ASSISTED, with the actual recorder preserved in history.
6. Personal closure records administrative handling; it is not clinical review.
   Clinical closure requires a currently authorized assigned doctor. Reasoned
   return, cancellation, source/reference review and reopening are separate actions.
   A normal retest does not automatically close an earlier follow-up.
7. Open the source to inspect source-specific policy, revision, provenance and
   immutable revision history. Correction uses the authoritative source version
   and a reason. Invalidation/reactivation history remains visible. Legacy chart
   markers are contextual references, not current workflow assessments.

1. 选择当前患者，从照护旅程、自测记录或照护中心快捷表单录入测量。随访录入需确认
   实际发生的当地时间及明确的 UTC 偏移；既往无偏移时间不会被默认为 UTC。
2. 保存后先显示身份回执，再单独读取当前有权访问的判定及随访链接。没有随访链接
   不等于正常。背景不明时保持“无法判定”，有权限时可说明原因并手动发起随访。
3. 可从测量回执、照护中心、照护旅程、预警、医生或护士工作台打开随访。列表按服务端
   游标翻页；“进行中的随访”对应 `TODAY` 协议值，并非按日历当天筛选。
4. 只显示当前服务端允许的操作。指派列出符合条件的姓名；接受时确认准确的负责人
   与截止时间，不修改截止时间。没有符合条件的负责人时明确提示。
5. 记录进度并提交带类型与编号的引用凭据，或明确标为自述的联系关系与结果。凭据
   需要当前访问与确认，服务端接受前不视为已核验。反馈仅在允许时使用本人录入，
   否则使用协助录入，并在历史中保留实际录入者。
6. 个人关闭是行政处理记录，不是临床复核；临床关闭要求当前有权限的指派医生。
   退回、取消、来源或凭据复核、重新开启均为独立的有原因操作。正常复测不会自动关闭。
7. 打开来源核验专用判定策略、修订、来源信息及不可变的修订历史。更正使用服务端
   来源版本并说明原因；保留作废及恢复有效的历史。原有图表标记仅供背景参考。

The assessment response does not currently provide its comparison unit. The UI
therefore withholds normalized comparison and threshold numbers instead of
pairing them with the original source unit. Original source values/units,
classification, policy identity and distinct threshold names remain visible.
Assignment candidates are requested only for an allowed assignment/escalation
action. Acceptance uses the exact responsible account/deadline; a signed-in
responsible person can see their authenticated account name without requesting
assignment-governance data. Clinical escalation starts a new cycle with no
assignee/deadline by default, with explicit eligible selection and doctor-only
clinical deadline decisions; a previous personal deadline is not carried over.

当前判定响应不提供比较单位，因此界面不显示归一化比较值及阈值数值，避免与原始
单位错误配对。原始数值与单位、判定状态、策略标识及各阈值名称仍保留。只有允许
指派或升级的操作才读取候选人。接受时确认准确的负责人账号及截止时间，当前负责人
可使用其已登录身份的姓名，无需读取指派管理数据。临床升级默认以无负责人及截止
时间开启新一轮；可明确选择符合条件的人员，仅当前有权限医生可设置临床截止时间，
不会沿用个人随访的截止时间。

## Uncertain results and privacy / 不确定结果与隐私

Repeated clicks do not create a second in-flight command. If the result is
uncertain, editing is locked and **Retry original request** reuses the exact
original identity and body. A conflict retains input, requires a successful
refresh and an explicit new decision. An enabled-workflow request is never
silently replaced with a legacy save when the feature turns off. Failed
assessment reads can be retried without entering the measurement again.

Protected state is memory-only. Route/patient changes are blocked while a command
is pending or uncertain; an unload warning is requested. Browser termination or
ignoring that warning can discard the in-memory request, so reconcile the saved
record before entering a replacement after restarting. Logout/current authority
loss clears protected data and drafts. Late results from old patients, sessions,
dialogs, source reads or history requests cannot repopulate the current context.

重复点击不会产生第二个进行中的命令。结果不确定时锁定编辑，“重试原始请求”复用
原始标识及内容。冲突会保留输入，要求成功刷新并明确作出新决定。开关关闭不会把
原有随访请求替换为旧格式新增。判定读取失败可重试读取，无需重复新增测量。

受保护状态仅保存在内存。请求提交中或不确定时阻止路由及患者切换，并请求浏览器
离开警告。关闭浏览器或忽略警告可能丢失内存中的请求，重新打开后应先核实已保存
记录，再考虑新增。退出登录或当前权限丢失会清除受保护内容及草稿；旧上下文的迟到
响应不会重新填充当前患者内容。

## Nursing and notification boundaries / 护理与通知边界

- Measurement nursing follow-up is independent of `CARE_PLAN`: it requires both a
  current nursing assignment and explicit `MEASUREMENTS` authorization. It does
  not grant care-plan access or clinical closure.
- Administrators can use the current nurse candidates, assign, retain the returned
  receipt/ID, and revoke a specified assignment ID. Admin status alone cannot read
  patient assignment history. The owner/current assigned doctor can load that
  history under their independent authority.
- Assignment expiry requires an explicit offset. Existing patient-grant expiry
  retains its application/database-local, offset-less contract.
- Delivery status concerns only the current actor's authorized recipient rows.
  Provider acceptance is not patient reading, assignment acceptance or closure.
  Automated deadline events are labeled SYSTEM/SCHEDULER, not human activity.
  Unknown delivery needs duplicate-risk acknowledgment before a permitted retry.
  Timeout retries keep the original opaque retry token/body; conflict requires a
  successful status refresh and a new explicit decision. No channel is created.

- 测量护理随访独立于 `CARE_PLAN`，同时要求当前护士指派及明确的 `MEASUREMENTS`
  授权；不附带照护计划访问或临床关闭权限。
- 管理员可读取护士候选人、指派、保留回执编号并撤销指定编号，不能仅凭管理员身份
  读取患者指派历史。所有者或当前指派医生凭其独立权限读取历史。
- 护士指派到期需要明确偏移；原有患者授权到期仍使用无偏移的应用／数据库当地时间。
- 通知状态仅显示当前账号有权访问的收件记录。渠道接收不等于患者阅读、接受指派或
  关闭随访。自动截止提醒明确标为系统调度。结果未知时需确认重复发送风险；超时复用
  原始重试标识与内容；冲突需成功刷新状态后重新决定。界面不会创建通知渠道。

## Verification boundaries / 验证边界

Run `node --test frontend/tests/*.test.mjs`, then `npm run build` in `frontend`;
repeat using `cn/frontend`. Tests include real Vue reactive state with synthetic
API replies, existing-page handler execution, compiled-component rendering, and
static token/accessibility contracts. They do not constitute browser, live API,
production database or real notification acceptance.

The existing six themes and mobile controls are retained, with scoped wrapping,
44px primary targets, visible focus rules and sticky dialog actions. Actual
six-theme contrast, 390px/200% text layout, keyboard/focus behavior and end-to-end
browser flows still require acceptance in a supported authorized browser.

执行上述两套 Node 测试及生产构建。测试覆盖真实 Vue 状态、模拟 API、现有页面处理
函数、编译组件及静态样式契约；不代表浏览器、真实 API、生产数据库或真实通知验收。
保留六主题及移动控件，新增局部换行、44px 主操作、可见焦点及粘底操作区。实际
六主题对比度、390px／200% 文字布局、键盘焦点及端到端流程仍需受支持浏览器验收。

## Correction scope and current context / 更正范围与当前情境

Journey corrections preserve omitted metric labels and notes. The original journey
`metricName` / `remark`, and self-monitor `sourceType` / `sourceExternalId` / `remark`,
are read-only in the versioned correction path. Omission or an identical supplied
value is accepted; a different value or clearing a nonempty field is rejected with
validation. Explicit null is accepted only when that field is already null. Values,
units, supported measurement context and occurrence time remain correctable with a
reason. This deliberately limits metadata editing until a separately authorized
history contract exists; notes/provenance are not copied into general snapshots.
The original native metadata remains accessible only through its existing authorized
record views. Source identity, revision and recorder metadata are server-owned.

照护旅程更正保留未提交的测量名称和备注。版本化更正中的旅程 `metricName`／`remark`，
以及自测 `sourceType`／`sourceExternalId`／`remark` 均为只读。省略或提交完全相同的值可接受；
更换值或清空已有内容会明确拒绝。仅原值已为空时可提交 null。读数、单位、支持的测量情境
和时间仍可附原因更正。这是当前明确的编辑限制，避免在缺少独立授权历史契约时丢失原始
情境或把备注广泛复制到快照。原始元数据仍按既有原记录权限查看；来源身份、版本和记录者
由服务端管理。

A source correction makes earlier plan-action confirmation insufficient on every
later attachment, including a different follow-up. A genuinely later doctor receipt
confirmation can qualify an active source. Invalidated sources and unknown revision
qualification metadata stay visibly blocked as `REFERENCE_CHANGED`. Existing care-plan
publication/review/closure rules are unchanged; a previously confirmed action is not
silently reset to create another review. Use an authorized current replacement action
or explicit reasoned dependency termination when its existing plan rules require it.

来源更正后，较早的计划行动确认不能在另一条随访或新一轮处理里重新生效。有效来源经真正
较晚的医生回执确认后才可重新满足相关凭据要求。作废来源或缺失版本资格信息继续显示
`REFERENCE_CHANGED`。不会暗中重置已确认的行动；如原计划规则要求，应由有权限者选择
当前替代行动，或明确说明原因终止依赖。原有计划发布、审核、关闭规则不变。

Notification links carry an exact patient context. The destination page checks current
access and shows that patient explicitly without changing the global patient selection.
Revoked/mismatched destinations stay denied. Workflow times with offsets display the
browser's local time and explicit UTC offset; original naive measurement times keep
their original meaning. Filtered lists scan at most 100 candidates per request; an empty
page may still offer More, which resumes immediately after the scanned boundary.

通知链接携带准确患者情境；目标页重新核验权限并明确显示目标患者，不改变全局选择。
撤权或情境不符时拒绝显示。带偏移的随访时间按浏览器本地时区显示并标明 UTC 偏移；
原始无偏移测量时间不重解释。筛选列表每次最多投影 100 个候选；空页仍可能出现“下一页”，
从已扫描边界之后继续。

Before following the retest link, match the global patient selector to the authorized patient shown in this detail. The link stays unavailable while those contexts differ.
复测入口仅在全局患者选择与当前已授权详情患者一致时可用；不一致时提示先选择本页患者。
