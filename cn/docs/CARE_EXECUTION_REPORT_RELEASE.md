# 照护执行报告发布证据

**状态：最终软件及明确限定的导出／视觉／媒体验收 PASS。** 2026-10-05 UTC 冻结；使用确切5b18运行源、EN第一次及披露一次同源 CN 任务重跑后的 CN第二次。全部22项在下述分层范围／限制内通过，后续仅证据文档发布单独核对。后端**尚未生产部署或启用**，`CARE_PLAN_ENABLED` 仍默认 `false`。

已接受被测运行提交 `5b18a6d9043cf65a34b71eb7644ee5a852b407b2`，完整树 `86405f52c3902691ab25b31fbe73a5114e30bb66`；仅测试实现 `59417c4e33a251c45e0752c0aae93f5784a174e4` 与本地协调 `7571bf9c6138a8ea0cc3bba1c4a074c41293e464` 完整树相同。[确切源 CI 37262008418](https://github.com/wangdj104/tx-analysis-service/actions/runs/37262008418) 七个选定必需结果已成功：沿用首轮六个成功及相同源一次 CN 第二次重跑，[机器可读记录](../../docs/verification/care-execution-report.json)冻结最终接受源及限定证据。旧绿候选 `e670a7c9fd27dbd4c54956c821f22afde06d5895`／树 `c685141eb99ec57468b59158fe90088427b1d58e`／运行 37257169838 保留为已核对支持性历史。后续文档提交与被测运行分开，[前期协作发布](CARE_PLAN_COLLABORATION_RELEASE.md)保留独立历史验收。

已批准要求基线：[照护执行报告设计](https://github.com/wangdj104/tx-analysis-service/blob/e670a7c9fd27dbd4c54956c821f22afde06d5895/docs/superpowers/specs/2026-10-04-care-execution-report-design.md)，SHA256 `8f4df780222b7e47ceb58f0b002500bfde58e3b18392ef9762c09424fbbc868f`；规格历史上的实施前措辞不能替代本发布记录。

## 已实现范围

报告从已认证患者的已发布协作计划生成最小必要只读复核辅助，提供当前快照、期间活动及 EN／zh-CN 的 HTML、PDF、当前事项 CSV、期间事件 CSV。不新增业务表、持久报告、后台队列、公开文件地址、长期下载 token 或临床写操作；不发送照护通知，不修改回执、复核、证据、授权或原记录。

入口包括有权家庭照护医生计划事项、有效分配医生工作台、护理跟进及计划详情。专用路由 `/care-plans/reports?patientId=<id>` 可带 `planId`，计划详情预设单计划。旧摘要／健康报告／导出页面仅在原权限下嵌入；CARE_PLAN-only 不扩大旧综合菜单／接口权限。包含照护的打印重新读取旧摘要及照护并核对上下文，不宣称跨模块原子临床快照。

现有 EN／CN 公开静态演示仍是前期虚构场景展示，本期没有新增执行报告模拟器或后端部署，原演示源也进入运行身份比较。下载不是处方、医学证明、匿名化、可恢复档案、医生已读确认或已验证临床结果。周期临床排程、新证据上传、临床效果、供应商投递及完整平台完成不属于本次验收。

## 当前与期间口径

- 当前 N 是 ACTIVE 计划当前已发布版本中唯一稳定事项 ID 集合，OPEN、NEEDS_HELP、SUBMITTED、CONFIRMED 之和等于 N；零事项不变成完成率。当前困难、待补充、逾期、待复核及可读 OPEN 问题不因超出期间而消失。
- 期限前提交在跨期限后仍待医生复核；退回／再次提交保留追加历史并使用正确最新等待起点。等待时长依据数据库 `currentAsOf`，不使用移动浏览器时钟；`generatedAt` 单独表示生成时间。
- 期间按 recorded_at 与 IANA 日界半开区间选择，实际执行时间独立，补录按记录时间。默认 30 个当地日历日期，成对明确日期允许含两端 1–366 天且结束不能在未来。UTC／亚洲／夏令时／闰日／边界有快速测试，真实角色浏览器夹具使用 UTC；允许受支持无歧义别名，拒绝歧义缩写及裸偏移。
- 事件数与不同事项数不同，重复提交及计划级事件分别保留；不能相加各类不同事项数，也不能以当前 N 为分母。已终结单计划可 N=0 而保留历史。
- 修订不将旧回执／确认复制到新事项，保留原版说明、身份、历史角色、SELF／ASSISTED、实际／记录时间、困难、跟进、复核。持久化 COMPLETED 显示“已关闭”且保留代码；SELF 或 CONFIRMED 不证明身份或临床改善。
- 当前可编辑问题分别标注权限、零条、单计划未纳入及已知／未知状态，已记录答复不是独立医生确认或完整答复历史。旧时间保留 LEGACY_UNZONED；所有自由医嘱、备注、问题、答复和意见保持原语言。

## 角色来源范围与撤权

| 身份 | 当前必要权限 |
| --- | --- |
| 记录所有者 | 有效账号／角色及拥有的有效患者记录；本人声明不是独立身份验证 |
| 分配医生 | 当前有效医生角色及有效患者分配 |
| 家属 | 当前家属角色及明确有效 CARE_PLAN 授权；READ 足够下载 |
| 护理 | 当前有效护理角色、有效未过期分配及明确有效 CARE_PLAN 授权 |
| 管理员／外人 | 角色、菜单或知道 ID 不提供临床捷径 |

家庭成员身份及旧空白／全模块授权不隐含 CARE_PLAN，报告 READ 不提供写或复核权。可选问题按原全记录／分配医生规则授权，护理来源身份不能成为旧完整记录捷径。CARE_PLAN-only 不读取无关用药／病史／问题正文或问题数量。

各证据引用另需原 MEASUREMENTS 或 MEDICAL 范围及患者／来源匹配。受限仅说明存在记录；有权引用包含允许类型／标识／标题和固定需认证位置，不带数值／正文／附件或外部下载链接。聚焦打开来源再次核对当前归属／范围，包括冷路由及会话变化。

正文采用独立、实际有效的只读 REPEATABLE_READ 快照，首次患者表读取取得数据库 UTC 时间。权限在投影／渲染前和完整响应缓冲后使用独立新鲜 READ_COMMITTED 读取。基础失权拒绝全部，可选已纳入范围失权返回 REPORT_ACCESS_CHANGED 并丢弃准备字节；新增权不会改动既有投影。有权零问题仍参与最终检查。最终检查／交付之后撤权不能收回网络或已保存副本，不承诺更强保证。

## 实际接口与失败契约

路径相对于配置 `/api` 基址，两类 POST 都只读且不使用 commandKey／expectedVersion。

| 方法与路径 | 结果 |
| --- | --- |
| POST `/care-plans/reports/preview` | HTTP 200，Result 包装类型化 DTO 及完整性信息 |
| POST `/care-plans/reports/export` | HTTP 200，完整缓冲 HTML／PDF／actions_csv／events_csv 附件 |

只接受单个 `application/json` 对象：`patientId`、可选 `planId`、成对可选 `fromDate`／`toDate`、必填 `timeZone`／`language`；导出另需 `format=html|pdf|actions_csv|events_csv`，预览拒绝 format。重复／未知键、尾随 JSON、非法 UTF-8、查询参数、无效枚举／ID／日期／时区和未来日期拒绝。不存在、草稿、无权或跨患者计划一律 ACCESS_DENIED，不暴露存在性。报告 schema 1 与工作流／家庭备份版本不同，DTO 仅最小字段，不是完整 Patient／PatientClinical。

| HTTP | 错误 | 处理 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 修正输入；超过 366 天属于输入错误 |
| 403 | ACCESS_DENIED | 不显示零条成功 |
| 404 | FEATURE_DISABLED | 功能保持关闭 |
| 409 | REPORT_ACCESS_CHANGED | 按新鲜可选权限重新生成 |
| 422 | REPORT_LIMIT_EXCEEDED | 显示 errorCode、数值 limit 及 CURRENT_ACTIONS／PERIOD_EVENTS／QUESTIONS／SOURCE_TEXT_BYTES／OUTPUT_BYTES |
| 500 | REPORT_DATA_INCONSISTENT | 异常关系／状态失败关闭 |
| 503 | REPORT_RENDER_UNAVAILABLE | PDF 资源／字符不支持时可选 HTML |
| 503 | REPORT_TIMEOUT | 缩小范围或重试，不给部分成功文件 |

其他错误仅暴露 errorCode，不带输入、临床正文或原异常。未认证沿用原认证层。响应为 `Cache-Control: no-store, private`；附件包含准确 Content-Type、安全 Content-Disposition 及 `X-Content-Type-Options: nosniff`。文件名采用固定前缀、语言及生成时间，不含患者姓名／诊断／自由文；成功必须完整生成后才提交响应头／正文，无提前流出或持久缓存。网络失败独立处理。

## 导出字体隐私及资源限制

HTML／PDF 单份选一种界面语言，自由原文不翻译；动态 HTML 转义。PDF 仅固定本地模板及已验证配置字体，拒绝外部实体／网络／任意本地资源。官方 CI 字体为完整文泉驿微米黑 face 0，不裁字或改映射，预期 TTF SHA256 为 `1c6503e656e7bda78d5958185a49fa9c7692c0446b7a3031a14655095acc8b61`；候选双语准备／许可证制品已核对：来源 TTC SHA256 `2420e8078af796b19a3f6ef13de527a1a91c1e7171eea115926c614ced1009b3`，完整 face 共 49,531 字形／34,600 映射码位；有保护原生／应用／导出任务接收准备字体。未单独取得原 JVM 字体使用日志。字体配置及可用性依安装环境而异。受支持拉丁／中日韩示例含英文中的中文有测试，检查全部实际可见字符；未支持复杂／RTL／组合／补充文字或缺字以 REPORT_RENDER_UNAVAILABLE 失败并提供 HTML 替代，不承诺通用 Unicode／字体／操作系统支持。

PDF／打印对长原文换行并重复表头。hash、非空栅格、提取文字或媒体解码属于完整性核对，不等于人工视觉审查。最终逐页检查须明确所有覆盖页及精确 RGB＋尺寸复用，不做模糊／时间戳遮蔽复用。

CSV 事项／事件行模型独立，固定本地化表头及稳定代码、BOM、严格 UTF-8、引号与多行单元格。非空行重复范围／时区／语言／版本／生成时间；公式／控制前缀文本字面化不改源记录，CSV 不是原始字节归档。空 CSV 严格 BOM＋表头，无虚构元数据行；实际下载点击前页面展示 0 行及实际范围／时区／语言／版本／时间，文件名保留语言／时间。空文件不自含离线元数据。

| 生产报告限制 | 数值 |
| --- | ---: |
| 当前事项 | 1,000 |
| 期间公开事件 | 5,000 |
| 纳入当前问题 | 200 |
| 纳入 UTF-8 原文 | 8 MiB |
| 输出文件／响应 | 32 MiB |
| 查询／渲染／最终授权总预算 | 30 秒 |
| 新报告客户端超时 | 45 秒 |

额外一行探针超限失败，不静默截断。保守 8 MiB 原存储单元预检在分配前执行，可能拒绝仅部分内容会显示的旧单元；累计纳入文本另受约束。actions_csv 不读取期间／问题，events_csv 不读取当前详情／问题；完整预览超限不禁止未受影响的小 CSV。可缩短期间、单计划或独立更小格式。服务端取消协作执行，不保证不可中断库立即停止。

患者／计划／选项／身份变化使旧 DTO、错误、导出元数据及 Blob URL 失效，退出／撤权／卸载立即清除。分发、转换／扫描、元数据发布及点击前均核对所有权，生成时禁用重复控件。关闭拒绝晚到客户端响应但不保证服务器停止，正文不放 URL、浏览器存储、遥测或日志。下载不表示医生已读／复核／收到。

## 安装与恢复

不新增报告专用业务迁移或扩大权限。分别为根目录／CN 使用[协作发布](CARE_PLAN_COLLABORATION_RELEASE.md#安装旧记录与恢复)中已审阅前置条件及适用幂等升级；安装不启用 CARE_PLAN_ENABLED、不分配护理／授予患者权限。本里程碑未执行生产迁移／部署／启用。

家庭档案版本 1 及原表清单不变，协作计划／版本／事项／回执／事件／证据、护理分配、授权／命令／outbox 和历史仍不在该有限档案内；报告不能恢复这些数据。完整数据库及附件存储备份／恢复独立，CI 完整合成 MySQL 恢复不改变家庭档案能力。

## 验证记录

以下完整本地门槛绑定已审阅 `6ab6db8eca1fd0fb00c02b14ddded818e9f2f129`／树 `c685141eb99ec57468b59158fe90088427b1d58e`，与发布候选字节等价。编制时独立核对保留摘要。使用已安装 JDK17／Maven3.9.11 包装器执行根 make test／build 等价步骤，不宣称执行字面 make 命令或本地原生／浏览器验收。

| 本地门槛 | 实际结果 |
| --- | --- |
| EN／CN 完整后端测试 | 各 595：594 通过，1 个未配置 CarePlanMysqlIntegrationTest 明确 SKIPPED，0 失败／错误 |
| EN／CN 测试后打包 | BUILD SUCCESS；打包故意不重跑测试 |
| EN 干净安装前端测试／构建 | 不变锁 npm ci，1,379 通过，0 失败／取消／跳过，生产构建通过 |
| CN 干净安装前端测试／构建 | 不变锁 npm ci，1,386 通过，0 失败／取消／跳过，生产构建通过 |
| 两演示语法及合并演示／CN／脚本 Node | 376 通过，0 失败／取消／跳过 |
| 字体准备测试 | 3 通过 |
| 语义修正聚焦 RED→GREEN | 最终前端 46 控制通过／34 预期语义失败 → 80／80；新后端生命周期各 2 预期断言失败 → 受影响各 53／53 |

最初 npm-ci 环境／缓存失败及同时进行的已安装 Playwright 源缺失脚本失败保留为历史失败，完整不变锁重试通过。前端测试环境诊断及中途过宽文字断言不是最终产品 RED，不通过依赖／锁变更或削弱断言掩盖失败。

独立全分支审查无 Critical／Important，限定 fix3 及 M10–M13 分别批准规格／质量；OWNER、可选 Today 完整性、受支持别名及持久生命周期标签已处理。审查批准不表示托管原生／浏览器验收。

**已确认历史绿候选门槛，不是最终验收：**CI 37257169838／`e670a7c9fd27dbd4c54956c821f22afde06d5895` 七个终态任务成功。[EN 原生／浏览器任务](https://github.com/wangdj104/tx-analysis-service/actions/runs/37257169838/job/111596643137)与[CN 任务](https://github.com/wangdj104/tx-analysis-service/actions/runs/37257169838/job/111596643144)均通过两轮有保护 schema／恢复步骤；失败即退出的循环各语言执行两次独立轮次，第二轮还要求真实浏览器／完整恢复。源声明十个原生方法，包含报告验收及完整恢复；未单独取得原任务日志／JUnit 数值总量，不宣称逐轮独立实测数值。

真实安全结果文件显示**各语言 58／58 通过**、无全局错误，修正 case022 两语言皆 passed／error none／最终阶段 teardown。结果 SHA256：EN `cb489867cd1342a7b8659c14ce48df360379cddf691ddec34eb2b387d8d41037`，CN `490876e310b5b0f0d8483bcd523240d78205a562b152d1d7aede1e5a29cb9c79`。源 retries=0，固定身份无失败／跳过／未运行；真实构建 Vue／Spring／一次性 MySQL 保留真实响应，无模拟成功。152 原报告已取得并核对确切来源／运行／ZIP／hash／成员；这些 e670 结果为历史支持，选定 5b18 EN1／CN2 结果及剩余冻结范围见下文。

## 来源对话框稳定捕获门槛

e670a7c 的浏览器场景及七个 CI 任务已通过，但其 390px 病历来源截图／视频在约 300ms 打开过渡期内就关闭对话框，不能证明过渡后的稳定可读性。诊断未建立生产缺陷或生产变更；六路径限定仅测试修正已独立审阅并以 `5b18a6d9043cf65a34b71eb7644ee5a852b407b2` 发布，其确切 CI 37262008418 及 EN1／CN2 成功证据已建立，两语言真实390px／桌面来源 PNG 已不透明可读。e670 候选记录为历史支持；没有等待或计划的新提交／运行，不宣称全部手机／屏幕完美，最终 CN 导出／媒体核对已完成，仅审阅后的文档提交／发布另行核对。

修正保留生产 UI／CSS／API、锁、保护、工作流、预算、场景身份／标题与截图名称；真实浏览器在捕获／关闭前轮询过渡类、透明度／动画／变换、视口几何及标题／确切来源单元／关闭控件的 elementFromPoint。本地谓词控制各语言 22／22，最终源新前端 EN 1,401／CN 1,408、两构建及共享脚本 109 通过。后端未变且未重跑，原各 595 本地后端（1 原生跳过）、打包、合并演示／脚本 376 及字体 3 明确作为不变输入复用；不宣称新本地 npm ci／原生／浏览器／布局通过。保留非阻塞正则敏感性：当前捕获前调用已核对，但单元定位可能因关闭也调用 readiness 而漏检前一调用删除，真实像素仍必需。

## 同源 CN 任务重跑

运行 37262008418 第一次通过五个标准任务及 [EN 必需原生／浏览器任务](https://github.com/wangdj104/tx-analysis-service/actions/runs/37262008418/job/111610994610)，EN 58／58。CN 为 57／58，仅 case013 在打印前初始患者选择中失败（`test_error`、`test-body`、`cn/frontend/e2e/reportBrowser.mjs:22:51`）；菜单移动／关闭但未选中，确切点击及生产根因未确认。新增稳定病历来源检查全部通过，实际 CN390px 来源 PNG 已清晰不透明，但不能关闭另一失败场景。

2026-10-05 04:43:47 UTC 已接受仅[失败 CN 任务](https://github.com/wangdj104/tx-analysis-service/actions/runs/37262008418/job/111610994889)一次有限重跑，**相同 5b18a6d 源**且不改代码／断言。重跑已成功，真实选定 CN 结果 58／58（含 case013／case022），新 CN ID／时间戳与失败轮分开选定；保留第一次结果／制品身份，相同名称／源 SHA 不能将失败轮文件当重跑证明。CI／原生／浏览器使用同一源 EN 第一次／CN 第二次证明，独立最终制品／视觉／媒体／源冻结仍必需。Playwright 重试仍为零，这次明确披露的工作流任务重跑另计。 GitHub 将七行最新元数据都分配第二次身份，但六个成功任务保留原 04:05 开始／结束时间，是沿用成功结果而非七任务重新执行。当前 [CN 重跑](https://github.com/wangdj104/tx-analysis-service/actions/runs/37262008418/job/111619160580) 04:43:51 UTC 开始；沿用 [EN 结果](https://github.com/wangdj104/tx-analysis-service/actions/runs/37262008418/job/111619193666)反映原 04:05:24–04:38:53 UTC 执行。

## 已确认选定运行与 EN 证据

运行 37262008418／源 `5b18a6d9043cf65a34b71eb7644ee5a852b407b2`／树 `86405f52c3902691ab25b31fbe73a5114e30bb66` 七个选定必需结果成功，相同两轮有保护原生／完整恢复在沿用 EN 第一次及 CN 第二次通过，未单独取得原 JUnit 数值。真实安全结果 EN58／58 SHA256 `893ae24a110b4f01aa4e7aa29f19980592da207d12f761f35c554afa9f7f5430`、CN58／58 SHA256 `0444a1d47716a83765d5dd22451df485855ad6caac862be7f3fa7b62f9a88f9b`，无全局错误。Playwright retries=0，单 CN 任务重跑如上披露。十个接受核心 ZIP 与 GitHub 字节／摘要匹配，50 个接受制品元数据身份绑定源／运行，各下载／检查范围见机器记录；两份选定字体来源匹配官方完整 TTF 摘要。

EN 独立导出审查已完成：76 原文件／内容／边界、20 PDF／376 页次，92 新图直接打开、212 精确重复、72 精确基线 RGB／尺寸复用，无可见 PDF 缺陷；封存摘要 `981313d50daf55aecfc46af32f396894c952c0195875627355173e5bec611430`。EN 选定媒体核对八 ZIP／766 成员、519 PNG 解码及 13 选定 MP4 完整解码，实际观看 55 命名截图／74 核心采样帧；390px／桌面来源对话框确实不透明可读。这是有限人工观看，不是全部视频播放。密集旧检查项 Unit 表头在390px裁切而来源字段／关闭仍可读；整页截图捕获带来短暂灰色／小画面录屏帧，限制及原捕获保留，交付片段连续截取而不称原始未编辑文件。选定 EN1／CN2 合并 PDF／内容／媒体核对完成。

## 已完成选定导出及全部页审查

全部152个选定 EN1／CN2 原字节、独立内容与补充边界检查通过，40 PDF／752页次覆盖238不同 RGB 图；合并审查直接打开92个未匹配 CN 图、映射212精确重复与448精确 RGB／尺寸基线页次（包括全部376个已完成同源 EN 页），不重渲染／重审 EN，不使用容差或时间戳遮蔽。封存摘要 `0a1190fc233baf55337300f31df32b5582d11f5b88456916f31b71387df6aea0`，源／PNG／RGB 全部再次核对，无可见 PDF 缺陷、JavaScript、表单或可操作 URL 注释。HTML 为结构／内容而非单独浏览器像素审查。原报告 ZIP：EN 制品11326076261（3,108,374字节，SHA256 `f57fc92477aeec1fe35da6216f7549cfd8e8b1d29e2c599a4a584fa61d70e0f1`）；接受 CN2 制品11326677949（3,107,665字节，SHA256 `4e6056d7f0f12f3b42571b98def3064772eac4c356b8fb29eefcd6593ecb0933`）。逐文件安全 hash 与接受轮次见机器记录。

## 已完成有限媒体与源冻结

接受 EN1／CN2 媒体核对16 ZIP／1,564成员、1,038 PNG解码及28选定原 MP4完整解码；人工覆盖110命名截图、32主预览、34深入坐标裁图、156核心采样帧、32额外 CN来源／边界样本与18交付样本。六个不透明原生来源 PNG 是最终稳定状态证明；CN视频120帧／4.80秒仅是最清晰近稳定样本，118帧／4.72秒仍有轻微残留，4.84–4.92秒是关闭透明过渡。成功 CN2新打印 PNG 确实包含新旧摘要／照护部分，失败 CN1排除。

保留展示／捕获限制：390px密集 Unit／单位表头裁切；整页截图导致短暂灰色／小页面录屏帧，部分打印视频大多为该捕获状态；某些原手机长 PNG 保留固定导航条；快速自动点击无指针标记。审查是有限像素，不是全视频连续观看或无障碍审计。EN桌面6.4秒、CN手机5.0秒片段连续截取后 H.264转码，无裁剪／覆盖／变速；选定预览为原 PNG 字节相同副本。最终媒体摘要 SHA256 `73cfeb1f425f840cd4789f2b6b8d0dd1872a5b928d207f0c22b01313efff0b87`，人工清单 `f3fe50cfc735e817871f05edef83b029bd15030804d1289485128eb12d93e61a`，交付媒体清单 `6fe3ded431371b2553ff061133c6c6e119fa5ea74dda4f7c96ad70dab3497978`，不包含私有交付身份。

排除精确七个文档路径后，原顺序1,269个 mode／type／blob／path条目 SHA256 为 `969395988c37d5f57288630c6498fe14000f2ac64cbf220342ab5547c2c98e4b`，被测运行与本地协调 HEAD 每项相同。审阅后的七文件索引／提交在提交前后按同一完整清单核对；文档 SHA 由 Git历史定位，远端发布另核对，不包含运行／测试／CI／锁／部署／演示源变化。

## 二十二项验收标准

每项在相同接受运行源的分层范围／明确限制内均为 **PASS**。机器记录对每项全局应用确切运行身份／七任务／原生／浏览器／报告／字体／全部页／媒体／源等价必需门槛，并另列每项具体层次；尤其 5、21 需原生生命周期／两轮／恢复，22 需完整最终证据链。上述本地已执行层及已审阅源，与必需原生／浏览器／制品／视觉门槛分开。下列 Java 定位位于 `src/test/java/org/familyhealthcare/service/`（旧服务测试部分位于 `impl/`）；快速前端在 `frontend/tests/`，浏览器在 `frontend/e2e/`。中文版加 `cn/` 前缀，须分别有结果；脚本为共享仓库相对路径。不以总测试数替代覆盖说明。

### 1. 当前 N 与唯一事项

**PASS 限定证据。** 投影测试核对四类当前状态、ID 去重及零事项；真实角色导出夹具要求四个唯一事项与 1+1+1+1=4，空范围由页面／CSV 控制核对。

测试／检查定位：`CareExecutionReportProjectionTest.currentDenominatorAndReviewWait`, `CareExecutionReportRenderTest.questionsAvailabilityAndZeroCurrentCountsRemainExplicit`, `careExecutionReport.spec.mjs`.

范围限制：事件行数不是当前 N；不计算完成率或临床效果。

### 2. 来源身份与原文

**PASS 限定证据。** 回执／困难／跟进／退回／复核分别保留操作者、历史角色、SELF／ASSISTED、实际／记录时间及原文；实际编译面板控制核对 OWNER 标签修正。

测试／检查定位：`CareExecutionReportProjectionTest.boundariesUnknownQuestionStatusesAndAppendIdentityRemainExplicit`, `CareExecutionReportRenderTest.localeNeverTranslatesClinicalNotes`, `careExecutionReportSemantics.test.mjs`.

范围限制：SELF 是声明而非独立身份验证；跟进是管理记录。

### 3. 待复核与期限口径

**PASS 限定证据。** 投影／界面控制检查基于快照的等待及追加顺序退回／再提交；原生 RECEIPT／RETURN 并发及真实角色夹具提供其他层次。

测试／检查定位：`CareExecutionReportProjectionTest.currentDenominatorAndReviewWait`, `CareExecutionReportProjectionTest.latestStateUsesAppendOrderRatherThanRecordedTime`, `CareExecutionReportMysqlAssertions.consistentSnapshotAcrossConcurrentRevision`.

范围限制：已提交待复核不因跨期限成为患者逾期；最终原生执行单独核对。

### 4. 期间之外的当前待处理

**PASS 限定证据。** 当前待处理／问题独立于 recorded_at 期间筛选；实际夹具／判据核对旧困难／联系、非零逾期／待补充及有权 OPEN 问题。

测试／检查定位：`CareExecutionReportProjectionTest.periodUsesRecordedTimeAndOriginalRevision`, `CareExecutionReportProjectionTest.questionsAndEvidenceAreSeparateScopes`, `scripts/care-report-fixture-oracle.mjs`.

范围限制：M6 仍是直接渲染器夹具深度限制，分层功能证据不表示该缺失夹具已补。

### 5. 修订与终结计划历史

**PASS 限定证据。** 旧版说明／回执保留，当前 N 使用 ACTIVE 当前版本；真实 CLOSE／投影控制保留 COMPLETED 与 PLAN_CLOSED，原生 REVISION／CANCEL／CLOSE 检查新读取状态。

测试／检查定位：`CareExecutionReportProjectionTest.periodUsesRecordedTimeAndOriginalRevision`, `CareExecutionReportProjectionTest.actualCloseLifecycleProjectsCompletedHistoryWithLocalizedExportLabels`, `CareExecutionReportMysqlAssertions.consistentSnapshotAcrossConcurrentRevision`.

范围限制：“已关闭”保留持久代码，不表示治疗成功。

### 6. 事件与不同事项

**PASS 限定证据。** 投影控制包含重复提交、无事项的计划事件、多版本及已终结单计划 N=0，实际 CSV 使用独立事项／事件行模型。

测试／检查定位：`CareExecutionReportProjectionTest.periodUsesRecordedTimeAndOriginalRevision`, `CareExecutionReportRenderTest.csvPreservesTwoRowModels`, `CareExecutionReportMysqlAssertions.consistentSnapshotAcrossConcurrentRevision`.

范围限制：主浏览器夹具发布在期间外，仅角色场景不证明计划级事件；不能相加各类不同事项数。

### 7. 草稿与内部计划排除

**PASS 限定证据。** 查询要求 workflow_version=1、已发布版本及固定公开事件白名单；共同夹具确实含旧内部计划 #1，访问拒绝且投影计数沿用；草稿版本／事件有直接排除断言。

测试／检查定位：`CarePlanTestFixture.java`, `CareExecutionReportAccessTest.planMustBelongToPatientAndHavePublishedCollaborativeRevision`, `CareExecutionReportProjectionTest.periodUsesRecordedTimeAndOriginalRevision`.

范围限制：未找到内部计划在四种实际文件中逐一标记排除的独立断言；精确投影与实际文件检查属于分层支持。

### 8. 时区日期与记录边界

**PASS 限定证据。** 快速契约覆盖 UTC／亚洲、23／25 小时夏令时、Apia 跳过日期、闰日／366／无效／未来边界；投影核对记录／补录边界；编译界面以真实 Intl 检查别名及无效控制。

测试／检查定位：`CareExecutionReportContractsTest.defaultThirtyLocalDates`, `CareExecutionReportContractsTest.dstHalfOpenRange`, `CareExecutionReportContractsTest.skippedApiaDateUsesZoneRules`, `CareExecutionReportContractsTest.strictDatesAndInclusiveMaximum`, `careExecutionReportSemantics.test.mjs`.

范围限制：主真实浏览器导出使用 UTC，不是穷尽夏令时／亚洲时区矩阵；后端与浏览器时区数据库可不同。

### 9. 旧无时区时间与混合文字

**PASS 限定证据。** LEGACY_UNZONED 明确标注；英文标签下中文原文保留，提取及支持字体栅格测试核对；最终字体使用与逐页人工检查须绑定最终运行。

测试／检查定位：`CareExecutionReportProjectionTest.questionsAndEvidenceAreSeparateScopes`, `CareExecutionReportPdfTest.bothLanguagesRenderAndExtractAllText`, `CareExecutionReportRenderTest.localeNeverTranslatesClinicalNotes`.

范围限制：PDF 拒绝未支持字形／塑形／RTL／组合／补充字符，可改用 HTML；通用 Unicode 渲染未经验证。

### 10. 真实 MySQL 一致快照

**PASS 限定证据。** 必需原生门槛使用独立读写连接，在投影读取之间实际提交 RECEIPT、RETURN、REVISION、CANCEL、CLOSE，每语言两轮比较不可变正文及随后新报告。

测试／检查定位：`CarePlanMysqlIntegrationTest.nativeExecutionReportAcceptance`, `CareExecutionReportMysqlAssertions.consistentSnapshotAcrossConcurrentRevision`.

范围限制：本地有保护原生测试为 SKIPPED；H2、发现测试及模拟观察者不满足门槛。

### 11. 实际隔离与新鲜权限

**PASS 限定证据。** 正文 RR 连接状态实际核对，渲染前及完整缓冲后使用独立 RC 权限读取；原生 SNAPSHOT／AFTER_RENDER 交叉六类 BASE／EVIDENCE／QUESTIONS 撤销／过期模式。

测试／检查定位：`CareExecutionReportProjectionTest.independentReadOnlyRepeatableReadSnapshotAndFirstTableTimestamp`, `CareExecutionReportAccessTest.eachCallUsesFreshReadCommittedConnectionAndRestoresOuterSnapshot`, `CareExecutionReportMysqlAssertions.freshPermissionAfterSnapshotRevocation`.

范围限制：原生已提交变化证据是必需最终 CI 门槛，同快照模拟检查不足。

### 12. 跨患者与角色拒绝

**PASS 限定证据。** 共同后端检查覆盖停用／失效身份、患者归属及护理条件；真实 JSON／各格式拒绝覆盖管理员／外人／跨患者／跨计划及撤权，另有聚焦来源防护。

测试／检查定位：`CareExecutionReportAccessTest.disabledActorPatientOrOnlyCurrentRoleDeniesWholeReport`, `CareExecutionReportAccessTest.staleDoctorOrAdminCannotReadQuestions`, `careExecutionReport.spec.mjs`, `careExecutionReportSources.spec.mjs`.

范围限制：浏览器不是每种停用／失效角色与格式的穷尽笛卡尔积，其余层次来自后端测试。

### 13. 只读与最小数据

**PASS 限定证据。** READ 足够，护理需角色＋分配＋授权，生成不写入／通知；原生 SQL 在 1／50 事项下检查预览／事项／事件、窄证据查询及优化键。

测试／检查定位：`CareExecutionReportAccessTest.readGrantIsEnough`, `CareExecutionReportAccessTest.narrowNurseNeverLoadsFullPatient`, `CareExecutionReportProjectionTest.csvLoadsOnlyItsProfile`, `CareExecutionReportMysqlAssertions.reportQueriesStayBounded`.

范围限制：CARE_PLAN-only 不读取无关用药／病史／问题正文或数量，独立 CSV 模式不是完整档案权限。

### 14. 证据归属与准备字节撤权

**PASS 限定证据。** 纳入引用／问题清单保留权限要求，包括有权零问题；基础失权整体拒绝，可选失权以 REPORT_ACCESS_CHANGED 丢弃准备输出；打开来源重新核对患者／模块。

测试／检查定位：`CareExecutionReportAccessTest.reassignmentOrDeletionOfReadableEvidenceInvalidatesPreparedReport`, `CareExecutionReportAccessTest.emptyIncludedQuestionSectionStillRequiresFinalAuthority`, `CareExecutionReportMysqlAssertions.freshPermissionAfterSnapshotRevocation`, `careExecutionReportSources.spec.mjs`.

范围限制：最终检查／交付副本边界无法消除：之后撤权不能收回网络或已保存副本。

### 15. 问题可用性与已记录答复

**PASS 限定证据。** 有权／零条、NOT_AUTHORIZED 与单计划未纳入分开；OPEN／ANSWERED／CANCELLED／未知状态及已记录答复保留当前可编辑旧数据口径；两类 CSV 不含问题。

测试／检查定位：`CareExecutionReportProjectionTest.questionsAndEvidenceAreSeparateScopes`, `CareExecutionReportProjectionTest.boundariesUnknownQuestionStatusesAndAppendIdentityRemainExplicit`, `CareExecutionReportRenderTest.questionsAvailabilityAndZeroCurrentCountsRemainExplicit`, `careExecutionReportUi.test.mjs`.

范围限制：已记录答复不是医生验证或完整历史，并非每种可用状态均在检查媒体中呈现。

### 16. 超限与渲染失败关闭

**PASS 限定证据。** 测试覆盖 1001／5001／201 探针、异常关系、原文／输出预算、工作／队列期限及序列化／渲染／字体／布局／写入失败；浏览器受限预览／空 CSV 检查独立导出，完整缓冲避免部分成功。

测试／检查定位：`CareExecutionReportProjectionTest.currentLimitDoesNotBlockIndependentEventCsv`, `CareExecutionReportProjectionTest.eventLimitDoesNotBlockIndependentActionCsv`, `CareExecutionReportContractsTest.java`, `CareExecutionReportServiceTest.java`, `CareExecutionReportApiTest.java`, `CareExecutionReportPdfTest.java`.

范围限制：原存储单元有保守 8 MiB 预检；测试栅格 256 MiB 总量为事后条件而非实时磁盘上限，并非所有失败均是实浏览器场景。

### 17. 注入与资源安全

**PASS 限定证据。** 转义 HTML、公式／控制安全 CSV、固定需认证来源路由及 PDF 外部／文件资源拒绝均有直接控制；实际攻击原文按文本检查，并用严格字节／语义判据及注释检查。

测试／检查定位：`CareExecutionReportRenderTest.htmlEscapesEveryDynamicField`, `CareExecutionReportRenderTest.dangerousTextIsLiteral`, `CareExecutionReportRenderTest.safeEvidenceLocationsExcludeExternalAndMismatchedTargets`, `CareExecutionReportPdfTest.networkAndFileResourcesAreDenied`, `scripts/inspect-care-report-content.py`.

范围限制：CSV 安全转义保留源记录，但不是原始字节归档或匿名化。

### 18. 元数据与空 CSV 例外

**PASS 限定证据。** 页面／HTML／PDF／非空行包含实际范围、语言、时区、版本及生成事实；空下载严格 BOM＋表头，点击前显示 0 行元数据并使用安全语言／时间文件名。

测试／检查定位：`CareExecutionReportRenderTest.csvPreservesTwoRowModels`, `CareExecutionReportApiTest.java`, `careExecutionReportClient.test.mjs`, `frontend/e2e/reportDownloads.mjs`, `scripts/care-report-fixture-oracle.mjs`.

范围限制：原下载不改写；传输比较仅允许缺一个 BOM 和同一 UTC 瞬时的 GMT／GMT+00:00，不是通用规范化；空文件不自含离线元数据。

### 19. 上下文会话与打印竞态

**PASS 限定证据。** 所有权序号／会话／上下文检查覆盖 A→B→A、选项、卸载、扫描／点击前、退出／登录、历史及来源／打印竞态；fix3 保留真实旧摘要撤权响应与照护 HTTP200，仅控制交付顺序，编译产品控制覆盖两种顺序。

测试／检查定位：`careExecutionReportClient.test.mjs`, `careExecutionReportIntegration.test.mjs`, `careExecutionRevokedPrint.test.mjs`, `careExecutionReportRaces.spec.mjs`, `careExecutionReportPrint.spec.mjs`, `careExecutionReportSources.spec.mjs`.

范围限制：观察 HTTP200 不证明释放拒绝前照护 DOM 已完全挂载；客户端取消不保证服务器取消，最终 case022 实运行结果已通过并单独记录。

### 20. 角色视口焦点打印与视觉

**PASS 限定证据。** 每语言八个角色×视口场景请求两种输出语言与四格式，四个正向语言×视口打印场景核对新读取、焦点、回调及清理；最终实际文件／逐页／帧审查另成证据层。

测试／检查定位：`careExecutionReport.spec.mjs`, `careExecutionReportPrint.spec.mjs`, `careExecutionReportSources.spec.mjs`, `CareExecutionReportPdfTest.java`.

范围限制：390px 与桌面范围有限；媒体解码／非空／hash 不证明人工视觉质量或全部视频完整观看。

### 21. 旧功能回归与默认关闭

**PASS 限定证据。** 双语完整后端／前端／构建／演示／脚本包含旧导出、状态机、时间线、通知、授权及备份契约；原生两轮／完整恢复与原十个浏览器场景仍必需，M11 明确保留可选今日数据拒绝／不可用的未完整状态。

测试／检查定位：`CareExecutionReportApiTest.featureOffReadsNoClinicalTablesAndConstructsNoExecutor`, `HealthExportWorkflowTest.java`, `ReportPdfFontTest.java`, `CarePlanAuthorizationTest.java`, `CarePlanTimelineTest.java`, `CarePlanNotificationTest.java`, `familyHealthReload.test.mjs`.

范围限制：两语言默认 false，无新业务表、备份格式改变、生产迁移／启用或新增公开报告演示。

### 22. 同一最终运行时与证据冻结

**PASS 限定证据。** 验收需同一确切最终运行提交／树、七个终态 CI 任务、两份 58 场景结果、真实原生／报告／字体／媒体证据与明确视觉范围；后续七路径文档提交须保留其余全部 mode／type／blob／path 项。

测试／检查定位：`.github/workflows/ci.yml`, `scripts/care-plan-browser-outcomes.mjs`, `scripts/package-care-report-artifacts.mjs`, `scripts/package-care-plan-browser-artifacts.mjs`.

范围限制：旧协作绿 CI 与暂存文件 result=passed 不构成新报告最终验收，失败／未运行历史明确保留。

## 最终制品视觉范围与历史尝试

选定 5b18 EN1／CN2 原报告／结果／字体 ZIP 下载及完整性**通过**，稳定对话框仅测试修正、确切 CI 及真实不透明可读来源 PNG 已建立；EN 独立内容／全页／媒体完成，CN 媒体核对及未排除源检查已完成，文档发布单独处理。旧 e670 捕获缺口为历史，不是新运行阻塞。当前目录静态推导预期各语言 76 个原报告文件（合计 152）：各 20 PDF、16 HTML、20 事项 CSV、20 事件 CSV；16 种角色／输出语言组合提供 64 个 UI 文件，空／受限场景提供 8 CSV，4 个仅渲染器 PDF 提供长文／覆盖样本。接受 EN1／CN2 清单／压缩包精确各76／合计152且全部独立内容／视觉检查通过；可选撤权 HTML 单独检查，不是额外暂存项。

最终记录须含已核对 GitHub 制品 ID／名称、运行／源 SHA、压缩包字节／摘要、安全报告清单摘要及每个允许合成文件的格式／版本目录／语言／场景／hash／字节／页或行数。结果须核对每语言全部 58 固定身份的终态／阶段；文件 result=passed 不代表场景正文／清理通过。字体准备／许可与实际配置摘要单独核对。GitHub 保留期限后制品可能不能下载，不变 hash／身份仍可核对，但不保证永久可下载。

历史 [37250920072](https://github.com/wangdj104/tx-analysis-service/actions/runs/37250920072)／运行 `122ef3fd1bbecfce8d69fa7ede88526ffb4bd3c8` 各语言 57／58，通过暂存 76 文件但 case022 在 teardown timedOut。独立旧导出审查核对 152 原字节／内容／语义及 40 PDF／752 页次：190 不同图直接打开，466 页次与其精确重复，96 页次精确 RGB＋尺寸复用 48 个已检查图，共覆盖 238 不同图。这是历史观察，不是最终运行页面；最终文件须重新确认身份／内容，仅精确 RGB＋尺寸相等可转移视觉观察，每个改变页直接检查。旧 HTML 为结构／内容检查而非独立浏览器像素审查，候选修正打印已在两份真实结果中通过，最终5b18全部页及有限媒体检查单独通过，此旧运行仅为历史。

更早 `15b59a8ea076329401a28c0ebacbe629e279fdd0` 两个原生／浏览器任务失败。限定修正及确定性探针是支持性诊断，不是恢复的原失败断言或实运行验收。fix3 修正合法取消兄弟请求之后保证响应的错误等待假设，控制真实响应顺序仍保留拒绝／无弹窗／无旧正文及清理断言。照护 HTTP200 事件不独立证明释放旧拒绝之前新照护区域已挂载。旧协作绿运行 37137746134 和早报告检查点不能代替最终证明。

制品仅选择合成原文件、有限 PNG／WebM／MP4 及安全清单／结果／字体来源，不含凭据、真实健康数据、raw 日志／trace／HAR／错误／存储／请求 dump 或签名／查询 URL。生产 32 MiB 输出与验收 30 MiB 暂存载荷分组（在 32 MiB 下载界限下预留 2 MiB ZIP／清单开销）、224 项／128 KiB 清单、页／几何／子进程限制分开。测试栅格总 256 MiB 及单页 30 MiB 在产生／读取之后检查，属于事后条件而非实时磁盘上限。

## 保留的非阻塞处理结论

- M4：最新摘要仍有多余但有界版本／标题／医嘱预检读取，接受有限低效，未证实错误输出
- M5：固定单 IN 列表占位替换依赖维护，已审阅当前调用绑定正确
- M6：非零逾期／待补充／期间外待处理的直接渲染器夹具仍缺，真实 SQL／浏览器／判据分层覆盖
- M8：密集 HTML／CSV 辅助保留可读性／审计成本，不做广泛格式重构
- M9：GC／终结器警告清理测试是非确定性补充，保留确定性文档资源所有权、失败写入及描述符控制
- Task9 M1：栅格字节总量是事后检查而非实时存储保证，未来逐页硬化不在本次发布

先前关闭项保持关闭，M10–M13 已在审阅运行时处理；不把这些限制默认为新修复或穷尽验证。

## 仅证据文档提交身份

最终运行提交与后续文档提交分开，仅冻结七个精确路径：README.md、cn/README.md、docs/USER_GUIDE.md、cn/docs/USER_GUIDE.md、docs/CARE_EXECUTION_REPORT_RELEASE.md、cn/docs/CARE_EXECUTION_REPORT_RELEASE.md、docs/verification/care-execution-report.json。旧协作证据及全部运行／测试／CI／依赖锁／部署／演示源不排除，必须保持不变。

逐项比较运行与文档提交其余 `git ls-tree -r` 条目，保留 mode／type／blob-ID／path、原顺序、LF 连接及末尾 LF，记录数量／SHA256。文档变化使完整树 SHA 不同属正常。文档提交由 Git 历史定位，获授权非强推发布后核对远端 main 身份／源等价；草稿计算、本地提交或旧远端查询不是未来发布证明。上述精确1,269条源身份／hash已核对，文档提交在审阅后由 Git历史定位，远端发布由控制任务另核对；本证据不提前宣称未来远端状态。

## 部署与验证边界

最终软件及所列导出／视觉／媒体验收在5b18通过，文档发布另行核对；确切 CI／原生／浏览器及不透明来源捕获已建立。未建立真实患者流程、临床效果、生产就绪、后端部署／迁移／启用、新公开报告演示或真实外部供应商投递。后续部署或打开 CARE_PLAN_ENABLED 需另行授权目标；已保存导出由用户控制隐私／保留。有限软件完成不能描述为整个医疗平台完成。
