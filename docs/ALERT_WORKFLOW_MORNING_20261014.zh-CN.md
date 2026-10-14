# 阶段 A 成果与交接（2026-10-14）

## 现在具备什么

已实现：测量保存 → 异常随访 → 明确负责人接手 → 处理凭据 → 本人／协助反馈 → 有依据地关闭。

两条原有入口都接入：照护旅程测量、血压／血糖自测。患者、家属、护士、医生沿用现有页面；护理需“有效指派＋明确测量授权”。个人关闭记录处理结果，临床关闭仍由当前指派医生负责。正常复测不会自动宣告完成。

更正保留旧快照及原因，关闭后的更正也会开启新一轮。撤销授权、缺反馈、证据失效、超时及重复点击均有明确处理。通知送达不等于接手或临床完成。

代码入口：[随访服务](../src/main/java/org/familyhealthcare/service/alertworkflow/AlertWorkflowService.java)、[测量桥接](../src/main/java/org/familyhealthcare/service/alertworkflow/MeasurementWorkflowBridge.java)、[现有页面随访组件](../frontend/src/components/alert-workflow/FollowUpDetail.vue)。中文实现对应cn目录。

SQL：[空库完整初始化](../src/main/resources/sql/init.sql)；已有库依次[预检](../src/main/resources/sql/alert_workflow_preflight_20261013.sql) → [增量](../src/main/resources/sql/alert_workflow_upgrade_20261013.sql) → [后检](../src/main/resources/sql/alert_workflow_postcheck_20261013.sql)。这些是待运维执行的文件，没有操作你的数据库。

## 已经实际验证

- 真实 Java 服务＋H2 跑通四条虚构流程：两类测量各走个人／临床模式；临床流程包含护士处理及医生关闭
- 后端两版各 389 项通过；前端英文 106、中文 98 项通过；两版生产构建成功
- 前端测试执行真实 Vue 状态和编译组件，API 为模拟数据；不能算真实浏览器端到端验收
- 六项新验证均在封存源 922eaac 执行，1,011 个源码／测试文件前后摘要一致；旧 365／99／91 仅保留为历史，未冒充新结果

本轮修复：更正不再丢失原备注；旧临床确认不能在另一条随访里重新生效；通知按目标患者打开并保留全局选择。原始名称／备注及自测来源信息目前只读，更换或清空会明确拒绝。未知来源资格继续阻断，不能当作“已确认”。

原始数值／单位、判定与策略可见。因 API 尚无权威比较单位，暂不展示归一化比较值和阈值数值，避免错误配单位。

## 源码复核已完成

独立定向复核结论：规格 PASS、质量 PASS（附一项非阻塞文档小项），有限源码检查点发布就绪 READY。审阅候选为 `5562039`，实际测试源为 `922eaacc`；完整身份见[核验记录](verification/alert-workflow.json)。小项 D1 已在本次文档收尾补齐第6项的前端收据链接，原审阅者仅核对文档改动，不重开产品复核。旧整分支 FAIL 与收据封存时“复核待完成”均保留为历史。

## 还不能称为上线完成

功能独立默认关闭，没有部署、生产迁移、真实通知或推送。真实 MySQL 5.7.18／Java 8、浏览器六主题／390px／200%／键盘焦点、真实 HTTP 角色流程和完整备份恢复仍待验收。

SQL 源码每版有 86 条建表语句、22 个随访守卫；这不是你的实际数据库表数。空库初始化与已有库升级已分开交接，需由运维先做完整备份、预检与隔离验收。家庭导出不包含完整协作历史。

本次是阶段 A 的实质成果；其余用药、库存、预约等 B/C/D 路线尚未宣称完成。

[详细成果与九项证据](../cn/docs/ALERT_WORKFLOW_RELEASE.md) · [运维步骤](../cn/docs/OFFLINE_DEPLOYMENT.md) · [机器可读核验](verification/alert-workflow.json)
