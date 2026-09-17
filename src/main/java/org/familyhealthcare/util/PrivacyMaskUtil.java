package org.familyhealthcare.util;

/**
 * datamaskutility class
 * tosensitivefieldenterrowmaskprocess, keepprotect privacydata
 */
public class PrivacyMaskUtil {

    /**
     * Phone Numbermask: keep the first3after 4, separated by*
     * example: 138****5678
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * ID Numbermask: keep the first3after 4, separated by*
     * example: 320***********1234
     */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 8) return idCard;
        int len = idCard.length();
        return idCard.substring(0, 3) + "***********" + idCard.substring(len - 4);
    }

    /**
     * Namemask: keepsurname, nameuse*
     * example: images*clear (3character) , images* (2character)
     */
    public static String maskName(String name) {
        if (name == null || name.length() <= 1) return name;
        if (name.length() == 2) return name.substring(0, 1) + "*";
        return name.substring(0, 1) + "*" + name.substring(name.length() - 1);
    }

    /**
     * Addressmask: keep the first6character, itsremaininguse*
     */
    public static String maskAddress(String address) {
        if (address == null || address.length() <= 6) return address;
        return address.substring(0, 6) + "***";
    }

    /**
     * Urgentcontactphonemask
     */
    public static String maskEmergencyPhone(String phone) {
        return maskPhone(phone);
    }

    /**
     * based onmasklevelBacknot samelevel process
     * level: 0-not mask, 1-Mild(Name/phone), 2-Moderate(+bodycopycertificate), 3-severe(+Address)
     */
    public static String maskByLevel(String value, String fieldType, int level) {
        if (value == null || level == 0) return value;
        switch (fieldType) {
            case "phone": return maskPhone(value);
            case "idCard": return level >= 2 ? maskIdCard(value) : value;
            case "name": return level >= 1 ? maskName(value) : value;
            case "address": return level >= 3 ? maskAddress(value) : value;
            default: return value;
        }
    }
}
