package org.familyhealthcare.config;

import org.familyhealthcare.interceptor.AuditLogInterceptor;
import org.familyhealthcare.interceptor.JwtInterceptor;
import org.familyhealthcare.interceptor.PermissionInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVCconfiguration
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Autowired
    private PermissionInterceptor permissionInterceptor;

    @Autowired
    private AuditLogInterceptor auditLogInterceptor;

    /** has  RestController systemonemount /api before suffix,  and before end baseURL, Nginx replacemanageonecause */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api", clazz ->
                clazz.isAnnotationPresent(RestController.class)
                        && clazz.getPackage().getName().startsWith("org.familyhealthcare.controller"));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Exact report routes are private even when authentication or the feature gate rejects them.
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(javax.servlet.http.HttpServletRequest request,
                    javax.servlet.http.HttpServletResponse response,Object handler) {
                if(handler instanceof org.springframework.web.method.HandlerMethod
                        && org.familyhealthcare.controller.CareExecutionReportController.class.isAssignableFrom(
                            ((org.springframework.web.method.HandlerMethod)handler).getBeanType())) {
                    response.setHeader("Cache-Control","no-store, private");
                    response.setHeader("X-Content-Type-Options","nosniff");
                }
                return true;
            }
        });
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/api/auth/**",
                        "/api/platform-branding/public",
                        "/api/health",
                        "/swagger-resources/**",
                        "/swagger-ui/**",
                        "/v2/api-docs/**",
                        "/v3/api-docs/**",
                        "/doc.html/**",
                        "/webjars/**",
                        "/error"
                );
        registry.addInterceptor(permissionInterceptor).addPathPatterns("/**");
        registry.addInterceptor(auditLogInterceptor).addPathPatterns("/**");
    }
}
