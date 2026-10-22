// This finite scalar presentation is never access or mutation authority.
const SUMMARY_FIELDS = ['availability', 'participation', 'automaticInputEligible', 'sourceStatus', 'source', 'doctor', 'qualificationSemantics', 'checkedAt']
const CHANNEL_FIELDS = ['state', 'contentVersion', 'decisionVersion', 'cycle', 'operation', 'actorId', 'actorKind', 'occurredAt']
const ACTOR_KINDS = ['ADMIN', 'ASSIGNED_DOCTOR', 'OWNER', 'DELEGATE']
const COPY = {
  en: {
    missing: 'Current review information was not supplied; service compatibility is unverified',
    unavailable: 'Current review qualification unavailable',
    invalid: 'Current review information could not be verified',
    none: 'No separate SOURCE/DOCTOR review recorded; legacy handling applies',
    approved: 'Source verified',
    approvedInvalidated: 'Source review: approved; record invalidated',
    pending: 'Source review pending; not used for automatic analysis',
    rejected: 'Source review not approved; not used for automatic analysis',
    changed: 'Source changed; not used for automatic analysis',
    awaiting: 'Source awaiting verification; not used for automatic analysis',
    noDoctor: 'No doctor review recorded',
    doctorApproved: 'Doctor review: approved',
    doctorRejected: 'Doctor review: not approved',
    adminApproved: 'Administrator review in DOCTOR channel: approved',
    adminRejected: 'Administrator review in DOCTOR channel: not approved',
    doctorRequested: 'DOCTOR review requested',
    doctorResubmitted: 'DOCTOR review resubmitted',
    doctorChanged: 'DOCTOR review superseded by a content change',
    eligible: 'Eligible as an automatic-analysis input',
    ineligible: 'Not used for automatic analysis',
    unknownAutomatic: 'Automatic-analysis qualification could not be verified',
    legacyAutomatic: 'Legacy handling applies',
    checked: 'Qualification checked at ',
    snapshot: 'Source qualification from this transaction snapshot; checked at ',
    legacyHeading: 'Legacy verification record',
    localTime: 'recorded local time; timezone unknown',
    actors: { ADMIN: 'Administrator', ASSIGNED_DOCTOR: 'Assigned doctor (recorded role)', OWNER: 'Owner', DELEGATE: 'Delegate' },
    operations: { APPROVE: 'Approval recorded by ', REJECT: 'Rejection recorded by ', REQUEST: 'Review requested by ', RESUBMIT: 'Review resubmitted by ', CONTENT_CHANGED: 'Content changed by ' }
  },
  zh: {
    missing: '未提供当前审核信息；服务版本兼容性未确认',
    unavailable: '当前审核资格不可用',
    invalid: '无法核实当前审核信息',
    none: '未记录独立 SOURCE／DOCTOR 审核；沿用旧版处理',
    approved: '来源已核验',
    approvedInvalidated: '来源审核已通过；记录已失效',
    pending: '来源待核对，不用于自动分析',
    rejected: '来源审核未通过，不用于自动分析',
    changed: '来源已变更，不用于自动分析',
    awaiting: '来源待核对，不用于自动分析',
    noDoctor: '未记录医生审核',
    doctorApproved: '医生审核：通过',
    doctorRejected: '医生审核：未通过',
    adminApproved: 'DOCTOR 通道管理员审核：通过',
    adminRejected: 'DOCTOR 通道管理员审核：未通过',
    doctorRequested: '已请求 DOCTOR 审核',
    doctorResubmitted: '已重新提交 DOCTOR 审核',
    doctorChanged: '内容已变更，原 DOCTOR 审核已被替代',
    eligible: '可作为自动分析输入',
    ineligible: '不用于自动分析',
    unknownAutomatic: '无法核实自动分析输入资格',
    legacyAutomatic: '沿用旧版处理',
    checked: '资格核对时间：',
    snapshot: '本次事务快照中的来源资格；核对时间：',
    legacyHeading: '旧版核验记录',
    localTime: '记录的本地时间；时区未知',
    actors: { ADMIN: '管理员', ASSIGNED_DOCTOR: '当时记录的指定医生身份', OWNER: '所有者', DELEGATE: '受托人' },
    operations: { APPROVE: '通过操作人：', REJECT: '未通过操作人：', REQUEST: '请求操作人：', RESUBMIT: '重新提交操作人：', CONTENT_CHANGED: '内容变更操作人：' }
  }
}

