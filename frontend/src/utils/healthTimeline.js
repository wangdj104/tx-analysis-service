const copy = {
  record:['record','条记录'], records:['records','条记录'], emptyHint:['Try another source or date range.','可切换来源或调整日期范围再试。'],
  all:['All sources','全部来源'], source:['Source','来源'], event:['Recorded event','记录事项'], current:['Current follow-up status','当前处理状态'],
  ACTIVE:['Active','进行中'],COMPLETED:['Recorded as completed','已记录完成'],CANCELLED:['Cancelled','已取消'],CLOSED:['Closed','已关闭'],PLANNED:['Planned','已计划'],DONE:['Recorded as done','已记录完成'],
  open:['Open original module and history','打开原模块与历史'], related:['Open linked measurement','打开关联测量'],
  scope:['Latest 200 authorized facts in the selected dates. Narrow the date range to read earlier records. Source permissions are checked again on every refresh.','显示所选日期内最新 200 条有权查看的事实；缩小日期范围可查看较早记录，每次刷新均重新核验来源权限。'],
  semantics:['Notification delivery does not establish execution. Follow-up closure does not establish treatment success. Times marked UTC retain their source time; other times are original local values.','通知送达不代表已执行，结束跟进不代表治疗有效。标注 UTC 的时间保留来源时区，其他时间为原记录当地时间。'],
  empty:['No readable facts for this source on the current page.','当前页没有此来源的可读事实。'],
  MANUAL:['Personal notes','个人事项'], MEASUREMENT:['Blood pressure / glucose','血压血糖'], BP_SELF_MONITOR:['Blood pressure / glucose','血压血糖'], HEALTH_MEASUREMENT:['Measurements','健康测量'],
  DIALYSIS:['Dialysis','透析'], INTAKE:['Intake facts','服药事实'], MEDICATION_LOG:['Medication records','用药记录'], MEDICATION_EXECUTION_EVENT:['Medication follow-up','用药跟进'],
  CARE_PLAN_EVENT:['Care plan','照护计划'], ALERT_EVENT:['Abnormality follow-up','异常跟进'], STOCK_EVENT:['Inventory follow-up','库存跟进'], STOCK_MOVEMENT:['Inventory movement','出入库'],
  FAMILY_ASSISTANCE_EVENT:['Family collaboration','家庭协作'], FAMILY_RECORD:['Family care records','家庭照护记录'], HANDOVER:['Handover recorded','已记录交接'],APPOINTMENT:['Accompaniment recorded','已记录陪诊'], EMERGENCY_ASSISTANCE_EVENT:['Emergency assistance','紧急求助'], EMERGENCY_RECORD:['Emergency records','紧急记录'],
  OPEN:['Awaiting handling','待处理'],RESOLVED:['Reconciled','已核对解决'],NOT_APPLICABLE:['No longer applicable','已不适用'],CANCELLED_MISTAKE:['Cancelled as recorded in error','已取消误记'],ADOPT_STOCK:['Stock review started','已开始库存核对'],RECORD_REPLENISHMENT:['Stock receipt recorded','已记录补货'],RECORD_COUNT:['Physical count recorded','已记录实物盘点'],RECONCILE:['Evidence reconciled','已核对凭据'],CHANGE_RULE:['Manual inventory rule changed','已调整库存规则'],CANCEL_MISTAKE:['Mistaken review cancelled','已取消误建核对'],STOCK_CONSUMED:['Inventory deduction recorded','已记录扣库'],STOCK_RESTORED:['Inventory compensation recorded','已记录库存补偿'],
  STOCK_IN:['Stock received','已入库'], STOCK_OUT:['Stock deducted','已出库'], STOCK_ADJUSTED:['Stock adjustment recorded','已记录库存调整'], EMERGENCY_REQUESTED:['Help requested','已发起求助'],
  PLAN_PUBLISHED:['Plan published','计划已发布'], REVISION_PUBLISHED:['Plan revision published','计划修订已发布'], RECEIPT_SUBMITTED:['Execution receipt submitted','已提交执行记录'], HELP_REQUESTED:['Help requested','已请求帮助'], FOLLOW_UP_RECORDED:['Follow-up recorded','已记录跟进'], RECEIPT_CONFIRMED:['Receipt confirmed','执行记录已确认'], RECEIPT_RETURNED:['Receipt returned','执行记录已退回'], PLAN_CANCELLED:['Plan cancelled','计划已取消'], PLAN_CLOSED:['Plan closed','计划已关闭'],
  BP:['Blood pressure recorded','已记录血压'],GLUCOSE:['Blood glucose recorded','已记录血糖'],SPO2:['Oxygen saturation recorded','已记录血氧'],WEIGHT:['Weight recorded','已记录体重'],HEART_RATE:['Heart rate recorded','已记录心率'],TEMPERATURE:['Temperature recorded','已记录体温'],CUSTOM:['Measurement recorded','已记录测量']
}
export function timelineText(key, locale='en') { return copy[key]?.[locale==='zh-CN'?1:0] || key || '—' }
export function timelineSource(event) { return event?.sourceType || 'MANUAL' }
const positive=value=>Number.isSafeInteger(value)&&value>0
export function timelineSourceLink(event, patientId) {
  if(!event || !positive(patientId) || event.patientId!==patientId)return ''
  const source=timelineSource(event), id=event.sourceRecordId??event.carePlanId??event.sourceId??event.id
  if(!positive(id))return ''
  switch(source) {
    case 'CARE_PLAN_EVENT': return `/care-plans/${id}`
    case 'ALERT_EVENT': return `/alert-workflows/${id}?patientId=${patientId}`
    case 'HEALTH_MEASUREMENT': return `/care-journey?tab=measurements&patientId=${patientId}&measurementId=${id}`
    case 'MEASUREMENT': case 'BP_SELF_MONITOR': return `/bp-self-monitor?patientId=${patientId}&recordId=${id}`
    case 'DIALYSIS': return '/dialysis'
    case 'MEDICATION_EXECUTION_EVENT': return `/medication?patientId=${patientId}&executionId=${id}`
    case 'INTAKE': case 'MEDICATION_LOG': return `/medication?patientId=${patientId}`
    case 'STOCK_EVENT': case 'STOCK_MOVEMENT': return `/care?patientId=${patientId}&stockId=${id}`
    case 'FAMILY_ASSISTANCE_EVENT': return `/care?patientId=${patientId}&assistanceId=${id}`
    case 'FAMILY_RECORD': return '/care'
    case 'EMERGENCY_ASSISTANCE_EVENT': return `/care-journey?tab=emergency&patientId=${patientId}&emergencyEventId=${id}`
    case 'EMERGENCY_RECORD': return '/care-journey?tab=emergency'
    default: return ''
  }
}
export function timelineRelatedLink(event, patientId) {
  if(!['HEALTH_MEASUREMENT','BP_SELF_MONITOR'].includes(event?.relatedSourceType))return ''
  return timelineSourceLink({...event,sourceType:event.relatedSourceType,sourceRecordId:event.relatedSourceId},patientId)
}
export function validTimelineRows(rows,patientId) {
  return Array.isArray(rows)&&rows.length<=200&&rows.every(row=>row&&row.patientId===patientId&&positive(row.id))
}
