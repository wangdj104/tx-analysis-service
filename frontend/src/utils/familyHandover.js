// 只比较本次交接读取的身份与修订，不把候选资格当正文权限或命令凭据。
export const handoverId = value => /^[1-9]\d*$/.test(String(value)) && Number.isSafeInteger(Number(value)) ? Number(value) : null;
export const isOrdinaryHandover = value => value?.sourceKind === 'CARE_ITEM' && value?.requestKind === 'HANDOVER';
export function typedHandoverSource(value) {
  if (!value || Object.keys(value).sort().join(',') !== 'careItemId,expectedNativeKind,sourceKind' || value.sourceKind !== 'CARE_ITEM' || value.expectedNativeKind !== 'HANDOVER' || !handoverId(value.careItemId)) return null;
  return Object.freeze({ sourceKind: 'CARE_ITEM', careItemId: handoverId(value.careItemId), expectedNativeKind: 'HANDOVER' });
}
export function sameHandoverContext(left, right) {
  if (!isOrdinaryHandover(left) || !isOrdinaryHandover(right)) return false;
  const fields = ['patientId','sourceKind','careItemId','requestKind','assistanceId','version','cycle','state','currentAssistanceId','currentSource'];
  if (!handoverId(left.patientId) || !handoverId(left.careItemId)) return false;
  if (left.assistanceId != null && (!handoverId(left.assistanceId) || !handoverId(left.version) || !handoverId(left.cycle))) return false;
  if (fields.some(key => left[key] !== right[key])) return false;
  const marker = value => value.familyWorkflowVersion ?? (value.assistanceId != null ? 1 : 0);
  return marker(left) === marker(right);
}
export function sameHandoverContent(detail, content) {
  return sameHandoverContext(detail, content) && ['originatorId','assignedUserId','acceptedBy','handledBy','acceptedAt','handledAt','confirmedAt','deadlineAt'].every(key => detail[key] === content[key]);
}
// 控件输入就是 UTC 墙钟；逐字段核实后拼 Z，不经过浏览器本地时区换算。
export function handoverUtcDeadline(value) {
  if (!value) return null;
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(value) || Number(value.slice(0,4)) < 1) throw new TypeError('INVALID_UTC_DEADLINE');
  const normalized = value.length === 16 ? `${value}:00` : value;
  const instant = new Date(`${normalized}Z`);
  if (!Number.isFinite(instant.getTime()) || instant.toISOString().slice(0,19) !== normalized) throw new TypeError('INVALID_UTC_DEADLINE');
  return `${normalized}Z`;
}