function ordinaryObject(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}
function hasFields(value, fields) { return ordinaryObject(value) && fields.every(key => Object.hasOwn(value, key)) }
function positive(value) { return Number.isSafeInteger(value) && value > 0 }
function validDateParts(match) {
  if (!match) return false
  const year = Number(match[1]), month = Number(match[2]), day = Number(match[3])
  const hour = Number(match[4]), minute = Number(match[5]), second = Number(match[6])
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
  return month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1] && hour < 24 && minute < 60 && second < 60
}
function validCheckedAt(value) {
  return typeof value === 'string' && validDateParts(value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(?:\d{3}|\d{6}|\d{9}))?Z$/))
}
function validOccurredAt(value) {
  return typeof value === 'string' && validDateParts(value.match(/^([1-9]\d{3})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})\.\d{6}$/))
}
function validChannel(channel, doctor) {
  if (!hasFields(channel, CHANNEL_FIELDS) || ![channel.contentVersion, channel.decisionVersion, channel.cycle, channel.actorId].every(positive)
      || !ACTOR_KINDS.includes(channel.actorKind) || !validOccurredAt(channel.occurredAt)
      || channel.decisionVersion === 1 && channel.cycle !== 1) return false
  const decision = channel.state === 'APPROVED' && channel.operation === 'APPROVE' || channel.state === 'REJECTED' && channel.operation === 'REJECT'
  const pending = channel.state === 'PENDING' && (channel.operation === 'RESUBMIT' && channel.decisionVersion > 1 && channel.cycle > 1
    || doctor && channel.operation === 'REQUEST' && channel.decisionVersion === 1 && channel.cycle === 1)
  const changed = channel.state === 'SUPERSEDED' && channel.operation === 'CONTENT_CHANGED' && channel.contentVersion > 1 && channel.decisionVersion > 1
  return !!(decision || pending || changed) && !(doctor && decision && (channel.decisionVersion === 1 || !['ADMIN', 'ASSIGNED_DOCTOR'].includes(channel.actorKind)))
}
function validAvailable(summary) {
  if (typeof summary.automaticInputEligible !== 'boolean' || !['NONE', 'SOURCE_ONLY', 'DOCTOR_ONLY', 'BOTH'].includes(summary.participation)) return false
  const needsSource = summary.participation === 'SOURCE_ONLY' || summary.participation === 'BOTH'
  const needsDoctor = summary.participation === 'DOCTOR_ONLY' || summary.participation === 'BOTH'
  if (needsSource ? !validChannel(summary.source, false) : summary.source !== null) return false
  if (needsDoctor ? !validChannel(summary.doctor, true) : summary.doctor !== null) return false
  if (needsSource && needsDoctor && summary.source.contentVersion !== summary.doctor.contentVersion) return false
  const eligible = summary.automaticInputEligible, status = summary.sourceStatus
  if (summary.participation === 'NONE') {
    return eligible ? status === 'CURRENT' || status === 'UNTRACKED' : status === 'SOURCE_REVIEW_REQUIRED' || status === 'SOURCE_INVALIDATED'
  }
  if (eligible) return status === 'CURRENT' && needsSource && summary.source.state === 'APPROVED'
  if (status === 'SOURCE_INVALIDATED') return true
  if (status === 'SOURCE_CHANGED') return needsSource && summary.source.state === 'SUPERSEDED'
  return status === 'SOURCE_REVIEW_REQUIRED' && (!needsSource || ['PENDING', 'REJECTED'].includes(summary.source.state))
}
function neutral(mode, copy, sourceStatus = null, observationText = '') {
  return {
    mode, participation: null, automaticInputEligible: null, sourceStatus,
    sourceText: copy[mode.toLowerCase()], doctorText: '', sourceActorText: '', doctorActorText: '',
    automaticText: copy.unknownAutomatic, automaticTone: 'info', observationText, legacyHeading: copy.legacyHeading
  }
}
function actorText(channel, copy) {
  return channel ? `${copy.operations[channel.operation]}${copy.actors[channel.actorKind]} #${channel.actorId} · ${channel.occurredAt} (${copy.localTime})` : ''
}

export function medicalReviewPresentation(summary, locale = 'en') {
  const copy = locale === 'zh' ? COPY.zh : COPY.en
  if (summary === null || summary === undefined) return neutral('MISSING', copy)
  if (!hasFields(summary, SUMMARY_FIELDS) || !['CURRENT_CHECK', 'CALLER_TRANSACTION_SNAPSHOT'].includes(summary.qualificationSemantics) || !validCheckedAt(summary.checkedAt)) return neutral('INVALID', copy)
  const observationText = (summary.qualificationSemantics === 'CURRENT_CHECK' ? copy.checked : copy.snapshot) + summary.checkedAt
  if (summary.availability === 'UNAVAILABLE') {
    if (summary.participation !== null || summary.automaticInputEligible !== null || summary.sourceStatus !== 'SOURCE_UNAVAILABLE' || summary.source !== null || summary.doctor !== null) return neutral('INVALID', copy)
    return neutral('UNAVAILABLE', copy, 'SOURCE_UNAVAILABLE', observationText)
  }
  if (summary.availability !== 'AVAILABLE' || !validAvailable(summary)) return neutral('INVALID', copy)
  if (summary.participation === 'NONE') {
    return {
      mode: 'NONE', participation: 'NONE', automaticInputEligible: summary.automaticInputEligible, sourceStatus: summary.sourceStatus,
      sourceText: copy.none, doctorText: copy.noDoctor, sourceActorText: '', doctorActorText: '',
      automaticText: copy.legacyAutomatic, automaticTone: 'info', observationText, legacyHeading: copy.legacyHeading
    }
  }
  const source = summary.source, doctor = summary.doctor
  const sourceText = source === null ? copy.awaiting : source.state === 'APPROVED'
    ? summary.sourceStatus === 'SOURCE_INVALIDATED' ? copy.approvedInvalidated : copy.approved
    : source.state === 'PENDING' ? copy.pending : source.state === 'REJECTED' ? copy.rejected : copy.changed
  const doctorText = doctor === null ? copy.noDoctor : doctor.state === 'APPROVED'
    ? doctor.actorKind === 'ADMIN' ? copy.adminApproved : copy.doctorApproved
    : doctor.state === 'REJECTED' ? doctor.actorKind === 'ADMIN' ? copy.adminRejected : copy.doctorRejected
    : doctor.state === 'PENDING' ? doctor.operation === 'REQUEST' ? copy.doctorRequested : copy.doctorResubmitted : copy.doctorChanged
  return {
    mode: 'PARTICIPANT', participation: summary.participation, automaticInputEligible: summary.automaticInputEligible, sourceStatus: summary.sourceStatus,
    sourceText, doctorText, sourceActorText: actorText(source, copy), doctorActorText: actorText(doctor, copy),
    automaticText: summary.automaticInputEligible ? copy.eligible : copy.ineligible,
    automaticTone: summary.automaticInputEligible ? 'success' : 'warning', observationText, legacyHeading: copy.legacyHeading
  }
}
