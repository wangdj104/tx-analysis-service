package org.familyhealthcare.util;

import java.math.BigDecimal;

/** 只核对现有列能否原样保存，不替用户舍入，也不改变临床判断。 */
public final class MeasurementInputNumbers {
    private MeasurementInputNumbers() { }
    public static void selfGlucose(BigDecimal value) { requireDecimal(value,5,2,"bloodGlucose"); }
    public static void journeyReading(BigDecimal value) { requireDecimal(value,12,3,"measurement value"); }
    public static boolean changed(BigDecimal value,BigDecimal original) {
        return value==null ? original!=null : original==null || value.compareTo(original)!=0;
    }
    public static void requireSelfInteger(Object value,String field) {
        if(value==null || value instanceof String && ((String)value).trim().isEmpty())return;
        try { new BigDecimal(value.toString()).intValueExact(); }
        catch(RuntimeException invalid) { throw new IllegalArgumentException(field+" must be an exact integer."); }
    }
    /** 导入血压必须完整且可精确保存，空值不能代替无效读数。 */
    public static int requireImportInteger(Object value, String field) {
        if (value instanceof Number || value instanceof String) {
            String number = value.toString().trim();
            if (!number.isEmpty() && number.length() <= 256) {
                try { return new BigDecimal(number).intValueExact(); }
                catch (NumberFormatException | ArithmeticException invalid) { /* 使用固定诊断，不带入原始值。 */ }
            }
        }
        throw new IllegalArgumentException(ExportLocalization.text(
                "The import blood-pressure field must be an exact 32-bit integer.", "导入血压字段必须是可精确保存的 32 位整数。"));
    }
    private static void requireDecimal(BigDecimal value,int precision,int scale,String field) {
        if(value==null)return;
        if(value.stripTrailingZeros().scale()>scale || value.abs().compareTo(BigDecimal.TEN.pow(precision-scale))>=0)
            throw new IllegalArgumentException(field+" cannot be stored exactly: check its range and decimal places.");
    }
}
