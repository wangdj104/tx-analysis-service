package org.familyhealthcare.service;

import java.util.Map;

/** A review always targets the exact content version the caller displayed. */
public final class MedicalRecordReviewContract {
    private MedicalRecordReviewContract() { }
    public static Long expectedVersion(Map<String,Object> body) {
        Object value=body.get("expectedContentVersion");
        if(value==null || !String.valueOf(value).matches("[1-9][0-9]*")) throw new IllegalArgumentException("expectedContentVersion is required and must be a positive integer.");
        try { long version=Long.parseLong(String.valueOf(value));requireVersion(version);return version; }
        catch(NumberFormatException invalid) {throw new IllegalArgumentException("Invalid expectedContentVersion.");}
    }
    public static void requireVersion(Long version) {
        if(version==null || version<1 || version>9007199254740991L) throw new IllegalArgumentException("expectedContentVersion is required and must be a positive integer.");
    }
    /** 只检查退回理由，不改写原文，也不收紧原有的批准备注规则。 */
    public static void requireRejectionReason(String note) {
        if (note == null || note.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85 || c == 0xFEFF))
            throw new IllegalArgumentException("Enter a reason for rejecting this record; it cannot be blank.");
        // 按 Unicode 码点计数，避免把一个 emoji 当成两个字符。
        if (note.codePointCount(0, note.length()) > 1000)
            throw new IllegalArgumentException("The rejection reason must be 1,000 characters or fewer.");
    }
    public static final class Conflict extends RuntimeException {
        public Conflict() {super("The medical record changed. Reopen the source and confirm its current version before reviewing.");}
    }
}
