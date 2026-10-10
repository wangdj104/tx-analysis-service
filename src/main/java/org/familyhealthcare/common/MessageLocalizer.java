package org.familyhealthcare.common;

/** Localizes explicitly identified system messages; never translates response data. */
public final class MessageLocalizer {
    private MessageLocalizer() { }
    public static String localize(String message) {
        return SystemMessageCatalog.localize(message, "zh".equals(org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage()));
    }
}
