package org.familyhealthcare.service;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.util.CurrentUserUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class PlatformBrandingService {
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern SAFE_LOGO = Pattern.compile("^(?:/[A-Za-z0-9._~!$&'()*+,;=:@%/-]+|https://[^\\s]+|data:image/(?:png|jpeg|webp|svg\\+xml);base64,[A-Za-z0-9+/=\\r\\n]+)$", Pattern.CASE_INSENSITIVE);
    private static final String DEFAULT_LOGO = "/logo.svg";
    private final JdbcTemplate jdbc;

    public PlatformBrandingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> get() {
        return jdbc.query("SELECT platform_name,organization_name,logo_data,page_background,ownership_text,updated_at FROM platform_branding WHERE id=1",
                rs -> {
                    if (!rs.next()) return defaults();
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("platformName", rs.getString("platform_name"));
                    value.put("organizationName", rs.getString("organization_name"));
                    value.put("logo", rs.getString("logo_data"));
                    value.put("pageBackground", rs.getString("page_background"));
                    value.put("ownershipText", rs.getString("ownership_text"));
                    value.put("updatedAt", rs.getObject("updated_at"));
                    return value;
                });
    }

    @Transactional
    public Result<Map<String, Object>> save(Map<String, Object> input) {
        if (!CurrentUserUtil.isAdmin()) return Result.error(403, "Only administrators can change platform branding.");
        String platform = text(input.get("platformName"), 80);
        String organization = text(input.get("organizationName"), 120);
        String logo = text(input.get("logo"), 1_600_000);
        String background = text(input.get("pageBackground"), 7);
        String ownership = text(input.get("ownershipText"), 240);
        if (platform == null) return Result.error(400, "Platform name is required and must not exceed 80 characters.");
        if (organization == null) return Result.error(400, "Organization name must not exceed 120 characters.");
        if (ownership == null) return Result.error(400, "Ownership text is required and must not exceed 240 characters.");
        if (background == null || !COLOR.matcher(background).matches()) return Result.error(400, "Page background must be a six-digit hex color.");
        if (logo == null || !SAFE_LOGO.matcher(logo).matches()) return Result.error(400, "Logo must be a local path, HTTPS URL, or supported image data URL.");
        jdbc.update("INSERT INTO platform_branding(id,platform_name,organization_name,logo_data,page_background,ownership_text,updated_by,updated_at) VALUES(1,?,?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE platform_name=VALUES(platform_name),organization_name=VALUES(organization_name),logo_data=VALUES(logo_data),page_background=VALUES(page_background),ownership_text=VALUES(ownership_text),updated_by=VALUES(updated_by),updated_at=VALUES(updated_at)",
                platform, organization, logo, background.toLowerCase(), ownership, CurrentUserUtil.getCurrentUserId(), LocalDateTime.now());
        return Result.ok(get());
    }

    private static String text(Object value, int max) {
        String clean = value == null ? "" : value.toString().trim();
        if (clean.length() > max || clean.indexOf('\u0000') >= 0) return null;
        return clean;
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("platformName", "Chengxin Health");
        value.put("organizationName", "Chengxin Health");
        value.put("logo", DEFAULT_LOGO);
        value.put("pageBackground", "#f5f7fb");
        value.put("ownershipText", "© 2026 Chengxin Health. All rights reserved.");
        value.put("updatedAt", null);
        return value;
    }
}
