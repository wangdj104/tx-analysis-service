const copy={
    coordinates:'经纬度',retryAdmissionFailed:'未能在线确认当前权限，未提交通知重试。请刷新当前状态后再决定。',
    retryConflict:'重试状态或令牌已改变。请先刷新并查看当前投递状态，再作新的重试决定。',
    refreshDeliveryStatus:'刷新并查看当前投递状态',retryIdenticalDelivery:'在线原样重试该通知操作',
    retrySubmitting:'正在检查当前权限并提交通知重试',
    capacityAction:'此次操作未记录：超出支持的请求容量。既有请求保持原状。必要时请联系当地急救服务。',legacyCreate:'通过既有入口记录紧急请求',legacyRecorded:'已记录请求。这不代表通知投递成功或已有人工响应。必要时请联系当地急救服务。',
    legacyUnknown:'已提交请求的结果不确定。请先查看事件记录，再决定是否建立另一请求；不会自动重试。',leadLost:'原负责人接手仅为历史记录，当前没有已确认有效权限的负责人。',handoffPending:'具名继任者尚未接手此次交接。',
    title:'紧急请求',intro:'此功能仅记录请求与联系尝试。通知投递不等于人工接手，不保证响应或医学安全。情况紧急时，请联系当地急救服务。',new:'建立紧急请求',refresh:'在线刷新',
    patient:'患者',more:'更多请求',history:'真实事件记录',deliveries:'我的通知尝试',moreEvents:'更多事件',moreDeliveries:'更多通知',
    close:'关闭',save:'记录操作',edit:'重新查看并编辑',reason:'实际原因',location:'位置说明',successor:'具名继任者用户ID',actualAt:'实际UTC时间',
    note:'实际进展说明',outcome:'实际结果',requester:'请求者',lead:'已接手负责人',pending:'待接手继任者',none:'无',accepted:'首次人工回应时间',
    progress:'进展记录时间',result:'已记录结果',snapshot:'首次信息快照',noEvidence:'尚无人工接手记录',disabled:'新紧急请求已关闭。已有记录仍须当前权限。',
    notReady:'无法确认存储就绪',forbidden:'当前无访问权限',error:'无法确认请求结果。尚未确认建立新请求。必要时请联系当地急救服务。',AUDIENCE_LIMIT:'请求未建立：超出支持的受众上限。必要时请联系当地急救服务。',
    CHANNEL_LIMIT:'请求未建立：超出支持的通知容量。必要时请联系当地急救服务。',unknown:'已提交操作的结果不确定。请在线查询原回执或原样重试。',saving:'正在在线检查当前权限并记录',
    receipt:'查询原回执',identical:'在线原样重试',duplicate:'我理解可能再次发送通知',retryDelivery:'重试通知',retryUnknown:'此次通知重试结果不确定',
    WAITING_FOR_CURRENT_ACCESS:'通知正在等待当前权限',CREATE_REQUEST:'建立请求',ACCEPT:'明确接手',PROGRESS:'记录实际进展',HANDOFF:'邀请具名继任者',
    RESOLVE:'记录实际结果',CANCEL_MISTAKE:'取消误发请求',REQUESTED:'已请求',ACCEPTED:'已人工接手',IN_PROGRESS:'已记录进展',RESOLVED:'已记录结果',
    CANCELLED_MISTAKE:'已取消误发',UNANSWERED:'尚无人工响应记录',ANSWERED:'已记录人工响应',DELIVERED:'通知已投递',QUEUED:'通知待投递',NO_CHANNEL:'无投递渠道',
    FAILED:'通知失败',UNKNOWN:'投递结果不确定',SUPPRESSED:'通知已抑制',CONTACTED_HELP:'已联系求助',CONTACTED_REQUESTER:'已联系请求者',
    HANDED_TO_SERVICES:'已向服务人员交接',UNABLE_TO_CONTACT:'未能取得联系',OTHER_ACTUAL_OUTCOME:'其他实际结果',stale:'离线／信息可能过时',
    generated:'原始生成时间',medicalHistory:'病史',allergies:'过敏信息',bloodType:'血型',medication:'用药',
    offlineTitle:'只读紧急医疗信息卡',offlineHint:'仅本页运行期间经在线授权的信息可离线阅读。刷新页面后必须重新联网授权；每次操作均需在线检查当前权限。'}
;
export const t=key=>copy[key]||key;
