// 先按十进制字面量检查；Number 转换不能替用户抹掉超出的位数。
function decimalParts(value) {
    if (value === null || value === undefined || (typeof value === 'number' && !Number.isFinite(value))) return null;
    const match = String(value).trim().match(/^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))(?:[eE]([+-]?\d+))?$/);
    if (!match) return null;
    const exponent = Number(match[5] || 0);
    if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 2147483647) return null;
    const fraction = match[3] ?? match[4] ?? '';
    let digits = `${match[2] || ''}${fraction}`.replace(/^0+/, ''), scale = fraction.length - exponent;
    if (!digits) return { digits: '0', scale: 0, negative: false };
    const trimmed = digits.replace(/0+$/, '');
    scale -= digits.length - trimmed.length;
    return { digits: trimmed, scale, negative: match[1] === '-' };
}
export function measurementFits(value, precision, scale) {
    const parsed = decimalParts(value);
    return !!parsed && parsed.scale <= scale && parsed.digits.length - parsed.scale <= precision - scale;
}
export function sameMeasurementDecimal(value, original) {
    const a = decimalParts(value), b = decimalParts(original);
    return !!a && !!b && a.digits === b.digits && a.scale === b.scale && a.negative === b.negative;
}
