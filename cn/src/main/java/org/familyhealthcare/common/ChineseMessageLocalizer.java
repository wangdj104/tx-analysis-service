package org.familyhealthcare.common;

/** Localizes explicitly identified system messages; never translates response data. */
public final class ChineseMessageLocalizer {
    private ChineseMessageLocalizer() { }
    public static String localize(String message) {
        return SystemMessageCatalog.localize(message, true);
    }
}
