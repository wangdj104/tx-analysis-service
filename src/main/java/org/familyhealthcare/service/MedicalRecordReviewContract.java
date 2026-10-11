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
    public static final class Conflict extends RuntimeException {
        public Conflict() {super("The medical record changed. Reopen the source and confirm its current version before reviewing.");}
    }
}
