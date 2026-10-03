package org.familyhealthcare.interceptor;

import org.familyhealthcare.entity.SysMenu;
import org.familyhealthcare.service.careplan.CarePlanProperties;
import org.familyhealthcare.service.careplan.CarePlanException;
import org.familyhealthcare.mapper.SysMenuMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

@Component
public class PermissionInterceptor implements HandlerInterceptor {
    @Autowired private SysMenuMapper menuMapper;
    @Autowired(required=false) private CarePlanProperties carePlanProperties=new CarePlanProperties();
    private static final LinkedHashMap<String,String> RULES = new LinkedHashMap<>();
    private static final Set<String> ADMIN_ONLY = new LinkedHashSet<>(Arrays.asList(
            "/api/menu", "/api/role", "/api/user", "/api/audit-log", "/api/platform-branding"
    ));
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
        RULES.put("/api/nutrition/", "nutrition-assessment:view");
        RULES.put("/api/health-report", "health-report:view"); RULES.put("/api/data-export", "data-export:view");
        RULES.put("/api/notification-channel", "notification:manage");
        RULES.put("/api/clinical-workbench", "clinical-workbench:view"); RULES.put("/api/clinical-import", "clinical-workbench:view");
        RULES.put("/api/doctor-workspace", "doctor-workspace:view");
        RULES.put("/api/platform-branding", "branding:manage");
    }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{
        if("OPTIONS".equalsIgnoreCase(request.getMethod()) || request.getRequestURI().equals("/api/platform-branding/public"))return true;
        String normalized=UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if((matchesRoutePrefix(normalized,"/api/care-plans")||matchesRoutePrefix(normalized,"/api/care-nurse-assignments"))
                &&!("GET".equalsIgnoreCase(request.getMethod())&&normalized.equals("/api/care-plans/capabilities"))
                &&!carePlanProperties.isEnabled())return careError(response,404,"FEATURE_DISABLED","照护计划协作未启用。");
        Object roles=request.getAttribute("roleCodes");
        if(roles instanceof List && ((List<?>)roles).contains("admin"))return true;
        String path=request.getRequestURI();
        if(request.getAttribute("userId")!=null && path.equals("/api/user/changePassword"))return true;
        if(request.getAttribute("userId")!=null && "GET".equalsIgnoreCase(request.getMethod())
                && (path.equals("/api/patient/specialty-menu-scope")
                    || path.equals("/api/patient/specialty-roles")
                    || path.matches("/api/patient/[0-9]+/specialty-roles")))return true;
        // New assignment reads are patient-scoped by the service; only admins can enter write routes.
        String selected=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)==null
                ? UrlPathHelper.defaultInstance.getPathWithinApplication(request)
                : request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE).toString();
        if(matchesRoutePrefix(selected,"/api/care-nurse-assignments")) {
            if(request.getAttribute("userId")!=null && "GET".equalsIgnoreCase(request.getMethod()))return true;
            return careError(response,403,"ACCESS_DENIED",CarePlanException.denied().getMessage());
        }
        for(String prefix:ADMIN_ONLY)if(path.startsWith(prefix))return deny(response);
        // Daily family-care pages are available to every signed-in account; each record still checks the selected patient's membership.
        if(request.getAttribute("userId")!=null){
            if(path.equals("/api/patient/names") || path.startsWith("/api/family-health") || path.startsWith("/api/medication")
                || path.startsWith("/api/bp-self-monitor") || path.startsWith("/api/medical-record") || path.startsWith("/api/notification-channel"))return true;
        }
        // Match the route Spring actually selected, not raw URI matrix parameters.
        Object matchedPattern=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String permissionPath=matchedPattern == null
                ? UrlPathHelper.defaultInstance.getPathWithinApplication(request) : matchedPattern.toString();
        String required=null;
        for(Map.Entry<String,String> rule:RULES.entrySet())if(matchesRoutePrefix(permissionPath,rule.getKey())){required=rule.getValue();break;}
        if(required==null)return true;
        Object raw=request.getAttribute("userId");if(raw==null)return deny(response);
        List<SysMenu> menus=menuMapper.selectMenusByUserId(Long.valueOf(raw.toString()));
        for(SysMenu menu:menus)if(hasPermission(required, menu.getPermission()))return true;
        return deny(response);
    }
    private boolean matchesRoutePrefix(String path, String prefix) {
        String root = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return path.equals(root) || path.startsWith(root + "/");
    }
    private boolean hasPermission(String required, String granted) {
        if (required.equals(granted)) return true;
        return "monitoring:view".equals(required) && "health-monitoring:view".equals(granted);
    }
    private boolean careError(HttpServletResponse response,int status,String code,String message)throws Exception{
        Map<String,Object> error=new LinkedHashMap<>();error.put("code",status);error.put("msg",message);error.put("data",Collections.singletonMap("errorCode",code));
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.getWriter().write(com.alibaba.fastjson2.JSON.toJSONString(error));return false;
    }
    private boolean deny(HttpServletResponse response)throws Exception{response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":403,\"msg\":\"Access denied\",\"data\":null}");return false;}
}
