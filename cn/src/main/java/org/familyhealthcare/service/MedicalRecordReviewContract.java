package org.familyhealthcare.service;

import java.util.Map;

/** A review always targets the exact content version the caller displayed. */
public final class MedicalRecordReviewContract {
    private MedicalRecordReviewContract() { }
    public static Long expectedVersion(Map<String,Object> body) {
        Object value=body.get("expectedContentVersion");
        if(value==null || !String.valueOf(value).matches("[1-9][0-9]*")) throw new IllegalArgumentException("expectedContentVersion 不能为空，且必须为正整数。");
        try { long version=Long.parseLong(String.valueOf(value));requireVersion(version);return version; }
        catch(NumberFormatException invalid) {throw new IllegalArgumentException("无效的 expectedContentVersion。");}
    }
    public static void requireVersion(Long version) {
        if(version==null || version<1 || version>9007199254740991L) throw new IllegalArgumentException("expectedContentVersion 不能为空，且必须为正整数。");
    }
    public static final class Conflict extends RuntimeException {
        public Conflict() {super("病历内容已变更，请重新打开原文并核对当前版本后再审核。");}
    }
}
