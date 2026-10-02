# 健康平台五十轮质量审查 2026-10-02

[English](../../docs/QUALITY_REVIEW_20261002_50_ROUNDS.md) · [中文演示](https://wangdj104.github.io/tx-analysis-service/cn/) · [English demo](https://wangdj104.github.io/tx-analysis-service/)

## 结果

**50 项独立审查目标全部完成。** 本次要求的窗口为 **2026 年 10 月 2 日 13:52–21:52 UTC**。已验证版本修复患者/编辑器归属、异步清理、本地日期与零值显示、提醒、偏好、轮询及图表可读性，并增加严格语义预约索引、上下文引导及局部演示存储恢复。新执行的最终回归通过 **2,476 项测试**、两个生产构建及完整性检查，精确提交的 CI、Pages、后端打包及标准公开地址资源再次核实。两段累计实测 **6 小时 16 分 41.606 秒**，保留中断及第一段最终清理未核实的限制。最终回归与报告核对在 21:52 边界之后完成，八小时工作窗口不等于连续运行八小时。

起始版本：`1f0c35a6c5ca3ecdd1bebb46096c8c2c16e4222f`。最终受审运行代码：[`2fc8c21e9f74897c4139d8337a585f6e89efff4c`](https://github.com/wangdj104/tx-analysis-service/commit/2fc8c21e9f74897c4139d8337a585f6e89efff4c)；代码树：`a8844aa300ed3b4563c8e2974a03b85b71b84645`。本报告源码链接均固定到该运行版本。

本次未修改临床阈值、用药剂量逻辑、临床解读或后端访问策略。每一轮对应有独立证据的不同审查目标，重复测试及合成观察循环不另算审查轮次。

## 改动与回归覆盖

- **患者与编辑器归属** — [9e3a01b](https://github.com/wangdj104/tx-analysis-service/commit/9e3a01b53840d5f5917f289ddf612eaf305ba51f)
  - 拒绝旧列表、详情、分析及报告结果，覆盖同患者重开与 A→B→A 往返
  - 固定原始数据与新增/更新操作快照，阻止重复发送；旧完成不能关闭、清空新草稿或解除其保存锁
  - 保留病历有意独立的筛选/编辑患者选择及透析历史范围，修复重复查询参数导航和返回录入时的加载
- **上传与报告资源** — 9e3a01b
  - 将病历 Edit 读取、真实排队上传回调和附件准备绑定当前弹窗；在关闭开始时立即失效，不等待离场动画结束
  - 准备/保存期间锁定控件，保留失败重试，压缩异常时回退原文件并释放预览/导出资源
  - 保持报告患者身份、格式与文件名一致，跳过旧汇总渲染；关闭客户端弹窗不能撤销已经发出的服务端写入
- **日期与数值显示** — [64a419d](https://github.com/wangdj104/tx-analysis-service/commit/64a419d5d613b87ddd6cc651c8206e0229206863)
  - 日记默认使用本地日历日期，清除校验时不恢复挂载时的旧值
  - 正确显示有效零值，英文血压历史日期列绑定实际字段；历史日期、原始值及单位不变
- **提醒生命周期与界面可访问性** — 64a419d
  - 检查提醒会话/患者/姓名/权限上下文，持久化失败时在同一挂载实例内保留成功发送记录
  - 补充本地化图标名称、引导焦点/Escape 行为，修正所选类型的图表空状态与图形对比度
  - 响应实时减少动态效果偏好，不重置缩放/图例状态；销毁时移除偏好监听器
- **偏好与重叠读取** — [f50fd19](https://github.com/wangdj104/tx-analysis-service/commit/f50fd19e7956977a49350b593117c2ef4c08753b)
  - 恢复失效列偏好及部分便利性存储失败，不改变有效用户选择
  - 拒绝旧病历列表/趋势/项目名称读取，保留局部与全局患者选择器的独立语义
  - 合并监测页重复自动读取，家庭照护用药记录的读取共享结果归属，同时保持明确刷新操作的优先级
- **公开演示可读性与构建检查** — f50fd19
  - 图表按实际宽度调整，保持 240 px 高度、明确标准/大字标签尺寸并清理调整大小监听器；修正英文单数待审计数
  - 将既有 html2canvas 分包延后到懒加载路由，根 CI 的后端矩阵在测试后增加打包步骤
- **预约标签与上下文引导状态** — [78de6e3](https://github.com/wangdj104/tx-analysis-service/commit/78de6e345a221f8d3a839b3a0975a7e3e608f627)
  - 用计算属性 ID 索引替代逐问题扫描，保持严格相等、重复 ID 首次匹配、NaN 不匹配、原可见条件及标题回退；集合、ID 和标题的响应式变化仍可见
  - 患者无问诊、待审队列为空或没有待办的一键照护任务时，引导使用符合实际状态的目标与说明
  - 切换患者、引导打开时完成操作及测量保存重绘后刷新引导上下文；保护排队定位，保留当前步骤和操作/关闭焦点
  - 定向源码/响应式及独立检查已通过，17:39–17:47 UTC 完成两种公开语言版本的受影响引导实测

- **演示便利性存储恢复** — [2fc8c21](https://github.com/wangdj104/tx-analysis-service/commit/2fc8c21e9f74897c4139d8337a585f6e89efff4c)
  - 即使引导完成状态保存失败，Finish 仍关闭引导、清理排队定位/高亮并返回焦点
  - 即使已存品牌删除失败，Reset 仍完整恢复一致的新会话，并提示旧品牌可能在重载后恢复
  - 有效品牌在保存失败时仍应用于本次访问，保留原持久化值，并明确提示更改未保存
  - 保留无效输入校验、正常成功行为、既有存储键、外观选择及无关存储值；不可用存储检查采用合成故障，真实浏览器仅检查正常存储

## 已记录的发布验证

五个已发布代码批次的本地通过结果如下。数量是测试用例数，不是不同缺陷数、独立复查断言数或观察循环数。

| 代码批次 | 英文后端 | 中文后端 | 英文前端 | 中文前端 | 演示合计 | 总数 | 两个构建 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 9e3a01b | 233 | 233 | 709 | 716 | 115 | 2,006 | 通过 |
| 64a419d | 233 | 233 | 817 | 824 | 123 | 2,230 | 通过 |
| f50fd19 | 233 | 233 | 891 | 898 | 145 | 2,400 | 通过 |
| 78de6e3 | 233 | 233 | 902 | 909 | 183 | 2,460 | 通过 |
| 2fc8c21 | 233 | 233 | 902 | 909 | 199 | 2,476 | 通过 |

最新发布批次的本地验证时间为 **18:05:24.195–18:05:52.705 UTC**。观察到达要求边界后，在 2fc8c21 上于 **21:52:14.792–21:52:44.970 UTC** 重新执行完整检查：**英文后端 233 + 中文后端 233 + 英文前端 902 + 中文前端 909 + 演示 199 = 2,476 项测试全部通过**。两个生产构建、演示语法/一致性及差异完整性检查通过，全部 **982 个完整源码指纹**在最终验证期间未变，且与已发布批次快照一致；运行代码工作区干净。既有大分包提示仍保留。

- 9e3a01b：精确提交的 [CI 通过](https://github.com/wangdj104/tx-analysis-service/actions/runs/37024704086)
- 64a419d：精确提交的 [CI 通过](https://github.com/wangdj104/tx-analysis-service/actions/runs/37031331153)，[Pages 通过](https://github.com/wangdj104/tx-analysis-service/actions/runs/37031331160)
- f50fd19：精确提交的 [CI 通过](https://github.com/wangdj104/tx-analysis-service/actions/runs/37037548266)，[Pages 通过](https://github.com/wangdj104/tx-analysis-service/actions/runs/37037548153)。两个矩阵任务新增的 **Package backend** 均在测试步骤后通过，这是打包验证，不是后端部署
- 78de6e3：精确提交的 [CI 通过](https://github.com/wangdj104/tx-analysis-service/actions/runs/37041945360)，[Pages 通过](https://github.com/wangdj104/tx-analysis-service/actions/runs/37041945300)。两个矩阵任务的 **Package backend** 均在测试步骤后通过，不代表已部署后端
- 2fc8c21：精确提交的 [CI 通过](https://github.com/wangdj104/tx-analysis-service/actions/runs/37045903409)，[Pages 通过](https://github.com/wangdj104/tx-analysis-service/actions/runs/37045903175)。两个矩阵任务的 **Package backend** 均在测试步骤后通过，不代表已部署后端
- **18:15:20.409–18:16:11.897 UTC**，12 个公开演示资源均返回 HTTP 200，并与 2fc8c21 字节一致。最终标准地址检查于 **21:52:42.538–21:54:03.977 UTC** 读取各语言的普通 HTML 地址及其中声明的准确脚本/样式地址，结果为 **12/12 HTTP 200、12/12 字节一致**。这仅确认抽查的公开静态演示资源，不代表登录应用部署

**21:52:42.289 UTC** 再次确认 main 指向 2fc8c21，对应既有 CI/Pages 及两个后端打包步骤均成功。这是对已发布运行结果的新状态核实，不是重新执行 CI。最终本地验证和标准公开资源核对在要求的观察边界之后完成。

[机器可读验证摘要](../../docs/verification/20261002_50_rounds.json)记录已验证发布、完成的最终检查、实测分段及覆盖限制。

下文永久测试链接固定到 2fc8c21。应用测试在 `frontend/tests` 与 `cn/frontend/tests` 配对；注明的演示测试覆盖两种语言。定向检查和独立复查与总回归有重叠，不能再累加到 2,476 项。

## 独立整合复查

合并实现及最终存储恢复改动通过 **28 组独立编写的场景 / 490 次断言**，在受审源码/运行时范围内未发现引入的回归或剩余阻断问题：

- 14 组双语组件场景 / 238 次断言，组合检查患者/编辑器归属、日期重置、筛选、轮询、保存后刷新及预约索引
- 2 组编译图表场景 / 66 次断言，将真实图表组件与 ECharts 一同挂载，检查空状态转换、动效偏好、缩放/图例状态保留、尺寸调整与销毁
- 12 组存储恢复场景 / 186 次断言，在受控持久化失败下验证引导完成、一致重置及有效品牌即时应用；永久 [storageRecovery.test.mjs][demo-storage] 另通过 16/16 项

复查复现三项原已存在的演示持久化失败，并重新验证已发布修正。API、宿主树、存储及媒体查询边界采用受控适配，并非浏览器/后端端到端或真实禁用存储测试。场景/断言数与总回归用例、审查轮次、实测观察时长分开统计。最终观察及窗口结束检查见下文。

## 五十项审查目标

50 项不同目标均在注明的范围内完成，最终验证在窗口边界后执行。完成不取消各行限制，也不把合成检查视为浏览器/后端验证。

| 轮次 | 目标 | 状态 | 结果与永久证据 |
| ---: | --- | --- | --- |
| 1 | 基线源码完整性与五组回归 | 已完成 | 基线工作区干净；1,268 项测试、两个生产构建及演示语法/双语一致性检查通过。最新版本数量见下文。 |
| 2 | 导航可用性与专科路由 | 已完成 | 专科菜单筛选与路由可用性的 30 项中英文定向检查通过，未证实新的菜单可用性缺陷。 [patientSpecialtyNavigation.test.mjs][specialty-cn]。 |
| 3 | 前进后退导航 | 已完成 | 重复 tab 查询参数以 Vue Router 数组形式传入时不再抛出异常；18 项检查覆盖历史导航、异常值及既有标签页。 [healthAnalysisNavigation.test.mjs][nav-cn]。 |
| 4 | 卸载页面与计时器生命周期 | 已完成 | 页面销毁后，旧读取、保存、文件及引导回调不再影响状态。组件检查通过；持续观察单独计入第 45 轮。 [medicalEditorLifecycle.test.mjs][edit-cn]; [accessibilityControls.test.mjs][a11y-cn]。 |
| 5 | 加载错误与重试状态 | 已完成 | 请求归属校验阻止旧响应、错误或收尾逻辑结束新加载；病历编辑读取失败时不能保存，可重新打开重试。 [medicalEditorLifecycle.test.mjs][edit-cn]; [medicalReadLifecycle.test.mjs][read-cn]。 |
| 6 | 切换患者后的最新响应 | 已完成 | 透析、血压与日记列表在切换患者后忽略旧成功、错误及加载状态回写；病历列表独立的全部患者筛选语义保留。 [dialysisPatientContext.test.mjs][dialysis-cn]; [bpPatientContext.test.mjs][bp-cn]; [nutritionDiaryContext.test.mjs][diary-cn]。 |
| 7 | 患者往返切换与同记录重开 | 已完成 | 操作轮次覆盖 A→B→A、内容相同的新草稿及同记录重开，避免仅凭患者编号相同接受旧完成结果。 [careEditorLifecycle.test.mjs][care-cn]; [medicalEditorLifecycle.test.mjs][edit-cn]。 |
| 8 | 读取与派生状态失效 | 已完成 | 透析分析来源、血压详情与报告预览固定原上下文或及时清空，旧内容不能填入替换后的上下文。 [dialysisPatientContext.test.mjs][dialysis-cn]; [bpPatientContext.test.mjs][bp-cn]; [healthReportContext.test.mjs][report-cn]。 |
| 9 | 待完成保存与替换编辑器 | 已完成 | 旧保存不能清空、关闭日记、透析、血压、照护或病历的新草稿，也不能解除其保存锁；数据与操作快照保持原提交内容。 [nutritionDiaryContext.test.mjs][diary-cn]; [dialysisPatientContext.test.mjs][dialysis-cn]; [bpPatientContext.test.mjs][bp-cn]; [careEditorLifecycle.test.mjs][care-cn]; [medicalEditorLifecycle.test.mjs][edit-cn]。 |
| 10 | 当前患者为空或已移除 | 已完成 | 默认、保留、移除及空选择通过既有选择器与清空上下文检查；有意使用局部患者选择的病历编辑保持原语义。 [currentPatient.test.mjs][patient-cn]; [medicalEditorLifecycle.test.mjs][edit-cn]。 |
| 11 | 空值零值与数值表单边界 | 已完成 | 真实 Element Plus 重置不再恢复上一位患者缓存的血糖值；独立挂载检查拒绝 64 次无效保存、接受 6 个有效对照，数值规则未变。 [bpPatientContext.test.mjs][bp-cn]。 |
| 12 | 日期时间与本地解析 | 已完成 | 默认日期改用本地日历日期；重置使用当前日期，不再恢复表单挂载时的旧日期。62 项配对检查覆盖午夜、跨年、闰日与夏令时对照。 [diaryDateAndDisplay.test.mjs][date-cn]。 |
| 13 | 重复提交与稳定数据快照 | 已完成 | 校验至网络发送期间的锁、逐行操作归属和独立数据快照阻止重复操作与发送替换数据；真实 Element Plus 禁用检查覆盖透析患者选择器。 [dialysisPatientContext.test.mjs][dialysis-cn]; [bpPatientContext.test.mjs][bp-cn]; [careEditorLifecycle.test.mjs][care-cn]; [medicalEditorLifecycle.test.mjs][edit-cn]。 |
| 14 | 取消关闭与返回生命周期 | 已完成 | 关闭开始时即使上下文失效，覆盖弹窗离场动画结束前的关闭按钮和 Escape；旧 afterLeave 不能关闭新弹窗，并检查引导暂停与返回录入导航。 [medicalEditorLifecycle.test.mjs][edit-cn]; [accessibilityControls.test.mjs][a11y-cn]; [dialysisPatientContext.test.mjs][dialysis-cn]。 |
| 15 | 校验与服务请求失败恢复 | 已完成 | 受控校验/API 失败保留当前可重试草稿，仅解除原操作的锁并抑制旧反馈；明确重试通过且不重复发送。 [nutritionDiaryContext.test.mjs][diary-cn]; [healthReportContext.test.mjs][report-cn]; [careEditorLifecycle.test.mjs][care-cn]; [bpPatientContext.test.mjs][bp-cn]。 |
| 16 | 上传类型数量与大小边界 | 已完成 | 新增 10 项配对合成检查覆盖支持的 PDF/图片、十文件队列、容量恢复及 413/超时重试；后端大小上限仅作配置阅读，未实际测试或确认部署。 [medicalUploadLifecycle.test.mjs][upload-cn]; [MedicalRecordManager.vue][upload-source-cn]。 |
| 17 | 排队文件选择与删除顺序 | 已完成 | 50 项既有配对生命周期检查通过，覆盖真实 Element Plus 排队回调、准备前删除、压缩乱序及快速重置/切换患者。 [medicalUploadLifecycle.test.mjs][upload-cn]。 |
| 18 | OCR 解析与异步替换 | 已完成 | 6 项配对条目保留检查及上传生命周期检查保留不同/冲突条目并拒绝旧 OCR 结果；未评估 OCR 识别准确率。 [medicalRecordItems.test.mjs][ocr-cn]; [medicalUploadLifecycle.test.mjs][upload-cn]。 |
| 19 | 预览 URL 与压缩清理 | 已完成 | 22 项配对测试验证解码或画布异常后仅完成一次的原文件回退及 URL 清理，后续文件可继续；未测量真实相机或编解码性能。 [imageCompression.test.mjs][compress-cn]; [medicalUploadLifecycle.test.mjs][upload-cn]。 |
| 20 | 上传重试与控件禁用 | 已完成 | 会话绑定回调、带标识的上传器替换及准备/保存锁通过 122 项新增病历编辑、56 项既有病历及 60 项独立关闭时序检查。 [medicalEditorLifecycle.test.mjs][edit-cn]; [medicalUploadLifecycle.test.mjs][upload-cn]。 |
| 21 | 营养评估空白与失败状态 | 已完成 | 既有评估检查通过，覆盖清空上下文、旧预览、延迟校验、保存失败重试与卸载；未修改评估实现或新增后端授权审查。 [nutritionPatientContext.test.mjs][nutrition-cn]。 |
| 22 | 营养日记日期与合计显示 | 已完成 | 明确指定的本地日期重置可经受真实表单校验及患者往返切换；历史日期、录入数量、合计与单位保持不变。 [diaryDateAndDisplay.test.mjs][date-cn]; [nutritionDiaryContext.test.mjs][diary-cn]。 |
| 23 | 用药提醒与调度 | 已完成 | 86 项永久配对测试及 54 项独立检查覆盖旧会话/患者/姓名响应、权限变化与持久化失败；到期、稍后提醒、状态及剂量语义未变。 [notificationLifecycle.test.mjs][notify-cn]。 |
| 24 | 缺失与零健康数值区分 | 已完成 | 有效的日记饮水量 0 显示为 0 ml，血压标准差 0 保持可见；编译表格插槽检查保留缺失/异常占位、原精度与单位。 [diaryDateAndDisplay.test.mjs][date-cn]。 |
| 25 | 照护历程详情与操作一致性 | 已完成 | 处方、预约、计划及康复保存保留新草稿，包括内容相同的预约重新选择；新增配对与独立对抗检查通过，临床及紧急处置处理器未变。 [careEditorLifecycle.test.mjs][care-cn]。 |
| 26 | 图表时间顺序与空数据 | 已完成 | 新增 6 项配对源码执行检查保留排序、系列对齐、相同时间的稳定顺序、缺失时间处理及原输入；所选测量类型具有正确空状态。 [chartAccessibility.test.mjs][chart-cn]; [VitalsTrendPanel.vue][vitals-source-cn]。 |
| 27 | 图例标签与单位 | 已完成 | 应用图表与图例颜色一致，所选类型的数据可用状态明确；原读数、单位换算与参考系列未变，实际 ECharts 引擎检查通过。 [chartAccessibility.test.mjs][chart-cn]; [appearanceChart.test.mjs][appearance-cn]。 |
| 28 | 表格排序筛选与分页范围 | 已完成 | 病历列表、局部趋势及项目名称读取拒绝旧结果、错误与收尾回写，同时保留局部/全局选择器独立性；排序对照通过，未测试管理审计分页。 [medicalListLifecycle.test.mjs][list-cn]; [medicalReadLifecycle.test.mjs][read-cn]。 |
| 29 | 图表调整大小与已销毁实例 | 已完成 | 实际 ECharts 检查确认实时动效偏好变更、稀疏替换与清空/重用标识后缩放和图例状态保留；监听器及导出创建的图表/节点有定向清理覆盖。 [chartAccessibility.test.mjs][chart-cn]; [healthReportContext.test.mjs][report-cn]。 |
| 30 | 打印与导出外观 | 已完成 | 新增 4 项配对快照/弹窗检查及 82 项配对外观/导出回归通过；源码/组件检查覆盖打印样式和取消清理，未测试纸张分页及真实登录态下载。 [healthReportContext.test.mjs][report-cn]; [appearanceChart.test.mjs][appearance-cn]。 |
| 31 | 六套配色与语义对比度 | 已完成 | 琥珀色图形在受测白色/暖米色卡片背景上达到 3:1，深色模式仍清晰；六套主题均有视觉抽查，不宣称完整主题/语言/尺寸组合覆盖或无障碍认证。 [chartAccessibility.test.mjs][chart-cn]; [healthAppearance.test.mjs][healthappearance-cn]。 |
| 32 | 键盘控件与焦点返回 | 已完成 | 本地化图标标签、引导步骤焦点、Escape 优先级与安全焦点返回通过配对/组件检查；另行观察了公开中英文演示的引导键盘流程。 [accessibilityControls.test.mjs][a11y-cn]; [accessibleInteractions.test.mjs][demo-a11y]。 |
| 33 | 弹窗语义与可访问名称 | 已完成 | 引导使用非模态语义，尊重真实模态弹窗的 Escape 优先级并忽略旧回调；列设置控件使用一致的可见名称。未实际使用屏幕阅读器。 [accessibilityControls.test.mjs][a11y-cn]。 |
| 34 | 大字与减少动态效果 | 已完成 | 修复后的公开中英文图表通过桌面/窄窗口及标准/大字检查；减少动态效果的偏好变更与监听清理通过组件测试，未操作真实系统动效设置。 [chartAccessibility.test.mjs][chart-cn]; [accessibilityControls.test.mjs][a11y-cn]; [reducedMotion.test.mjs][demo-motion]; [chartResponsive.test.mjs][demo-chart]。 |
| 35 | 外观偏好持久化 | 已完成 | 定向偏好读写可容忍失败，大字即时生效，便利性偏好写入失败不再让已成功保存的测量草稿保持打开；未证明禁用全部存储的登录应用可正常使用。 [preferenceRecovery.test.mjs][preferences-cn]。 |
| 36 | 窄窗口导航 | 已完成 | 在实测 CSS 502×757 下，中英文导航与大字控件可用，横向菜单可到达末项，键盘导航后焦点进入主内容；不宣称实体手机或更窄浏览器覆盖。 [responsive.test.mjs][demo-responsive]; [accessibleInteractions.test.mjs][demo-a11y]。 |
| 37 | 窄窗口表单与日期选择器 | 已完成 | 公开测量/日期弹窗完整显示，无效保存被阻止，取消/Escape 返回焦点；每种语言各保存一条虚构读数，新增一个可见行并更新图表，重置恢复基线。未核实隐藏总条数。 [accessibleInteractions.test.mjs][demo-a11y]; [responsive.test.mjs][demo-responsive]。 |
| 38 | 窄窗口表格访问 | 已完成 | 在 502 px 视口中，观察到的中英文近期读数表格宽度及 scrollWidth 均为 429 px，四列完整显示；不代表覆盖全部登录应用表格。 [responsive.test.mjs][demo-responsive]。 |
| 39 | 窄窗口图表可读性 | 已完成 | 原约 7 px 的渲染标签已修正；修复后图表窄窗口为 421×240、桌面为 815×240，14/18 px 字体对应 16/20 px 字形高度，标签均在边界内且页面无横向溢出。 [chartResponsive.test.mjs][demo-chart]。 |
| 40 | 中英文应用与演示一致性 | 已完成 | 双语图表/单数计数、38 项定向及 56 项独立引导检查通过；中英文真实浏览器验证无问诊、待审为空、无一键任务、患者切换、任务完成、测量重绘、有效目标及焦点返回，随后重置虚构数据。这是受影响演示覆盖，不是登录应用端到端验证。[auditGuideParity.test.mjs][demo-parity]; [statsPlural.test.mjs][demo-plural]; [guideConsultationContext.test.mjs][demo-guide-context]。 |
| 41 | 生产分包加载成本 | 已完成 | 将 html2canvas 从首屏 vendor 依赖图拆出，英文/中文延后加载 47,225/47,231 gzip 字节；全应用 gzip 增加 615/567 字节。静态导入解析与独立重建通过，未测量浏览器加载时间。 [buildChunking.test.mjs][chunk-cn]。 |
| 42 | 轮询重叠与可见性 | 已完成 | 监测页合并重复自动读取，家庭照护用药记录的完整/任务读取共享结果归属；手动、患者、范围及保存后刷新保持优先级。54 项永久配对与 118 项独立检查通过。 [pollingOwnership.test.mjs][poll-cn]。 |
| 43 | 损坏或不可用的浏览器存储 | 已完成 | 旧列选择可回退且不改变有效选择语义，偏好失败不破坏即时界面和同实例提醒历史；演示 Finish/Reset/有效品牌也可在合成持久化失败下恢复，并显示真实提示。真实浏览器仅检查正常存储。[preferenceRecovery.test.mjs][preferences-cn]; [notificationLifecycle.test.mjs][notify-cn]; [storageRecovery.test.mjs][demo-storage]。 |
| 44 | 大型合成列表与重复操作 | 已完成 | 计算属性索引替代基线的反复线性扫描，保持严格首次匹配、NaN、可见条件及 Vue 响应式语义。2,000 个预约/问题原需 2,001,000 次比较，现为首次构建读取 2,000 次 ID 加按键查询，缓存读取不重建索引。22 项专项检查及独立复查通过；未测试浏览器/真实列表性能。[careAppointmentLookup.test.mjs][lookup-cn]; [CareCenter.vue][care-source-cn]。 |
| 45 | 实测观察与受跟踪资源 | 已完成 | 两段累计 6 小时 16 分 41.606 秒、1,506 个循环及 99,785 次断言。第二段于 21:52:00.007 UTC 自然结束，最终跟踪清理通过；此前中断、29 分 41.601 秒间隔及第一段最终清理未核实仍明确保留，不宣称八小时连续运行或完全无泄漏。 |
| 46 | 独立最终代码复查 | 已完成 | 跨批次整合及最终存储改动通过 28 组独立场景 / 490 次断言，包括真实编译图表整合；范围内未发现引入的回归或剩余阻断问题。三项原有演示存储失败已修复并复查。另行执行的最终回归、源码/发布核对及观察亦已完成，记录如下。[storageRecovery.test.mjs][demo-storage]; [chartAccessibility.test.mjs][chart-cn]。 |
| 47 | 最终五组回归 | 已完成 | 在 2fc8c21 上于 21:52:14.792–21:52:44.970 UTC 重新验证，2,476 项全部通过：后端 233 + 233、前端 902 + 909、演示 199；无失败、取消或跳过。 |
| 48 | 最终构建与源码身份 | 已完成 | 两个最终生产构建及演示语法/一致性/差异检查通过；982 个源码指纹在运行期间未变且与发布快照一致，运行提交/代码树为 2fc8c21/a8844aa，工作区干净；既有大分包提示保留。 |
| 49 | 精确提交的 CI Pages 与上线检查 | 已完成 | 21:52:42.289 UTC 再次核实 main、对应提交成功的 CI/Pages 与两个打包步骤；至 21:54:03.977 UTC，标准 HTML 及声明资源共 12 项均与 2fc8c21 一致。此前受影响浏览器流程检查保留其实测限制。 |
| 50 | 延后发布观察与最终核对 | 已完成 | 观察持续至原定 21:52 边界，于 21:52:00.007 UTC 自然结束；随后新回归与标准公开资源检查通过，50 项目标均按证据核对。实测时长排除已记录间隔，不等同于整个八小时工作窗口。 |

## 真实浏览器证据

真实浏览器检查使用桌面 Chromium 中的公开无后端演示及虚构数据。首轮于 **16:10–16:20 UTC**，表单/保存补充于 **16:27–16:32 UTC**，实测 CSS 视口为 **502×757** 与 **1180×757**。对应原生窗口尺寸为 510×848、1188×848；窗口尺寸不等同于视口。

- 窄窗口导航、大字控件、测量/详情弹窗、必填日期校验与原生日期选择器完整显示；取消/Escape 及引导流程将焦点返回入口
- 每种语言各保存一条有效虚构测量，新增一个可见近期读数行并更新图表；重置后可见表格与图表精确恢复。七行可见窗口不能证明隐藏总数。CSV 下载事件等待超时且未返回文件路径，未观察到真实浏览器下载错误，因此文件完成情况/内容未核实，不能判定导出失败
- 英文六套主题均在桌面视觉检查，中文六套主题均在窄窗口大字模式检查，并补充深色/暖米色状态；未覆盖六主题 × 两语言 × 两尺寸的完整组合
- 最初窄窗口图表标签即使开启大字仍仅约 7 CSS px。f50fd19 后续检查中，**两语言 × 桌面/窄窗口 × 标准/大字**全部通过：窄图表 421×240、桌面 815×240，与 viewBox 一致；字体 14/18 px，实测字形高度 16/20 px，点标记 7×7 px；标签均包含在边界内，页面没有观察到横向溢出
- 图表后续检查还通过 7/30 天范围、患者重绘、离开/返回路由、重置及焦点流程，英文单数计数已解决
- **78de6e3 引导后续检查，17:39–17:47 UTC：** 两种公开语言版本的受影响流程均通过。患者/医生模式下，无问诊患者的引导指向既有患者选择器；引导保持打开时切换患者，恢复正确消息框目标，未创建会话或发送消息
- 清空虚构待审队列及完成可用的一键照护任务后，引导文案和唯一有效目标更新，当前步骤保留；仍待录入的测量与一键任务正确区分。每种语言另保存一条虚构测量，重绘后引导重新定位，焦点返回测量入口，并完成该虚构患者的最后任务
- 两种语言均通过快速下一步/上一步配合患者切换、暂停/Escape 清理、重启与焦点返回；窄大字检查为 CSS 502×757，另在 1180×757 桌面抽查。较高任务组可正常纵向滚动，引导控件保持可见；未穷举引导/主题/视口组合
- 引导检查后重置每种语言的虚构基线，恢复三项待审、任务数 1/4 及最新读数 126/82。最终浏览器为桌面 1180×757、深色主题、标准文字，无引导高亮目标。受影响引导范围内未发现新的产品缺陷，观察与最终验证在本报告中分别记录

- **2fc8c21 正常存储检查，18:15–18:19 UTC：** 两种语言均完成引导五步与 Finish，移除高亮、返回焦点并成功重启；引导打开时 Reset 恢复默认角色/患者、概览及虚构基线，同时保留当时所选主题和文字大小
- 两种语言的有效虚构品牌立即生效，并在普通重载后保留；Reset 再重载恢复默认品牌/标题。其他品牌字段保持原值；无效输入校验由独立合成检查覆盖。品牌重绘后焦点位于文档 body，不作更强的品牌表单焦点保持结论
- 窄窗口中文标准/大字确认提示在 CSS 502×757 内完整显示；定向窄图表检查保留 421×240、七个标记及可读的 14 px 标签，未重跑全部图表/主题组合
- 所有虚构品牌和数据均已恢复。最终浏览器为桌面 1180×757、默认品牌、深色主题、标准文字、引导隐藏且无高亮目标。**该浏览器检查未实际测试存储不可用，其失败/恢复证据仍属于合成测试**

这些观察未覆盖实体手机、真实 320/390 px 浏览器视口、登录态 Vue 应用、真实相机、通知送达、屏幕阅读器或操作系统减少动态效果设置。源码层面的窄尺寸与动效测试不冒充相应浏览器/设备观察。截图支持上述观察；不宣称本次提供了新的录屏交付物。

## 分段实测稳定性观察

组件观察执行真实中英文日记、健康报告及血压录入逻辑与 Vue 响应式状态。日记/报告实例跨循环保留，在生命周期检查后有意替换；血压使用生产录入模板、真实 Element Plus 表单/校验和合成宿主树。API、报告 DOM/画布/URL、底层输入控件与数据采用受控适配。15 秒节奏是合成工作负载频率，不是人类使用模型或浏览器/服务端压力测试。

- **第一段：15:05:36.800–15:20:07.000 UTC**，实测 **870.200 秒**，完成 **58 个循环 / 3,830 次断言**。该段在源码更新间歇中中断，最后空闲边界通过资源检查，但**未验证最终卸载清理**；最后运行快照仍保留四个 Vue 作用域、两个血压挂载及观察心跳
- **间隔：1,781.601 秒（29 分 41.601 秒）**，从最后核实观察到恢复，不计入实测时长
- **第二段：15:49:48.601–21:52:00.007 UTC**，实测 **21,731.405 秒（6 小时 02 分 11.405 秒）**，完成 **1,448 个循环 / 95,955 次断言**；在要求的边界自然结束，通过进程内最终卸载清理检查

两段合计 **22,601.606 秒（6 小时 16 分 41.606 秒）、1,506 个完成循环、99,785 次断言**，总计按未舍入的单调时钟测量计算。**意外产品、测试不变量及清理断言失败均为零**，第一段中断另行保留。两段均未记录超过 60 秒的观察心跳间隔，最长分别为 30.079 秒与 30.075 秒；段内未检测到受监测源码变化，第二段八个受监测文件哈希与最终运行代码一致。先前时长不追溯覆盖后续改变的代码，也不代表全部应用组件，不能表述为八小时不间断测试。

工作负载覆盖乱序读取、保存中断、不可变数据快照、新草稿锁归属、失败保存重试、重复提交抑制、报告转换中断、旧汇总拒绝及 URL/图表清理。两段共包含 **296 次仍有待完成操作时的卸载检查**、**1,002 次预期图表渲染失败清理检查**。短期预检的时长、循环及断言不计入上述总数。

第二段最终受跟踪资源平衡如下：

- 计时器：**创建 185,637 = 触发 135,833 + 取消 49,804**；独立观察心跳也已清除
- API/校验操作：**开始 72,972 / 完成 72,972**
- Vue 作用域：**创建 292 / 停止 292**；血压挂载：**146 / 146**
- 导出 URL：**创建 2,896 / 释放 2,896**；图表实例和临时图表节点分别为 **964 / 964**
- 最终存活的受跟踪计时器、API 操作、作用域及血压挂载均为**零**，URL/图表/节点差额也为零

这仅验证第二段最终清理，不能消除第一段最终清理未核实的限制。最终清理前，各空闲边界按设计保留活跃组件作用域/挂载。

内存在显式垃圾回收后采样。第一段堆为 **25.63→27.31 MiB**，RSS 采样范围 **106.39–111.20 MiB**。第二段堆为 **25.67→25.65 MiB**，范围 **25.25–28.32 MiB**；RSS 采样范围 **106.84–113.67 MiB**，末值 **113.55 MiB**。这些进程采样与跟踪平衡仅用于诊断，不是生产性能基准，也不能证明不存在任何泄漏。

## 性能测量与后续机会

- **延后捕获依赖：** 针对 f50fd19 捕获分包改动，受控归档源码前后构建及独立静态导入分析复现首屏 JS/CSS gzip 减少：英文 **47,225 字节**（533,181→485,956），中文 **47,231 字节**（542,125→494,894）。懒加载透析依赖闭包仍包含捕获库，所有输出静态导入均可解析；拆包使全应用 gzip 增加 **615/567 字节**。这是逐文件 level-9 gzip 之和，不是 HTTP 传输、FCP、LCP 或浏览器速度测量。[永久分包测试][chunk-cn]
- **剩余分包大小：** Element Plus/ECharts 的既有大分包提示仍存在。进一步导入或 tree-shaking 优化可单独测量，不以已证实加载故障为前提
- **大列表查找：** 最初仅测查找表达式的审查证实，等长 100/1,000/5,000 项合成列表需 5,050/500,500/12,502,500 次比较。已发布计算属性索引移除反复线性扫描。受控 2,000 项前后对照记录基线 2,001,000 次比较；新实现首次构建读取 2,000 次 ID，Map has/set/get 各 2,000 次，并逐预约检查一次 NaN；未变缓存读取为零次 ID 读取及 2,000 次按键查询。未测量 Map 内部操作
  - 重复 ID 保留首项，包括空标题；ID 类型/身份不强制转换，排除 NaN，+0/-0 匹配，原链接可见条件与回退保留。响应式替换、插入/删除/重排、ID/类型变化、问题链接及标题更新通过 22 项专项检查与独立复查。[永久查找测试][lookup-cn]；[当前查找源码][care-source-cn]
  - 最终 Node 基准中，2,000 项响应式首次计算的中位耗时为英文 429.407→4.335 ms、中文 420.268→3.392 ms；缓存读取为英文 426.855→0.596 ms、中文 424.588→0.658 ms。每条件一次预热、三次采样。最终测量包含计算属性投影/索引构建，不能与较早仅测表达式的耗时视为同一方法。不宣称浏览器/DOM/帧耗时、真实列表分布或用户可见提速
- **偏好与轮询范围：** 不受支持的已存 care-mode 字符串仍可能使两种角色均未选中，可作为局部易用性改进。照护中心隐藏标签页轮询保持不变，未证实自然重复请求重叠
- **更广泛验证：** 真实设备尺寸、屏幕阅读器、打印分页、真实文件下载、在线后端联调及通知送达需另行针对实际环境检查

## 范围与保留限制

- 已发布 Pages 是虚构数据、无后端的演示；登录态 Vue 应用及后端与之分开。本次没有部署后端服务
- 后端回归/打包使用测试依赖与合成数据，不是新增后端授权审查、渗透测试、安全认证或临床验证
- 未使用真实患者数据，未修改或验证临床阈值、剂量规则、诊断、AI 解读或临床建议
- 客户端请求归属校验抑制旧界面回写，不能撤销已发出的服务端写入
- 真实浏览器宽度仅为上述实测 CSS 尺寸；更小的源码测试夹具不证明真实 320/390 px 或实体手机覆盖
- 存储恢复仅限已测试偏好及同实例提醒历史，不保证全面禁用存储的登录会话、跨标签页或重载去重
- **完成时序：** 观察持续至原定 **21:52 UTC** 边界，于 **21:52:00.007 UTC** 结束；新执行的最终回归在 **21:52:44.970 UTC** 结束，标准公开资源验证至 **21:54:03.977 UTC**，随后完成最终证据核对。50 项目标均已完成，上述范围限制保留

[nav-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/healthAnalysisNavigation.test.mjs
[patient-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/currentPatient.test.mjs
[specialty-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/patientSpecialtyNavigation.test.mjs
[dialysis-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/dialysisPatientContext.test.mjs
[bp-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/bpPatientContext.test.mjs
[diary-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/nutritionDiaryContext.test.mjs
[report-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/healthReportContext.test.mjs
[care-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/careEditorLifecycle.test.mjs
[edit-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/medicalEditorLifecycle.test.mjs
[upload-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/medicalUploadLifecycle.test.mjs
[ocr-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/medicalRecordItems.test.mjs
[compress-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/imageCompression.test.mjs
[nutrition-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/nutritionPatientContext.test.mjs
[date-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/diaryDateAndDisplay.test.mjs
[notify-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/notificationLifecycle.test.mjs
[chart-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/chartAccessibility.test.mjs
[appearance-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/appearanceChart.test.mjs
[healthappearance-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/healthAppearance.test.mjs
[a11y-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/accessibilityControls.test.mjs
[preferences-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/preferenceRecovery.test.mjs
[list-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/medicalListLifecycle.test.mjs
[read-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/medicalReadLifecycle.test.mjs
[poll-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/pollingOwnership.test.mjs
[chunk-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/buildChunking.test.mjs
[care-source-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/src/views/CareCenter.vue
[upload-source-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/src/views/MedicalRecordManager.vue
[vitals-source-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/src/components/VitalsTrendPanel.vue
[demo-responsive]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/responsive.test.mjs
[demo-chart]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/chartResponsive.test.mjs
[demo-a11y]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/accessibleInteractions.test.mjs
[demo-motion]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/reducedMotion.test.mjs
[demo-parity]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/auditGuideParity.test.mjs
[demo-plural]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/statsPlural.test.mjs
[lookup-cn]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/cn/frontend/tests/careAppointmentLookup.test.mjs
[demo-guide-context]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/guideConsultationContext.test.mjs
[demo-storage]: https://github.com/wangdj104/tx-analysis-service/blob/2fc8c21e9f74897c4139d8337a585f6e89efff4c/demo/tests/storageRecovery.test.mjs
