import { measurementText as t } from './measurementReadModelCopy.js';
// 这里只描述来源、单位和显示形状；医学判断始终使用后端既有策略。
export const measurementMetrics = Object.freeze({
    BP: { comparisonUnit: 'mmHg' }, GLUCOSE: { comparisonUnit: 'mmol/L' },
    SPO2: { comparisonUnit: '%' }, WEIGHT: { comparisonUnit: 'kg' },
    HEART_RATE: { comparisonUnit: 'bpm' }, TEMPERATURE: { comparisonUnit: '°C' },
    CUSTOM: { comparisonUnit: null }
});
const sourceMetrics = Object.freeze({
    BP_SELF_MONITOR: Object.freeze(['BP', 'GLUCOSE']),
    HEALTH_MEASUREMENT: Object.freeze(Object.keys(measurementMetrics))
});
export const metricsForSource = source => sourceMetrics[source] || [];
export const isLegalMeasurement = (source, metric) => metricsForSource(source).includes(metric);
export const measurementUnit = unit => typeof unit === 'string' && unit.trim() ? unit : t('unknownUnit');
export function measurementName(item, metric) {
    return metric === 'CUSTOM' ? (typeof item.metricName === 'string' && item.metricName.trim() ? item.metricName : t('customMeasurement')) : t(metric);
}
export const finiteReading = value => ['string', 'number'].includes(typeof value) && String(value).trim() !== '' && Number.isFinite(Number(value));
export function localMeasurementTime(value) {
    const match = typeof value === 'string' && value.match(/^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?$/);
    if (!match || Number(match[2]) > 23 || Number(match[3]) > 59 || Number(match[4] || 0) > 59) return false;
    const day = new Date(`${match[1]}T00:00:00Z`);
    return Number.isFinite(day.getTime()) && day.toISOString().slice(0, 10) === match[1];
}
export function canPlotMeasurement(item, metric) {
    if (!isLegalMeasurement(item.sourceType, metric) || !measurementMetrics[metric]?.comparisonUnit) return false;
    if (item.sourceStatus !== 'ACTIVE' || item.comparable !== true || item.assessable !== true || !['NORMAL', 'ABNORMAL'].includes(item.status) || !Array.isArray(item.qualityFlags) || item.qualityFlags.length || !localMeasurementTime(item.plotTime)) return false;
    return metric === 'BP' ? item.comparisonUnit === 'mmHg' && finiteReading(item.comparisonValue?.systolic) && finiteReading(item.comparisonValue?.diastolic) : item.comparisonUnit === measurementMetrics[metric].comparisonUnit && finiteReading(item.comparisonValue);
}
export function measurementValue(value, metric) {
    if (metric === 'BP' && value && typeof value === 'object') return `${value.systolic ?? '—'} / ${value.diastolic ?? '—'}`;
    if (value == null) return '—';
    return metric === 'BP' ? `${t('primaryOnly')}: ${value}` : String(value);
}
export function originalMeasurementTime(item) {
    const raw = item.rawTime || {};
    if (item.sourceType === 'BP_SELF_MONITOR') return `${raw.recordDate ?? '—'} ${raw.recordTime == null || raw.recordTime === '' ? t('noTime') : raw.recordTime}`;
    return raw.measuredAt == null || raw.measuredAt === '' ? t('noTime') : String(raw.measuredAt);
}
export function measurementQuality(item) {
    const flags = Array.isArray(item.qualityFlags) ? [...item.qualityFlags] : ['unknown'];
    if (item.sourceStatus !== 'ACTIVE' && !flags.includes(item.sourceStatus)) flags.push(item.sourceStatus || 'unknown');
    return flags.map(t).join(' · ');
}
export function sevenDayRange() {
    const today = new Date(), from = new Date(today);
    from.setDate(today.getDate() - 6);
    const day = value => `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
    return { from: day(from), to: day(today) };
}
