import { localMeasurementTime, measurementUnit } from './measurementReadModelPresentation.js';
import { originalText as t } from './originalMeasurementCopy.js';
export const originalId = value => Number.isSafeInteger(Number(value)) && Number(value) > 0;
const present = value => value != null && String(value).trim() !== '';
export function selfTime(row = {}) {
    const date = present(row.recordDate) ? String(row.recordDate) : t('noDate');
    const time = present(row.recordTime) ? String(row.recordTime) : t('noTime');
    return `${date} ${time}${localMeasurementTime(`${row.recordDate}T${row.recordTime}`) ? '' : ` · ${t('timeUnconfirmed')}`}`;
}
export function recordedTime(value) {
    if (!present(value)) return t('noTime');
    const raw = String(value);
    return localMeasurementTime(raw.replace(' ', 'T')) ? raw : `${raw} · ${t('timeUnconfirmed')}`;
}
export const selfBp = row => `${row?.systolicBp ?? '—'} / ${row?.diastolicBp ?? '—'} mmHg`;
export const selfGlucose = row => `${row?.bloodGlucose ?? '—'} ${measurementUnit(row?.bgUnit)}`;
export function selfValues(row = {}) {
    return [row.systolicBp != null || row.diastolicBp != null ? selfBp(row) : '', row.bloodGlucose != null ? selfGlucose(row) : ''].filter(Boolean).join(' · ') || '—';
}
export const journeyValues = row => `${row?.value_primary ?? '—'}${row?.value_secondary != null ? ` / ${row.value_secondary}` : ''} ${measurementUnit(row?.unit)}`;
export const journeyName = row => row?.metric_type === 'CUSTOM' ? (present(row.metric_name) ? row.metric_name : t('customMeasurement')) : t(row?.metric_type || 'unknown');
// 这里只检查原资料是否齐全，不计算医学正常/异常，也不补历史资料。
export function originalIssues(row, metric) {
    if (!row) return [t('noReading')];
    const messages = [];
    if (!localMeasurementTime(`${row.recordDate}T${row.recordTime}`)) messages.push(t('timeUnconfirmed'));
    if (metric !== 'GLUCOSE' && (row.systolicBp != null || row.diastolicBp != null) && (row.systolicBp == null || row.diastolicBp == null)) messages.push(t('incompleteBp'));
    if (metric !== 'BP' && row.bloodGlucose != null) {
        if (!['mmol/l', 'mg/dl'].includes(String(row.bgUnit ?? '').trim().toLowerCase())) messages.push(t('unknownUnit'));
        if (!['Fasting', 'After Meal2h', 'Random', '空腹', '餐后2小时', '随机'].includes(row.measurePeriod)) messages.push(t('periodUnknown'));
    }
    if (row.verificationStatus !== 'VERIFIED') messages.push(t('verificationNeeded'));
    return messages;
}
export const originalEditableFields = Object.freeze(['recordDate','recordTime','measureType','systolicBp','diastolicBp','bloodGlucose','bgUnit','measurePeriod','remark']);
// 详情白名单逐键返回；合法空值保留，缺键或错类型不能开启编辑。
export function validOriginalDetail(item, recordId, patientId) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return false;
    for (const key of ['id','patientId']) if (!Object.hasOwn(item,key) || !Number.isSafeInteger(item[key]) || item[key] <= 0) return false;
    if (item.id !== Number(recordId) || item.patientId !== Number(patientId)) return false;
    for (const key of [...originalEditableFields,'sourceType','verificationStatus']) {
        if (!Object.hasOwn(item,key)) return false;
        const value = item[key];
        if (value === null) continue;
        if (['systolicBp','diastolicBp','bloodGlucose'].includes(key) ? typeof value !== 'number' || !Number.isFinite(value) : typeof value !== 'string') return false;
    }
    return true;
}
export function originalEditPatch(original, draft, dirty) {
    const patch = {};
    // 未加载、未明确修改都省略；null显示为空也不是一次用户清空操作。
    for (const key of originalEditableFields) if (Object.hasOwn(original || {}, key) && dirty?.[key] === true && draft[key] !== original[key]) patch[key] = draft[key];
    return patch;
}
export function boundedMeasurementContext(data, patientId, days) {
    const c = data?.measurementContext;
    if (Number(data?.patient?.id) !== Number(patientId) || c?.mode !== 'RAW_BOUNDED_V1' || c.coverage !== 'WINDOW_LATEST_NON_SNAPSHOT' || c.sourceType !== 'BP_SELF_MONITOR' || c.timeBasis !== 'RECORDED_LOCAL_TIME_ZONE_UNKNOWN') return null;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(c.from || '') || !/^\d{4}-\d{2}-\d{2}$/.test(c.to || '') || !localMeasurementTime(`${c.from}T00:00`) || !localMeasurementTime(`${c.to}T00:00`)) return null;
    if ((Date.parse(`${c.to}T00:00:00Z`) - Date.parse(`${c.from}T00:00:00Z`)) / 86400000 !== Number(days) - 1 || Number(days) < 1 || Number(days) > 90) return null;
    for (const key of ['latestBloodPressure','latestGlucose']) if (!Object.hasOwn(c,key) || (c[key] != null && (!originalId(c[key].id) || Number(c[key].patientId) !== Number(patientId) || c[key].recordDate < c.from || c[key].recordDate > c.to || !c[key].recordDate))) return null;
    return c;
}
