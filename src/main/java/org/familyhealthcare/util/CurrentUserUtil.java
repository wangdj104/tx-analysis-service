package org.familyhealthcare.util;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * currentSign Inuserutility class
 */
public class CurrentUserUtil {

    private static final ThreadLocal<Long> SYSTEM_USER_ID = new ThreadLocal<>();

    /**
     * getcurrentSign InuserID
     */
    public static Long getCurrentUserId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return SYSTEM_USER_ID.get();
        }
        HttpServletRequest request = attributes.getRequest();
        Object userId = request.getAttribute("userId");
        if (userId == null) {
            return null;
        }
        return Long.valueOf(userId.toString());
    }

    /**
     * getcurrentSign InUsername
     */
    public static String getCurrentUsername() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        Object username = request.getAttribute("username");
        if (username == null) {
            return null;
        }
        return username.toString();
    }

    /**
     * determinecurrentuserYesNofor super administrator (admin)
     */
    @SuppressWarnings("unchecked")
    public static boolean isAdmin() {
        return hasRole("admin");
    }

    /** Return whether the current authenticated account has the requested role code. */
    @SuppressWarnings("unchecked")
    public static boolean hasRole(String roleCode) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        Object roleCodes = request.getAttribute("roleCodes");
        if (roleCodes == null) {
            return false;
        }
        return ((List<String>) roleCodes).contains(roleCode);
    }

    /** provideafter platformsettimetaskin specifieduser datarangewithinrun, endafter Automaticclearmanagelineprocessup down text.  */
    public static <T> T runAsUser(Long userId, java.util.function.Supplier<T> action) {
        Long previous = SYSTEM_USER_ID.get();
        SYSTEM_USER_ID.set(userId);
        try {
            return action.get();
        } finally {
            if (previous == null) SYSTEM_USER_ID.remove(); else SYSTEM_USER_ID.set(previous);
        }
    }
}
