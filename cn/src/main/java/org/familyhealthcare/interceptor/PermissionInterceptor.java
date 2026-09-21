package org.familyhealthcare.interceptor;

import org.familyhealthcare.entity.SysMenu;
import org.familyhealthcare.mapper.SysMenuMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

@Component
public class PermissionInterceptor implements HandlerInterceptor {
    @Autowired private SysMenuMapper menuMapper;
    private static final LinkedHashMap<String,String> RULES = new LinkedHashMap<>();
    static {
        RULES.put("/api/menu", "menu:manage"); RULES.put("/api/role", "role:manage"); RULES.put("/api/user", "user:manage");
        RULES.put("/api/patient", "patient:manage"); RULES.put("/api/patient-clinical", "patient:manage");
        RULES.put("/api/monitoring", "monitoring:view"); RULES.put("/api/family-health", "family-health:view");
        RULES.put("/api/dialysis", "dialysis:view"); RULES.put("/api/dry-weight", "dry-weight:view"); RULES.put("/api/ai-analysis", "dialysis:ai:view");
        RULES.put("/api/medical-record", "medical:view"); RULES.put("/api/health-indicator", "medical:view");
        RULES.put("/api/medication-reminder", "medication:reminder:view"); RULES.put("/api/medication", "medication:view");
        RULES.put("/api/bp-self-monitor", "bp-self-monitor:view"); RULES.put("/api/complication", "complication:view");
        RULES.put("/api/alert", "alert:view"); RULES.put("/api/bp-pattern", "bp-pattern:view");
        RULES.put("/api/nutrition-diary", "nutrition-diary:view"); RULES.put("/api/nutrition-assessment", "nutrition-assessment:view");
        RULES.put("/api/health-report", "health-report:view"); RULES.put("/api/data-export", "data-export:view");
        RULES.put("/api/notification-channel", "notification:manage");
        RULES.put("/api/clinical-workbench", "clinical-workbench:view"); RULES.put("/api/clinical-import", "clinical-workbench:view");
        RULES.put("/api/doctor-workspace", "doctor-workspace:view");
        RULES.put("/api/platform-branding", "branding:manage");
    }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{
        if("OPTIONS".equalsIgnoreCase(request.getMethod()) || request.getRequestURI().equals("/api/platform-branding/public"))return true;
        Object roles=request.getAttribute("roleCodes");
        if(roles instanceof List && ((List<?>)roles).contains("admin"))return true;
        // Daily family-care pages are available to every signed-in account; each record still checks the selected patient's membership.
        if(request.getAttribute("userId")!=null){
            String path=request.getRequestURI();
            if(path.equals("/api/patient/names") || path.equals("/api/user/changePassword") || path.startsWith("/api/family-health") || path.startsWith("/api/medication")
                || path.startsWith("/api/bp-self-monitor") || path.startsWith("/api/medical-record") || path.startsWith("/api/notification-channel"))return true;
        }
        String required=null;
        for(Map.Entry<String,String> rule:RULES.entrySet())if(request.getRequestURI().startsWith(rule.getKey())){required=rule.getValue();break;}
        if(required==null)return true;
        Object raw=request.getAttribute("userId");if(raw==null)return deny(response);
        List<SysMenu> menus=menuMapper.selectMenusByUserId(Long.valueOf(raw.toString()));
        for(SysMenu menu:menus)if(hasPermission(required, menu.getPermission()))return true;
        return deny(response);
    }
    private boolean hasPermission(String required, String granted) {
        if (required.equals(granted)) return true;
        return "monitoring:view".equals(required) && "health-monitoring:view".equals(granted);
    }
    private boolean deny(HttpServletResponse response)throws Exception{response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":403,\"msg\":\"Access deniedthis feature\",\"data\":null}");return false;}
}
