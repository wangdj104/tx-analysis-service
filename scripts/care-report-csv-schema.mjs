// Frozen acceptance oracle for the approved bilingual CSV contract. Never reads renderer source.
export const REPORT_CSV_SCHEMA = Object.freeze({
  "actions_csv": [
    {
      "key": "schema",
      "en": "Report schema version",
      "zh": "报告结构版本"
    },
    {
      "key": "generated",
      "en": "Generated at (UTC)",
      "zh": "生成时间（UTC）"
    },
    {
      "key": "asOf",
      "en": "Current state as of (UTC)",
      "zh": "当前状态读取时间（UTC）"
    },
    {
      "key": "patient",
      "en": "Patient ID",
      "zh": "患者ID"
    },
    {
      "key": "scopeCode",
      "en": "Scope code",
      "zh": "范围码"
    },
    {
      "key": "scopeLabel",
      "en": "Scope",
      "zh": "范围"
    },
    {
      "key": "scopePlan",
      "en": "Scope plan ID",
      "zh": "范围计划ID"
    },
    {
      "key": "from",
      "en": "From date",
      "zh": "开始日期"
    },
    {
      "key": "to",
      "en": "To date (inclusive)",
      "zh": "结束日期（含当日）"
    },
    {
      "key": "zone",
      "en": "Time zone",
      "zh": "时区"
    },
    {
      "key": "start",
      "en": "Range start (UTC)",
      "zh": "期间开始（UTC）"
    },
    {
      "key": "end",
      "en": "Range end exclusive (UTC)",
      "zh": "期间结束（不含，UTC）"
    },
    {
      "key": "language",
      "en": "Language",
      "zh": "界面语言"
    },
    {
      "key": "source",
      "en": "Source code",
      "zh": "来源码"
    },
    {
      "key": "completeCode",
      "en": "Completeness code",
      "zh": "完整性码"
    },
    {
      "key": "completeLabel",
      "en": "Completeness",
      "zh": "完整性"
    },
    {
      "key": "safety",
      "en": "CSV text safety",
      "zh": "CSV文本安全"
    },
    {
      "key": "plan",
      "en": "Plan ID",
      "zh": "计划ID"
    },
    {
      "key": "revision",
      "en": "Published revision ID",
      "zh": "已发布版本ID"
    },
    {
      "key": "revisionNo",
      "en": "Published revision number",
      "zh": "已发布版本号"
    },
    {
      "key": "action",
      "en": "Action ID",
      "zh": "事项ID"
    },
    {
      "key": "title",
      "en": "Plan title",
      "zh": "计划标题"
    },
    {
      "key": "instructions",
      "en": "Published plan instructions",
      "zh": "已发布计划说明"
    },
    {
      "key": "instruction",
      "en": "Action instruction",
      "zh": "事项指示"
    },
    {
      "key": "assignee",
      "en": "Assigned user ID",
      "zh": "负责人用户ID"
    },
    {
      "key": "availableCode",
      "en": "Assignee available code",
      "zh": "负责人可用码"
    },
    {
      "key": "availableLabel",
      "en": "Assignee availability",
      "zh": "负责人可用性"
    },
    {
      "key": "due",
      "en": "Due at (UTC)",
      "zh": "期限（UTC）"
    },
    {
      "key": "statusCode",
      "en": "Current status code",
      "zh": "当前状态码"
    },
    {
      "key": "statusLabel",
      "en": "Current status",
      "zh": "当前状态"
    },
    {
      "key": "overdueCode",
      "en": "Overdue code",
      "zh": "逾期码"
    },
    {
      "key": "overdueLabel",
      "en": "Overdue",
      "zh": "逾期"
    },
    {
      "key": "supplementCode",
      "en": "Needs supplement code",
      "zh": "待补充码"
    },
    {
      "key": "supplementLabel",
      "en": "Needs supplement",
      "zh": "待补充"
    },
    {
      "key": "waiting",
      "en": "Review waiting since (UTC)",
      "zh": "本次待复核起点（UTC）"
    },
    {
      "key": "evidence",
      "en": "Evidence references",
      "zh": "证据引用"
    },
    {
      "key": "receipt.id",
      "en": "Latest receipt / Event ID",
      "zh": "最近提交 / 事件ID"
    },
    {
      "key": "receipt.typeCode",
      "en": "Latest receipt / Event type code",
      "zh": "最近提交 / 事件类型码"
    },
    {
      "key": "receipt.typeLabel",
      "en": "Latest receipt / Event type",
      "zh": "最近提交 / 事件类型"
    },
    {
      "key": "receipt.actor",
      "en": "Latest receipt / Actor ID",
      "zh": "最近提交 / 操作者ID"
    },
    {
      "key": "receipt.name",
      "en": "Latest receipt / Actor name (historical)",
      "zh": "最近提交 / 操作者姓名（历史快照）"
    },
    {
      "key": "receipt.roleCode",
      "en": "Latest receipt / Actor role code (historical)",
      "zh": "最近提交 / 操作者角色码（历史快照）"
    },
    {
      "key": "receipt.roleLabel",
      "en": "Latest receipt / Actor role (historical)",
      "zh": "最近提交 / 操作者角色（历史快照）"
    },
    {
      "key": "receipt.relationCode",
      "en": "Latest receipt / Actor relation code (historical)",
      "zh": "最近提交 / 操作者关系码（历史快照）"
    },
    {
      "key": "receipt.relationLabel",
      "en": "Latest receipt / Actor relation (historical)",
      "zh": "最近提交 / 操作者关系（历史快照）"
    },
    {
      "key": "receipt.modeCode",
      "en": "Latest receipt / Entry mode code",
      "zh": "最近提交 / 代录方式码"
    },
    {
      "key": "receipt.modeLabel",
      "en": "Latest receipt / Entry mode",
      "zh": "最近提交 / 代录方式"
    },
    {
      "key": "receipt.kindCode",
      "en": "Latest receipt / Administrative follow-up kind code",
      "zh": "最近提交 / 行政跟进类别码"
    },
    {
      "key": "receipt.kindLabel",
      "en": "Latest receipt / Administrative follow-up kind",
      "zh": "最近提交 / 行政跟进类别"
    },
    {
      "key": "receipt.recorded",
      "en": "Latest receipt / Recorded at (UTC)",
      "zh": "最近提交 / 记录时间（UTC）"
    },
    {
      "key": "receipt.occurred",
      "en": "Latest receipt / Actual execution at (UTC)",
      "zh": "最近提交 / 实际执行时间（UTC）"
    },
    {
      "key": "receipt.note",
      "en": "Latest receipt / Original note",
      "zh": "最近提交 / 原文备注"
    },
    {
      "key": "receipt.evidence",
      "en": "Latest receipt / Evidence references",
      "zh": "最近提交 / 证据引用"
    },
    {
      "key": "return.id",
      "en": "Latest return / Event ID",
      "zh": "最近退回 / 事件ID"
    },
    {
      "key": "return.typeCode",
      "en": "Latest return / Event type code",
      "zh": "最近退回 / 事件类型码"
    },
    {
      "key": "return.typeLabel",
      "en": "Latest return / Event type",
      "zh": "最近退回 / 事件类型"
    },
    {
      "key": "return.actor",
      "en": "Latest return / Actor ID",
      "zh": "最近退回 / 操作者ID"
    },
    {
      "key": "return.name",
      "en": "Latest return / Actor name (historical)",
      "zh": "最近退回 / 操作者姓名（历史快照）"
    },
    {
      "key": "return.roleCode",
      "en": "Latest return / Actor role code (historical)",
      "zh": "最近退回 / 操作者角色码（历史快照）"
    },
    {
      "key": "return.roleLabel",
      "en": "Latest return / Actor role (historical)",
      "zh": "最近退回 / 操作者角色（历史快照）"
    },
    {
      "key": "return.relationCode",
      "en": "Latest return / Actor relation code (historical)",
      "zh": "最近退回 / 操作者关系码（历史快照）"
    },
    {
      "key": "return.relationLabel",
      "en": "Latest return / Actor relation (historical)",
      "zh": "最近退回 / 操作者关系（历史快照）"
    },
    {
      "key": "return.modeCode",
      "en": "Latest return / Entry mode code",
      "zh": "最近退回 / 代录方式码"
    },
    {
      "key": "return.modeLabel",
      "en": "Latest return / Entry mode",
      "zh": "最近退回 / 代录方式"
    },
    {
      "key": "return.kindCode",
      "en": "Latest return / Administrative follow-up kind code",
      "zh": "最近退回 / 行政跟进类别码"
    },
    {
      "key": "return.kindLabel",
      "en": "Latest return / Administrative follow-up kind",
      "zh": "最近退回 / 行政跟进类别"
    },
    {
      "key": "return.recorded",
      "en": "Latest return / Recorded at (UTC)",
      "zh": "最近退回 / 记录时间（UTC）"
    },
    {
      "key": "return.occurred",
      "en": "Latest return / Actual execution at (UTC)",
      "zh": "最近退回 / 实际执行时间（UTC）"
    },
    {
      "key": "return.note",
      "en": "Latest return / Original note",
      "zh": "最近退回 / 原文备注"
    },
    {
      "key": "return.evidence",
      "en": "Latest return / Evidence references",
      "zh": "最近退回 / 证据引用"
    },
    {
      "key": "review.id",
      "en": "Latest doctor review / Event ID",
      "zh": "最近医生复核 / 事件ID"
    },
    {
      "key": "review.typeCode",
      "en": "Latest doctor review / Event type code",
      "zh": "最近医生复核 / 事件类型码"
    },
    {
      "key": "review.typeLabel",
      "en": "Latest doctor review / Event type",
      "zh": "最近医生复核 / 事件类型"
    },
    {
      "key": "review.actor",
      "en": "Latest doctor review / Actor ID",
      "zh": "最近医生复核 / 操作者ID"
    },
    {
      "key": "review.name",
      "en": "Latest doctor review / Actor name (historical)",
      "zh": "最近医生复核 / 操作者姓名（历史快照）"
    },
    {
      "key": "review.roleCode",
      "en": "Latest doctor review / Actor role code (historical)",
      "zh": "最近医生复核 / 操作者角色码（历史快照）"
    },
    {
      "key": "review.roleLabel",
      "en": "Latest doctor review / Actor role (historical)",
      "zh": "最近医生复核 / 操作者角色（历史快照）"
    },
    {
      "key": "review.relationCode",
      "en": "Latest doctor review / Actor relation code (historical)",
      "zh": "最近医生复核 / 操作者关系码（历史快照）"
    },
    {
      "key": "review.relationLabel",
      "en": "Latest doctor review / Actor relation (historical)",
      "zh": "最近医生复核 / 操作者关系（历史快照）"
    },
    {
      "key": "review.modeCode",
      "en": "Latest doctor review / Entry mode code",
      "zh": "最近医生复核 / 代录方式码"
    },
    {
      "key": "review.modeLabel",
      "en": "Latest doctor review / Entry mode",
      "zh": "最近医生复核 / 代录方式"
    },
    {
      "key": "review.kindCode",
      "en": "Latest doctor review / Administrative follow-up kind code",
      "zh": "最近医生复核 / 行政跟进类别码"
    },
    {
      "key": "review.kindLabel",
      "en": "Latest doctor review / Administrative follow-up kind",
      "zh": "最近医生复核 / 行政跟进类别"
    },
    {
      "key": "review.recorded",
      "en": "Latest doctor review / Recorded at (UTC)",
      "zh": "最近医生复核 / 记录时间（UTC）"
    },
    {
      "key": "review.occurred",
      "en": "Latest doctor review / Actual execution at (UTC)",
      "zh": "最近医生复核 / 实际执行时间（UTC）"
    },
    {
      "key": "review.note",
      "en": "Latest doctor review / Original note",
      "zh": "最近医生复核 / 原文备注"
    },
    {
      "key": "review.evidence",
      "en": "Latest doctor review / Evidence references",
      "zh": "最近医生复核 / 证据引用"
    },
    {
      "key": "help.id",
      "en": "Latest difficulty / Event ID",
      "zh": "最近困难 / 事件ID"
    },
    {
      "key": "help.typeCode",
      "en": "Latest difficulty / Event type code",
      "zh": "最近困难 / 事件类型码"
    },
    {
      "key": "help.typeLabel",
      "en": "Latest difficulty / Event type",
      "zh": "最近困难 / 事件类型"
    },
    {
      "key": "help.actor",
      "en": "Latest difficulty / Actor ID",
      "zh": "最近困难 / 操作者ID"
    },
    {
      "key": "help.name",
      "en": "Latest difficulty / Actor name (historical)",
      "zh": "最近困难 / 操作者姓名（历史快照）"
    },
    {
      "key": "help.roleCode",
      "en": "Latest difficulty / Actor role code (historical)",
      "zh": "最近困难 / 操作者角色码（历史快照）"
    },
    {
      "key": "help.roleLabel",
      "en": "Latest difficulty / Actor role (historical)",
      "zh": "最近困难 / 操作者角色（历史快照）"
    },
    {
      "key": "help.relationCode",
      "en": "Latest difficulty / Actor relation code (historical)",
      "zh": "最近困难 / 操作者关系码（历史快照）"
    },
    {
      "key": "help.relationLabel",
      "en": "Latest difficulty / Actor relation (historical)",
      "zh": "最近困难 / 操作者关系（历史快照）"
    },
    {
      "key": "help.modeCode",
      "en": "Latest difficulty / Entry mode code",
      "zh": "最近困难 / 代录方式码"
    },
    {
      "key": "help.modeLabel",
      "en": "Latest difficulty / Entry mode",
      "zh": "最近困难 / 代录方式"
    },
    {
      "key": "help.kindCode",
      "en": "Latest difficulty / Administrative follow-up kind code",
      "zh": "最近困难 / 行政跟进类别码"
    },
    {
      "key": "help.kindLabel",
      "en": "Latest difficulty / Administrative follow-up kind",
      "zh": "最近困难 / 行政跟进类别"
    },
    {
      "key": "help.recorded",
      "en": "Latest difficulty / Recorded at (UTC)",
      "zh": "最近困难 / 记录时间（UTC）"
    },
    {
      "key": "help.occurred",
      "en": "Latest difficulty / Actual execution at (UTC)",
      "zh": "最近困难 / 实际执行时间（UTC）"
    },
    {
      "key": "help.note",
      "en": "Latest difficulty / Original note",
      "zh": "最近困难 / 原文备注"
    },
    {
      "key": "help.evidence",
      "en": "Latest difficulty / Evidence references",
      "zh": "最近困难 / 证据引用"
    },
    {
      "key": "follow.id",
      "en": "Latest administrative follow-up / Event ID",
      "zh": "最近行政跟进 / 事件ID"
    },
    {
      "key": "follow.typeCode",
      "en": "Latest administrative follow-up / Event type code",
      "zh": "最近行政跟进 / 事件类型码"
    },
    {
      "key": "follow.typeLabel",
      "en": "Latest administrative follow-up / Event type",
      "zh": "最近行政跟进 / 事件类型"
    },
    {
      "key": "follow.actor",
      "en": "Latest administrative follow-up / Actor ID",
      "zh": "最近行政跟进 / 操作者ID"
    },
    {
      "key": "follow.name",
      "en": "Latest administrative follow-up / Actor name (historical)",
      "zh": "最近行政跟进 / 操作者姓名（历史快照）"
    },
    {
      "key": "follow.roleCode",
      "en": "Latest administrative follow-up / Actor role code (historical)",
      "zh": "最近行政跟进 / 操作者角色码（历史快照）"
    },
    {
      "key": "follow.roleLabel",
      "en": "Latest administrative follow-up / Actor role (historical)",
      "zh": "最近行政跟进 / 操作者角色（历史快照）"
    },
    {
      "key": "follow.relationCode",
      "en": "Latest administrative follow-up / Actor relation code (historical)",
      "zh": "最近行政跟进 / 操作者关系码（历史快照）"
    },
    {
      "key": "follow.relationLabel",
      "en": "Latest administrative follow-up / Actor relation (historical)",
      "zh": "最近行政跟进 / 操作者关系（历史快照）"
    },
    {
      "key": "follow.modeCode",
      "en": "Latest administrative follow-up / Entry mode code",
      "zh": "最近行政跟进 / 代录方式码"
    },
    {
      "key": "follow.modeLabel",
      "en": "Latest administrative follow-up / Entry mode",
      "zh": "最近行政跟进 / 代录方式"
    },
    {
      "key": "follow.kindCode",
      "en": "Latest administrative follow-up / Administrative follow-up kind code",
      "zh": "最近行政跟进 / 行政跟进类别码"
    },
    {
      "key": "follow.kindLabel",
      "en": "Latest administrative follow-up / Administrative follow-up kind",
      "zh": "最近行政跟进 / 行政跟进类别"
    },
    {
      "key": "follow.recorded",
      "en": "Latest administrative follow-up / Recorded at (UTC)",
      "zh": "最近行政跟进 / 记录时间（UTC）"
    },
    {
      "key": "follow.occurred",
      "en": "Latest administrative follow-up / Actual execution at (UTC)",
      "zh": "最近行政跟进 / 实际执行时间（UTC）"
    },
    {
      "key": "follow.note",
      "en": "Latest administrative follow-up / Original note",
      "zh": "最近行政跟进 / 原文备注"
    },
    {
      "key": "follow.evidence",
      "en": "Latest administrative follow-up / Evidence references",
      "zh": "最近行政跟进 / 证据引用"
    }
  ],
  "events_csv": [
    {
      "key": "schema",
      "en": "Report schema version",
      "zh": "报告结构版本"
    },
    {
      "key": "generated",
      "en": "Generated at (UTC)",
      "zh": "生成时间（UTC）"
    },
    {
      "key": "asOf",
      "en": "Current state as of (UTC)",
      "zh": "当前状态读取时间（UTC）"
    },
    {
      "key": "patient",
      "en": "Patient ID",
      "zh": "患者ID"
    },
    {
      "key": "scopeCode",
      "en": "Scope code",
      "zh": "范围码"
    },
    {
      "key": "scopeLabel",
      "en": "Scope",
      "zh": "范围"
    },
    {
      "key": "scopePlan",
      "en": "Scope plan ID",
      "zh": "范围计划ID"
    },
    {
      "key": "from",
      "en": "From date",
      "zh": "开始日期"
    },
    {
      "key": "to",
      "en": "To date (inclusive)",
      "zh": "结束日期（含当日）"
    },
    {
      "key": "zone",
      "en": "Time zone",
      "zh": "时区"
    },
    {
      "key": "start",
      "en": "Range start (UTC)",
      "zh": "期间开始（UTC）"
    },
    {
      "key": "end",
      "en": "Range end exclusive (UTC)",
      "zh": "期间结束（不含，UTC）"
    },
    {
      "key": "language",
      "en": "Language",
      "zh": "界面语言"
    },
    {
      "key": "source",
      "en": "Source code",
      "zh": "来源码"
    },
    {
      "key": "completeCode",
      "en": "Completeness code",
      "zh": "完整性码"
    },
    {
      "key": "completeLabel",
      "en": "Completeness",
      "zh": "完整性"
    },
    {
      "key": "safety",
      "en": "CSV text safety",
      "zh": "CSV文本安全"
    },
    {
      "key": "plan",
      "en": "Plan ID",
      "zh": "计划ID"
    },
    {
      "key": "revision",
      "en": "Published revision ID",
      "zh": "已发布版本ID"
    },
    {
      "key": "revisionNo",
      "en": "Published revision number",
      "zh": "已发布版本号"
    },
    {
      "key": "action",
      "en": "Action ID",
      "zh": "事项ID"
    },
    {
      "key": "title",
      "en": "Plan title",
      "zh": "计划标题"
    },
    {
      "key": "instructions",
      "en": "Original published instructions",
      "zh": "原发布版本说明"
    },
    {
      "key": "instruction",
      "en": "Original action instruction",
      "zh": "原事项指示"
    },
    {
      "key": "statusCode",
      "en": "Historical status after event code",
      "zh": "事件后历史状态码"
    },
    {
      "key": "statusLabel",
      "en": "Historical status after event",
      "zh": "事件后的历史状态"
    },
    {
      "key": "lifecycleCode",
      "en": "Plan lifecycle at generation code",
      "zh": "生成时计划生命周期码"
    },
    {
      "key": "lifecycleLabel",
      "en": "Plan lifecycle at generation",
      "zh": "生成时计划生命周期"
    },
    {
      "key": "currentCode",
      "en": "Revision is current at generation code",
      "zh": "生成时是否当前版本码"
    },
    {
      "key": "currentLabel",
      "en": "Revision is current at generation",
      "zh": "生成时是否当前版本"
    },
    {
      "key": "event.id",
      "en": "Event ID",
      "zh": "事件ID"
    },
    {
      "key": "event.typeCode",
      "en": "Event type code",
      "zh": "事件类型码"
    },
    {
      "key": "event.typeLabel",
      "en": "Event type",
      "zh": "事件类型"
    },
    {
      "key": "event.actor",
      "en": "Actor ID",
      "zh": "操作者ID"
    },
    {
      "key": "event.name",
      "en": "Actor name (historical)",
      "zh": "操作者姓名（历史快照）"
    },
    {
      "key": "event.roleCode",
      "en": "Actor role code (historical)",
      "zh": "操作者角色码（历史快照）"
    },
    {
      "key": "event.roleLabel",
      "en": "Actor role (historical)",
      "zh": "操作者角色（历史快照）"
    },
    {
      "key": "event.relationCode",
      "en": "Actor relation code (historical)",
      "zh": "操作者关系码（历史快照）"
    },
    {
      "key": "event.relationLabel",
      "en": "Actor relation (historical)",
      "zh": "操作者关系（历史快照）"
    },
    {
      "key": "event.modeCode",
      "en": "Entry mode code",
      "zh": "代录方式码"
    },
    {
      "key": "event.modeLabel",
      "en": "Entry mode",
      "zh": "代录方式"
    },
    {
      "key": "event.kindCode",
      "en": "Administrative follow-up kind code",
      "zh": "行政跟进类别码"
    },
    {
      "key": "event.kindLabel",
      "en": "Administrative follow-up kind",
      "zh": "行政跟进类别"
    },
    {
      "key": "event.recorded",
      "en": "Recorded at (UTC)",
      "zh": "记录时间（UTC）"
    },
    {
      "key": "event.occurred",
      "en": "Actual execution at (UTC)",
      "zh": "实际执行时间（UTC）"
    },
    {
      "key": "event.note",
      "en": "Original note",
      "zh": "原文备注"
    },
    {
      "key": "event.evidence",
      "en": "Evidence references",
      "zh": "证据引用"
    }
  ]
})
