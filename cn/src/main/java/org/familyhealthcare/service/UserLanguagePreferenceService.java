package org.familyhealthcare.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** Persists the language most recently selected by each signed-in user. */
@Service
public class UserLanguagePreferenceService {
    private static final Logger log = LoggerFactory.getLogger(UserLanguagePreferenceService.class);
    private static final String KEY = "language";
    @Autowired private JdbcTemplate jdbc;

    public void capture(Long userId, String acceptLanguage) {
        if (userId == null || acceptLanguage == null || acceptLanguage.trim().isEmpty()) return;
        String language = normalize(acceptLanguage);
        try {
            jdbc.update("INSERT INTO user_preference(user_id,pref_key,pref_value) VALUES(?,?,?) " +
                            "ON DUPLICATE KEY UPDATE pref_value=VALUES(pref_value),updated_at=CURRENT_TIMESTAMP",
                    userId, KEY, language);
        } catch (DataAccessException e) {
            log.warn("Unable to persist language preference for userId={}", userId, e);
        }
    }

    public String get(Long userId) {
        if (userId == null) return "zh-CN";
        try {
            List<String> values = jdbc.queryForList(
                    "SELECT pref_value FROM user_preference WHERE user_id=? AND pref_key=? LIMIT 1",
                    String.class, userId, KEY);
            return values.isEmpty() ? "zh-CN" : normalize(values.get(0));
        } catch (DataAccessException e) {
            log.warn("Unable to read language preference for userId={}", userId, e);
            return "zh-CN";
        }
    }

    public static String normalize(String language) {
        return language != null && language.trim().toLowerCase().startsWith("zh") ? "zh-CN" : "en-US";
    }
}
