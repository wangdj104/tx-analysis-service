const messages = {
  title: '共同照护目标', intro: '一起提出日常、非临床目标，由被提名账户本人接手，再记录进展与反馈。完成仅是行政记录，不是医学结论。',
  patient: '当前患者档案', recordId: '档案编号', account: '账户编号', unnamed: '未提供姓名的账户', owner: '档案负责人', ownerHelp: '档案归属不证明患者本人身份或同意。接手只表示当前登录账户承担此目标的责任。',
  refresh: '刷新目标', refreshCurrent: '重读当前详情（只读）', new: '提出新目标', view: '查看目标', close: '关闭', back: '返回目标列表', more: '加载下一页', first: '读取第一页', filter: '查看范围',
  idle: '请选择一个目标查看。', noPatient: '请先在页面顶部选择患者。', loading: '正在读取…', ready: '共同照护目标可用。', disabled: '当前已关闭新变更。已有目标与历史仍可在现有权限内查看。', readOnly: '你可以查看目标和历史，当前权限不允许修改。',
  ABSENT: '共同目标的数据结构尚未准备，不会改用旧目标存储。', INCOMPATIBLE: '共同目标的数据结构需要检查，暂时无法使用此面板。', denied: '当前已无访问权限，受保护的详情和草稿已清除。', unavailable: '暂时无法读取此信息，请重试只读查询。', empty: '此范围内暂无目标。', invalidLink: '此共同目标链接无效，请打开当前所选患者的目标。', mismatch: '此链接属于另一个患者档案，尚未读取。', recover: '打开当前所选患者的目标',
  OPEN: '尚未关闭的目标', PROPOSED: '已提议', ACTIVE: '已接手，正在进行', COMPLETED: '已记录行政完成', CANCELLED: '已取消', UNASSIGNED: '尚未指定责任人', AWAITING_ACCEPTANCE: '等待被提名账户本人接手', ACCEPTED: '本人已接手', UNRESOLVED: '责任人当前无法继续，等待重新指定。', HISTORIC: '关闭时的历史责任记录',
  titleField: '目标标题', goalText: '希望一起做到什么', progressCriterion: '哪些可观察事实能说明有进展', responsibility: '责任人具体承担什么', reviewDate: '复看日期（可选）', nominee: '被提名账户', creator: '提议人', acceptedBy: '接手账户编号', acceptedAt: '接手记录时间（UTC）', cycle: '约定周期', version: '版本',
  CREATE: '提出目标', REVISE: '修改或重新指定', ACCEPT: '本人接手', DECLINE: '拒绝本次提名', STEP_BACK: '退出当前责任', ADD_PROGRESS: '记录实际进展', ADD_FEEDBACK: '记录反馈', COMPLETE: '记录行政完成', CANCEL: '取消目标', REOPEN: '重新打开目标',
  reason: '理由', note: '记录的原话或摘要', reportedDate: '填报日期（可选）', reportedHelp: '填报日期由记录者填写，不是已核实的测量发生时间。', result: '此目标实际取得的结果', feedbackSource: '反馈来源', chooseSource: '请明确选择来源',
  ACCOUNT_STATEMENT: '记录者自己的意见', REPORTED_PATIENT: '记录者转述患者反馈', REPORTED_PARTICIPANT: '记录者转述照护参与者反馈', NOT_OBTAINED: '尚未取得反馈', feedbackHelp: '转述反馈不等于已验证的患者签字或临床认可。“尚未取得反馈”不能满足目标完成条件。',
  progress: '本周期最新进展', feedback: '本周期最新反馈', noProgress: '本周期尚未记录进展。', noFeedback: '本周期尚未记录反馈。', recorder: '记录账户编号', recordedAt: '服务器记录时间（UTC）', closure: '关闭时记录的结果', completionHelp: '行政完成要求本周期责任人已本人接手且目前仍有资格，已有实际进展，最新反馈确已取得。只有当前档案负责人可以记录完成。',
  newCycle: '修改或重开会开始新的约定周期，责任人需要重新接手。旧进展和反馈保留在历史中，不能满足新周期的完成条件。', acceptConfirm: '我已阅读当前约定，并以本次登录账户的身份承担责任。', completeConfirm: '我已核对本周期的进展、反馈来源和填写的结果，现在记录行政完成。',
  keep: '保留当前提名', unassigned: '暂不指定责任人', me: '我愿意负责', choose: '选择当前合格的关联账户', nominationHelp: '提名不会授予访问权限，保存时还会再次检查被选账户的当前资格。', keepOther: '只有当前档案负责人可以替换或清除另一账户的提名。你可以保留此提名并说明目标内容。', searchName: '按参与者姓名缩小范围', search: '查找关联账户', chooseNominee: '选择当前可用的关联账户', noCandidates: '没有符合条件的关联账户。', candidateUnavailable: '暂时无法核实候选人资格，请重试查找。', candidateOverflow: '关联人员过多，请输入更具体的姓名后再查找。',
  history: '目标历史', historyHelp: '历史跨周期保留。打开一条事件可查看当时记录的内容。', event: '查看此事件', before: '事件发生前', after: '事件发生后', noBefore: '此事件创建了目标。', noEvents: '此次未返回历史事件。',
  save: '保存本次决定', saving: '正在发送原请求…', saved: '已保存，回执已确认本次操作。', receipt: '已保存回执', savedReadFailure: '操作已保存，但当前详情未能重新读取。下方重试只会读取，不会再次写入。', unknown: '结果仍待核对，原请求键和输入已冻结。请勿提交新的决定。', checking: '正在核对原请求结果…', checkReceipt: '核对已保存回执（只读）', retry: '核对并重试唯一原请求', retryHelp: '没有查到回执不代表失败。只有当前写入权限可用时，才会使用同一冻结请求重试。',
  conflict: '服务器因冲突拒绝本次决定，输入已保留供对照。', invalid: '服务器拒绝了此输入，请对照当前目标后重新决定。', review: '读取最新目标', compare: '用于对照的最新目标', decide: '将我的草稿作为新的决定', decisionHelp: '请核对下方最新版本和责任安排。选择新决定会保留你的输入，下次保存时使用当前版本和新的请求键。',
  dirty: '草稿有未保存的修改，请继续编辑，或明确放弃后再离开。', keepEditing: '继续编辑', discard: '放弃未保存草稿', lockedLeave: '需先核对原操作结果才能关闭此窗口。登录身份或患者上下文变化会清除受保护数据。',
  required: '请明确填写此项或勾选确认。', tooLong: '文字超过标注的字符上限。', invalidDate: '请输入真实的日历日期，格式为 YYYY-MM-DD。', invalidText: '文字中含有无效字符。',
  COMMON_GOAL_VERSION_CONFLICT: '已有较新版本，请读取后再决定。', COMMON_GOAL_AUTHORITY_CHANGED: '责任账户已改变，请读取最新目标后再决定。', COMMON_GOAL_COMPLETION_NOT_READY: '本周期尚未满足行政完成条件。', COMMON_GOALS_DISABLED: '当前已关闭新变更，已有回执仍可读取。', COMMON_GOAL_RECEIPT_UNKNOWN: '原操作结果仍未知，请保留冻结请求并继续核对。', COMMON_GOAL_INTEGRITY: '此目标或回执需要检查，请勿把待核对的请求替换为新请求。', COMMON_GOAL_UNAVAILABLE: '此目标或历史事件当前不可用。', COMMON_GOAL_SCHEMA_ABSENT: '共同目标数据结构尚未准备。', COMMON_GOAL_SCHEMA_INCOMPATIBLE: '共同目标数据结构需要检查。', COMMON_GOAL_INVALID_CURSOR: '此页与所选范围不匹配，请重新读取第一页。',
  COMMON_GOAL_NOMINEE_UNAVAILABLE: '被提名账户当前已不符合条件，请读取最新目标后重新决定。', COMMON_GOAL_CANDIDATES_OVERFLOW: '关联人员过多，请缩小姓名范围。', COMMON_GOAL_ELIGIBILITY_LIMIT: '无法在支持的范围内完成当前参与者资格核实。', COMMON_GOAL_LIMIT: '请求超出支持的大小，请缩短输入后重新核对。', COMMON_GOAL_NO_CHANGE: '没有需要保存的实际变更。', COMMON_GOAL_INVALID_REQUEST: '请核对必填项及支持的文字长度。', VERSION_CONFLICT: '已有较新版本，请读取后明确作出新的决定。', COMMAND_CONFLICT: '此请求键与已有请求冲突，请读取当前目标后再决定。', ACCESS_DENIED: '当前已无访问权限，受保护的详情已清除。'
}
export const goalText = key => messages[key] || messages.unavailable
export const goalPerson = person => person?.displayName || messages.unnamed
